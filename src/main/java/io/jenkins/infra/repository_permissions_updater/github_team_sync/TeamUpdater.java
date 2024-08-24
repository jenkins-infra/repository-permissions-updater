package io.jenkins.infra.repository_permissions_updater.github_team_sync;


import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.jenkins.infra.repository_permissions_updater.github_team_sync.Role.*;

public class TeamUpdater {
    private static final Logger logger = LoggerFactory.getLogger(TeamUpdater.class);

    private final GitHubService gitHubService;


    public TeamUpdater(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    public void updateTeam(RepoTeamDefinition team) {
        try {
            String orgName = team.getOrgName();
            String teamName = team.getTeamName();
            GHOrganization org = gitHubService.getOrganization(orgName);
            String repoName = team.getRepoName();
            GHRepository repo = org.getRepository(repoName);
            GHTeam ghTeam = org.getTeamByName(teamName);
            Role teamRole = team.getRole();


            // Create team if it doesn't exist
            if (ghTeam == null) {
                ghTeam = gitHubService.createTeam(orgName, teamName, GHTeam.Privacy.CLOSED);
                logger.info("Team '{}' created.", teamName);
                gitHubService.updateTeamRole(repo, ghTeam, teamRole);
                logger.info("Team role for '{}' updated to '{}' in repository '{}'.", teamName, teamRole, repo.getName());
            }

            // Remove team if team name and developers are all empty
            if ((teamName == null || teamName.trim().isEmpty()) && team.getDevelopers().isEmpty()) {
                String potentialTeamName = repoName + " Developers";
                GHTeam currTeam = gitHubService.getTeamFromRepo(orgName, repoName, potentialTeamName);
                if (currTeam != null) {
                    gitHubService.removeTeamFromRepository(currTeam, repo);
                }
            }

            // Update team members if they are different from the yaml definition
            updateTeamMembers(ghTeam, team.getDevelopers(), teamName);
            // Update role of other teams in repository if it's different from the yaml definition
            updateAdditionalTeam(org, repo, ghTeam, team.getAdditionalTeams());

        } catch (IOException e) {
            logger.error("Error updating team", e);
        }
    }

    public void updateSpecialTeam(SpecialTeamDefinition team) {
        try {
            String orgName = team.getOrgName();
            String teamName = team.getTeamName();
            GHOrganization org = gitHubService.getOrganization(orgName);
            GHTeam ghTeam = org.getTeamByName(teamName);

            if (ghTeam == null) {
                throw new IOException("Team not found: " + teamName);
            } else {
                updateTeamMembers(ghTeam, team.getDevelopers(), teamName);
            }

        } catch (Exception e) {
            logger.error("Error updating special team", e);
        }

    }


    private void updateTeamMembers(GHTeam ghTeam, Set<String> developers, String teamName) throws IOException {
        Set<String> currentMembers = gitHubService.getCurrentTeamMembers(ghTeam);
        // Add new developers from yaml file
        for (String dev : developers) {
            if (!currentMembers.contains(dev)) {
                gitHubService.addDeveloperToTeam(ghTeam, dev);
                logger.info("Developer: '" + dev + "' added to team: " + teamName);
            }
        }
        // Remove developers not in yaml file
        for (String member : currentMembers) {
            if (!developers.contains(member)) {
                gitHubService.removeDeveloperFromTeam(ghTeam, member);
                logger.info("Developer: '" + member + "' removed from team: " + teamName);
            }
        }
    }

    private void updateAdditionalTeam(
            GHOrganization org, GHRepository repo, GHTeam repoTeam,
            Set<AdditionalTeamDefinition> additionalTeams) throws IOException {

        Set<String> currentTeamMap = gitHubService.getCurrentTeams(repo, repoTeam);
        boolean isFirstRun = FirstRunCheck.isFirstRun();

        Set<String> additionalTeamNames = new HashSet<>();
        // update roles of additional teams from yaml file
        for (AdditionalTeamDefinition additionalTeam : additionalTeams) {
            String name = additionalTeam.getName();
            Role role = additionalTeam.getRole();
            GHTeam ghTeam = org.getTeamByName(name);
            additionalTeamNames.add(name);

            if (ghTeam != null && role != null) {
                gitHubService.updateTeamRole(repo, ghTeam, role);
            } else if (ghTeam == null) {
                logger.error("Additional team not found: " + name);
            }
        }

        // remove teams that are not in the yaml file
        for (String currentTeam : currentTeamMap) {
            GHTeam ghTeam = org.getTeamByName(currentTeam);

            if (!additionalTeamNames.contains(currentTeam)) {
                // backfill team name if it's the first run
                if (isFirstRun) {
                    // Note: currently, roles are not handled due to API limitations. Therefore, the role is set to null.
                    YamlTeamManager.backfillAdditionalTeamName(currentTeam, null);
                } else {
                    ghTeam.remove(repo);
                }
            }
        }
    }
}



