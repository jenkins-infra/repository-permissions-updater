package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.CheckForNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class GitHubImpl extends GitHubAPI {
    private static final Logger LOGGER = Logger.getLogger(GitHubImpl.class.getName());

    private static final String GITHUB_PUBLIC_KEY_URL = "https://api.github.com/repos/%s/actions/secrets/public-key";
    private static final String GITHUB_SECRET_URL = "https://api.github.com/repos/%s/actions/secrets/%s";
    private static final String GITHUB_USERNAME = System.getenv("GITHUB_USERNAME");
    private static final String GITHUB_PASSWORD = System.getenv("GITHUB_TOKEN");
    private static final String GITHUB_BASIC_AUTH_VALUE = Base64.getEncoder().encodeToString((GITHUB_USERNAME + ":" + GITHUB_PASSWORD).getBytes(StandardCharsets.UTF_8));
    private static final String GITHUB_BASIC_AUTH_HEADER = "Basic %s";
    private static final Gson gson = new Gson();
    private static final String GITHUB_JSON_TEMPLATE = "{\"encrypted_value\":\"%s\",\"key_id\":\"%s\"}";

    @Override
    @CheckForNull
    public GitHubPublicKey getRepositoryPublicKey(String repositoryName) {
        LOGGER.log(Level.INFO, "GET call to retrieve public key for {}", new Object[]{repositoryName});
        URL url;
        try {
            url = URI.create(String.format(GITHUB_PUBLIC_KEY_URL, repositoryName)).toURL();
        } catch (MalformedURLException e) {
            LOGGER.log(Level.SEVERE, "Malformed URL", e);
            return null;
        }

        int responseCode = 0;
        int attemptNumber = 1;
        int maxAttempts = 3;
        while (responseCode != HttpURLConnection.HTTP_OK) {

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to open connection", e);
                return null;
            }

            // The GitHub API doesn't do an auth challenge
            conn.setRequestProperty("Authorization", String.format(GITHUB_BASIC_AUTH_HEADER, GITHUB_BASIC_AUTH_VALUE));
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try {
                conn.setRequestMethod("GET");
            } catch (ProtocolException e) {
                LOGGER.log(Level.SEVERE, "Protocol error", e);
                return null;
            }
            try {
                conn.connect();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Connection error", e);
                return null;
            }

            try {
                responseCode = conn.getResponseCode();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "ResponseCode error", e);
                return null;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                if (attemptNumber == maxAttempts) {
                    LOGGER.log(Level.WARNING, "Failed to retrieve public key for {}, response code: {}", new Object[]{repositoryName, responseCode});
                    return null;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "Interrupted", e);
                    return null;
                }
                LOGGER.log(Level.INFO, "Retrying retrieving public key for {} attempt {}/{}", new Object[]{repositoryName, attemptNumber, maxAttempts});
                attemptNumber++;
            } else {
                return retrievePublicKeyFromResponse(conn);
            }
        }
        return null;
    }

    private static GitHubPublicKey retrievePublicKeyFromResponse(HttpURLConnection conn) {
        try (final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            final String text = bufferedReader.lines().collect(Collectors.joining());
            final JsonObject json = gson.fromJson(text, JsonObject.class);
            return new GitHubPublicKey(json.get("key_id").getAsString(), json.get("key").getAsString());
        } catch (final IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to parse github response", e);
        }
        return null;
    }

    @Override
    public void createOrUpdateRepositorySecret(String name, String encryptedSecret, String repositoryName, String keyId) {
        LOGGER.log(Level.INFO, "Create/update the secret {} for {} encrypted with key {}", new Object[]{name, repositoryName, keyId});
        URL url;
        try {
            url = URI.create(String.format(GITHUB_SECRET_URL, repositoryName, name)).toURL();
        } catch (MalformedURLException e) {
            LOGGER.log(Level.SEVERE, "Malformed URL", e);
            return;
        }

        int responseCode = 0;
        int attemptNumber = 1;
        int maxAttempts = 3;
        while (responseCode != HttpURLConnection.HTTP_NO_CONTENT) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) url.openConnection();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Connection error", e);
                return;
            }

            // The GitHub API doesn't do an auth challenge
            conn.setRequestProperty("Authorization", String.format(GITHUB_BASIC_AUTH_HEADER, GITHUB_BASIC_AUTH_VALUE));
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            try {
                conn.setRequestMethod("PUT");
            } catch (ProtocolException e) {
                LOGGER.log(Level.SEVERE, "Protocol error", e);
                return;
            }
            conn.setDoOutput(true);

            try(OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream())) {
                osw.write(String.format(GITHUB_JSON_TEMPLATE,encryptedSecret, keyId));
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "IO Error", e);
                return;
            }
            try {
                responseCode = conn.getResponseCode();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "ResponseCode error", e);
                return;
            }

            if (responseCode != HttpURLConnection.HTTP_NO_CONTENT) {
                if (attemptNumber == maxAttempts) {
                    LOGGER.log(Level.WARNING, "Failed to create/update secret {} for {}, response code: {}", new Object[]{name, repositoryName, responseCode});
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "Interrupted", e);
                    return;
                }
                LOGGER.log(Level.INFO, "Retrying create/update secret {} for {} attempt {}/{}", new Object[]{name, repositoryName, attemptNumber, maxAttempts});
                attemptNumber++;
            }
        }
    }
}
