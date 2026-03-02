package io.jenkins.infra.repository_permissions_updater.cli.commands;

import io.jenkins.infra.repository_permissions_updater.ArtifactoryPermissionsUpdater;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * Command to sync permissions to Artifactory.
 * Configuration is via system properties (see Jenkinsfile for examples).
 */
@Command(
        name = "sync",
        description = "Sync permissions to Artifactory based on YAML definitions",
        mixinStandardHelpOptions = true)
public class SyncCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        ArtifactoryPermissionsUpdater.syncPermissions();
        return 0;
    }
}
