package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import java.io.IOException;
import java.util.Set;

import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHTeam;

public interface GitHubService {

    GHOrganization getOrganization(String name) throws IOException;

    void addDeveloperToTeam(GHTeam team, String developer) throws IOException;

    void removeDeveloperFromTeam(GHTeam team, String developer) throws IOException;

    Set<String> getCurrentTeamMembers(GHTeam team) throws IOException;

    GHTeam createTeam(String orgName, String teamName, GHTeam.Privacy privacy) throws IOException;
}