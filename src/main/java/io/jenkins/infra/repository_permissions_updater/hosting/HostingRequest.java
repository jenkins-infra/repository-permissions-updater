package io.jenkins.infra.repository_permissions_updater.hosting;

import java.util.Collections;
import java.util.List;

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
    private final boolean enableCD;

    public HostingRequest(
            String repositoryUrl,
            String newRepoName,
            List<String> githubUsers,
            List<String> jenkinsProjectUsers,
            boolean enableCD) {
        this.repositoryUrl = repositoryUrl;
        this.newRepoName = newRepoName;
        this.githubUsers = Collections.unmodifiableList(trimList(githubUsers));
        this.jenkinsProjectUsers = Collections.unmodifiableList(trimList(jenkinsProjectUsers));
        this.enableCD = enableCD;
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

    public boolean isEnableCD() {
        return enableCD;
    }

    private List<String> trimList(List<String> users) {
        return users.stream().filter(it -> !it.trim().isEmpty()).toList();
    }
}
