package io.jenkins.infra.repository_permissions_updater;

import java.io.IOException;

public abstract class JiraAPI implements Definition.IssueTracker.JiraComponentSource {

    static JiraAPI INSTANCE = null;

    public abstract String getComponentId(String componentName) throws IOException;

    abstract boolean isUserPresent(String username);

    /* Singleton support */
    public static synchronized JiraAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new JiraImpl(System.getProperty("jiraUrl", "https://issues.jenkins.io"));
        }
        return INSTANCE;
    }
}
