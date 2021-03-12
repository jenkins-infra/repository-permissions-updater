package io.jenkins.infra.repository_permissions_updater;

import groovy.json.JsonSlurper;

class KnownUsers {
    /**
     * URL to JSON with a list of valid Artifactory user names.
     */
    private static final String ARTIFACTORY_USER_NAMES_URL = System.getProperty('artifactoryUserNamesJsonListUrl', 'https://reports.jenkins.io/artifactory-ldap-users-report.json');
    private static List<String> knownArtifactoryUsers = new JsonSlurper().parse(new URL(ARTIFACTORY_USER_NAMES_URL))

    static boolean existsInArtifactory(String username) {
        return knownArtifactoryUsers.contains(username.toLowerCase())
    }

    /**
     * URL to JSON with a list of valid Jira user names.
     */
    private static final String JIRA_USER_NAMES_URL = System.getProperty('jiraUserNamesJsonListUrl', 'https://reports.jenkins.io/artifactory-ldap-users-report.json'); // TODO Fix URL
    private static List<String> knownJiraUsers = new JsonSlurper().parse(new URL(JIRA_USER_NAMES_URL))

    static boolean existsInJira(String username) {
        return knownJiraUsers.contains(username)
    }
}
