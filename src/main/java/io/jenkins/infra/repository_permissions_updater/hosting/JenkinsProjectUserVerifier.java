package io.jenkins.infra.repository_permissions_updater.hosting;

import io.jenkins.infra.repository_permissions_updater.KnownUsers;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public class JenkinsProjectUserVerifier implements Verifier {

    @Override
    public void verify(HostingRequest request, HashSet<VerificationMessage> hostingIssues) throws IOException {

        List<String> jenkinsProjectUsers = request.getJenkinsProjectUsers();
        if (!jenkinsProjectUsers.isEmpty()) {
            String missingInArtifactory = jenkinsProjectUsers.stream()
                    .filter(user -> !KnownUsers.existsInArtifactory(user))
                    .collect(Collectors.joining(", "));

            if (StringUtils.isNotBlank(missingInArtifactory)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED,
                        "The following usernames in 'Jenkins project users to have release permission' need to log into [Artifactory](https://repo.jenkins-ci.org/): %s (reports are re-synced hourly, wait to re-check for a bit after logging in)",
                        missingInArtifactory));
            }
        } else {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "The Jenkins project users list is empty. Please specify at least one user."));
        }
    }
}
