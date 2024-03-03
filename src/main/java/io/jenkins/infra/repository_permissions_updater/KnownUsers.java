package io.jenkins.infra.repository_permissions_updater;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class KnownUsers {
    private static final String ARTIFACTORY_USER_NAMES_URL = System.getProperty("artifactoryUserNamesJsonListUrl", "https://reports.jenkins.io/artifactory-ldap-users-report.json");
    private static final String JIRA_USER_NAMES_URL = System.getProperty("jiraUserNamesJsonListUrl", "https://reports.jenkins.io/jira-users-report.json");

    private static Set<String> knownArtifactoryUsers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private static Set<String> knownJiraUsers = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        try {
            knownArtifactoryUsers.addAll(parseJson(new URL(ARTIFACTORY_USER_NAMES_URL)));
            knownJiraUsers.addAll(parseJson(new URL(JIRA_USER_NAMES_URL)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressFBWarnings(value = "URLCONNECTION_SSRF_FD", justification = "Not relevant in this situation.")
    private static Set<String> parseJson(URL url) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, new TypeToken<Set<String>>(){}.getType());
        }
    }

    public static boolean existsInArtifactory(String username) {
        return knownArtifactoryUsers.contains(username);
    }

    public static boolean existsInJira(String username) {
        return knownJiraUsers.contains(username);
    }
}
