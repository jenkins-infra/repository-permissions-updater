package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.kohsuke.github.*;


public class GitHubServiceImpl implements GitHubService {
    private GitHub github;

    private static final Map<Role, GHOrganization.Permission> PERMISSIONS_MAP = Map.of(
            Role.READ, GHOrganization.Permission.PULL,
            Role.TRIAGE, GHOrganization.Permission.TRIAGE,
            Role.WRITE, GHOrganization.Permission.PUSH,
            Role.MAINTAIN, GHOrganization.Permission.MAINTAIN,
            Role.ADMIN, GHOrganization.Permission.ADMIN
    );

    public GitHubServiceImpl(String oauthToken) {
        try {
            this.github = new GitHubBuilder().withOAuthToken(oauthToken).build();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public GHTeam getTeamFromRepo(
            String repoName, String orgName, String teamName) throws IOException {
        GHOrganization org = github.getOrganization(orgName);
        GHRepository repo = org.getRepository(repoName);
        Set<GHTeam> teams = ((GHRepository) repo).getTeams();

        for (GHTeam team : teams) {
            if (team.getName().equals(teamName)) {
                return team;
            }
        }
        return null;
    }

    @Override
    public GHOrganization getOrganization(String name) throws IOException {
        return github.getOrganization(name);
    }


    @Override
    public void addDeveloperToTeam(GHTeam team, String developer) throws IOException {
        GHUser user = github.getUser(developer);
        team.add(user);
    }

    @Override
    public void removeDeveloperFromTeam(GHTeam team, String developer) throws IOException {
        GHUser user = github.getUser(developer);
        team.remove(user);
    }

    @Override
    public Set<String> getCurrentTeamMembers(GHTeam team) throws IOException {
        Set<String> members = new HashSet<>();
        for (GHUser member : team.listMembers()) {
            members.add(member.getLogin());
        }
        return members;
    }

    @Override
    public GHTeam createTeam(String orgName, String teamName, GHTeam.Privacy privacy) throws IOException {
        GHOrganization org = github.getOrganization(orgName);
        return org.createTeam(teamName).privacy(privacy).create();
    }

    @Override
    public void updateTeamRole(GHRepository repo, GHTeam ghTeam, Role role) throws IOException {
        GHOrganization.Permission permission = PERMISSIONS_MAP.get(role);
        GHOrganization.RepositoryRole repoRole = GHOrganization.RepositoryRole.from(permission);
        ghTeam.add(repo, repoRole);
    }

    @Override
    public void removeTeamFromRepository(GHTeam team, GHRepository repo) throws IOException {
        team.remove(repo);
    }

    /**
     * Retrieves the names of all additional teams associated with the given GitHub repository, excluding the repo team.
     * This method returns only team names because the current Java GitHub API does not support retrieving roles
     * that teams hold within specific repositories. Therefore, role-related information is not available.
     */
    @Override
    public Set<String> getCurrentTeams(GHRepository repo, GHTeam repoTeam) throws IOException {
        Set<GHTeam> allTeams = repo.getTeams();

        return allTeams.stream()
                .filter(team -> !team.equals(repoTeam))
                .map(GHTeam::getName)
                .collect(Collectors.toSet());
    }

}
