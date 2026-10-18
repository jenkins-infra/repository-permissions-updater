package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a local git repository for tests, which {@link RepositoryCheckout} can clone like a GitHub one.
 */
final class TestGitRepository {

    static final String HTML_URL = "https://github.com/me/cool-plugin";

    private final Path dir;

    TestGitRepository(Path dir) throws IOException, InterruptedException {
        this.dir = dir;
        git("init", "--quiet", "--initial-branch=main");
    }

    TestGitRepository commit(String file, String contents) throws IOException, InterruptedException {
        Path path = dir.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
        git("add", file);
        git("commit", "--quiet", "-m", "update " + file);
        return this;
    }

    TestGitRepository git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "git", "-c", "user.name=Test", "-c", "user.email=test@example.org", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor())
                .as(String.join(" ", command) + "\n" + output)
                .isZero();
        return this;
    }

    RepositoryCheckout checkout() throws IOException, InterruptedException {
        return RepositoryCheckout.clone(dir.toUri().toString(), HTML_URL);
    }

    Path dir() {
        return dir;
    }
}
