package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The text files of the default branch of the repository to host, read once from the local clone so that checks
 * can search the sources in memory.
 */
public final class RepositoryFiles {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "groovy", "kt", "jelly", "css", "scss", "less", "js", "html", "properties", "md", "adoc", "xml");
    private static final int MAX_FILE_SIZE = 2 * 1024 * 1024;

    private final String blobUrl;
    private final Map<String, String> files;

    RepositoryFiles(String blobUrl, Map<String, String> files) {
        this.blobUrl = blobUrl;
        this.files = Collections.unmodifiableMap(new TreeMap<>(files));
    }

    public Optional<String> get(String path) {
        return Optional.ofNullable(files.get(path));
    }

    /**
     * @return the files whose path matches, keyed by path relative to the repository root
     */
    public Map<String, String> matching(Predicate<String> path) {
        Map<String, String> result = new TreeMap<>();
        files.forEach((p, content) -> {
            if (path.test(p)) {
                result.put(p, content);
            }
        });
        return result;
    }

    /**
     * @return a Markdown link to the file at the given line, {@code line} 0 links to the file itself
     */
    public String link(String path, int line) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (line > 0) {
            return "[%s#L%d](%s%s#L%d)".formatted(name, line, blobUrl, path, line);
        }
        return "[%s](%s%s)".formatted(path, blobUrl, path);
    }

    static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Reads the text files of the default branch from a local clone.
     */
    public static RepositoryFiles from(RepositoryCheckout checkout) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (String path : checkout.findFiles("", "")) {
            if (isTextFile(path) && checkout.size(path) <= MAX_FILE_SIZE) {
                checkout.read(path).ifPresent(content -> files.put(path, content));
            }
        }
        return new RepositoryFiles(checkout.fileUrl(""), files);
    }

    private static boolean isTextFile(String path) {
        if (path.contains("node_modules/") || path.startsWith("target/") || path.startsWith("work/")) {
            return false;
        }
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        return TEXT_EXTENSIONS.contains(extension);
    }
}
