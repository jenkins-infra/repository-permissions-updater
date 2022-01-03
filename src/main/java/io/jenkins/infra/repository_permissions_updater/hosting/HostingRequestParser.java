package io.jenkins.infra.repository_permissions_updater.hosting;

import io.jenkins.infra.repository_permissions_updater.hosting.HostingRequest.IssueTracker;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GitHub;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_SLUG;

public final class HostingRequestParser {

    private static final String SEPARATOR = "### ";

    private static final String REPO_URL = "Repository URL";
    private static final String NEW_REPOSITORY_NAME = "New Repository Name";
    private static final String ISSUE_TRACKER = "Issue tracker";
    private static final String GITHUB_USERS = "GitHub users to have commit permission";
    private static final String JENKINS_PROJECT_USERS = "Jenkins project users to have release permission";


    private HostingRequestParser() {
    }

    public static HostingRequest retrieveAndParse(int id) throws IOException {
        GitHub github = GitHub.connect();
        GHIssue issue = github.getRepository(HOSTING_REPO_SLUG)
                .getIssue(id);

        String body = issue.getBody();

        return HostingRequestParser.parse(body);
    }

    public static HostingRequest parse(String body) {
        Map<String, Field> fields = convert(body);

        return new HostingRequest(
                fields.get(REPO_URL).asString(),
                fields.get(NEW_REPOSITORY_NAME).asString(),
                fields.get(GITHUB_USERS).asList()
                        .stream()
                        .map(user -> user.replace("@", ""))
                        .collect(Collectors.toList()),
                fields.get(JENKINS_PROJECT_USERS).asList(),
                IssueTracker.fromString(fields.get(ISSUE_TRACKER).asString())
        );
    }

    static Map<String, Field> convert(String content) {
        Map<String, Field> parts = Arrays.asList(content.split(SEPARATOR))
                .stream()
                .filter(StringUtils::isNoneBlank)
                .map(field -> {
                    String key = field.split("\r\n|\n", 2)[0];
                    String value = field
                            .replace(key + "\n\n", "")
                            .replace(key + "\r\n", "");
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
            return Arrays.stream(field.split("\n|\r\n"))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }

        public String asString() {
            return field;
        }
    }
}
