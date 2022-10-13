package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

public abstract class ConditionChecker {
    public abstract boolean checkCondition(HostingRequest issue) throws IOException;

    protected boolean fileExistsInForkFrom(HostingRequest issue, String fileName) throws IOException {
        boolean res = false;
        GitHub github = GitHub.connect();
        String forkFrom = issue.getRepositoryUrl();

        if (StringUtils.isNotBlank(forkFrom)) {
            Matcher m = Pattern.compile("(?:https://github\\.com/)?(\\S+)/(\\S+)", Pattern.CASE_INSENSITIVE).matcher(forkFrom);
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                GHRepository repo = github.getRepository(owner + "/" + repoName);
                try {
                    GHContent file = repo.getFileContent(fileName);
                    res = file != null && file.isFile();
                } catch (IOException ignored) {
                }
            }
        }
        return res;
    }
}
