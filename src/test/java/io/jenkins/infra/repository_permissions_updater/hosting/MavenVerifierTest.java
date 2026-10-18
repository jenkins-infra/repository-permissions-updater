package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenVerifierTest {

    @TempDir
    Path dir;

    private final HashSet<VerificationMessage> issues = new HashSet<>();

    @Test
    void groupIdInheritedFromParent() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir).commit("pom.xml", """
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <parent>
                            <groupId>org.jenkins-ci.plugins</groupId>
                            <artifactId>plugin</artifactId>
                            <version>5.28</version>
                            <relativePath />
                          </parent>
                          <artifactId>cool</artifactId>
                          <version>1.0-SNAPSHOT</version>
                          <packaging>hpi</packaging>
                        </project>
                        """);

        try (RepositoryCheckout checkout = repo.checkout()) {
            MavenVerifier verifier = new MavenVerifier(issues, checkout);
            verifier.verify(request());
            assertThat(verifier.hasBuildFile(request())).isTrue();
        }

        assertThat(messages())
                .doesNotContain(MavenVerifier.INVALID_POM)
                .contains("You must add a 'groupId' in your pom.xml with the value `io.jenkins.plugins`.");
    }

    @Test
    void invalidPom() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir).commit("pom.xml", "<project>");

        try (RepositoryCheckout checkout = repo.checkout()) {
            new MavenVerifier(issues, checkout).verify(request());
        }

        assertThat(messages()).containsExactly(MavenVerifier.INVALID_POM);
    }

    @Test
    void noPom() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir).commit("build.gradle", "");

        try (RepositoryCheckout checkout = repo.checkout()) {
            MavenVerifier verifier = new MavenVerifier(issues, checkout);
            verifier.verify(request());
            assertThat(verifier.hasBuildFile(request())).isFalse();
        }

        assertThat(issues).isEmpty();
    }

    private static HostingRequest request() {
        return new HostingRequest(TestGitRepository.HTML_URL, "cool-plugin", List.of("me"), List.of("me"), false);
    }

    private List<String> messages() {
        return issues.stream().map(VerificationMessage::getMessage).toList();
    }
}
