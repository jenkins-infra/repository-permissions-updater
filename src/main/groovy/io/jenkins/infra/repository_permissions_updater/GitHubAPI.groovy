package io.jenkins.infra.repository_permissions_updater

import edu.umd.cs.findbugs.annotations.CheckForNull
import edu.umd.cs.findbugs.annotations.NonNull
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressFBWarnings("LI_LAZY_INIT_STATIC") // Something related to Groovy
abstract class GitHubAPI {
    private static final Logger LOGGER = Logger.getLogger(GitHubAPI.class.getName())

    /* Interface */

    static final class GitHubPublicKey {
        private final String keyId
        private final String key

        GitHubPublicKey(@NonNull String keyId, @NonNull String key) {
            this.keyId = keyId
            this.key = key
        }

        String getKeyId() {
            return keyId
        }

        String getKey() {
            return key
        }

        @Override
        String toString() {
            Objects.toString(keyId) + '/' + Objects.toString(key)
        }
    }

    /**
     * Returns a repository's public key to be used to encrypt secrets.
     *
     * @link https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#get-a-repository-public-key
     * @param repository the repository (as org/repo)
     * @return the base64 encoded public key
     */
    abstract GitHubPublicKey getRepositoryPublicKey(String repository)

    /**
     * Creates or update a secret in a repository.
     *
     * @link https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#create-or-update-a-repository-secret
     * @param name the secret name
     * @param encryptedSecret the encrypted, base64 encoded secret value
     * @param repositoryName the repository name
     */
    abstract void createOrUpdateRepositorySecret(String name, String encryptedSecret, String repositoryName, String keyId);

    /* Singleton support */
    private static GitHubAPI INSTANCE = null;
    static synchronized GitHubAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GitHubImpl();
        }
        return INSTANCE;
    }

    /* Implementation */
    private static class GitHubImpl extends GitHubAPI {
        @Override
        @CheckForNull
        GitHubPublicKey getRepositoryPublicKey(String repositoryName) {
            LOGGER.log(Level.INFO, "GET call to retrieve public key for ${repositoryName}")
            URL url = new URL("https://api.github.com/repos/${repositoryName}/actions/secrets/public-key")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()

            // The GitHub API doesn't do an auth challenge
            conn.setRequestProperty('Authorization', 'Basic ' + Base64.getEncoder().encodeToString((System.getenv("GITHUB_USERNAME") + ':' + System.getenv("GITHUB_TOKEN")).getBytes(StandardCharsets.UTF_8)))
            conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
            conn.setRequestMethod('GET')
            conn.connect()
            String text = conn.getInputStream().getText()

            def json = new JsonSlurper().parseText(text)
            return new GitHubPublicKey(json.key_id, json.key)
        }

        @Override
        void createOrUpdateRepositorySecret(String name, String encryptedSecret, String repositoryName, String keyId) {
            LOGGER.log(Level.INFO, "Create/update the secret ${name} for ${repositoryName} encrypted with key ${keyId}")
            URL url = new URL("https://api.github.com/repos/${repositoryName}/actions/secrets/${name}")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()

            // The GitHub API doesn't do an auth challenge
            conn.setRequestProperty('Authorization', 'Basic ' + Base64.getEncoder().encodeToString((System.getenv("GITHUB_USERNAME") + ':' + System.getenv("GITHUB_TOKEN")).getBytes(StandardCharsets.UTF_8)))
            conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
            conn.setRequestMethod('PUT')
            conn.setDoOutput(true)
            OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream())
            osw.write('{"encrypted_value":"' + encryptedSecret + '","key_id":"' + keyId + '"}')
            osw.close()

            String text = conn.getInputStream().getText()
        }
    }
}
