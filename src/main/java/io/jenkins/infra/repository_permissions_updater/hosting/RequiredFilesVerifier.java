package io.jenkins.infra.repository_permissions_updater.hosting;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

public class RequiredFilesVerifier implements Verifier {

    @Override
    public void verify(HostingRequest request, HashSet<VerificationMessage> hostingIssues) throws IOException {

        GitHub github = GitHub.connect();
        String forkFrom = request.getRepositoryUrl();
        String forkTo = request.getNewRepoName();

        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE)
                    .matcher(forkFrom);
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHRepository repo = github.getRepository(owner + "/" + repoName);
                checkJenkinsfile(repo, hostingIssues);
                checkSecurityScan(repo, hostingIssues);
                checkCodeOwners(repo, hostingIssues, forkTo);
                checkGitignore(repo, hostingIssues);
                checkDependencyBot(repo, hostingIssues);
                if (request.isEnableCD()) {
                    checkFilesForCD(repo, hostingIssues);
                }
            }
        }
    }

    private void checkCodeOwners(GHRepository repo, HashSet<VerificationMessage> hostingIssues, String forkTo)
            throws IOException {
        String expected = "* @jenkinsci/" + forkTo + "-developers";
        GHContent file = null;
        try {
            file = repo.getFileContent(".github/CODEOWNERS");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Missing file `.github/CODEOWNERS`. Please add this file containing the line: `" + expected + "`"));
            return;
        }
        try (BufferedReader bufferedReader =
                new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            if (bufferedReader.lines().noneMatch(line -> line.equals(expected))) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The file `.github/CODEOWNERS` doesn't contain the expected line `" + expected + "`"));
            }
        }
    }

    private void checkJenkinsfile(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GHContent file = null;
        try {
            file = repo.getFileContent("Jenkinsfile");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `Jenkinsfile`. Please add a Jenkinsfile to your repo so it can be built on ci.jenkins.io. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/Jenkinsfile)"));
            return;
        }
        try (BufferedReader bufferedReader =
                new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            if (bufferedReader.lines().noneMatch(line -> line.startsWith("buildPlugin("))) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "It seems your `Jenkinsfile` is not calling `buildPlugin`. "));
            }
        }
    }

    private void checkGitignore(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GHContent file = null;
        try {
            file = repo.getFileContent(".gitignore");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.gitignore`. Please add a `.gitignore` to help keep your git repo lean. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/gitignore)"));
            return;
        }
        try (BufferedReader bufferedReader =
                new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            List<String> lines = bufferedReader.lines().toList();
            if (lines.stream()
                    .noneMatch(line -> line.equals("target") || line.equals("target/") || line.equals("/target/"))) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "The file `.gitignore` doesn't exclude `target`. Please add a line so that you don't check-in this directory by accident."));
            }
            if (lines.stream()
                    .noneMatch(line -> line.equals("work") || line.equals("work/") || line.equals("/work/"))) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "The file `.gitignore` doesn't exclude `work`. Please add a line so that you don't check-in this directory by accident."));
            }
        }
    }

    private void checkSecurityScan(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        if (fileNotExistsInRepo(repo, ".github/workflows/jenkins-security-scan.yml")
                && fileNotExistsInRepo(repo, ".github/workflows/jenkins-security-scan.yaml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.github/workflows/jenkins-security-scan.yml`. This file helps to keep your plugin conform to security standards defined by the Jenkins project."
                                    + " A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/.github/workflows/jenkins-security-scan.yml)"));
        }
    }

    private void checkDependencyBot(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        if (fileNotExistsInRepo(repo, ".github/dependabot.yml")
                && fileNotExistsInRepo(repo, ".github/dependabot.yaml")
                && fileNotExistsInRepo(repo, "renovate.json")
                && fileNotExistsInRepo(repo, ".github/renovate.json")
                && fileNotExistsInRepo(repo, ".github/workflows/updatecli.yml")
                && fileNotExistsInRepo(repo, ".github/workflows/updatecli.yaml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "No files found related to automatically updating the plugin dependencies. "
                                    + "Please ensure that you have dependabot, renovate or updatecli configured in the repo. "
                                    + "A suitable version for dependabot can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/.github/dependabot.yml)"));
        }
    }

    private void checkFilesForCD(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {

        if (fileNotExistsInRepo(repo, ".mvn/extensions.xml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.mvn/extensions.xml`. This file is required when CD is enabled. "
                                    + "A suitable version can be downloaded [here](https://raw.githubusercontent.com/jenkinsci/archetypes/refs/heads/master/common-files/.mvn/extensions.xml)"));
        }
        if (fileNotExistsInRepo(repo, ".github/workflows/cd.yaml")
                && fileNotExistsInRepo(repo, ".github/workflows/cd.yml")) {
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "Missing file `.github/workflows/cd.yaml`. This file is required when CD is enabled. "
                                    + "A suitable version can be downloaded [here](https://raw.githubusercontent.com/jenkinsci/.github/master/workflow-templates/cd.yaml"));
        }
        if (!fileNotExistsInRepo(repo, ".github/release-drafter.yml")
                || !fileNotExistsInRepo(repo, ".github/release-drafter.yaml")) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The file `.github/release-drafter.y*ml` should be removed when CD is enabled."));
        }
        if (!fileNotExistsInRepo(repo, ".github/workflows/release-drafter.yml")
                || !fileNotExistsInRepo(repo, ".github/workflows/release-drafter.yaml")) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The file `.github/workflows/release-drafter.y*ml` should be removed when CD is enabled."));
        }
        GHContent file = null;
        String expected = "-Dchangelist.format=%d.v%s";
        try {
            file = repo.getFileContent(".mvn/maven.config");
            try (BufferedReader bufferedReader =
                    new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
                if (bufferedReader.lines().noneMatch(line -> line.equals(expected))) {
                    hostingIssues.add(new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            "The file `.mvn/maven.config` doesn't contain the expected line `"
                                    + expected.replaceAll("%", "%%") + "`"));
                }
            }

        } catch (GHFileNotFoundException e) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Missing file `.mvn/config`. This file is required when CD is enabled. "
                            + "Download [maven.config](https://raw.githubusercontent.com/jenkinsci/archetypes/refs/heads/master/common-files/.mvn/maven.config) "
                            + "and add the line: `" + expected.replaceAll("%", "%%") + "`"));
        }
    }

    private boolean fileNotExistsInRepo(GHRepository repo, String fileName) throws IOException {
        try {
            GHContent file = repo.getFileContent(fileName);
            return file == null || !file.isFile();
        } catch (GHFileNotFoundException e) {
            return true;
        }
    }
}
