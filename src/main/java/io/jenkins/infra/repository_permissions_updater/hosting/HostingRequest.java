package io.jenkins.infra.repository_permissions_updater.hosting;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class HostingRequest {

    /**
     * e.g https://github.com/user/repo-name
     */
    private final String repositoryUrl;
    /**
     * e.g. your-cool-plugin
     */
    private final String newRepoName;
    private final List<String> githubUsers;
    private final List<String> jenkinsProjectUsers;
    private final IssueTracker issueTracker;

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

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getNewRepoName() {
        return newRepoName;
    }

    public List<String> getGithubUsers() {
        return githubUsers;
    }

    public List<String> getJenkinsProjectUsers() {
        return jenkinsProjectUsers;
    }

    public IssueTracker getIssueTracker() {
        return issueTracker;
    }

    public enum IssueTracker {
        GITHUB, JIRA;

        public static IssueTracker fromString(String string) {
            return string.toLowerCase(Locale.ROOT).contains("git") ? GITHUB : JIRA;
        }
    }
}
