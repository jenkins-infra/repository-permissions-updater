package io.jenkins.infra.repository_permissions_updater.github_team_sync;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeamSyncExecutor {
    private static final Logger logger = LoggerFactory.getLogger(TeamSyncExecutor.class);
    private final TeamUpdater teamUpdater;

    public TeamSyncExecutor(TeamUpdater teamUpdater) {
        this.teamUpdater = teamUpdater;
    }

    public static void main(String[] args) {
        // change your repo secret name here
        GitHubService gitHubService = new GitHubServiceImpl(System.getenv("GITHUB_OAUTH"));
        TeamUpdater teamUpdater = new TeamUpdater(gitHubService);
        TeamSyncExecutor executor = new TeamSyncExecutor(teamUpdater);

        executor.run(args);
    }

    public void run(String[] args) {
        if (args.length == 0) {
            logger.info("No file path provided.");
            System.exit(1);
        }

        for (String yamlFilePath : args) {
            try {
                logger.info("Processing team configuration for file: " + yamlFilePath);
                GithubTeamDefinition team = YAMLTeamLoader.loadTeam(yamlFilePath);
                teamUpdater.updateTeam(team);
            } catch (Exception e) {
                logger.error("Failed to update team for file " + yamlFilePath + ": " + e.getMessage(), e);
            }
        }
    }
}