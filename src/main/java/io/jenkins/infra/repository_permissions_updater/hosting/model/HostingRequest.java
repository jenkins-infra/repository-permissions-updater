package io.jenkins.infra.repository_permissions_updater.hosting.model;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * @param repositoryUrl e.g https://github.com/user/repo-name
 * @param newRepoName   e.g. your-cool-plugin
 */
public record HostingRequest(String repositoryUrl, String newRepoName,
                             List<String> githubUsers,
                             List<String> jenkinsProjectUsers,
                             io.jenkins.infra.repository_permissions_updater.hosting.model.HostingRequest.IssueTracker issueTracker) {

    public HostingRequest(
            String repositoryUrl,
            String newRepoName,
            List<String> githubUsers,
            List<String> jenkinsProjectUsers,
            IssueTracker issueTracker
    ) {
        this.repositoryUrl = repositoryUrl;
        this.newRepoName = newRepoName;
        this.githubUsers = Collections.unmodifiableList(githubUsers);
        this.jenkinsProjectUsers = Collections.unmodifiableList(jenkinsProjectUsers);
        this.issueTracker = issueTracker;
    }

    public enum IssueTracker {
        GITHUB, JIRA;

        public static IssueTracker fromString(String string) {
            return string.toLowerCase(Locale.ROOT).contains("git") ? GITHUB : JIRA;
        }
    }
}
