package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;


public class GitHubServiceImpl implements GitHubService {
    private GitHub github;

    public GitHubServiceImpl(String oauthToken) {
        try {
            this.github = new GitHubBuilder().withOAuthToken(oauthToken).build();
        } catch (IOException e) {
            e.printStackTrace();
        }
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
}
