package io.jenkins.infra.repository_permissions_updater.hosting.verify;

import io.jenkins.infra.repository_permissions_updater.hosting.HostingChecker;
import io.jenkins.infra.repository_permissions_updater.hosting.HostingConfig;
import io.jenkins.infra.repository_permissions_updater.hosting.model.HostingRequest;
import io.jenkins.infra.repository_permissions_updater.hosting.model.VerificationMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HostingFieldVerifierConsumer implements VerifierConsumer {
    @Override
    public void accept(HostingRequest issue, HashSet<VerificationMessage> hostingIssues) {
        String forkTo = issue.newRepoName();
        String forkFrom = issue.repositoryUrl();
        List<String> users = issue.githubUsers();

        // check list of users
        if (users.isEmpty()) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("MISSING_LIST_OF_USERS")));
        }

        if (StringUtils.isBlank(forkFrom)) {
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, ""));
        } else {
            boolean updateIssue = false;
            if (forkFrom.endsWith(".git")) {
                forkFrom = forkFrom.substring(0, forkFrom.length() - 4);
                updateIssue = true;
            }

            if (forkFrom.startsWith("http://")) {
                forkFrom = forkFrom.replace("http://", "https://");
                updateIssue = true;
            }

            // check the repo they want to fork from to make sure it conforms
            if (!HostingConfig.GITHUB_FORK_PATTERN.matcher(forkFrom).matches()) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, forkFrom));
            }

            if (updateIssue) {
            }
        }

        if (StringUtils.isBlank(forkTo)) {
            HashSet<VerificationMessage> subitems = new HashSet<>();
            subitems.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("IT_MUST_MATCH_ARTIFACT_ID")));
            subitems.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("IT_MUST_MATCH_PLUGIN")));
            subitems.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("IT_MUST_LOWERCASE")));
            subitems.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("IT_MUST_NOT_CONTAIN_JENKINS")));
            subitems.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("IT_MUST_HYPHENS")));
            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, subitems, HostingConfig.RESOURCE_BUNDLE.getString("IT_MUST_NEW_REPO")));
        } else {
            // we don't like camel case - ThisIsCamelCase becomes this-is-camel-case
            Matcher m = Pattern.compile("(\\B[A-Z]+?(?=[A-Z][^A-Z])|\\B[A-Z]+?(?=[^A-Z]))").matcher(forkTo);
            String forkToLower = m.replaceAll("-$1").toLowerCase();
            if (forkToLower.contains("-jenkins") || forkToLower.contains("-hudson")) {
                forkToLower = forkToLower.replace("-jenkins", "").replace("-hudson", "");
            } else if (forkToLower.contains("jenkins") || forkToLower.contains("hudson")) {
                forkToLower = forkToLower.replace("jenkins", "").replace("hudson", "");
            }

            // sometimes if we remove jenkins/hudson, we're left with something like -jmh, so trim it
            forkToLower = StringUtils.strip(forkToLower, "- ");

            if (!forkToLower.endsWith("-plugin")) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingConfig.RESOURCE_BUNDLE.getString("NEW_REPO_MUST_END")));
            }
        }
    }
}
