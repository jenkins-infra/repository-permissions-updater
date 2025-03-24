package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class RequiredFilesVerifier implements Verifier {

    @Override
    public void verify(HostingRequest request, HashSet<VerificationMessage> hostingIssues) throws IOException {

        GitHub github = GitHub.connect();
        String forkFrom = request.getRepositoryUrl();
        String forkTo = request.getNewRepoName();

        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", CASE_INSENSITIVE).matcher(forkFrom);
            if(m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHRepository repo = github.getRepository(owner + "/" + repoName);
                checkJenkinsfile(repo, hostingIssues);
                checkSecurityScan(repo, hostingIssues);
                checkCodeOwners(repo, hostingIssues, forkTo);
                checkGitignore(repo, hostingIssues);
            }
        }
    }

    private void checkCodeOwners(GHRepository repo, HashSet<VerificationMessage> hostingIssues, String forkTo) throws IOException {
        String expected = "* @jenkinsci/" + forkTo + "-developers";
        GHContent file = null;
        try {
            file = repo.getFileContent(".github/CODEOWNERS");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Missing file `.github/CODEOWNERS`. Please add this file containing the line: `" + expected + "`"));
            return;
        }
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            if (bufferedReader.lines().noneMatch(line -> line.equals(expected))) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The file `.github/CODEOWNERS` doesn't contain the expected line `" + expected + "`"));
            }
        }
    }

    private void checkJenkinsfile(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GHContent file = null;
        try {
            file = repo.getFileContent("Jenkinsfile");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Missing file `Jenkinsfile`. Please add a Jenkinsfile to your repo so it can be built on ci.jenkins.io. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/Jenkinsfile)"));
            return;
        }
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            if (bufferedReader.lines().noneMatch(line -> line.startsWith("buildPlugin("))) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "It seems your `Jenkinsfile` is not calling `buildPlugin`. "));
            }
        }
    }

    private void checkGitignore(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        GHContent file = null;
        try {
            file = repo.getFileContent(".gitignore");
        } catch (GHFileNotFoundException e) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Missing file `.gitignore`. Please add a `.gitignore` to help keep your git repo lean. A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/gitignore)"));
            return;
        }
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(file.read(), StandardCharsets.UTF_8))) {
            if (bufferedReader.lines().noneMatch(line -> line.equals("target") || line.equals("work") || line.equals("target/") || line.equals("work/") || line.equals("/target/") || line.equals("/work/"))) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The file `.gitignore` doesn't exclude `target` and `work`. Please add these lines so that you don't check-in these directories by accident."));
            }
        }
    }

    private void checkSecurityScan(GHRepository repo, HashSet<VerificationMessage> hostingIssues) throws IOException {
        if (fileNotExistsInRepo(repo, ".github/workflows/jenkins-security-scan.yml") &&
                fileNotExistsInRepo(repo, ".github/workflows/jenkins-security-scan.yaml")) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Missing file `.github/workflows/jenkins-security-scan.yml`. This file helps to keep your plugin conform to security standards defined by the Jenkins project." +
                    " A suitable version can be downloaded [here](https://github.com/jenkinsci/archetypes/blob/master/common-files/.github/workflows/jenkins-security-scan.yml)"));
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
