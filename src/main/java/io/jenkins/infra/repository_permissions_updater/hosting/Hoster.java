package io.jenkins.infra.repository_permissions_updater.hosting;

import com.atlassian.jira.rest.client.api.ComponentRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.AssigneeType;
import com.atlassian.jira.rest.client.api.domain.BasicComponent;
import com.atlassian.jira.rest.client.api.domain.Component;
import com.atlassian.jira.rest.client.api.domain.input.ComponentInput;
import io.atlassian.util.concurrent.Promise;
import io.jenkins.infra.repository_permissions_updater.hosting.HostingRequest.IssueTracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_NAME;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_SLUG;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.INFRA_ORGANIZATION;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.JIRA_PROJECT;
import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.TARGET_ORG_NAME;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.stream.Collectors.joining;

public class Hoster {

    private static final Logger LOGGER = LoggerFactory.getLogger(Hoster.class);

    public static void main(String[] args) {
        new Hoster().run(Integer.parseInt(args[0]));
    }

    private void run(int issueID) {
        LOGGER.info("Approving hosting request " + issueID);

        JiraRestClient client = null;
        try {
            final HostingRequest hostingRequest = HostingRequestParser.retrieveAndParse(issueID);

            String defaultAssignee = hostingRequest.getJenkinsProjectUsers().get(0);
            String forkFrom = hostingRequest.getRepositoryUrl();
            List<String> users = hostingRequest.getGithubUsers();
            IssueTracker issueTrackerChoice = hostingRequest.getIssueTracker();

            String forkTo = hostingRequest.getNewRepoName();

            if (StringUtils.isBlank(forkFrom) || StringUtils.isBlank(forkTo) || users.isEmpty()) {
                LOGGER.info("Could not retrieve information (or information does not exist) from the Hosting request");
                return;
            }

            // Parse forkFrom in order to determine original repo owner and repo name
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE).matcher(forkFrom);
            if (m.matches()) {
                if (!forkGitHub(m.group(1), m.group(2), forkTo, users, issueTrackerChoice == IssueTracker.GITHUB)) {
                    LOGGER.error("Hosting request failed to fork repository on Github");
                    return;
                }
            } else {
                LOGGER.error("ERROR: Cannot parse the source repo: " + forkFrom);
                return;
            }

            // create the JIRA component
            if (issueTrackerChoice == IssueTracker.JIRA && !createComponent(forkTo, defaultAssignee)) {
                LOGGER.error("Hosting request failed to create component " + forkTo + " in JIRA");
                return;
            }

            client = JiraHelper.createJiraClient();
            String componentId = "";
            try {
                if (issueTrackerChoice == IssueTracker.JIRA) {
                    BasicComponent component = JiraHelper.getBasicComponent(client, JIRA_PROJECT, forkTo);
                    if (component.getId() != null) {
                        componentId = component.getId().toString();
                    }
                }
            } catch (IOException | TimeoutException | ExecutionException | InterruptedException ex) {
                LOGGER.error("Could not get component ID for " + forkTo + " component in Jira");
                componentId = "";
            }

            String prUrl = createUploadPermissionPR(issueID, forkTo, users, Collections.singletonList(defaultAssignee), issueTrackerChoice == IssueTracker.GITHUB, componentId);
            if (StringUtils.isBlank(prUrl)) {
                LOGGER.error("Could not create upload permission pull request");
            }

            String prDescription = "";
            String repoPermissionsActionText = "Create PR for upload permissions";
            if (!StringUtils.isBlank(prUrl)) {
                prDescription = "\n\nA [pull request](" + prUrl + ") has been created against the repository permissions updater to "
                        + "setup release permissions. Additional users can be added by modifying the created file.";
                repoPermissionsActionText = "Add additional users for upload permissions, if needed";
            }

            String issueTrackerText;
            if (issueTrackerChoice == IssueTracker.JIRA) {
                issueTrackerText = "\n\nA Jira component named " + forkTo + " has also been created with "
                        + defaultAssignee + " as the default assignee for issues.";
            } else {
                issueTrackerText = "\n\nGitHub issues has been selected for issue tracking and was enabled for the forked repo.";
            }

            // update the issue with information on next steps
            String msg = "Hosting request complete, the code has been forked into the jenkinsci project on GitHub as "
                    + "https://github.com/jenkinsci/" + forkTo
                    + issueTrackerText
                    + prDescription
                    + "\n\nPlease remove your original repository (if there are no other forks) so that the jenkinsci organization repository "
                    + "is the definitive source for the code. If there are other forks, please contact GitHub support to make the jenkinsci repo the root of the fork network (mention that Jenkins approval was given in support request 569994). "
                    + "Also, please make sure you properly follow the [documentation on documenting your plugin](https://jenkins.io/doc/developer/publishing/documentation/) "
                    + "so that your plugin is correctly documented. \n\n"
                    + "You will also need to do the following in order to push changes and release your plugin: \n\n"
                    + "* [Accept the invitation to the Jenkins CI Org on Github](https://github.com/jenkinsci)\n"
                    + "* [" + repoPermissionsActionText + "](https://github.com/jenkins-infra/repository-permissions-updater/#requesting-permissions)\n"
                    + "* [Releasing your plugin](https://jenkins.io/doc/developer/publishing/releasing/)\n"
                    + "\n\nIn order for your plugin to be built by the [Jenkins CI Infrastructure](https://ci.jenkins.io) and check pull requests,"
                    + " please add a [Jenkinsfile](https://jenkins.io/doc/book/pipeline/jenkinsfile/) to the root of your repository with the following content:\n"
                    + "`buildPlugin()`"
                    + "\n\nWelcome aboard!";

            // add comment
            GitHub github = GitHub.connect();
            GHIssue issue = github.getRepository(HOSTING_REPO_SLUG).getIssue(issueID);
            issue.comment(msg);
            issue.close();

            LOGGER.info("Hosting setup complete");
        } catch (IOException e) {
            LOGGER.error("Failed setting up hosting for " + issueID + ". ", e);
        } finally {
            if (!JiraHelper.close(client)) {
                LOGGER.warn("Failed to close JIRA client, possible leaked file descriptors");
            }
        }
    }

    boolean forkGitHub(String owner, String repo, String newName, List<String> maintainers, boolean useGHIssues) {
        boolean result = false;
        try {

            GitHub github = GitHub.connect();
            GHOrganization org = github.getOrganization(TARGET_ORG_NAME);
            GHRepository check = org.getRepository(newName);
            if (check != null) {
                LOGGER.error("Repository with name " + newName + " already exists in " + TARGET_ORG_NAME);
                return false;
            }

            // check if there is an existing real (not-renamed) repository with the name
            // if a repo has been forked and renamed, we can clone as that name and be fine
            // we just want to make sure we don't fork to a current repository name.
            check = org.getRepository(repo);
            if (check != null && check.getName().equalsIgnoreCase(repo)) {
                LOGGER.error("Repository " + repo + " can't be forked, an existing repository with that name already exists in " + TARGET_ORG_NAME);
                return false;
            }

            LOGGER.info("Forking " + repo);

            GHUser user = github.getUser(owner);
            if (user == null) {
                LOGGER.warn("No such user: " + owner);
                return false;
            }
            GHRepository orig = user.getRepository(repo);
            if (orig == null) {
                LOGGER.warn("No such repository: " + repo);
                return false;
            }

            GHRepository r;
            try {
                r = orig.forkTo(org);
            } catch (IOException e) {
                // we started seeing 500 errors, presumably due to time out.
                // give it a bit of time, and see if the repository is there
                LOGGER.info("GitHub reported that it failed to fork " + owner + "/" + repo + ". But we aren't trusting");
                r = null;
                for (int i = 0; r == null && i < 5; i++) {
                    Thread.sleep(1000);
                    r = org.getRepository(repo);
                }
                if (r == null)
                    throw e;
            }
            if (newName != null) {
                r.renameTo(newName);

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
                getOrCreateRepoLocalTeam(github, org, r, maintainers.isEmpty() ? singletonList(user.getName()) : maintainers);
            } catch (IOException e) {
                // if 'user' is an org, the above command would fail
                LOGGER.warn("Failed to add " + user + " to the new repository. Maybe an org?: " + e.getMessage());
                // fall through
            }
            setupRepository(r, useGHIssues);

            LOGGER.info("Created https://github.com/" + TARGET_ORG_NAME + "/" + (newName != null ? newName : repo));

            // remove all the existing teams
            for (GHTeam team : legacyTeams)
                team.remove(r);

            result = true;
        } catch (InterruptedException | IOException e) {
            LOGGER.error("Failed to fork a repository: ", e);
        }

        return result;
    }

    /**
     * Fix up the repository set up to our policy.
     */
    private static void setupRepository(GHRepository r, boolean useGHIssues) throws IOException {
        r.enableIssueTracker(useGHIssues);
        r.enableWiki(false);
    }

    /**
     * Creates a repository local team, and grants access to the repository.
     */
    private static GHTeam getOrCreateRepoLocalTeam(GitHub github, GHOrganization org, GHRepository r, List<String> githubUsers) throws IOException {
        String teamName = r.getName() + " Developers";
        GHTeam t = org.getTeams().get(teamName);
        if (t == null) {
            GHTeamBuilder ghCreateTeamBuilder = org.createTeam(teamName).privacy(GHTeam.Privacy.CLOSED);
            List<String> maintainers = emptyList();
            if (!githubUsers.isEmpty()) {
                maintainers = githubUsers.stream()
                        // in order to be added as a maintainer of a team you have to be a member of the org already
                        .filter(user -> isMemberOfOrg(github, org, user))
                        .collect(Collectors.toList());
                ghCreateTeamBuilder = ghCreateTeamBuilder.maintainers(maintainers.toArray(new String[0]));
            }
            t = ghCreateTeamBuilder.create();

            List<String> usersNotInMaintainers = new ArrayList<>(githubUsers);
            usersNotInMaintainers.removeAll(maintainers);
            final GHTeam team = t;
            usersNotInMaintainers.forEach(addUserToTeam(github, team));
            // github automatically adds the user to the team who created the team, we don't want that
            team.remove(github.getMyself());
        }

        t.add(r, GHOrganization.Permission.ADMIN); // make team an admin on the given repository, always do in case the config is wrong
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
                LOGGER.error(String.format("Failed to add user %s to team %s", user, team.getName()), e);
            }
        };
    }

    String createUploadPermissionPR(int issueId, String forkTo, List<String> ghUsers, List<String> releaseUsers, boolean useGHIssues, String jiraComponentId) {
        String prUrl = "";
        boolean isPlugin = forkTo.endsWith("-plugin");
        if (isPlugin) {
            String branchName = "hosting-" + forkTo + "-permissions";
            try {
                if (releaseUsers.isEmpty()) {
                    LOGGER.info("No users defined for release permissions, will not create PR");
                    return null;
                }

                GitHub github = GitHub.connect();
                GHOrganization org = github.getOrganization(INFRA_ORGANIZATION);
                GHRepository repo = org.getRepository(HOSTING_REPO_NAME);

                String artifactPath = getArtifactPath(github, forkTo);
                if (StringUtils.isBlank(artifactPath)) {
                    LOGGER.info("Could not resolve artifact path for " + forkTo);
                    return null;
                }

                if (!repo.getBranches().containsKey(branchName)) {
                    repo.createRef("refs/heads/" + branchName, repo.getBranch("master").getSHA1());
                }

                GHContentBuilder builder = repo.createContent();
                builder = builder.branch(branchName).message("Adding upload permissions file for " + forkTo);
                String name = forkTo.replace("-plugin", "");
                String content = "---\n" +
                        "name: \"" + name + "\"\n" +
                        "github: &GH \"" + TARGET_ORG_NAME + "/" + forkTo + "\"\n" +
                        "paths:\n" +
                        "- \"" + artifactPath + "\"\n" +
                        "developers:\n";
                StringBuilder developerBuilder = new StringBuilder();
                for (String u : releaseUsers) {
                    developerBuilder.append("- \"").append(u).append("\"\n");
                }
                content += developerBuilder.toString();

                content += "issues:\n";
                if (useGHIssues) {
                    content += "  - github: *GH\n";
                } else if (StringUtils.isNotEmpty(jiraComponentId)) {
                    content += "  - jira: " + jiraComponentId + "\n";
                }

                builder.content(content).path("permissions/plugin-" + forkTo.replace("-plugin", "") + ".yml").commit();

                String prText = String.format("Hello from your friendly Jenkins Hosting Bot!%n%n" +
                                "This is an automatically created PR for:%n%n" +
                                "- #%s%n" +
                                "- https://github.com/%s/%s%n%n" +
                                "The user(s) listed in the permissions file may not have logged in to Artifactory yet, check the PR status.%n" +
                                "To check again, hosting team members will retrigger the build using Checks area or by closing and reopening the PR.%n%n" +
                                "cc %s",
                        issueId, TARGET_ORG_NAME,
                        forkTo, ghUsers.stream().map(u -> "@" + u).collect(joining(", ")));

                GHPullRequest pr = repo.createPullRequest("Add upload permissions for " + forkTo, branchName, repo.getDefaultBranch(), prText);
                prUrl = pr.getHtmlUrl().toString();
                LOGGER.info("Created PR for repository permissions updater: " + prUrl);
            } catch (Exception e) {
                LOGGER.error("Error creating PR", e);
            }
        } else {
            LOGGER.info("Can only create PR's for plugin permissions at this time");
            return null;
        }
        return prUrl;
    }

    private static String getArtifactPath(GitHub github, String forkTo) throws IOException {
        String res = "";

        GHOrganization org = github.getOrganization(TARGET_ORG_NAME);
        GHRepository fork = org.getRepository(forkTo);

        if (fork == null) {
            return null;
        }

        String artifactId = null;
        String groupId = null;

        String[] buildFiles = new String[]{"pom.xml", "build.gradle"};
        for (String buildFile : buildFiles) {
            try {
                GHContent file = fork.getFileContent(buildFile);
                if (file != null && file.isFile()) {
                    String contents = IOUtils.toString(file.read(), StandardCharsets.UTF_8);
                    if (buildFile.equalsIgnoreCase("pom.xml")) {
                        artifactId = MavenVerifier.getArtifactId(contents);
                        groupId = MavenVerifier.getGroupId(contents);
                    } else if (buildFile.equalsIgnoreCase("build.gradle")) {
                        artifactId = GradleVerifier.getShortName(contents);
                        groupId = GradleVerifier.getGroupId(contents);
                    }
                    break;
                }
            } catch (IOException e) {
                LOGGER.error("Could not find supported build file (pom.xml or build.gradle) to get artifact path from or another error occurred.");
                return null;
            }
        }

        if (!StringUtils.isBlank(artifactId) && !StringUtils.isBlank((groupId))) {
            res = String.format("%s/%s", groupId.replace('.', '/'), artifactId);
        }
        return res;
    }

    private boolean createComponent(String subcomponent, String owner) {
        LOGGER.info(String.format("Adding a new JIRA subcomponent %s to the %s project, owned by %s",
                subcomponent, JIRA_PROJECT, owner));

        boolean result = false;
        JiraRestClient client = null;
        try {
            client = JiraHelper.createJiraClient();
            final ComponentRestClient componentClient = client.getComponentClient();
            final Promise<Component> createComponent = componentClient.createComponent(JIRA_PROJECT,
                    new ComponentInput(subcomponent, "subcomponent", owner, AssigneeType.COMPONENT_LEAD));
            final Component component = JiraHelper.wait(createComponent);
            LOGGER.info("New component created. URL is " + component.getSelf().toURL());
            result = true;
        } catch (Exception e) {
            LOGGER.error("Failed to create a new component: ", e);
            e.printStackTrace();
        } finally {
            if (!JiraHelper.close(client)) {
                LOGGER.warn("Failed to close JIRA client, possible leaked file descriptors");
            }
        }

        return result;
    }

}
