package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import java.util.Set;

public class RepoTeamDefinition {

    private String repoName;
    private String orgName;
    private String teamName;

    private static final String DEFAULT_ORG_NAME = "jenkinsci";
    private final Role role = Role.ADMIN;
    private Set<String> developers;
    private Set<AdditionalTeamDefinition> additionalTeams;

    public RepoTeamDefinition(String repoName, String orgName, String teamName,
                              Set<String> developers, Set<AdditionalTeamDefinition> additionalTeams) {
        this.repoName = repoName;
        this.orgName = orgName != null ? orgName : DEFAULT_ORG_NAME;
        this.teamName = teamName;
        this.developers = developers;
        this.additionalTeams = additionalTeams;
    }
    
    public RepoTeamDefinition() {
    }


    public String getRepoName() {
        return repoName;
    }

    public String getOrgName() {
        return orgName;
    }

    public String getTeamName() {
        return teamName;
    }

    public Role getRole() {
        return role;
    }

    public Set<String> getDevelopers() {
        return developers;
    }

    public Set<AdditionalTeamDefinition> getAdditionalTeams() {
        return additionalTeams;
    }
}