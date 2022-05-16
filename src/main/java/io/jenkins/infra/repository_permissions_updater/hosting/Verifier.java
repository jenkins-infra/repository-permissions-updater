package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.util.HashSet;

public interface Verifier {
    void verify(HostingRequest request, HashSet<VerificationMessage> hostingIssues) throws IOException;
}
