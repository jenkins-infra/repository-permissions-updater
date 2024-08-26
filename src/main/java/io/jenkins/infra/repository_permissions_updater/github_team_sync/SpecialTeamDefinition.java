package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import java.util.Set;

public class SpecialTeamDefinition{
    private String orgName;
    private String teamName;
    private Set<String> developers;

    private static final String DEFAULT_ORG_NAME = "jenkinsci";

    public SpecialTeamDefinition(String orgName, String teamName, Set<String> developers) {
        this.orgName = orgName != null ? orgName : DEFAULT_ORG_NAME;
        this.teamName = teamName;
        this.developers = developers;
    }

    public SpecialTeamDefinition() {
    }

    public String getOrgName() {
        return orgName;
    }

    public String getTeamName() {
        return teamName;
    }

    public Set<String> getDevelopers() {
        return developers;
    }
}
