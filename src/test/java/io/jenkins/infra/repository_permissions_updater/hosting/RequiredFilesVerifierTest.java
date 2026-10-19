package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequiredFilesVerifierTest {

    @TempDir
    Path dir;

    private final HashSet<VerificationMessage> issues = new HashSet<>();

    @Test
    void allFilesPresent() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir)
                .commit(
                        "Jenkinsfile",
                        "buildPlugin(useContainerAgent: true, configurations: [[platform: 'linux', jdk: 25]])")
                .commit(".github/workflows/jenkins-security-scan.yml", "")
                .commit(".github/CODEOWNERS", "* @jenkinsci/cool-plugin-developers\n")
                .commit(".gitignore", "target\nwork\n")
                .commit(".github/renovate.json", "{}")
                .commit(".mvn/extensions.xml", "<extensions/>")
                .commit(
                        ".mvn/maven.config",
                        "-Pconsume-incrementals\n-Pmight-produce-incrementals\n-Dchangelist.format=%d.v%s\n")
                .commit(".github/workflows/cd.yaml", "");

        verify(repo, true);

        assertThat(issues).isEmpty();
    }

    @Test
    void missingFiles() throws Exception {
        TestGitRepository repo =
                new TestGitRepository(dir).commit("pom.xml", "<project/>").commit(".github/release-drafter.yml", "");

        verify(repo, true);

        assertThat(issues.stream().map(VerificationMessage::getMessage))
                .anyMatch(m -> m.startsWith("Missing file `Jenkinsfile`"))
                .anyMatch(m -> m.startsWith("Missing file `.github/workflows/jenkins-security-scan.yml`"))
                .anyMatch(m -> m.startsWith("Missing file `.github/CODEOWNERS`"))
                .anyMatch(m -> m.startsWith("Missing file `.gitignore`"))
                .anyMatch(m -> m.startsWith("No files found related to automatically updating"))
                .anyMatch(m -> m.startsWith("Missing file `.mvn/extensions.xml`"))
                .anyMatch(m -> m.startsWith("Missing file `.github/workflows/cd.yaml`"))
                .anyMatch(m -> m.startsWith("Missing file `.mvn/maven.config`"))
                .anyMatch(m -> m.startsWith("The file `.github/release-drafter.y*ml` should be removed"));
    }

    @Test
    void wrongFileContents() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir)
                .commit(".github/CODEOWNERS", "* @someone\n")
                .commit(".gitignore", "*.iml\n");

        verify(repo, false);

        assertThat(issues.stream().map(VerificationMessage::getMessage))
                .contains(
                        "The file `.github/CODEOWNERS` doesn't contain the expected line `* @jenkinsci/cool-plugin-developers`")
                .anyMatch(m -> m.startsWith("The file `.gitignore` doesn't exclude `target`"))
                .anyMatch(m -> m.startsWith("The file `.gitignore` doesn't exclude `work`"));
    }

    private void verify(TestGitRepository repo, boolean enableCD) throws Exception {
        try (RepositoryCheckout checkout = repo.checkout()) {
            new RequiredFilesVerifier(issues, checkout)
                    .verify(new HostingRequest(
                            TestGitRepository.HTML_URL, "cool-plugin", List.of("me"), List.of("me"), null, enableCD));
        }
    }

    @Test
    public void jenkinsfileWithNewestJava() {
        HashSet<VerificationMessage> issues = new HashSet<>();
        new RequiredFilesVerifier(issues, null).validateJenkinsFile("""
                        buildPlugin(useContainerAgent: true, configurations: [
                          [platform: 'linux', jdk: 25],
                          [platform: 'windows', jdk: 21],
                        ])
                        """);
        assertTrue(issues.isEmpty(), issues::toString);
    }

    @Test
    public void jenkinsfileWithoutNewestJava() {
        HashSet<VerificationMessage> issues = new HashSet<>();
        new RequiredFilesVerifier(issues, null).validateJenkinsFile("""
                        buildPlugin(useContainerAgent: true, configurations: [
                          [platform: 'linux', jdk: 21],
                          [platform: 'windows', jdk: 21],
                        ])
                        """);
        assertEquals(1, issues.size());
        assertEquals(
                VerificationMessage.Severity.WARNING, issues.iterator().next().getSeverity());
    }
}
