package io.jenkins.infra.repository_permissions_updater.hosting;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks that build output was never committed, on any branch, as it would be copied into the jenkinsci organization.
 */
public class GitHistoryVerifier implements Verifier {

    private static final String UNWANTED_FILES =
            "It was detected that you have files in the `%1$s` folder or that you had in the past files in that folder. "
                    + "Please remove the `%1$s` folder and also rewrite the git history to never have contained any files in the `%1$s` folder.";

    private static final List<String> UNWANTED_FOLDERS = List.of("target", "work");

    private final HashSet<VerificationMessage> hostingIssues;
    private final RepositoryCheckout checkout;

    public GitHistoryVerifier(HashSet<VerificationMessage> hostingIssues, RepositoryCheckout checkout) {
        this.hostingIssues = hostingIssues;
        this.checkout = checkout;
    }

    @Override
    public void verify(HostingRequest request) throws IOException {
        Set<String> found;
        try {
            found = checkout.topLevelFoldersInHistory(UNWANTED_FOLDERS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        for (String folder : UNWANTED_FOLDERS) {
            if (found.contains(folder)) {
                hostingIssues.add(
                        new VerificationMessage(VerificationMessage.Severity.REQUIRED, UNWANTED_FILES, folder));
            }
        }
    }
}
