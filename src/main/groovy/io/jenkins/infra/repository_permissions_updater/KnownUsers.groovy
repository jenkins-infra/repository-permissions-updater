package io.jenkins.infra.repository_permissions_updater;

import groovy.json.JsonSlurper;

class KnownUsers {
    /**
     * URL to JSON with a list of valid Artifactory user names.
     */
    private static final String ARTIFACTORY_USER_NAMES_URL = System.getProperty('artifactoryUserNamesJsonListUrl', 'https://reports.jenkins.io/artifactory-ldap-users-report.json');
    private static Set<String> knownArtifactoryUsers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER)
    static {
        knownArtifactoryUsers.addAll(new JsonSlurper().parse(new URL(ARTIFACTORY_USER_NAMES_URL)))
    }

    static boolean existsInArtifactory(String username) {
        return knownArtifactoryUsers.contains(username)
    }

    /**
     * URL to JSON with a list of valid Jira user names.
     */
    private static final String JIRA_USER_NAMES_URL = System.getProperty('jiraUserNamesJsonListUrl', 'https://reports.jenkins.io/jira-users-report.json');
    private static Set<String> knownJiraUsers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER)
    static {
        knownJiraUsers.addAll(new JsonSlurper().parse(new URL(JIRA_USER_NAMES_URL)))
    }

    static boolean existsInJira(String username) {
        return knownJiraUsers.contains(username)
    }
}
