package io.jenkins.infra.repository_permissions_updater.cli.commands;

import io.jenkins.infra.repository_permissions_updater.hosting.HostingChecker;
import io.jenkins.infra.repository_permissions_updater.hosting.HostingRequest;
import io.jenkins.infra.repository_permissions_updater.hosting.VerificationMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.kohsuke.github.GitHub;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Command to run the hosting checks against a repository before a hosting request is opened.
 * Runs the same verifications as {@link CheckHostingCommand} but prints the result instead of commenting on an issue.
 */
@Command(
        name = "precheck-hosting",
        description = {
            "Run the hosting checks against your repository before opening a hosting request.",
            "The repository must be pushed to GitHub, the checks read it from there.",
            "Requires a GitHub token in GITHUB_OAUTH (e.g. GITHUB_OAUTH=$(gh auth token))."
        },
        mixinStandardHelpOptions = true)
public class PrecheckHostingCommand implements Callable<Integer> {

    private static final Pattern REPO_URL = Pattern.compile("https?://github\\.com/[^/\\s]+/([^/\\s]+?)(\\.git)?/?");

    @Option(
            names = {"-r", "--repository-url"},
            required = true,
            description = "URL of the repository to host, e.g. https://github.com/your-org/your-cool-plugin")
    private String repositoryUrl;

    @Option(
            names = {"-n", "--new-repository-name"},
            description =
                    "Name of the repository in the jenkinsci organization (defaults to the current repository name)")
    private String newRepositoryName;

    @Option(
            names = {"-g", "--github-user"},
            required = true,
            split = ",",
            description = "GitHub user(s) to have commit permission, repeatable or comma separated")
    private List<String> githubUsers;

    @Option(
            names = {"-j", "--jenkins-user"},
            required = true,
            split = ",",
            description =
                    "Jenkins project (accounts.jenkins.io) user(s) to have release permission, repeatable or comma separated")
    private List<String> jenkinsUsers;

    @Option(
            names = "--cd",
            negatable = true,
            defaultValue = "true",
            fallbackValue = "true",
            description =
                    "Whether automated releases via GitHub Actions (JEP-229 CD) will be requested (default: ${DEFAULT-VALUE})")
    private boolean enableCD;

    @Override
    public Integer call() throws IOException {
        if (!hasGitHubCredentials()) {
            System.err.println("A GitHub token is required to run the hosting checks, without one the GitHub API rate"
                    + " limit is exhausted before the checks complete.");
            System.err.println("Set GITHUB_OAUTH, e.g. `export GITHUB_OAUTH=$(gh auth token)`, and try again.");
            return 2;
        }

        String name = newRepositoryName != null ? newRepositoryName.trim() : repositoryName(repositoryUrl);

        HostingRequest request = new HostingRequest(
                repositoryUrl.trim(), name, List.of(), List.of(), users(githubUsers, jenkinsUsers), enableCD);

        TerminalReport report = new TerminalReport(TerminalReport.colorSupported());
        System.out.println("Running hosting checks against " + request.getRepositoryUrl());
        HostingChecker.VerificationResult result =
                new HostingChecker().runVerifications(request, check -> System.out.println(report.progress(check)));
        boolean hasRequired =
                result.issues().stream().anyMatch(i -> i.getSeverity() == VerificationMessage.Severity.REQUIRED);

        System.out.print(
                report.render(request.getRepositoryUrl(), name, result.issues(), result.failedVerifications()));

        if (hasRequired) {
            return 1;
        }
        return result.failedVerifications().isEmpty() ? 0 : 3;
    }

    private static boolean hasGitHubCredentials() {
        try {
            GitHub github = GitHub.connect();
            return !github.isAnonymous() && github.isCredentialValid();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Builds the users the way the hosting request template's "GitHub and Jenkins project users" field is parsed.
     * Pairing a GitHub user with a Jenkins user only matters when hosting, so each is listed on its own.
     */
    static List<Map<String, String>> users(List<String> githubUsers, List<String> jenkinsUsers) {
        List<Map<String, String>> users = new ArrayList<>();
        githubUsers.stream()
                .map(String::trim)
                .filter(u -> !u.isEmpty())
                .forEach(u -> users.add(Map.of("github", u.startsWith("@") ? u : "@" + u)));
        jenkinsUsers.stream().map(String::trim).filter(u -> !u.isEmpty()).forEach(u -> users.add(Map.of("ldap", u)));
        return users;
    }

    static String repositoryName(String repositoryUrl) {
        Matcher m = REPO_URL.matcher(repositoryUrl.trim());
        return m.matches() ? m.group(1) : "";
    }
}
