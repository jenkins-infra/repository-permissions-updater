package io.jenkins.infra.repository_permissions_updater;

abstract class GitHubAPI {
    /**
     * True iff this is a dry-run (no API calls resulting in modifications)
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    /* Interface */
    abstract String getRepositoryPublicKey(String repository);

    abstract String createOrUpdateRepositorySecret();

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
        String getRepositoryPublicKey(String repositoryName) {
            // TODO
            return null;
        }

        @Override
        String createOrUpdateRepositorySecret() {
            // TODO
            return null;
        }
    }
}
