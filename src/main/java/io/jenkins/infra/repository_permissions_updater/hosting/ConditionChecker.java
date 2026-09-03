package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;

public abstract class ConditionChecker {
    public abstract boolean checkCondition(HostingRequest issue) throws IOException;

    protected boolean fileExistsInForkFrom(HostingRequest issue, String fileName) throws IOException {
        return HostingChecker.fileExistsInRepo(issue, fileName);
    }
}
