package io.jenkins.infra.repository_permissions_updater;

public class TeamDefinition {

    private String name = "";
    private String[] developers = new String[0];

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getDevelopers() {
        return developers.clone();
    }

    public void setDevelopers(String[] developers) {
        this.developers = developers.clone();
    }
}
