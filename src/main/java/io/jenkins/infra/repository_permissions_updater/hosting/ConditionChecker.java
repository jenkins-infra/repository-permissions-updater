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
        return HostingChecker.fileExistsInRepo(issue, fileName);
    }
}
