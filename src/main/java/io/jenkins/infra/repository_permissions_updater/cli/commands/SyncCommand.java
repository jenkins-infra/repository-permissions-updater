package io.jenkins.infra.repository_permissions_updater.cli.commands;

import io.jenkins.infra.repository_permissions_updater.ArtifactoryPermissionsUpdater;
import io.jenkins.infra.repository_permissions_updater.GitHubPermissionsSyncer;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import picocli.CommandLine.Command;

/**
 * Command to sync permissions to Artifactory, and (independently) reconcile GitHub team membership for all
 * opted-in components/teams ({@code manageGitHubPermissions}/{@code manageGitHubTeam}).
 * Configuration is via system properties (see Jenkinsfile for examples).
 */
@Command(
        name = "sync",
        description = "Sync permissions to Artifactory based on YAML definitions",
        mixinStandardHelpOptions = true)
public class SyncCommand implements Callable<Integer> {

    private static final Logger LOGGER = Logger.getLogger(SyncCommand.class.getName());

    /**
     * Independent of the {@code dryRun} flag (read once in {@link #call()} and passed to
     * {@link ArtifactoryPermissionsUpdater}'s constructor, rather than each class re-reading the system
     * property itself): controls whether GitHub permissions management (for components that have opted in
     * via {@code manageGitHubPermissions}/{@code manageGitHubTeam}) actually mutates GitHub team membership,
     * or only computes and logs/reports the diff. Defaults to {@code true} (safe, report-only) so this stays
     * report-only until deliberately turned off, e.g. via {@code -DgithubPermissionsDryRun=false}. Has no
     * effect when {@code dryRun} is {@code true}, since GitHub permissions sync doesn't run in dry-run/PR
     * builds at all (no credentials).
     */
    private static final boolean GITHUB_PERMISSIONS_DRY_RUN =
            Boolean.parseBoolean(System.getProperty("githubPermissionsDryRun", "true"));

    @Override
    public Integer call() throws Exception {
        boolean dryRun = Boolean.getBoolean("dryRun");

        new ArtifactoryPermissionsUpdater(dryRun).syncPermissions();

        /*
         * Reconcile GitHub team membership for all opted-in components/teams (manageGitHubPermissions /
         * manageGitHubTeam), writing a JSON diff report either way. Whether this actually mutates GitHub or
         * only computes and logs/reports the diff is controlled independently by the
         * "githubPermissionsDryRun" system property -- see GitHubPermissionsSyncer's class javadoc. Wrapped
         * in try/catch so a GitHub-sync failure doesn't fail the whole command after Artifactory sync has
         * already succeeded.
         */
        if (!dryRun) {
            try {
                File definitionsDir = new File(System.getProperty("definitionsDir", "./permissions"));
                File artifactoryApiDir = new File(System.getProperty("artifactoryApiTempDir", "./json"));
                new GitHubPermissionsSyncer(GITHUB_PERMISSIONS_DRY_RUN)
                        .sync(
                                definitionsDir,
                                new File("teams/"),
                                new File(artifactoryApiDir, "github-permissions-diff.json"));
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to sync GitHub permissions", ex);
            }
        }

        return 0;
    }
}
