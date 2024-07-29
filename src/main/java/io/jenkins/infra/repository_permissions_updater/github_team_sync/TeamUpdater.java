package io.jenkins.infra.repository_permissions_updater.github_team_sync;


import java.io.IOException;
import java.util.Set;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeamUpdater {
    private static final Logger logger = LoggerFactory.getLogger(TeamUpdater.class);
    private final GitHubService gitHubService;

    public TeamUpdater(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    public void updateTeam(GithubTeamDefinition team) {
        try {
            String[] parts = team.getName().split("/");
            String orgName = parts[0];
            String repoName = parts[1];

            GHOrganization org = gitHubService.getOrganization(orgName);
            GHRepository repo = org.getRepository(repoName);
            GHTeam ghTeam = org.getTeamByName(team.getTeamName());


            if (repo != null) {
                // Create team if it doesn't exist
                if (ghTeam == null) {
                    ghTeam = gitHubService.createTeam(orgName, team.getTeamName(), GHTeam.Privacy.CLOSED);
                    ghTeam.add(repo, GHOrganization.RepositoryRole.custom("push"));
                    logger.info("Team: '" + team.getTeamName() + "' created and added to repository: " + repoName);
                }

                // Update team members
                updateTeamMembers(ghTeam, team.getDevelopers());
            } else {
                logger.info("Repository not found: " + repoName);
            }
        } catch (IOException e) {
            logger.error("Error updating team", e);
        }
    }

    private void updateTeamMembers(GHTeam ghTeam, Set<String> developers) throws IOException {
        Set<String> currentMembers = gitHubService.getCurrentTeamMembers(ghTeam);
        for (String dev : developers) {
            if (!currentMembers.contains(dev)) {
                gitHubService.addDeveloperToTeam(ghTeam, dev);
                logger.info("Developer: '" + dev + "' added to team: " + ghTeam.getName());
            }
        }
        for (String member : currentMembers) {
            if (!developers.contains(member)) {
                gitHubService.removeDeveloperFromTeam(ghTeam, member);
                logger.info("Developer: '" + member + "' removed from team: " + ghTeam.getName());
            }
        }
    }
}


