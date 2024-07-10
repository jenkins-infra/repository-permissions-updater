package io.jenkins.infra.repository_permissions_updater.launcher;

import io.jenkins.infra.repository_permissions_updater.ArtifactoryAPI;
import io.jenkins.infra.repository_permissions_updater.helper.ArtifactoryHelper;
import io.jenkins.infra.repository_permissions_updater.helper.PayloadHelper;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ArtifactoryPermissionsUpdater {

    /**
     * Temporary directory that this tool will write Artifactory API JSON payloads to. Must not exist prior to execution.
     */
    private static final File ARTIFACTORY_API_DIR = new File(System.getProperty("artifactoryApiTempDir", "./json"));
    /**
     * If enabled, will not send PUT/DELETE requests to Artifactory, only GET (i.e. not modifying).
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.getName());

    public static void main(String[] args) throws Exception {
        if (DRY_RUN_MODE) {
            LOGGER.log(Level.INFO, "Running in dry run mode");
        }
        ArtifactoryAPI artifactoryApi = ArtifactoryAPI.getInstance();
        /*
         * Generate JSON payloads from YAML permission definition files in DEFINITIONS_DIR and writes them to ARTIFACTORY_API_DIR.
         * Any problems with the input here are fatal so PR builds fails.
         */
        PayloadHelper.create(ARTIFACTORY_API_DIR.toPath()).run();
        /*
         * Submit generated Artifactory group JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
         File groupsJsonDir = new File(ARTIFACTORY_API_DIR, "groups");
        ArtifactoryHelper.submitArtifactoryObjects(groupsJsonDir, "group", artifactoryApi::createOrReplaceGroup);
        ArtifactoryHelper.removeExtraArtifactoryObjects(groupsJsonDir, "group", artifactoryApi::listGeneratedGroups, artifactoryApi::deleteGroup);
        /*
         * Submit generated Artifactory permission target JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        File permissionTargetsJsonDir = new File(ARTIFACTORY_API_DIR, "permissions");
        ArtifactoryHelper.submitArtifactoryObjects(permissionTargetsJsonDir, "permission target", artifactoryApi::createOrReplacePermissionTarget);
        ArtifactoryHelper.removeExtraArtifactoryObjects(permissionTargetsJsonDir, "permission target", artifactoryApi::listGeneratedPermissionTargets, artifactoryApi::deletePermissionTarget);
        /*
         * For all CD-enabled GitHub repositories, obtain a token from Artifactory and attach it to a GH repo as secret.
         */
        ArtifactoryHelper.generateTokens(new File(ARTIFACTORY_API_DIR, "cd.index.json"));
    }
}
