package io.jenkins.infra.repository_permissions_updater.cli.commands;

import io.jenkins.infra.repository_permissions_updater.GitHubPermissionsSyncer;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * Computes the GitHub permissions diff (desired vs. actual team membership for opted-in components/teams),
 * and either reports it or applies it, without touching Artifactory at all. Useful for testing
 * {@code manageGitHubPermissions}/{@code manageGitHubTeam} changes for one or a few components in isolation,
 * e.g. against a checkout containing only {@code permissions/plugin-slack.yml}.
 * <p>
 * Requires a {@code GITHUB_TOKEN} environment variable with enough access to read (and, if not running in
 * dry-run mode, write) membership of the teams involved (an org member's token with {@code read:org} scope
 * at minimum for reads; adding/removing members requires the token's owner to be a maintainer of those teams
 * or an org owner).
 * <p>
 * Configuration is via system properties (see Jenkinsfile for examples of the same properties used by the
 * {@code sync} command):
 * <ul>
 *   <li>{@code definitionsDir} (default {@code ./permissions})</li>
 *   <li>{@code teamsDir} (default {@code ./teams})</li>
 *   <li>{@code githubDiffOutput} (default {@code ./json/github-permissions-diff.json})</li>
 *   <li>{@code githubPermissionsDryRun} (default {@code true}): when {@code true}, only computes and
 *       logs/reports the diff; when {@code false}, also applies it (adds/removes the affected GitHub team
 *       members). Defaults to {@code true} so running this command is safe by default -- pass
 *       {@code -DgithubPermissionsDryRun=false} deliberately to actually mutate GitHub.</li>
 * </ul>
 */
@Command(
        name = "github-sync",
        description = "Compute (and optionally apply) the GitHub permissions diff for opted-in components/teams"
                + " (no Artifactory calls)",
        mixinStandardHelpOptions = true)
public class GitHubSyncCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        File definitionsDir = new File(System.getProperty("definitionsDir", "./permissions"));
        File teamsDir = new File(System.getProperty("teamsDir", "./teams"));
        File reportFile = new File(System.getProperty("githubDiffOutput", "./json/github-permissions-diff.json"));
        boolean dryRun = Boolean.parseBoolean(System.getProperty("githubPermissionsDryRun", "true"));

        File parent = reportFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create output directory " + parent.getPath());
        }

        GitHubPermissionsSyncer.generateDiffReport(definitionsDir, teamsDir, reportFile, dryRun);
        System.out.println("Wrote GitHub permissions diff report to " + reportFile.getPath()
                + (dryRun ? " (dry-run, nothing applied)" : " (applied changes to GitHub)"));
        return 0;
    }
}
