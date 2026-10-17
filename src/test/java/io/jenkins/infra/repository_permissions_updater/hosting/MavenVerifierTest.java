package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
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
        return new HostingRequest(TestGitRepository.HTML_URL, "cool-plugin", List.of("me"), List.of("me"), null, false);
    }

    private List<String> messages() {
        return issues.stream().map(VerificationMessage::getMessage).toList();
    }

    private static Model pom(String content) throws Exception {
        return new MavenXpp3Reader().read(new StringReader(content));
    }

    private static Set<String> subItems(HashSet<VerificationMessage> issues) {
        assertEquals(1, issues.size(), issues::toString);
        return issues.iterator().next().getSubItems().stream()
                .map(VerificationMessage::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    public void redundantDependencies() throws Exception {
        Model model = pom("""
                <project>
                  <dependencies>
                    <dependency><groupId>commons-codec</groupId><artifactId>commons-codec</artifactId></dependency>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId></dependency>
                    <dependency><groupId>org.jenkins-ci.main</groupId><artifactId>jenkins-test-harness</artifactId><scope>test</scope></dependency>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><scope>test</scope></dependency>
                    <dependency><groupId>org.jenkins-ci.plugins</groupId><artifactId>credentials</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        HashSet<VerificationMessage> issues = new HashSet<>();
        new MavenVerifier(issues, null)
                .checkRedundantDependencies(
                        model,
                        Set.of("org.jenkins-ci.main:jenkins-test-harness"),
                        Set.of("commons-codec:commons-codec", "org.apache.commons:commons-lang3"),
                        Set.of("junit:junit"));
        assertEquals(
                Set.of(
                        "`commons-codec:commons-codec` is provided by Jenkins core",
                        "`org.jenkins-ci.main:jenkins-test-harness` is already added by the parent pom",
                        "`junit:junit` is already added as test dependency by `jenkins-test-harness`"),
                subItems(issues));
    }

    @Test
    public void bundledArtifacts() throws Exception {
        Model model = pom("""
                <project>
                  <properties>
                    <hpi.bundledArtifacts>commons-codec,error_prone_annotations,google-api-client</hpi.bundledArtifacts>
                  </properties>
                </project>
                """);
        HashSet<VerificationMessage> issues = new HashSet<>();
        new MavenVerifier(issues, null).checkBundledArtifacts(model, Set.of("commons-codec:commons-codec"));
        assertEquals(
                Set.of(
                        "`commons-codec` is provided by Jenkins core",
                        "`error_prone_annotations` contains annotations that are only needed at compile time"),
                subItems(issues));
    }

    @Test
    public void redundantBuildPlugins() throws Exception {
        Model parent = pom("""
                <project>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>1</version></plugin>
                      </plugins>
                    </pluginManagement>
                    <plugins>
                      <plugin><groupId>org.jenkins-ci.tools</groupId><artifactId>maven-hpi-plugin</artifactId></plugin>
                      <plugin><groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId></plugin>
                    </plugins>
                  </build>
                </project>
                """);
        Model model = pom("""
                <project>
                  <build>
                    <plugins>
                      <plugin><groupId>org.jenkins-ci.tools</groupId><artifactId>maven-hpi-plugin</artifactId><extensions>true</extensions></plugin>
                      <plugin>
                        <groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId><version>2.0</version>
                        <configuration><java/></configuration>
                      </plugin>
                      <plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId><version>0.8</version>
                        <executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions>
                      </plugin>
                      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3</version></plugin>
                    </plugins>
                  </build>
                </project>
                """);
        HashSet<VerificationMessage> issues = new HashSet<>();
        new MavenVerifier(issues, null).checkRedundantBuildPlugins(model, parent);
        assertEquals(
                Set.of(
                        "`maven-hpi-plugin` is already configured by the parent pom, remove it",
                        "The version of `spotless-maven-plugin` is managed by the parent pom, remove the `<version>`",
                        "The version of `jacoco-maven-plugin` is managed by the parent pom, remove the `<version>`"),
                subItems(issues));
    }

    @Test
    public void noRedundantBuildPlugins() throws Exception {
        HashSet<VerificationMessage> issues = new HashSet<>();
        new MavenVerifier(issues, null)
                .checkRedundantBuildPlugins(pom("<project/>"), pom("<project><build/></project>"));
        assertTrue(issues.isEmpty());
    }
}
