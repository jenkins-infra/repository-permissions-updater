package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;

public class FileExistsConditionChecker extends ConditionChecker {
    private final String fileName;

    public FileExistsConditionChecker(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public boolean checkCondition(HostingRequest issue) throws IOException {
        return fileExistsInForkFrom(issue, fileName);
    }
}
