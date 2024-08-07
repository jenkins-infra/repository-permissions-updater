package io.jenkins.infra.repository_permissions_updater.hosting.condition;

import io.jenkins.infra.repository_permissions_updater.hosting.model.HostingRequest;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PomFileExistsCondition implements Predicate<HostingRequest> {

    private static final String POM_FILE_NAME = "pom.xml";
    private static final Pattern GITHUB_HOST_PATTERN = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Logger LOGGER = LoggerFactory.getLogger(PomFileExistsCondition.class);
    private final GitHub github;

    public PomFileExistsCondition() throws IOException {
        this.github = GitHub.connect();
    }

    @Override
    public boolean test(HostingRequest hostingRequest) {
        boolean res = false;

        String forkFrom = hostingRequest.repositoryUrl();

        if (!StringUtils.isNotBlank(forkFrom)) return res;

        Matcher m = GITHUB_HOST_PATTERN.matcher(forkFrom);

        if (!m.matches()) return res;

        String owner = m.group(1);
        String repoName = m.group(2);

        GHRepository repo = null;
        try {
            repo = github.getRepository(owner + "/" + repoName);
        } catch (IOException e) {
            LOGGER.warn("Could not get repo {}", owner + "/" + repoName, e);
        }
        if (repo == null) return res;

        try {
            GHContent file = repo.getFileContent(POM_FILE_NAME);
            res = file != null && file.isFile();
        } catch (IOException ignored) {
        }
        return res;
    }
}
