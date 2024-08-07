package io.jenkins.infra.repository_permissions_updater.helper;

import java.nio.file.Path;

public sealed interface PayloadHelper permits PayloadHelperImpl {

    void run() throws Exception;

    static PayloadHelper create(Path apiOutputDir) throws Exception {
        return new PayloadHelperImpl(apiOutputDir);
    }

}
