package io.jenkins.infra.repository_permissions_updater.hosting;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public class HostingFieldVerifier implements Verifier {

    @Override
    public void verify(HostingRequest issue, HashSet<VerificationMessage> hostingIssues) {
        String forkTo = issue.getNewRepoName();
        String forkFrom = issue.getRepositoryUrl();
        List<String> users = issue.getGithubUsers();

        // check list of users
        if (users.isEmpty()) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "Missing list of users to authorize in 'GitHub Users to Authorize as Committers'"));
        }

        if (StringUtils.isBlank(forkFrom)) {
            hostingIssues.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, ""));
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
            if (!Pattern.matches("https://github\\.com/(\\S+)/(\\S+)", forkFrom)) {
                hostingIssues.add(new VerificationMessage(
                        VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, forkFrom));
            }

            if (updateIssue) {
                // TODO implement update
            }
        }

        if (StringUtils.isBlank(forkTo)) {
            HashSet<VerificationMessage> subitems = new HashSet<>();
            subitems.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "It must match the artifactId (with -plugin added) from your pom.xml."));
            subitems.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "It must end in -plugin if hosting request is for a Jenkins plugin."));
            subitems.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "It must be all lowercase."));
            subitems.add(
                    new VerificationMessage(VerificationMessage.Severity.REQUIRED, "It must NOT contain \"Jenkins\"."));
            subitems.add(new VerificationMessage(
                    VerificationMessage.Severity.REQUIRED,
                    "It must use hyphens ( - ) instead of spaces or camel case."));
            hostingIssues.add(
                    new VerificationMessage(
                            VerificationMessage.Severity.REQUIRED,
                            subitems,
                            "You must specify the repository name to fork to in 'New Repository Name' field with the following rules:"));
        } else {
            String originalForkTo = forkTo;
            // we don't like camel case - ThisIsCamelCase becomes this-is-camel-case
            Matcher m = Pattern.compile("(\\B[A-Z]+?(?=[A-Z][^A-Z])|\\B[A-Z]+?(?=[^A-Z]))")
                    .matcher(forkTo);
            String forkToLower = m.replaceAll("-$1").toLowerCase();
            if (forkToLower.contains("-jenkins") || forkToLower.contains("-hudson")) {
                forkToLower = forkToLower.replace("-jenkins", "").replace("-hudson", "");
            } else if (forkToLower.contains("jenkins") || forkToLower.contains("hudson")) {
                forkToLower = forkToLower.replace("jenkins", "").replace("hudson", "");
            }

            // sometimes if we remove jenkins/hudson, we're left with something like -jmh, so trim it
            forkToLower = StringUtils.strip(forkToLower, "- ");

            if (!forkToLower.endsWith("-plugin")) {
                hostingIssues.add(
                        new VerificationMessage(
                                VerificationMessage.Severity.REQUIRED,
                                "'New Repository Name' must end with \"-plugin\" (disregard if you are not requesting hosting of a plugin)"));
            }

            // we don't like spaces...
            forkToLower = forkToLower.replace(" ", "-");

            if (!forkToLower.equals(originalForkTo)) {
                // TODO implement update
                //                issueUpdates.add(new IssueInputBuilder().setFieldValue(JiraHelper.FORK_TO_JIRA_FIELD,
                // forkToLower).build());
            }
        }

        // TODO implement update
        //        if(issueUpdates.size() > 0) {
        //            for(IssueInput issueUpdate : issueUpdates) {
        //                issueClient.updateIssue(issue.getKey(), issueUpdate).claim();
        //            }
        //        }
    }
}
