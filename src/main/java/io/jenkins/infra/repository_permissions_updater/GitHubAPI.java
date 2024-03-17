package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;

import java.io.*;
import java.net.HttpURLConnection;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


abstract public class GitHubAPI {

    private static final Logger LOGGER = Logger.getLogger(GitHubAPI.class.getName());

    /* Interface */
    static final class GitHubPublicKey {
        private final String KeyId;
        private final String Key;

        GitHubPublicKey(String keyId, String key) {
            KeyId = keyId;
            Key = key;
        }

        public String getKeyId() {
            return KeyId;
        }

        public String getKey() {
            return Key;
        }

        @Override
        public String toString() {
            return "GithubPublicKey{" +
                    "KeyId='" + KeyId + '\'' +
                    ", Key='" + Key + '\'' +
                    '}';
        }
    }

    /**
     * Returns a repository's public key to be used to encrypt secrets.
     *
     * @link https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#get-a-repository-public-key
     * @param repository the repository (as org/repo)
     * @return the base64 encoded public key
     */
    abstract GitHubPublicKey getRepositoryPublicKey(String repository);

    /**
     * Creates or update a secret in a repository.
     *
     * @param name            the secret name
     * @param encryptedSecret the encrypted, base64 encoded secret value
     * @param repositoryName  the repository name
     * @link https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#create-or-update-a-repository-secret
     */

    abstract void createOrUpdateRepositorySecret(String name, String encryptedSecret, String repositoryName, String keyId);


    /* Singleton support */
    private static GitHubAPI INSTANCE;

    static synchronized GitHubAPI getInstance()
    {
        if (INSTANCE == null) {
            INSTANCE = new GitHubImpl();
        }
        return INSTANCE;
    }

    /* Implementation */
    private static class GitHubImpl extends GitHubAPI {

        @Override
        GitHubPublicKey getRepositoryPublicKey(String repositoryName) {
            LOGGER.log(Level.INFO ,String.format("GET call to retrieve public key for %s" , repositoryName) );

            try {
                URL url = new URL(
                        String.format("https://api.github.com/repos/%s/actions/secrets/public-key",repositoryName)
                );

                int responseCode;
                int attemptNumber = 1;
                int maxAttempts = 3;

                while (attemptNumber <= maxAttempts)
                {
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                    // The GitHub API doesn't do an auth challenge
                    conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((System.getenv("GITHUB_USERNAME") + ':' + System.getenv("GITHUB_TOKEN")).getBytes(StandardCharsets.UTF_8)));
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setRequestMethod("GET");
                    conn.connect();

                    responseCode = conn.getResponseCode();

                    if (responseCode != HttpURLConnection.HTTP_OK)
                    {
                        if (responseCode ==  maxAttempts)
                        {
                            LOGGER.log(Level.WARNING,
                                    String.format(
                                            "Failed to retrieve public key for %s, response code: %s",repositoryName , responseCode)
                            );
                            return null;
                        }
                        Thread.sleep(200);
                        LOGGER.log(Level.INFO,
                                String.format("Retrying retrieving public key for %s attempt %s/%s",repositoryName , attemptNumber , maxAttempts)
                        );
                        attemptNumber++;
                    } else {
                        return retrievePublicKeyFromResponse(conn);
                    }
                }
            } catch (IOException e ) {

                LOGGER.log(Level.WARNING,
                        String.format(
                                "RuntimeError: Failed to retrieve public key for %s,",repositoryName)
                );
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        private static GitHubPublicKey retrievePublicKeyFromResponse(HttpURLConnection conn) throws IOException {

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            Gson gson = new Gson();

            Map<String, String> data = gson.fromJson(response.toString(), Map.class);

            return new GitHubPublicKey(
                    data.get("key_id"),
                    data.get("key")
            );
        }

        @Override
        void createOrUpdateRepositorySecret(String name, String encryptedSecret, String repositoryName, String keyId) {

            LOGGER.log(Level.INFO, String.format(
                    "Create/update the secret %s for %s encrypted with key %s",
                    name , repositoryName , keyId
                    )
            );

            try {
                URL url = new URL(String.format(
                        "https://api.github.com/repos/%s/actions/secrets/%s",repositoryName,name
                ));

                int responseCode;
                int attemptNumber = 1;
                int maxAttempts = 3;

                while (attemptNumber <= maxAttempts)
                {
                    HttpURLConnection conn = ( HttpURLConnection) url.openConnection();

                    // The GitHub API doesn't do an auth challenge
                    conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((System.getenv("GITHUB_USERNAME") + ':' + System.getenv("GITHUB_TOKEN")).getBytes(StandardCharsets.UTF_8)));
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream());
                    osw.write(
                            String.format(
                                    "{\"encrypted_value\":\"%s\",\"key_id\":\"%s\"}",
                                    encryptedSecret, keyId));
                    osw.close();
                    responseCode = conn.getResponseCode();

                    if (responseCode == HttpURLConnection.HTTP_NO_CONTENT )
                        break; // Action completed !

                    if (attemptNumber == maxAttempts) {
                        LOGGER.log(Level.WARNING,
                                String.format("Failed to create/update secret %s for %s, response code: %s",
                                        name , repositoryName , responseCode)
                        );
                        break;
                    }

                    Thread.sleep(200);
                    LOGGER.log(Level.INFO,
                            String.format("Retrying create/update secret %s for %s attempt %s/%s",
                                    name ,repositoryName , attemptNumber , maxAttempts)
                    );
                    attemptNumber++;
                }


            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }

        }
    }


}
