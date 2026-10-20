package io.jenkins.infra.repository_permissions_updater.hosting;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_NAME;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_SLUG;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.INFRA_ORGANIZATION;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.TARGET_ORG_NAME;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.stream.Collectors.joining;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHTeamBuilder;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hoster {

    private static final Logger LOGGER = LoggerFactory.getLogger(Hoster.class);

    private final boolean debug;

    public Hoster() {
        debug = System.getProperty("debugHosting", "false").equalsIgnoreCase("true");
    }

    public void run(int issueID) {
        LOGGER.info("Approving hosting request {}", issueID);

        try {

            final HostingRequest hostingRequest = HostingRequestParser.retrieveAndParse(issueID, null);

            String forkFrom = hostingRequest.getRepositoryUrl();
            List<String> users = hostingRequest.getGithubUsers();
            boolean enableCD = hostingRequest.isEnableCD();

            String forkTo = hostingRequest.getNewRepoName();

            if (StringUtils.isBlank(forkFrom) || StringUtils.isBlank(forkTo) || users.isEmpty()) {
                String msg = "Could not retrieve information (or information does not exist) from the Hosting request";
                LOGGER.info(msg);
                reportHostingFailure(issueID, msg);
                return;
            }

            // Parse forkFrom in order to determine original repo owner and repo name
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE)
                    .matcher(forkFrom);
            if (m.matches()) {
                if (!debug && !forkGitHub(issueID, m.group(1), m.group(2), forkTo, users)) {
                    LOGGER.error("Hosting request failed to fork repository on Github");
                    return;
                }
            } else {
                String msg = "ERROR: Cannot parse the source repo: " + forkFrom;
                LOGGER.error(msg);
                reportHostingFailure(issueID, msg);
                return;
            }

            String prUrl = createUploadPermissionPR(issueID, forkTo, hostingRequest, enableCD);
            if (StringUtils.isBlank(prUrl)) {
                String msg = "Could not create upload permission pull request";
                LOGGER.error(msg);
                reportHostingFailure(issueID, msg);
                return;
            }

            String prDescription = "";
            String repoPermissionsActionText = "Create PR for upload permissions";
            if (!StringUtils.isBlank(prUrl)) {
                prDescription = "\n\nA [pull request](" + prUrl
                        + ") has been created against the repository permissions updater to "
                        + "setup release permissions. Additional users can be added by modifying the created file.";
                repoPermissionsActionText = "Add additional users for upload permissions, if needed";
            }

            String issueTrackerText;
            issueTrackerText = "\n\nGitHub issues have been enabled for the forked repo.";

            // update the issue with information on next steps
            String msg = "Hosting request complete, the code has been forked into the jenkinsci project on GitHub as "
                    + "https://github.com/jenkinsci/" + forkTo
                    + issueTrackerText
                    + prDescription
                    + "\n\nPlease [delete your original repository](" + forkFrom
                    + "/settings?confirm_delete=yes) (if there are no other forks), under 'Danger Zone', so that the jenkinsci organization repository "
                    + "is the definitive source for the code. If there are other forks, then use the 'Leave fork network' action in the 'Danger Zone' on the settings page of your new jenkinsci repository. "
                    + "Also, please make sure you properly follow the [documentation on documenting your plugin](https://jenkins.io/doc/developer/publishing/documentation/) "
                    + "so that your plugin is correctly documented. \n\n"
                    + "You will also need to do the following in order to push changes and release your plugin: \n\n"
                    + "* [Accept the invitation to the Jenkins CI Org on Github](https://github.com/jenkinsci)\n"
                    + "* [" + repoPermissionsActionText
                    + "](https://github.com/jenkins-infra/repository-permissions-updater/#requesting-permissions)\n"
                    + "* [Releasing your plugin](https://jenkins.io/doc/developer/publishing/releasing/)"
                    + "\n\nWelcome aboard!";

            // add comment
            if (!debug) {
                GitHub github = GitHub.connect();
                GHIssue issue = github.getRepository(HOSTING_REPO_SLUG).getIssue(issueID);
                issue.comment(msg);
                issue.close();
            } else {
                LOGGER.info("Debug mode enabled, not updating issue with message: {}", msg);
            }

            LOGGER.info("Hosting setup complete");
        } catch (IOException e) {
            LOGGER.error("Failed setting up hosting for {}. ", issueID, e);
        }
    }

    private void reportHostingFailure(int issueID, String errorMessage) throws IOException {
        GitHub github = GitHub.connect();
        GHIssue issue = github.getRepository(HOSTING_REPO_SLUG).getIssue(issueID);
        String msg = "Hosting request failed,\n\n"
                + errorMessage
                + "\n\nSomeone from the hosting team will look into this as soon as possible.\n"
                + "Sorry for the inconvenience!";
        if (debug) {
            LOGGER.info("Debug mode enabled, not updating issue with message: {}", msg);
            return;
        }
        issue.comment(msg);
    }

    private boolean renameRepository(GHRepository r, String newName) throws IOException {
        r.renameTo(newName);
        return true;
    }

    boolean forkGitHub(
            int issueID, String owner, String repo, String newName, List<String> maintainers) {
        boolean result = false;
        try {

            GitHub github = GitHub.connect();
            GHOrganization org = github.getOrganization(TARGET_ORG_NAME);
            GHRepository check = org.getRepository(newName);
            if (check != null) {
                String msg = "Repository with name " + newName + " already exists in " + TARGET_ORG_NAME;
                LOGGER.error(msg);
                reportHostingFailure(issueID, msg);
                return false;
            }

            // check if there is an existing real (not-renamed) repository with the name
            // if a repo has been forked and renamed, we can clone as that name and be fine
            // we just want to make sure we don't fork to a current repository name.
            check = org.getRepository(repo);
            if (check != null && check.getName().equalsIgnoreCase(repo)) {
                String msg = "Repository " + repo
                        + " can't be forked, an existing repository with that name already exists in "
                        + TARGET_ORG_NAME;
                LOGGER.error(msg);
                reportHostingFailure(issueID, msg);
                return false;
            }

            LOGGER.info("Forking {}", repo);

            GHUser user = github.getUser(owner);
            if (user == null) {
                LOGGER.warn("No such user: {}", owner);
                reportHostingFailure(issueID, "No such user: " + owner);
                return false;
            }
            GHRepository orig = user.getRepository(repo);
            if (orig == null) {
                LOGGER.warn("No such repository: {}", repo);
                reportHostingFailure(issueID, "No such repository: " + repo);
                return false;
            }

            GHRepository r;
            try {
                r = orig.createFork().organization(org).create();
            } catch (IOException e) {
                // we started seeing 500 errors, presumably due to time out.
                // give it a bit of time and see if the repository is there
                LOGGER.info("GitHub reported that it failed to fork {}/{}. But we aren't trusting", owner, repo);
                r = null;
                for (int i = 0; r == null && i < 5; i++) {
                    Thread.sleep(1000);
                    r = org.getRepository(repo);
                }
                if (r == null) throw e;
            }
            if (newName != null) {
                boolean renameResult = false;
                for (int i = 0; i < 5; i++) {
                    try {
                        renameResult = renameRepository(r, newName);
                        break;
                    } catch (HttpException e) {
                        LOGGER.warn("Failed to rename repository from {} to {}", repo, newName);
                        if (e.getResponseCode() == 422) {
                            Thread.sleep(2000);
                        } else {
                            throw new IOException(
                                    "Failed to rename repository from " + repo + " to " + newName + ":", e);
                        }
                    }
                }
                LOGGER.info("Renamed repository from {} to {}", repo, newName);
                if (!renameResult) {
                    throw new IOException(
                            "Failed to rename repository from " + repo + " to " + newName + " after 5 tries.");
                }

                r = null;
                for (int i = 0; r == null && i < 5; i++) {
                    Thread.sleep(1000);
                    r = org.getRepository(newName);
                }
                if (r == null)
                    throw new IOException(repo + " renamed to " + newName + " but not finding the new repository");
            }

            // GitHub adds a lot of teams to this repo by default, which we don't want
            Set<GHTeam> legacyTeams = r.getTeams();

            try {
                getOrCreateRepoLocalTeam(
                        github, org, r, maintainers.isEmpty() ? singletonList(user.getName()) : maintainers);
            } catch (IOException e) {
                // if 'user' is an org, the above command would fail
                LOGGER.warn("Failed to add {} to the new repository. Maybe an org?: {}", user, e.getMessage());
                // fall through
            }
            setupRepository(r);
            createRuleset(r);

            LOGGER.info("Created https://github.com/{}/{}", TARGET_ORG_NAME, newName != null ? newName : repo);

            // remove all the existing teams
            for (GHTeam team : legacyTeams) team.remove(r);

            result = true;
        } catch (InterruptedException | IOException e) {
            LOGGER.error("Failed to fork a repository: ", e);
            try {
                reportHostingFailure(issueID, e.getMessage());
            } catch (IOException ioe) {
                LOGGER.warn("Failed to send failure message to the hosting request", ioe);
            }
        }

        return result;
    }

    /**
     * Fix up the repository set up to our policy.
     */
    private static void setupRepository(GHRepository r) throws IOException {
        r.enableIssueTracker(true);
        r.enableWiki(false);
        r.setHomepage("https://plugins.jenkins.io/" + r.getName().replace("-plugin", "") + "/");
        r.createAutolink()
                .withKeyPrefix("JENKINS-")
                .withUrlTemplate("https://issue-redirect.jenkins.io/browse/JENKINS-<num>")
                .withIsAlphanumeric(false)
                .create();
        r.createAutolink()
                .withKeyPrefix("SECURITY-")
                .withUrlTemplate("https://jenkins.io/security/issue/SECURITY-<num>")
                .withIsAlphanumeric(false)
                .create();
    }

    /**
     * Creates a repository local team, and grants access to the repository.
     */
    private static GHTeam getOrCreateRepoLocalTeam(
            GitHub github, GHOrganization org, GHRepository r, List<String> githubUsers) throws IOException {
        String teamName = r.getName() + " Developers";
        GHTeam t = org.getTeams().get(teamName);
        if (t == null) {
            GHTeamBuilder ghCreateTeamBuilder = org.createTeam(teamName).privacy(GHTeam.Privacy.CLOSED);
            List<String> maintainers = emptyList();
            if (!githubUsers.isEmpty()) {
                maintainers = githubUsers.stream()
                        // to be added as a maintainer of a team, you have to be a member of the org already
                        .filter(user -> isMemberOfOrg(github, org, user))
                        .toList();
                ghCreateTeamBuilder = ghCreateTeamBuilder.maintainers(maintainers.toArray(new String[0]));
            }
            t = ghCreateTeamBuilder.create();

            List<String> usersNotInMaintainers = new ArrayList<>(githubUsers);
            usersNotInMaintainers.removeAll(maintainers);
            final GHTeam team = t;
            usersNotInMaintainers.forEach(addUserToTeam(github, team));
            // GitHub automatically adds the user to the team who created the team, we don't want that
            team.remove(github.getMyself());
        }

        // make team an admin on the given repository, always do in case the config is wrong
        t.add(r, GHOrganization.RepositoryRole.from(GHOrganization.Permission.ADMIN));
        return t;
    }

    private static boolean isMemberOfOrg(GitHub gitHub, GHOrganization org, String user) {
        try {
            GHUser ghUser = gitHub.getUser(user);
            return org.hasMember(ghUser);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static Consumer<String> addUserToTeam(GitHub github, GHTeam team) {
        return user -> {
            try {
                team.add(github.getUser(user));
            } catch (IOException e) {
                LOGGER.error("Failed to add user %s to team %s".formatted(user, team.getName()), e);
            }
        };
    }

    @SuppressFBWarnings(value = "VA_FORMAT_STRING_USES_NEWLINE", justification = "TODO needs triage")
    String createUploadPermissionPR(int issueId, String forkTo, HostingRequest hostingRequest, boolean enableCD) {
        String prUrl = "";
        boolean isPlugin = forkTo.endsWith("-plugin");
        List<String> releaseUsers = hostingRequest.getJenkinsProjectUsers();
        List<String> ghUsers = hostingRequest.getGithubUsers();
        if (isPlugin) {
            String branchName = "hosting-" + forkTo + "-permissions";
            try {
                if (hostingRequest.getJenkinsProjectUsers().isEmpty()) {
                    LOGGER.info("No users defined for release permissions, will not create PR");
                    return null;
                }

                GitHub github = GitHub.connect();
                GHOrganization org = github.getOrganization(INFRA_ORGANIZATION);
                GHRepository repo = org.getRepository(HOSTING_REPO_NAME);

                String artifactPath = getArtifactPath(github, forkTo, hostingRequest);
                if (StringUtils.isBlank(artifactPath)) {
                    LOGGER.info("Could not resolve artifact path for {}", forkTo);
                    return null;
                }

                if (!debug && !repo.getBranches().containsKey(branchName)) {
                    repo.createRef(
                            "refs/heads/" + branchName, repo.getBranch("master").getSHA1());
                }

                GHContentBuilder builder = repo.createContent();
                if (!debug) {
                    builder = builder.branch(branchName).message("Adding upload permissions file for " + forkTo);
                }
                String name = forkTo.replace("-plugin", "");
                String developers = getDevelopers(
                        hostingRequest.isManageGitHubPermissions(),
                        hostingRequest.getGithubJenkinsProjectUsers(),
                        releaseUsers);
                String content = """
                        ---
                        name: "%s"
                        github: &GH "%s/%s"
                        paths:
                        - "%s"
                        developers:
                        %s
                        issues:
                          - github: *GH
                        %s
                        """.formatted(
                                name,
                                TARGET_ORG_NAME,
                                forkTo,
                                artifactPath,
                                developers,
                                enableCD ? "cd:\n  enabled: true" : "");

                if (!debug) {
                    builder.content(content)
                            .path("permissions/plugin-" + forkTo.replace("-plugin", "") + ".yml")
                            .commit();
                } else {
                    LOGGER.info("Debug mode enabled, not creating PR content file with content: {}", content);
                }

                String prText = """
                        Hello from your friendly Jenkins Hosting Bot!
                        This is an automatically created PR for:
                        - #%s
                        - https://github.com/%s/%s
                        The user(s) listed in the permissions file may not have logged in to Artifactory yet, check the PR status.
                        To check again, hosting team members will retrigger the build using Checks area or by closing and reopening the PR.
                        cc %s
                        """.formatted(
                                issueId,
                                TARGET_ORG_NAME,
                                forkTo,
                                ghUsers.stream().map(u -> "@" + u).collect(joining(", ")));

                if (!debug) {
                    GHPullRequest pr = repo.createPullRequest(
                            "Add upload permissions for " + forkTo, branchName, repo.getDefaultBranch(), prText);
                    prUrl = pr.getHtmlUrl().toString();
                } else {
                    prUrl = "DEBUG_MODE_PR_URL";
                }
                LOGGER.info("Created PR for repository permissions updater: {}", prUrl);
            } catch (Exception e) {
                LOGGER.error("Error creating PR", e);
            }
        } else {
            LOGGER.info("Can only create PR's for plugin permissions at this time");
            return null;
        }
        return prUrl;
    }

    static @NonNull String getDevelopers(
            boolean isManageGitHubPermissions,
            List<Map<String, String>> githubJenkinsProjectUsers,
            List<String> releaseUsers) {
        String developers;
        if (isManageGitHubPermissions) {
            StringBuilder devbuilder = new StringBuilder();
            githubJenkinsProjectUsers.forEach(entry -> {
                devbuilder.append("  - ");
                if (entry.containsKey("ldap")) {
                    devbuilder.append("ldap: \"").append(entry.get("ldap")).append("\"");
                    if (entry.containsKey("github")) {
                        devbuilder
                                .append("\n    github: \"")
                                .append(entry.get("github").replace("@", ""))
                                .append("\"");
                    }
                } else if (entry.containsKey("github")) {
                    devbuilder
                            .append("github: \"")
                            .append(entry.get("github").replace("@", ""))
                            .append("\"");
                }
                devbuilder.append("\n");
            });
            devbuilder.append("manageGitHubPermissions: true");
            developers = devbuilder.toString();
        } else {
            developers = releaseUsers.stream().map(u -> "  - \"" + u + "\"").collect(joining("\n"));
        }
        return developers.stripTrailing();
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /**
     * Status check context reported by ci.jenkins.io for pull request builds.
     */
    private static final String JENKINS_CI_PR_HEAD_STATUS_CONTEXT = "continuous-integration/jenkins/pr-head";

    /**
     * GitHub App (integration) id of ci.jenkins.io, used to scope the required status check to that app.
     */
    private static final int JENKINS_CI_INTEGRATION_ID = 74951;

    /**
     * Creates a ruleset on the given repository covering the default branch, requiring a pull request before
     * merging, blocking force pushes and deletions, and requiring the
     * {@value #JENKINS_CI_PR_HEAD_STATUS_CONTEXT} status check from ci.jenkins.io to pass.
     */
    private void createRuleset(GHRepository repo) throws IOException {
        JsonArray includedRefs = new JsonArray();
        includedRefs.add("~DEFAULT_BRANCH");

        JsonObject refName = new JsonObject();
        refName.add("include", includedRefs);
        refName.add("exclude", new JsonArray());

        JsonObject conditions = new JsonObject();
        conditions.add("ref_name", refName);

        JsonArray rules = new JsonArray();
        rules.add(createRulesetRule("pull_request"));
        rules.add(createRulesetRule("non_fast_forward"));
        rules.add(createRulesetRule("deletion"));
        rules.add(createRequiredStatusChecksRule());

        JsonObject ruleset = new JsonObject();
        ruleset.addProperty("name", "Default branch protection");
        ruleset.addProperty("target", "branch");
        ruleset.addProperty("enforcement", "active");
        ruleset.add("conditions", conditions);
        ruleset.add("rules", rules);

        String url = "https://api.github.com/repos/" + repo.getFullName() + "/rulesets";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + System.getenv("GITHUB_OAUTH"))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(ruleset), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IOException("Failed to create ruleset for " + repo.getFullName() + ": HTTP "
                        + response.statusCode() + " " + response.body());
            }
            LOGGER.info("Created ruleset for {}", repo.getFullName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating ruleset for " + repo.getFullName(), e);
        }
    }

    private static JsonObject createRulesetRule(String type) {
        JsonObject rule = new JsonObject();
        rule.addProperty("type", type);
        return rule;
    }

    private static JsonObject createRequiredStatusChecksRule() {
        JsonObject statusCheck = new JsonObject();
        statusCheck.addProperty("context", JENKINS_CI_PR_HEAD_STATUS_CONTEXT);
        statusCheck.addProperty("integration_id", JENKINS_CI_INTEGRATION_ID);

        JsonArray requiredStatusChecks = new JsonArray();
        requiredStatusChecks.add(statusCheck);

        JsonObject parameters = new JsonObject();
        parameters.add("required_status_checks", requiredStatusChecks);
        parameters.addProperty("strict_required_status_checks_policy", false);

        JsonObject rule = createRulesetRule("required_status_checks");
        rule.add("parameters", parameters);
        return rule;
    }

    private String getArtifactPath(GitHub github, String forkTo, HostingRequest hostingRequest) throws IOException {
        String res = "";

        GHOrganization org = github.getOrganization(TARGET_ORG_NAME);
        GHRepository fork = org.getRepository(forkTo);
        if (debug) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE)
                    .matcher(hostingRequest.getRepositoryUrl());
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                fork = github.getRepository(owner + "/" + repoName);
            } else {
                return null;
            }
        }

        if (fork == null) {
            return null;
        }

        String artifactId = null;
        String groupId = null;

        try {
            GHContent file = fork.getFileContent("pom.xml");
            if (file != null && file.isFile()) {
                String contents = IOUtils.toString(file.read(), StandardCharsets.UTF_8);
                if (file.isFile()) {
                    artifactId = MavenVerifier.getArtifactId(contents);
                    groupId = MavenVerifier.getGroupId(contents);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not find pom.xml to get artifact path from or another error occurred.");
            return null;
        }

        if (!StringUtils.isBlank(artifactId) && !StringUtils.isBlank((groupId))) {
            res = "%s/%s".formatted(groupId.replace('.', '/'), artifactId);
        }
        return res;
    }
}
