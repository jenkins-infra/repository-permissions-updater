package io.jenkins.infra.repository_permissions_updater.hosting;

import static io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig.HOSTING_REPO_SLUG;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostingChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(HostingChecker.class);

    public static final String CLONE_FAILED =
            "Could not clone %s to check its files. Make sure the repository exists and is public, then re-check.";

    public static final String INVALID_FORK_FROM =
            "Repository URL '%s' is not a valid GitHub repository (check that you do not have .git at the end, GitHub API doesn't support this).";

    public void checkRequest(int issueID) throws IOException {
        boolean debug = System.getProperty("debugHosting", "false").equalsIgnoreCase("true");

        // problems with the request itself, e.g. malformed user lines
        HashSet<VerificationMessage> hostingIssues = new HashSet<>();
        final HostingRequest hostingRequest = HostingRequestParser.retrieveAndParse(issueID, hostingIssues);
        hostingIssues.addAll(runVerifications(hostingRequest).issues());

        LOGGER.info("Done checking hosting for {}, found {} issues", issueID, hostingIssues.size());

        StringBuilder msg = new StringBuilder("Hello from your friendly Jenkins Hosting Checker\n\n");
        LOGGER.info("Checking if there were errors");
        if (!hostingIssues.isEmpty()) {
            msg.append("It appears you have some issues with your hosting request. Please see the list below and "
                    + "correct all issues marked Required. Your hosting request will not be "
                    + "approved until these issues are corrected. Issues marked with Warning "
                    + "or Info are just recommendations and will not stall the hosting process.\n");
            LOGGER.info("Appending issues to msg");
            msg.append(formatIssues(hostingIssues));
            msg.append(
                    "\nYou can re-trigger a check by editing your hosting request or by commenting `/hosting re-check`");
        } else {
            msg.append("It looks like you have everything in order for your hosting request. "
                            + "A member of the [Jenkins hosting team](https://www.jenkins.io/project/teams/hosting/#members-of-the-hosting-team) "
                            + "will check over things that I am not able to check"
                            + "(code review, README content, etc) and process the request as quickly as possible. "
                            + "Thank you for your patience.\n")
                    .append("\nHosting team members can host this request with `/hosting host`");
        }

        if (!debug) {
            LOGGER.info(msg.toString());
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
            LOGGER.info("Here are the results of the checking: {}", msg.toString());
        }
    }

    /**
     * Runs all hosting verifications against the given request.
     *
     * @return the problems found, and the names of any verifications that could not complete
     */
    public VerificationResult runVerifications(HostingRequest hostingRequest) {
        return runVerifications(hostingRequest, step -> LOGGER.info("{}", step));
    }

    /**
     * Runs all hosting verifications against the given request.
     *
     * @param onProgress called with a description of each step as it starts
     * @return the problems found, and the names of any verifications that could not complete
     */
    public VerificationResult runVerifications(HostingRequest hostingRequest, Consumer<String> onProgress) {
        HashSet<VerificationMessage> hostingIssues = new HashSet<>();
        List<String> failedVerifications = new ArrayList<>();

        RepositoryCheckout checkout = null;
        Optional<String> gitHubUrl = RepositoryCheckout.gitHubUrl(hostingRequest.getRepositoryUrl());
        if (gitHubUrl.isPresent()) {
            onProgress.accept("Cloning " + gitHubUrl.get());
            try {
                checkout = RepositoryCheckout.cloneGitHub(gitHubUrl.get());
            } catch (IOException e) {
                LOGGER.error("Error cloning {}", gitHubUrl.get(), e);
                // without a clone none of the file checks run, so this must block the request
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.REQUIRED, CLONE_FAILED, gitHubUrl.get()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        // an invalid repository URL is reported by the Request verification, nothing to clone in that case

        try {
            List<Verification> verifications = new ArrayList<>();
            verifications.add(new Verification("Request", new HostingFieldVerifier(hostingIssues)));
            verifications.add(new Verification("GitHub", new GitHubVerifier(hostingIssues)));
            verifications.add(new Verification("JenkinsProjectUsers", new JenkinsProjectUserVerifier(hostingIssues)));
            MavenVerifier mavenVerifier = null;
            if (checkout != null) {
                mavenVerifier = new MavenVerifier(hostingIssues, checkout);
                verifications.add(new Verification("History", new GitHistoryVerifier(hostingIssues, checkout)));
                verifications.add(new Verification("Maven", mavenVerifier));
                verifications.add(new Verification("Jelly", new JellyVerifier(hostingIssues, checkout)));
                verifications.add(
                        new Verification("RequiredFiles", new RequiredFilesVerifier(hostingIssues, checkout)));
            }

            for (Verification verification : verifications) {
                try {
                    onProgress.accept("Checking " + verification.name());
                    verification.verifier().verify(hostingRequest);
                } catch (Exception e) {
                    LOGGER.error("Error running verification {}", verification.name(), e);
                    failedVerifications.add(verification.name());
                }
            }

            if (mavenVerifier != null && !mavenVerifier.hasBuildFile(hostingRequest)) {
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.WARNING, "No pom.xml detected."));
            }
        } finally {
            if (checkout != null) {
                checkout.close();
            }
        }
        return new VerificationResult(hostingIssues, failedVerifications);
    }

    private record Verification(String name, Verifier verifier) {}

    /**
     * @param issues problems found in the hosting request
     * @param failedVerifications names of verifications that threw an exception, so their checks did not run fully
     */
    public record VerificationResult(Set<VerificationMessage> issues, List<String> failedVerifications) {}

    /**
     * Formats verification results as a Markdown list, most severe first.
     */
    public static String formatIssues(Set<VerificationMessage> issues) {
        StringBuilder msg = new StringBuilder();
        appendIssues(msg, issues, 0);
        return msg.toString();
    }

    private static void appendIssues(StringBuilder msg, Set<VerificationMessage> issues, int level) {
        for (VerificationMessage issue :
                issues.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
            if (level == 0) {
                msg.append("%s %s %s: %s%n"
                        .formatted(
                                "-",
                                issue.getSeverity().getColor(),
                                issue.getSeverity().getMessage(),
                                issue.getMessage()));
            } else {
                msg.append("%s %s%n".formatted(StringUtils.repeat(" ", level * 2) + "-", issue.getMessage()));
            }

            if (issue.getSubItems() != null) {
                appendIssues(msg, issue.getSubItems(), level + 1);
            }
        }
    }

    public static boolean fileExistsInRepo(HostingRequest issue, String fileName) throws IOException {
        boolean res = false;
        GitHub github = GitHub.connect();
        String forkFrom = issue.getRepositoryUrl();
        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("https://github\\.com/(\\S+)/(\\S+)", CASE_INSENSITIVE)
                    .matcher(forkFrom);
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
