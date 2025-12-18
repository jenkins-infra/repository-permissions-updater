package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class GitHubImpl extends GitHubAPI {
    private static final Logger LOGGER = Logger.getLogger(GitHubImpl.class.getName());

    private final String githubPublicKeyUrl;
    private final String githubSecrectUrl;
    private static final String GITHUB_USERNAME = System.getenv("GITHUB_USERNAME");
    private static final String GITHUB_PASSWORD = System.getenv("GITHUB_TOKEN");
    private static final String GITHUB_BASIC_AUTH_VALUE = Base64.getEncoder()
            .encodeToString((GITHUB_USERNAME + ":" + GITHUB_PASSWORD).getBytes(StandardCharsets.UTF_8));
    private static final String GITHUB_BASIC_AUTH_HEADER = "Basic %s";
    private static final Gson gson = new Gson();
    private static final String GITHUB_JSON_TEMPLATE = """
            {"encrypted_value":"%s","key_id":"%s"}
            """;

    GitHubImpl(String githubPublicKeyUrl, String githubSecrectUrl) {
        this.githubPublicKeyUrl = githubPublicKeyUrl;
        this.githubSecrectUrl = githubSecrectUrl;
    }

    @Override
    @CheckForNull
    @SuppressFBWarnings("URLCONNECTION_SSRF_FD")
    public GitHubPublicKey getRepositoryPublicKey(String repositoryName) throws IOException {
        LOGGER.log(Level.INFO, "GET call to retrieve public key for {0}", new Object[] {repositoryName});
        URL url = URI.create(String.format(githubPublicKeyUrl, repositoryName)).toURL();

        int responseCode = 0;
        int attemptNumber = 1;
        int maxAttempts = 3;
        while (responseCode != HttpURLConnection.HTTP_OK) {

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // The GitHub API doesn't do an auth challenge
            conn.setRequestProperty("Authorization", String.format(GITHUB_BASIC_AUTH_HEADER, GITHUB_BASIC_AUTH_VALUE));
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestMethod("GET");
            conn.connect();
            responseCode = conn.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                if (attemptNumber == maxAttempts) {
                    LOGGER.log(
                            Level.WARNING,
                            "Failed to retrieve public key for {0}, response code: {1}",
                            new Object[] {repositoryName, responseCode});
                    return null;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted for GitHub", e);
                }
                LOGGER.log(Level.INFO, "Retrying retrieving public key for {0} attempt {1}/{2}", new Object[] {
                    repositoryName, attemptNumber, maxAttempts
                });
                attemptNumber++;
            } else {
                return retrievePublicKeyFromResponse(conn);
            }
        }
        return null;
    }

    private static GitHubPublicKey retrievePublicKeyFromResponse(HttpURLConnection conn) throws IOException {
        try (final BufferedReader bufferedReader =
                new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            final String text = bufferedReader.lines().collect(Collectors.joining());
            final JsonObject json = gson.fromJson(text, JsonObject.class);
            return new GitHubPublicKey(
                    json.get("key_id").getAsString(), json.get("key").getAsString());
        } catch (final IOException e) {
            throw new IOException("Failed to parse GitHub response", e);
        }
    }

    @Override
    @SuppressFBWarnings({"URLCONNECTION_SSRF_FD", "VA_FORMAT_STRING_USES_NEWLINE"})
    public void createOrUpdateRepositorySecret(String name, String encryptedSecret, String repositoryName, String keyId)
            throws IOException {
        LOGGER.log(Level.INFO, "Create/update the secret {0} for {1} encrypted with key {2}", new Object[] {
            name, repositoryName, keyId
        });
        URL url = URI.create(String.format(githubSecrectUrl, repositoryName, name))
                .toURL();

        int responseCode = 0;
        int attemptNumber = 1;
        int maxAttempts = 3;
        while (!(responseCode == HttpURLConnection.HTTP_NO_CONTENT || responseCode == HttpURLConnection.HTTP_CREATED)) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // The GitHub API doesn't do an auth challenge
            conn.setRequestProperty("Authorization", String.format(GITHUB_BASIC_AUTH_HEADER, GITHUB_BASIC_AUTH_VALUE));
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);

            try (OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                osw.write(GITHUB_JSON_TEMPLATE.formatted(encryptedSecret, keyId));
            } catch (IOException e) {
                throw new IOException("IO error to send data to GitHub", e);
            }
            responseCode = conn.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_NO_CONTENT && responseCode != HttpURLConnection.HTTP_CREATED) {
                if (attemptNumber == maxAttempts) {
                    LOGGER.log(
                            Level.WARNING,
                            "Failed to create/update secret {0} for {1}, response code: {2}",
                            new Object[] {name, repositoryName, responseCode});
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted for GitHub", e);
                }
                LOGGER.log(
                        Level.INFO,
                        "Retrying create/update secret {0} for {1} attempt {2}/{3}, code: {4}",
                        new Object[] {name, repositoryName, attemptNumber, maxAttempts, responseCode});
                attemptNumber++;
            }
        }
    }
}
