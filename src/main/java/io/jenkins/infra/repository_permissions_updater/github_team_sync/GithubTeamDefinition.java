package io.jenkins.infra.repository_permissions_updater.github_team_sync;

import java.util.Set;

public class GithubTeamDefinition {

    private String repoName;
    private String teamName;
    private Set<String> developers;

    public GithubTeamDefinition(String repoName, String teamName, Set<String> developers) {
        this.repoName = repoName;
        this.teamName = teamName;
        this.developers = developers;
    }

    public GithubTeamDefinition() {
    }

    public String getName() {
        return repoName;
    }

    public String getTeamName(){
        return teamName;
    }

    public Set<String> getDevelopers() {
        return developers;
    }
}


