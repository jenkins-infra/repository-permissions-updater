package io.jenkins.infra.repository_permissions_updater;

public abstract class JiraAPI implements Definition.IssueTracker.JiraComponentSource {

    static JiraAPI INSTANCE = null;

    public abstract String getComponentId(String componentName);

    abstract boolean isUserPresent(String username);

    /* Singleton support */
    public static synchronized JiraAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new JiraImpl();
        }
        return INSTANCE;
    }
}
