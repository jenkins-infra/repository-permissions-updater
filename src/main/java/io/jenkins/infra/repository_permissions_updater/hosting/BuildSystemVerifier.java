package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;

public interface BuildSystemVerifier extends Verifier {
    boolean hasBuildFile(HostingRequest issue) throws IOException;
}
