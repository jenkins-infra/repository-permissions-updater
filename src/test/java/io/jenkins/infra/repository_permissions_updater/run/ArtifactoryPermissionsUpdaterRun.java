package io.jenkins.infra.repository_permissions_updater.run;

class ArtifactoryPermissionsUpdaterRun {
    public static void main(String[] args) throws Exception {
        System.setProperty("dryRun", "true");
        String[] runArgs = {"--dry-run", "--definitionsDir=./temp/def", "--artifactoryApiTempDir=./temp/artiifacts"};
        ArtifactoryPermissionsUpdater.main(runArgs);
    }
}
