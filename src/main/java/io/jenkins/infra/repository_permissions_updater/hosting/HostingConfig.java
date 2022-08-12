package io.jenkins.infra.repository_permissions_updater.hosting;

public final class HostingConfig {

    static final String TARGET_ORG_NAME;
    static final String INFRA_ORGANIZATION;
    static final String HOSTING_REPO_SLUG;
    static final String HOSTING_REPO_NAME = "repository-permissions-updater";
    static final String JIRA_URL = "https://issues.jenkins.io";
    static final String JIRA_USERNAME = System.getenv("JIRA_USERNAME");
    static final String JIRA_PASSWORD = System.getenv("JIRA_PASSWORD");
    static final String JIRA_PROJECT;

    private HostingConfig() {
    }

    static {
        String orgOverride = System.getenv("ORG_NAME");
        INFRA_ORGANIZATION = orgOverride != null ? orgOverride : "jenkins-infra";
        HOSTING_REPO_SLUG = INFRA_ORGANIZATION + "/" + HOSTING_REPO_NAME;

        String targetOrgOverride = System.getenv("TARGET_ORG_NAME");
        TARGET_ORG_NAME = targetOrgOverride != null ? targetOrgOverride : "jenkinsci";

        String projectOverride = System.getenv("JIRA_PROJECT_NAME");
        JIRA_PROJECT = projectOverride != null ? projectOverride : "JENKINS";
    }
}
