package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.jenkins.infra.repository_permissions_updater.hosting.condition.PomFileExistsCondition;
import io.jenkins.infra.repository_permissions_updater.hosting.model.HostingRequest;
import io.jenkins.infra.repository_permissions_updater.hosting.model.VerificationMessage;
import io.jenkins.infra.repository_permissions_updater.hosting.model.Version;
import io.jenkins.infra.repository_permissions_updater.hosting.verify.*;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Triplet;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_SLUG;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class HostingChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(HostingChecker.class);

    public static final Version LOWEST_JENKINS_VERSION = new Version(2, 440, 3);

    private static final boolean DEBUG_HOSTING = Boolean.getBoolean("debugHosting");
    public static final String INITIAL_HOSTING_REQUEST_FEEDBACK = """
            It appears you have some issues with your hosting request. Please see the list below and \
            correct all issues marked Required. Your hosting request will not be \
            approved until these issues are corrected. Issues marked with Warning \
            or Info are just recommendations and will not stall the hosting process.
            """;

    public static void main(String[] args) throws IOException {
        new HostingChecker().checkRequest(Integer.parseInt(args[0]));
    }

    public void checkRequest(int issueID) throws IOException {
        boolean hasBuildSystem = false;
        HashSet<VerificationMessage> hostingIssues = new HashSet<>();

        ArrayList<Triplet<String, BiConsumer<HostingRequest, HashSet<VerificationMessage>>, Predicate<HostingRequest>>> verifications = new ArrayList<>();
        verifications.add(Triplet.with("Jira", new HostingFieldVerifierConsumer(), null));
        verifications.add(Triplet.with("GitHub", new GitHubVerifierConsumer(), null));
        verifications.add(Triplet.with("Maven", new MavenVerifierConsumer(), new PomFileExistsCondition()));
        verifications.add(Triplet.with("JenkinsProjectUsers", new JenkinsProjectUserVerifierConsumer(), null));

        final HostingRequest hostingRequest = HostingRequestParser.retrieveAndParse(issueID);

        for (Triplet<String, BiConsumer<HostingRequest, HashSet<VerificationMessage>>, Predicate<HostingRequest>> verifier : verifications) {
            try {
                boolean runIt = verifier.getValue2() == null || verifier.getValue2().test(hostingRequest);
                if (runIt) {
                    LOGGER.info("Running verification '" + verifier.getValue0() + "'");
                    verifier.getValue1().accept(hostingRequest, hostingIssues);
                }

                if (verifier.getValue1() instanceof MavenVerifierConsumer mavenVerifierConsumer) {
                    hasBuildSystem |= mavenVerifierConsumer.hasBuildFile(hostingRequest);
                }
            } catch (Exception e) {
                LOGGER.error("Error running verification '" + verifier.getValue0(), e);
            }
        }

        if (!hasBuildSystem) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.WARNING, "No pom.xml detected."));
        }

        LOGGER.info("Done checking hosting for " + issueID + ", found " + hostingIssues.size() + " issues");

        StringBuilder msg = new StringBuilder("Hello from your friendly Jenkins Hosting Checker\n\n");
        LOGGER.info("Checking if there were errors");
        if (!hostingIssues.isEmpty()) {
            msg.append(INITIAL_HOSTING_REQUEST_FEEDBACK);
            LOGGER.info("Appending issues to msg");
            appendIssues(msg, hostingIssues, 1);
            msg.append("""

                    You can re-trigger a check by editing your hosting request or by commenting `/hosting re-check`""");
        } else {
            msg.append("""
                            It looks like you have everything in order for your hosting request. \
                            A member of the [Jenkins hosting team](https://www.jenkins.io/project/teams/hosting/#members-of-the-hosting-team) \
                            will check over things that I am not able to check\
                            (code review, README content, etc) and process the request as quickly as possible. \
                            Thank you for your patience.
                            """)
                    .append("""

                            Hosting team members can host this request with `/hosting host`""");
        }

        LOGGER.info(msg.toString());
        if (!DEBUG_HOSTING) {
            GitHub github = GitHub.connect();
            GHIssue issue = github.getRepository(HOSTING_REPO_SLUG).getIssue(issueID);
            issue.comment(msg.toString());

            if (hostingIssues.isEmpty()) {
                issue.addLabels("hosting-request", "bot-check-complete");
                issue.removeLabels("needs-fix");
            } else {
                issue.removeLabels("bot-check-complete");
                issue.addLabels("hosting-request", "needs-fix");
            }
        } else {
            LOGGER.info("Here are the results of the checking:");
            LOGGER.info(msg.toString());
        }
    }

    private void appendIssues(StringBuilder msg, Set<VerificationMessage> issues, int level) {
        for (VerificationMessage issue : issues.stream().sorted(Comparator.reverseOrder()).toList()) {
            if (level == 1) {
                msg.append("%s %s %s: %s%n".formatted(StringUtils.repeat("*", level), issue.getSeverity().getColor(), issue.getSeverity().getMessage(), issue.getMessage()));
            } else {
                msg.append("%s %s%n".formatted(StringUtils.repeat("*", level), issue.getMessage()));
            }

            if (issue.getSubItems() != null) {
                appendIssues(msg, issue.getSubItems(), level + 1);
            }
        }
    }

    public static boolean fileExistsInRepo(HostingRequest issue, String fileName) throws IOException {
        boolean res = false;
        GitHub github = GitHub.connect();
        String forkFrom = issue.repositoryUrl();
        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("https://github\\.com/(\\S+)/(\\S+)", CASE_INSENSITIVE).matcher(forkFrom);
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                try {
                    GHRepository repo = github.getRepository(owner + "/" + repoName);
                    GHContent file = repo.getFileContent(fileName);
                    res = file != null && file.isFile();
                } catch (GHFileNotFoundException ignored) {
                }
            }
        }
        return res;
    }
}
