package io.jenkins.infra.repository_permissions_updater

import edu.umd.cs.findbugs.annotations.CheckForNull
import edu.umd.cs.findbugs.annotations.NonNull
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.json.JsonOutput
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

    /**
     * Adds a topic to a repository
     *
     * @param repositoryName the repository name
     * @param topic the topic to add to the repository
     */
    abstract void addTopicToRepository(String repositoryName, String topic);

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
            HttpURLConnection conn = createConnection("https://api.github.com/repos/${repositoryName}/actions/secrets/public-key", 'GET')
            conn.connect()
            String text = conn.getInputStream().getText()

            def json = new JsonSlurper().parseText(text)
            return new GitHubPublicKey(json.key_id, json.key)
        }

        @Override
        void createOrUpdateRepositorySecret(String name, String encryptedSecret, String repositoryName, String keyId) {
            LOGGER.log(Level.INFO, "Create/update the secret ${name} for ${repositoryName} encrypted with key ${keyId}")
            HttpURLConnection conn = createConnection("https://api.github.com/repos/${repositoryName}/actions/secrets/${name}", 'PUT')
            conn.setDoOutput(true)
            OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream())
            osw.write('{"encrypted_value":"' + encryptedSecret + '","key_id":"' + keyId + '"}')
            osw.close()

            String text = conn.getInputStream().getText()
        }

        private static HttpURLConnection createConnection(String url, String method) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()

            conn.setRequestProperty('Authorization', 'Basic ' + Base64.getEncoder().encodeToString((System.getenv("GITHUB_USERNAME") + ':' + System.getenv("GITHUB_TOKEN")).getBytes(StandardCharsets.UTF_8)))
            conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
            conn.setRequestMethod(method)
        }

        @Override
        void addTopicToRepository(String repositoryName, String topic) {
            LOGGER.log(Level.INFO, "Add topic ${topic} on ${repositoryName}")

            HttpURLConnection conn = createConnection("https://api.github.com/repos/${repositoryName}/topics", 'GET')
            conn.connect()

            def response = conn.getInputStream().getText()
            conn.disconnect()
            def json = new JsonSlurper().parseText(response)

            if (!json.names.contains(topic)) {
                json.names.add(topic)
                conn = createConnection("https://api.github.com/repos/${repositoryName}/topics", 'PUT')
                conn.setDoOutput(true)
                def osw = new OutputStreamWriter(conn.getOutputStream())
                osw.write(JsonOutput.toJson(json))
                osw.close()

                conn.getInputStream().getText();
            } else {
                LOGGER.log(Level.INFO, "Repository ${repositoryName} already has topic ${topic}")
            }
        }
    }
}
