package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class ArtifactoryImpl extends ArtifactoryAPI {
    private static final Logger LOGGER = Logger.getLogger(ArtifactoryImpl.class.getName());
    /**
     * URL to Artifactory
     */
    private static final String ARTIFACTORY_URL = System.getProperty("artifactoryUrl", "https://repo.jenkins-ci.org");
    /**
     * URL to the permissions API of Artifactory
     */
    private static final String ARTIFACTORY_PERMISSIONS_API_URL = ARTIFACTORY_URL + "/api/security/permissions";
    /**
     * URL to the groups API of Artifactory
     */
    private static final String ARTIFACTORY_GROUPS_API_URL = ARTIFACTORY_URL + "/api/security/groups";
    /**
     * URL to the groups API of Artifactory
     */
    private static final String ARTIFACTORY_TOKEN_API_URL = ARTIFACTORY_URL + "/access/api/v1/tokens";

    /**
     * True iff this is a dry-run (no API calls resulting in modifications)
     */
    public static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    private static final String ARTIFACTORY_OBJECT_NAME_PREFIX = System.getProperty("artifactoryObjectPrefix", Boolean.getBoolean("development") ? "generateddev-" : "generatedv2-");

    private static final Gson gson = new Gson();

    static {
        String token = System.getenv("ARTIFACTORY_TOKEN");
        if (token == null) {
            BEARER_TOKEN = null;
            if (!DRY_RUN_MODE) {
                throw new IllegalStateException("ARTIFACTORY_TOKEN must be provided unless dry-run mode is used");
            }
        } else {
            if (System.getProperty("java.version").startsWith("1.")) {
                // URLEncoder#encode(String, Charset) exists since Java 10
                throw new IllegalStateException("You need at least Java 10 to run this unless dry-run mode is used");
            }

            BEARER_TOKEN = "Bearer " + token;
        }
    }

    private static final String BEARER_TOKEN;

    /**
     * Creates or replaces a permission target based on the provided payload.
     * @oaram name the name of the permission target
     * @param payloadFile the file containing the API payload.
     */
    @Override
    public void createOrReplacePermissionTarget(String name, File payloadFile) {
        createOrReplace(ARTIFACTORY_PERMISSIONS_API_URL, name, "permission target", payloadFile);
    }

    @Override
    public void deletePermissionTarget(String target) {
        delete(ARTIFACTORY_PERMISSIONS_API_URL, target, "permission target");
    }

    @Override
    public List<String> listGeneratedPermissionTargets() {
        return list(ARTIFACTORY_PERMISSIONS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX);
    }

    @Override
    public List<String> listGeneratedGroups() {
        return list(ARTIFACTORY_GROUPS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX);
    }

    @Override
    public void createOrReplaceGroup(String name, File payloadFile) {
        createOrReplace(ARTIFACTORY_GROUPS_API_URL, name, "group", payloadFile);
    }

    @Override
    public void deleteGroup(String group) {
        delete(ARTIFACTORY_GROUPS_API_URL, group, "group");
    }

    @Override
    @CheckForNull
    public String generateTokenForGroup(String username, String group, long expiresInSeconds) {
       return withConnection("POST", ARTIFACTORY_TOKEN_API_URL, conn -> {
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            try (OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                var params = createParams(username, group, expiresInSeconds);
                osw.write(params);
            }
            try (final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                final String text = bufferedReader.lines().collect(Collectors.joining());
                var object = gson.fromJson(text, JsonObject.class);
                return object.get("access_token").getAsString();
            }
        });
    }

    public static String createParams(String username, String group, long expiresInSeconds) {
        Map<String, String> params = Map.of(
                "username", username,
                "scope", "applied-permissions/groups:readers," + group,
                "expires_in", String.valueOf(expiresInSeconds)
        );

        return params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + encodeValue(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encodeValue(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            throw new RuntimeException("Encoding not supported", e);
        }
    }

    private static List<String> list(String apiUrl, String prefix) {
        return withConnection("GET", apiUrl, conn -> {
            List<String> result = new ArrayList<>();
            try (final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                final String text = bufferedReader.lines().collect(Collectors.joining());
                JsonArray array = JsonParser.parseString(text).getAsJsonArray();
                for (JsonElement element : array) {
                    String name = element.getAsJsonObject().get("name").getAsString();
                    if (name.startsWith(prefix)) {
                        result.add(name);
                    }
                }
            }
            return result;
        });
    }

    /**
     *
     * @param apiUrl The API base URL (does not include trailing '/')
     * @param name this is the full object name as provided by {@link ArtifactoryAPI#toGeneratedName}.
     * @param kind the human readable kind of object for log messages
     * @param payloadFile the file containing the payload
     */
    private static void createOrReplace(String apiUrl, String name, String kind, File payloadFile) {
        withConnection("PUT", apiUrl + '/' + name, conn -> {
            conn.setDoOutput(true);
            try (OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                osw.write(Files.readString(payloadFile.toPath()));
            }
            return null;
        });
    }

    /**
     * Deletes the specified {@code name} using {@code apiUrl}.
     * @param apiUrl the base URL to the deletion API
     * @param name the name of the object to delete
     * @param kind the human-readable kind of object being deleted (for a log message)
     */
    private static void delete(String apiUrl, String name, String kind) {
        withConnection("'DELETE'", apiUrl + '/' + name, conn -> {
            try (final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                final String text = bufferedReader.lines().collect(Collectors.joining());
                LOGGER.log(Level.INFO, text);
            }
            return name;
        });
    }

    @FunctionalInterface
    public interface HttpRequestConsumer<T> {
        T accept(HttpURLConnection conn) throws IOException;
    }

    @SuppressFBWarnings("URLCONNECTION_SSRF_FD")
    private static <T> T withConnection(String verb, String url, HttpRequestConsumer<T> closure) {
        if (DRY_RUN_MODE) {
            LOGGER.log(Level.INFO, "Dry-run mode: Skipping {0} call to {1}", new Object[]{verb, url});
            return null;
        }
        LOGGER.log(Level.INFO, "Sending {0} to {1}", new Object[]{verb, url});

        HttpURLConnection conn = null;
        try {
            URL _url = new URL(url);
            conn = (HttpURLConnection) _url.openConnection();
            conn.setRequestMethod(verb);
            if (BEARER_TOKEN != null && !BEARER_TOKEN.isEmpty()) {
                conn.addRequestProperty("Authorization", BEARER_TOKEN);
            }

            return closure.accept(conn);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "IOException occurred while processing the request", e);
        } finally {
            try {
                if (conn != null) {
                    if (conn.getResponseCode() < 200 || conn.getResponseCode() > 399) {
                        LOGGER.log(Level.INFO, "{0} request to {1} returned error: HTTP {2} {3}",
                                new Object[]{verb, url, conn.getResponseCode(), conn.getResponseMessage()});
                    } else {
                        LOGGER.log(Level.INFO, "{0} request to {1} returned: HTTP {2} {3}",
                                new Object[]{verb, url, conn.getResponseCode(), conn.getResponseMessage()});
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "IOException occurred while getting the response code", e);
            }
        }
        return null;
    }
}
