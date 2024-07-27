package io.jenkins.infra.repository_permissions_updater.hosting.verify;

import io.jenkins.infra.repository_permissions_updater.KnownUsers;
import io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig;
import io.jenkins.infra.repository_permissions_updater.hosting.model.HostingRequest;
import io.jenkins.infra.repository_permissions_updater.hosting.model.VerificationMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class JenkinsProjectUserVerifierConsumer implements VerifierConsumer {
    @Override
    public void accept(HostingRequest request, HashSet<VerificationMessage> hostingIssues) {
        String missingInArtifactory = request.jenkinsProjectUsers()
                .stream()
                .filter(Predicate.not(KnownUsers::existsInArtifactory))
                .collect(Collectors.joining(", "));

        String missingInJira = request.jenkinsProjectUsers()
                .stream().filter(Predicate.not(KnownUsers::existsInJira))
                .collect(Collectors.joining(", "));

        if (StringUtils.isNotBlank(missingInArtifactory)) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("MISSING_IN_ARTIFACTORY"), missingInArtifactory));
        }
        if (StringUtils.isNotBlank(missingInJira)) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("MISSING_IN_JIRA"), missingInJira));
        }
    }
}
