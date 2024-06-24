package io.jenkins.infra.repository_permissions_updater.jira;

public interface JiraAPI {

    static JiraAPI instance() {
        return JiraAPIImpl.getInstance();
    }

    Long getComponentId(String componentName);

    boolean isUserPresent(String username);

}
