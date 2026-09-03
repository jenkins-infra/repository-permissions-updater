package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;

public interface Verifier {
    void verify(HostingRequest request) throws IOException;
}
