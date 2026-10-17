package io.jenkins.infra.repository_permissions_updater.hosting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;

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
    private final List<Map<String, String>> githubJenkinsProjectUsers;
    private final boolean enableCD;
    private final boolean manageGitHubPermissions;

    public HostingRequest(
            String repositoryUrl,
            String newRepoName,
            @NonNull List<String> githubUsers,
            @NonNull List<String> jenkinsProjectUsers,
            List<Map<String, String>> githubJenkinsProjectUsers,
            boolean enableCD) {
        this.repositoryUrl = repositoryUrl;
        this.newRepoName = newRepoName;
        this.githubUsers = trimList(githubUsers);
        this.jenkinsProjectUsers = trimList(jenkinsProjectUsers);
        this.githubJenkinsProjectUsers = githubJenkinsProjectUsers;
        this.enableCD = enableCD;
        this.manageGitHubPermissions = githubJenkinsProjectUsers != null;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getNewRepoName() {
        return newRepoName;
    }

    @NonNull
    public List<String> getGithubUsers() {
        if (manageGitHubPermissions) {
            return githubJenkinsProjectUsers.stream()
                    .filter(it -> it.containsKey("github") && it.get("github").startsWith("@"))
                    .map(it -> it.get("github"))
                    .toList();
        }
        return githubUsers;
    }

    @NonNull
    public List<String> getJenkinsProjectUsers() {
        if (manageGitHubPermissions) {
            return githubJenkinsProjectUsers.stream()
                    .filter(it -> it.containsKey("ldap") && !it.get("ldap").startsWith("@"))
                    .map(it -> it.get("ldap"))
                    .toList();
        }
        return jenkinsProjectUsers;
    }

    public List<Map<String, String>> getGithubJenkinsProjectUsers() {
        return githubJenkinsProjectUsers;
    }

    public boolean isEnableCD() {
        return enableCD;
    }

    private List<String> trimList(List<String> users) {
        return users.stream().filter(it -> !it.trim().isEmpty()).toList();
    }

    public boolean isManageGitHubPermissions() {
        return manageGitHubPermissions;
    }
}
