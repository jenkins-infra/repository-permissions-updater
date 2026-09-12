package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Reads current GitHub team membership using the GraphQL API, batching many teams into as few requests as
 * possible (via query aliases) to stay well within rate limits even when the managed set grows into the
 * thousands of teams (based on the bulk-query approach prototyped at
 * https://gist.github.com/halkeye/c1e8348c8ac4cf8d476376b43df2bf6e), and applies membership changes
 * (add/remove) via the REST API, which is what GitHub exposes for team membership mutations.
 */
class GitHubTeamsAPIImpl extends GitHubTeamsAPI {
    private static final Logger LOGGER = Logger.getLogger(GitHubTeamsAPIImpl.class.getName());

    private static final String GITHUB_GRAPHQL_URL = "https://api.github.com/graphql";
    private static final String GITHUB_REST_URL = "https://api.github.com";
    private static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");

    /** Number of teams queried (via aliased sub-queries) per GraphQL request. */
    private static final int BATCH_SIZE = 50;

    /** Max team members fetched per team; teams with more members would need pagination (not yet implemented). */
    private static final int MAX_MEMBERS_PER_TEAM = 100;

    @NonNull
    @Override
    public Map<String, Set<String>> fetchTeamMembers(@NonNull String organization, @NonNull Set<String> teamSlugs)
            throws IOException {
        Map<String, Set<String>> result = new HashMap<>();
        List<String> slugs = new ArrayList<>(teamSlugs);

        for (int start = 0; start < slugs.size(); start += BATCH_SIZE) {
            List<String> batch = slugs.subList(start, Math.min(start + BATCH_SIZE, slugs.size()));
            result.putAll(fetchBatch(organization, batch));
        }

        // Teams that don't exist (or have no members) on GitHub yet are reported as empty, not omitted, so
        // that callers can compute "everyone in the desired set needs to be added" diffs correctly.
        for (String slug : teamSlugs) {
            result.putIfAbsent(slug, Set.of());
        }
        return result;
    }

    @Override
    public void addTeamMember(@NonNull String organization, @NonNull String teamSlug, @NonNull String login)
            throws IOException {
        JsonObject body = new JsonObject();
        // Always "member", never "maintainer" - this project only ever manages membership, not team admin rights.
        body.addProperty("role", "member");
        restRequest("PUT", membershipUrl(organization, teamSlug, login), body.toString());
    }

    @Override
    public void removeTeamMember(@NonNull String organization, @NonNull String teamSlug, @NonNull String login)
            throws IOException {
        restRequest("DELETE", membershipUrl(organization, teamSlug, login), null);
    }

    private static String membershipUrl(String organization, String teamSlug, String login) {
        return GITHUB_REST_URL + "/orgs/" + organization + "/teams/" + teamSlug + "/memberships/" + login;
    }

    private Map<String, Set<String>> fetchBatch(String organization, List<String> slugs) throws IOException {
        Map<String, String> aliasToSlug = new HashMap<>();
        StringBuilder subQueries = new StringBuilder();
        int i = 0;
        for (String slug : slugs) {
            String alias = "t" + (i++);
            aliasToSlug.put(alias, slug);
            subQueries
                    .append(alias)
                    .append(": team(slug: ")
                    .append(gsonString(slug))
                    .append(") { slug members(first: ")
                    .append(MAX_MEMBERS_PER_TEAM)
                    .append(") { totalCount nodes { login } } }\n");
        }

        String query = "query { organization(login: " + gsonString(organization) + ") {\n" + subQueries + "} }";

        JsonObject response = postGraphQl(query);
        Map<String, Set<String>> membersBySlug = new HashMap<>();

        JsonObject data = response.getAsJsonObject("data");
        JsonObject org = data == null ? null : data.getAsJsonObject("organization");
        if (org == null) {
            LOGGER.log(
                    Level.WARNING,
                    "GraphQL response for organization ''{0}'' had no data, skipping batch",
                    organization);
            return membersBySlug;
        }

        for (Map.Entry<String, String> entry : aliasToSlug.entrySet()) {
            JsonElement teamElement = org.get(entry.getKey());
            if (teamElement == null || teamElement.isJsonNull()) {
                // Team doesn't exist (yet) on GitHub - treated as empty by the caller.
                continue;
            }
            JsonObject team = teamElement.getAsJsonObject();
            JsonObject membersObj = team.getAsJsonObject("members");
            int totalCount = membersObj.get("totalCount").getAsInt();
            if (totalCount > MAX_MEMBERS_PER_TEAM) {
                LOGGER.log(
                        Level.WARNING,
                        "Team ''{0}'' has {1} members, exceeding the {2} fetched per request; pagination not yet"
                                + " implemented, diff for this team may be incomplete",
                        new Object[] {entry.getValue(), totalCount, MAX_MEMBERS_PER_TEAM});
            }
            JsonArray nodes = membersObj.getAsJsonArray("nodes");
            Set<String> logins = new TreeSet<>();
            for (JsonElement node : nodes) {
                logins.add(node.getAsJsonObject().get("login").getAsString());
            }
            membersBySlug.put(entry.getValue(), logins);
        }
        return membersBySlug;
    }

    private JsonObject postGraphQl(String query) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);

        int responseCode;
        int attemptNumber = 1;
        int maxAttempts = 3;
        IOException lastError = null;
        while (attemptNumber <= maxAttempts) {
            URL url = URI.create(GITHUB_GRAPHQL_URL).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + GITHUB_TOKEN);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            try (OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                osw.write(body.toString());
            }

            responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String text = reader.lines().collect(Collectors.joining());
                    return JsonParser.parseString(text).getAsJsonObject();
                }
            }

            lastError = new IOException("GraphQL request failed with response code " + responseCode);
            LOGGER.log(Level.WARNING, "Attempt {0}/{1} to query GitHub GraphQL API failed with code {2}", new Object[] {
                attemptNumber, maxAttempts, responseCode
            });
            attemptNumber++;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while retrying GitHub GraphQL request", e);
            }
        }
        throw lastError;
    }

    /**
     * Issues a REST API request (used for team membership mutations, which GitHub does not expose via
     * GraphQL), retrying transient failures the same way {@link #postGraphQl(String)} does.
     *
     * @param method HTTP method, e.g. {@code "PUT"}/{@code "DELETE"}
     * @param url full request URL
     * @param jsonBody request body to send, or {@code null} for a request with no body (e.g. DELETE)
     */
    @SuppressFBWarnings(
            value = "URLCONNECTION_SSRF_FD",
            justification = "url is built from the constant GitHub REST API host plus already-validated"
                    + " organization/teamSlug/login segments, not arbitrary user input.")
    private void restRequest(String method, String url, String jsonBody) throws IOException {
        int attemptNumber = 1;
        int maxAttempts = 3;
        IOException lastError = null;
        while (attemptNumber <= maxAttempts) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + GITHUB_TOKEN);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestMethod(method);

            if (jsonBody != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                try (OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                    osw.write(jsonBody);
                }
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                conn.disconnect();
                return;
            }

            lastError = new IOException(method + " " + url + " failed with response code " + responseCode);
            LOGGER.log(Level.WARNING, "Attempt {0}/{1} of {2} {3} failed with code {4}", new Object[] {
                attemptNumber, maxAttempts, method, url, responseCode
            });
            attemptNumber++;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while retrying GitHub REST request", e);
            }
        }
        throw lastError;
    }

    private static String gsonString(String value) {
        JsonArray tmp = new JsonArray();
        tmp.add(value);
        return tmp.get(0).toString();
    }
}
