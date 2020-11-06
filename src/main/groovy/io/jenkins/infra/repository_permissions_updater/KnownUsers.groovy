package io.jenkins.infra.repository_permissions_updater;

import groovy.json.JsonSlurper;

class KnownUsers {
    /**
     * URL to JSON with a list of valid Artifactory user names.
     */
    private static final String ARTIFACTORY_USER_NAMES_URL = System.getProperty('artifactoryUserNamesJsonListUrl', 'https://reports.jenkins.io/artifactory-ldap-users-report.json');

    private static List<String> knownUsers = new JsonSlurper().parse(new URL(ARTIFACTORY_USER_NAMES_URL))

    static boolean exists(String username) {
        return knownUsers.contains(username)
    }
}
