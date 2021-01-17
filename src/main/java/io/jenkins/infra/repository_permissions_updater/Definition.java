package io.jenkins.infra.repository_permissions_updater;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressFBWarnings("UUF_UNUSED_PUBLIC_OR_PROTECTED_FIELD")
public class Definition {

    public static class CD {
        public boolean enabled;
    }

    public static class Security {
        public SecurityContacts contacts;
    }

    public static class SecurityContacts {
        public String email;
        public String jira;
    }

    /**
     * Holds issue tracker information and provides a simple API to create issue tracker references.
     *
     * Call {@link #isJira()} and/or {@link #isGitHubIssues()} to determine the kind of tracker.
     * Some invalid input may result in both returning {@code false}, in that case other methods will throw exceptions.
     */
    public static class IssueTracker {
        public interface JiraComponentSource {
            String getComponentId(String componentName);
        }

        private static final Logger LOGGER = Logger.getLogger(IssueTracker.class.getName());

        public String jira;
        public String github;
        public boolean report = true;

        public boolean isJira() {
            if (jira == null) {
                return false;
            }
            if (!jira.matches("[a-zA-Z0-9_.-]+")) {
                LOGGER.log(Level.INFO, "Unexpected Jira component name, skipping: " + jira);
                return false;
            }
            return true;
        }

        public boolean isGitHubIssues() {
            if (github == null) {
                return false;
            }
            if (!github.startsWith("jenkinsci/")) {
                LOGGER.log(Level.INFO, "Unexpected GitHub repo for issue tracker, skipping: " + jira);
                return false;
            }
            return true;
        }

        private String loadComponentId(JiraComponentSource source) {
            String jiraComponentId = jira;
            if (!jira.matches("[0-9]+")) {
                // CreateIssueDetails needs the numeric Jira component ID
                jiraComponentId = source.getComponentId(jira);
                if (jiraComponentId == null) {
                    LOGGER.warning("Failed to determine Jira component ID for '" + jira + "', the component may not exist");
                    return null;
                }
            }
            return jiraComponentId;
        }

        public String getViewUrl(JiraComponentSource source) {
            if (isJira()) {
                final String id = loadComponentId(source);
                if (id != null) {
                    return "https://issues.jenkins.io/issues/?jql=component=" + id;
                }
                return null;
            }
            if (isGitHubIssues()) {
                return "https://github.com/" + github + "/issues";
            }
            throw new IllegalStateException("Invalid issue tracker: " + github + " / " + jira);
        }

        public String getReportUrl(JiraComponentSource source) {
            if (!report) {
                return null;
            }
            if (isJira()) {
                final String id = loadComponentId(source);
                if (id != null) {
                    return "https://issues.jenkins.io/secure/CreateIssueDetails!init.jspa?pid=10172&issuetype=1&components=" + id;
                }
                return null;
            }
            if (isGitHubIssues()) {
                return "https://github.com/" + github + "/issues/new/choose"; // The 'choose' URL works even when there are no issue templates
            }
            throw new IllegalStateException("Invalid issue tracker: " + github + " / " + jira);
        }

        /**
         * Returns the characteristic reference for the issue tracker of this component.
         * For GitHub Issues, this is the 'orgname/reponame', for Jira, it's the component name or ID.
         * @return the characteristic reference for the issue tracker of this component
         */
        public String getReference() {
            if (isJira()) {
                return jira;
            }
            if (isGitHubIssues()) {
                return github;
            }
            throw new IllegalStateException("Invalid issue tracker: " + github + " / " + jira);
        }

        public String getType() {
            if (isJira()) {
                return "jira";
            }
            if (isGitHubIssues()) {
                return "github";
            }
            throw new IllegalStateException("Invalid issue tracker: " + github + " / " + jira);
        }
    }

    private String name = "";
    private String[] paths = new String[0];
    private String[] developers = new String[0];
    private IssueTracker[] issues = new IssueTracker[0];

    private String github;

    public CD getCd() {
        return cd;
    }

    public void setCd(CD cd) {
        this.cd = cd;
    }

    private CD cd;
    private Security security; // unused, just metadata for Jenkins security team

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getPaths() {
        return paths.clone();
    }

    public void setPaths(String[] paths) {
        this.paths = paths.clone();
    }

    public IssueTracker[] getIssues() {
        return issues.clone();
    }

    public void setIssues(IssueTracker[] paths) {
        this.issues = paths.clone();
    }

    public String[] getDevelopers() {
        return developers.clone();
    }

    public void setDevelopers(String[] developers) {
        this.developers = developers.clone();
    }

    public void setGithub(String github) {
        this.github = github;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public String getGithub() {
        if (github != null && github.startsWith("jenkinsci/")) {
            return github;
        }
        return null;
    }
}