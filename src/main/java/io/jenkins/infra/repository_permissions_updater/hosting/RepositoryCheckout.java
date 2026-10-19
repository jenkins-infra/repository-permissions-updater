package io.jenkins.infra.repository_permissions_updater.hosting;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A local clone of the repository requested for hosting, so that verifications can read its files from disk
 * instead of making one GitHub API request per file.
 *
 * <p>The clone has the full history of all branches but only fetches file contents for the default branch,
 * which keeps it small and fast even for repositories with long histories.
 * The repository is untrusted: symbolic links are checked out as plain files and Git LFS content is not downloaded.
 */
public final class RepositoryCheckout implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryCheckout.class);

    private static final Pattern GITHUB_URL =
            Pattern.compile("https?://github\\.com/([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?", CASE_INSENSITIVE);

    private static final long GIT_TIMEOUT_MINUTES = 10;

    private final Path tempDir;
    private final Path root;
    private final String htmlUrl;
    private final String commit;

    private RepositoryCheckout(Path tempDir, Path root, String htmlUrl, String commit) {
        this.tempDir = tempDir;
        this.root = root;
        this.htmlUrl = htmlUrl;
        this.commit = commit;
    }

    /**
     * @return the {@code https://github.com/owner/repo} URL of a hosting request's repository URL,
     *     or empty if it is not a GitHub repository URL
     */
    public static Optional<String> gitHubUrl(String repositoryUrl) {
        if (repositoryUrl == null) {
            return Optional.empty();
        }
        Matcher m = GITHUB_URL.matcher(repositoryUrl.trim());
        return m.matches() ? Optional.of("https://github.com/" + m.group(1) + "/" + m.group(2)) : Optional.empty();
    }

    /**
     * Clones a GitHub repository.
     *
     * @param htmlUrl the {@code https://github.com/owner/repo} URL, see {@link #gitHubUrl(String)}
     */
    public static RepositoryCheckout cloneGitHub(String htmlUrl) throws IOException, InterruptedException {
        return clone(htmlUrl + ".git", htmlUrl);
    }

    /**
     * Clones a repository.
     *
     * @param cloneUrl where to clone from
     * @param htmlUrl the web URL of the repository, used to link to files in messages
     */
    static RepositoryCheckout clone(String cloneUrl, String htmlUrl) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("hosting-checkout-");
        try {
            Path root = tempDir.resolve("repo");
            runGit(
                    tempDir,
                    "clone",
                    "--quiet",
                    "--filter=blob:none",
                    "--config",
                    "core.symlinks=false",
                    "--",
                    cloneUrl,
                    root.toString());
            String commit = runGit(root, "rev-parse", "HEAD").trim();
            return new RepositoryCheckout(tempDir, root, htmlUrl, commit);
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteRecursively(tempDir);
            throw e;
        }
    }

    /**
     * @return whether the given path, relative to the repository root, is a regular file on the default branch
     */
    public boolean isFile(String path) {
        Path file = resolve(path);
        return file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * @return the contents of the given file on the default branch decoded as UTF-8, with invalid bytes replaced,
     *     or empty if there is no such file
     */
    public Optional<String> read(String path) throws IOException {
        if (!isFile(path)) {
            return Optional.empty();
        }
        // not Files.readString, which fails on files that are not valid UTF-8, e.g. ISO-8859-1 .properties files
        return Optional.of(new String(Files.readAllBytes(resolve(path)), StandardCharsets.UTF_8));
    }

    /**
     * @return the paths, relative to the repository root and using {@code /} as separator, of all regular files on
     *     the default branch below the given directory whose name ends with the given suffix
     */
    public List<String> findFiles(String directory, String suffix) throws IOException {
        Path dir = resolve(directory);
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Path gitDir = root.resolve(".git");
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(p -> !p.startsWith(gitDir))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /**
     * @return the size in bytes of the given file on the default branch, or 0 if there is no such file
     */
    public long size(String path) throws IOException {
        return isFile(path) ? Files.size(resolve(path)) : 0;
    }

    Path root() {
        return root;
    }

    /**
     * @return the GitHub URL to view the given file at the checked out commit
     */
    public String fileUrl(String path) {
        return htmlUrl + "/blob/" + commit + "/" + path;
    }

    /**
     * @return which of the given top-level folders contained files in any commit, on any branch
     */
    public Set<String> topLevelFoldersInHistory(Collection<String> folders) throws IOException, InterruptedException {
        List<String> args =
                new ArrayList<>(List.of("log", "--all", "--full-history", "--format=", "--name-only", "--"));
        args.addAll(folders);
        return runGit(root, args.toArray(String[]::new))
                .lines()
                .filter(file -> file.contains("/"))
                .map(file -> file.split("/", 2)[0])
                .filter(folders::contains)
                .collect(Collectors.toSet());
    }

    /**
     * Resolves a repository-relative path, refusing anything that would escape the repository.
     */
    private Path resolve(String path) {
        Path resolved = root.resolve(path).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    @Override
    public void close() {
        deleteRecursively(tempDir);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to delete temporary checkout {}", dir, e);
        }
    }

    @SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification =
                    "No shell is involved, and the only external argument is a GitHub clone URL passed after --")
    private static String runGit(Path workingDir, String... args) throws IOException, InterruptedException {
        // a user's core.fsmonitor setting would start a background daemon for every temporary clone
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.fsmonitor=false"));
        command.addAll(List.of(args));
        Path outputFile = Files.createTempFile("hosting-git-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            // never prompt for credentials, e.g. when the repository does not exist
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            // do not download large files, the checks only look at source files
            pb.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
            Process process = pb.start();
            if (!process.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("git " + String.join(" ", args) + " timed out");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("git command failed with exit code " + process.exitValue() + ": " + output);
            }
            return output;
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
