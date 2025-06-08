package io.jenkins.infra.repository_permissions_updater;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

public abstract class GitHubAPI {
    static GitHubAPI INSTANCE = null;

    static final class GitHubPublicKey {
        private final String keyId;
        private final String key;

        GitHubPublicKey(@NonNull String keyId, @NonNull String key) {
            this.keyId = keyId;
            this.key = key;
        }

        String getKeyId() {
            return keyId;
        }

        String getKey() {
            return key;
        }

        @Override
        public String toString() {
            return keyId + '/' + key;
        }
    }

    /**
     * Returns a repository's public key to be used to encrypt secrets.
     *
     * @link https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#get-a-repository-public-key
     * @param repository the repository (as org/repo)
     * @return the base64 encoded public key
     */
    abstract GitHubPublicKey getRepositoryPublicKey(String repository) throws IOException;

    /**
     * Creates or update a secret in a repository.
     *
     * @link https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#create-or-update-a-repository-secret
     * @param name the secret name
     * @param encryptedSecret the encrypted, base64 encoded secret value
     * @param repositoryName the repository name
     */
    abstract void createOrUpdateRepositorySecret(
            String name, String encryptedSecret, String repositoryName, String keyId) throws IOException;

    static synchronized GitHubAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GitHubImpl(
                    "https://api.github.com/repos/%s/actions/secrets/public-key",
                    "https://api.github.com/repos/%s/actions/secrets/%s");
        }
        return INSTANCE;
    }
}
