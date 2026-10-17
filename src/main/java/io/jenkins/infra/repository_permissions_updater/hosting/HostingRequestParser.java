package io.jenkins.infra.repository_permissions_updater.hosting;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_SLUG;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GitHub;

public final class HostingRequestParser {

    private static final String SEPARATOR = "### ";

    private static final String REPO_URL = "Repository URL";
    private static final String NEW_REPOSITORY_NAME = "New Repository Name";
    public static final String GITHUB_USERS = "GitHub users to have commit permission";
    public static final String JENKINS_PROJECT_USERS = "Jenkins project users to have release permission";
    public static final String GITHUB_JENKINS_PROJECT_USERS = "GitHub and Jenkins project users";
    private static final String AUTOMATIC_RELEASES = "Automated release via GitHub Actions (recommended)";

    private HostingRequestParser() {}

    public static HostingRequest retrieveAndParse(int id, @CheckForNull HashSet<VerificationMessage> hostingIssues)
            throws IOException {
        GitHub github = GitHub.connect();
        GHIssue issue = github.getRepository(HOSTING_REPO_SLUG).getIssue(id);

        String body = issue.getBody();

        return HostingRequestParser.parse(body, hostingIssues);
    }

    public static HostingRequest parse(String body, HashSet<VerificationMessage> hostingIssues) {
        Map<String, Field> fields = convert(body);

        List<String> githubUsers = List.of();
        if (fields.containsKey(GITHUB_USERS)) {
            githubUsers = fields.get(GITHUB_USERS).asList();
        }
        List<String> jenkinsProjectUsers = List.of();
        if (fields.containsKey(JENKINS_PROJECT_USERS)) {
            jenkinsProjectUsers = fields.get(JENKINS_PROJECT_USERS).asList();
        }
        List<Map<String, String>> githubJenkinsProjectUsers = null;
        if (fields.containsKey(GITHUB_JENKINS_PROJECT_USERS)) {
            githubJenkinsProjectUsers = fields.get(GITHUB_JENKINS_PROJECT_USERS).asList().stream()
                    .map(user -> {
                        String[] parts = user.split("\\s+");
                        if (parts.length == 2) {
                            String github;
                            String ldap;
                            if (parts[0].startsWith("@")) {
                                github = parts[0];
                                ldap = parts[1];
                            } else if (parts[1].startsWith("@")) {
                                github = parts[1];
                                ldap = parts[0];
                            } else {
                                github = parts[0];
                                ldap = parts[1];
                                if (hostingIssues != null) {
                                    hostingIssues.add(new VerificationMessage(
                                            VerificationMessage.Severity.REQUIRED,
                                            "No GitHub user found in `" + GITHUB_JENKINS_PROJECT_USERS + "` for line: `"
                                                    + user + "`"));
                                }
                            }
                            if (github.startsWith("@") && ldap.startsWith("@") && hostingIssues != null) {
                                hostingIssues.add(new VerificationMessage(
                                        VerificationMessage.Severity.REQUIRED,
                                        "Both GitHub and Jenkins project user have `@` prefix in `"
                                                + GITHUB_JENKINS_PROJECT_USERS + "` for line: `" + user
                                                + "`. The `@` prefix is not allowed for Jenkins project user names."));
                            }
                            // keep the GitHub user name with the @ prefix here. We strip it only during the actual
                            // hosting request processing,
                            // We need to verify that the GitHub user name is valid and exists, and the @ prefix is
                            // required for that.
                            return Map.of("github", github, "ldap", ldap);
                        } else if (parts.length == 1) {
                            if (parts[0].startsWith("@")) {
                                return Map.of("github", parts[0]);
                            } else {
                                return Map.of("ldap", parts[0]);
                            }
                        } else {
                            if (hostingIssues != null) {
                                hostingIssues.add(new VerificationMessage(
                                        VerificationMessage.Severity.REQUIRED,
                                        "More than 2 entries in `" + GITHUB_JENKINS_PROJECT_USERS + "` for line: `"
                                                + user + "`"));
                            }
                            return Map.<String, String>of();
                        }
                    })
                    .toList();
        }
        return new HostingRequest(
                fields.get(REPO_URL).asString(),
                fields.get(NEW_REPOSITORY_NAME).asString(),
                githubUsers,
                jenkinsProjectUsers,
                githubJenkinsProjectUsers,
                fields.get(AUTOMATIC_RELEASES) != null
                        && fields.get(AUTOMATIC_RELEASES).asString().equalsIgnoreCase("yes"));
    }

    static Map<String, Field> convert(String content) {
        Map<String, Field> parts = Arrays.asList(content.split(SEPARATOR)).stream()
                .filter(StringUtils::isNoneBlank)
                .map(field -> {
                    String key = field.split("\r\n|\n", 2)[0];
                    String value = field.replace(key + "\n\n", "").replace(key + "\r\n", "");
                    return new AbstractMap.SimpleEntry<>(key, new Field(value));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return parts;
    }

    static class Field {
        private final String field;

        public Field(String field) {
            this.field = field.trim();
        }

        public List<String> asList() {
            return Arrays.stream(field.split("\n|\r\n")).map(String::trim).toList();
        }

        public String asString() {
            return field;
        }
    }
}
