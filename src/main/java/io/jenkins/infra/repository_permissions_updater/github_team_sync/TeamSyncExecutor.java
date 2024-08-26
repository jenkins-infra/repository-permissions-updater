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
        GitHubService gitHubService = new GitHubServiceImpl(System.getenv("GITHUB_OAUTH"));
        TeamUpdater teamUpdater = new TeamUpdater(gitHubService);
        TeamSyncExecutor executor = new TeamSyncExecutor(teamUpdater);

        executor.run(args);
    }

    public void run(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("No file path provided.");
        }

        for (String yamlFilePath : args) {
            try {
                logger.info("Processing team configuration for file: " + yamlFilePath);
                Object team = YamlTeamManager.loadTeam(yamlFilePath);

                if (team instanceof RepoTeamDefinition) {
                    teamUpdater.updateTeam((RepoTeamDefinition) team);
                } else if (team instanceof SpecialTeamDefinition) {
                    teamUpdater.updateSpecialTeam((SpecialTeamDefinition) team);
                } else {
                    throw new IllegalArgumentException("Unsupported team definition type.");
                }
            } catch (Exception e) {
                logger.error("Failed to update team for file " + yamlFilePath + ": " + e.getMessage(), e);
            }
        }
    }
}