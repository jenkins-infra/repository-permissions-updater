package io.jenkins.infra.repository_permissions_updater.github_team_sync;

public class GitHubTeamSyncExecutor {

    public static void main(String[] args) {
        
        if (args.length == 0) {
            System.out.println("No file path provided.");
            System.exit(1);
        }

        String yamlFilePath = args[0];
        GithubTeamDefinition team = yamlTeamLoader.loadTeam(yamlFilePath);
        TeamUpdater.updateTeam(team);
    }
    
}
