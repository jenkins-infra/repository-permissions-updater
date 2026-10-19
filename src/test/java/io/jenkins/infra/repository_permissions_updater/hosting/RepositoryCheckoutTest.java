package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCheckoutTest {

    @TempDir
    Path dir;

    @Test
    void readsFilesOfDefaultBranch() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir)
                .commit("pom.xml", "<project/>")
                .commit("src/main/resources/a/config.jelly", "<j:jelly/>")
                .commit("src/main/resources/b/index.jelly", "<j:jelly/>")
                .commit("src/main/resources/b/help.html", "<p/>");

        try (RepositoryCheckout checkout = repo.checkout()) {
            assertThat(checkout.isFile("pom.xml")).isTrue();
            assertThat(checkout.read("pom.xml")).contains("<project/>");
            assertThat(checkout.isFile("Jenkinsfile")).isFalse();
            assertThat(checkout.read("Jenkinsfile")).isEmpty();
            assertThat(checkout.isFile("src")).isFalse();
            assertThat(checkout.findFiles("src/main/resources", ".jelly"))
                    .containsExactly("src/main/resources/a/config.jelly", "src/main/resources/b/index.jelly");
            assertThat(checkout.findFiles("does/not/exist", ".jelly")).isEmpty();
            assertThat(checkout.fileUrl("pom.xml"))
                    .matches("https://github.com/me/cool-plugin/blob/[0-9a-f]{40}/pom.xml");
        }
    }

    @Test
    void refusesPathsOutsideTheRepository() throws Exception {
        Files.writeString(dir.resolve("secret.txt"), "secret");
        Path repoDir = Files.createDirectory(dir.resolve("repo"));
        TestGitRepository repo = new TestGitRepository(repoDir).commit("pom.xml", "<project/>");

        try (RepositoryCheckout checkout = repo.checkout()) {
            assertThat(checkout.read("../secret.txt")).isEmpty();
            assertThat(checkout.read("../../secret.txt")).isEmpty();
        }
    }

    @Test
    void doesNotFollowSymbolicLinks() throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "secret");
        Path repoDir = Files.createDirectory(dir.resolve("repo"));
        TestGitRepository repo = new TestGitRepository(repoDir).commit("README.md", "readme");
        try {
            Files.createSymbolicLink(repoDir.resolve("pom.xml"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not supported: " + e);
        }
        repo.git("add", "pom.xml").git("commit", "--quiet", "-m", "link");

        try (RepositoryCheckout checkout = repo.checkout()) {
            // checked out as a plain file containing the link target, not the target's contents
            assertThat(checkout.read("pom.xml"))
                    .hasValueSatisfying(contents -> assertThat(contents).endsWith("secret.txt"));
        }
    }

    @Test
    void findsFoldersRemovedOnMergedBranch() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir).commit("pom.xml", "initial");
        repo.git("checkout", "--quiet", "-b", "feature")
                .commit("target/classes/Foo.class", "build output")
                .git("rm", "--quiet", "-r", "target")
                .git("commit", "--quiet", "-m", "remove build output")
                .git("checkout", "--quiet", "main")
                .commit("README.md", "readme")
                .git("merge", "--quiet", "--no-ff", "-m", "merge feature", "feature");

        try (RepositoryCheckout checkout = repo.checkout()) {
            assertThat(checkout.topLevelFoldersInHistory(List.of("target", "work")))
                    .containsExactly("target");
            assertThat(checkout.isFile("target/classes/Foo.class")).isFalse();
        }
    }

    @Test
    void ignoresNestedFoldersWithTheSameName() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir)
                .commit("src/main/java/target/Foo.java", "nested target package")
                .commit("docs/work/notes.md", "nested work folder");

        try (RepositoryCheckout checkout = repo.checkout()) {
            assertThat(checkout.topLevelFoldersInHistory(List.of("target", "work")))
                    .isEmpty();
        }
    }

    @Test
    void deletesCloneOnClose() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir).commit("pom.xml", "<project/>");
        Path root;
        try (RepositoryCheckout checkout = repo.checkout()) {
            root = checkout.root();
            assertThat(root).isDirectory();
        }
        assertThat(root).doesNotExist();
    }

    @Test
    void parsesGitHubUrls() {
        assertThat(RepositoryCheckout.gitHubUrl("https://github.com/me/cool-plugin"))
                .contains("https://github.com/me/cool-plugin");
        assertThat(RepositoryCheckout.gitHubUrl(" http://github.com/me/cool-plugin.git/ "))
                .contains("https://github.com/me/cool-plugin");
        assertThat(RepositoryCheckout.gitHubUrl("https://gitlab.com/me/cool-plugin"))
                .isEmpty();
        assertThat(RepositoryCheckout.gitHubUrl("https://github.com/me/cool-plugin/tree/main"))
                .isEmpty();
        assertThat(RepositoryCheckout.gitHubUrl(null)).isEmpty();
    }

    @Test
    void readsFilesThatAreNotUtf8() throws Exception {
        TestGitRepository repo = new TestGitRepository(dir).commit("pom.xml", "<project/>");
        Path properties = repo.dir().resolve("src/main/resources/Messages_de.properties");
        Files.createDirectories(properties.getParent());
        Files.write(properties, "name=Gr\u00fc\u00dfe".getBytes(StandardCharsets.ISO_8859_1));
        repo.git("add", ".").git("commit", "--quiet", "-m", "latin-1");

        try (RepositoryCheckout checkout = repo.checkout()) {
            assertThat(checkout.read("src/main/resources/Messages_de.properties"))
                    .hasValueSatisfying(contents -> assertThat(contents).startsWith("name=Gr"));
            assertThat(RepositoryFiles.from(checkout).get("pom.xml")).contains("<project/>");
        }
    }
}
