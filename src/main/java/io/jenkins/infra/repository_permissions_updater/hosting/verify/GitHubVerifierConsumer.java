package io.jenkins.infra.repository_permissions_updater.hosting.verify;

import io.jenkins.infra.repository_permissions_updater.hosting.HostingChecker;
import io.jenkins.infra.repository_permissions_updater.hosting.model.HostingRequest;
import io.jenkins.infra.repository_permissions_updater.hosting.model.VerificationMessage;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

public final class GitHubVerifierConsumer implements VerifierConsumer {

    private final GitHub gitHub;

    public GitHubVerifierConsumer() throws IOException {
        gitHub = GitHub.connect();
    }

    @Override
    public void accept(HostingRequest request, HashSet<VerificationMessage> hostingIssues) {

        String forkFrom = request.repositoryUrl();
        List<String> users = request.githubUsers();

        if (users != null && !users.isEmpty()) {
            List<String> invalidUsers = new ArrayList<>();
            for (String user : users) {
                if (StringUtils.isBlank(user)) {
                    continue;
                }

                try {
                    GHUser ghUser = this.gitHub.getUser(user.trim());
                    if (ghUser == null || !ghUser.getType().equalsIgnoreCase("user")) {
                        invalidUsers.add(user.trim());
                    }
                } catch(IOException e) {
                    invalidUsers.add(user.trim());
                }
            }

            if (invalidUsers.size() > 0) {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The following usernames in 'GitHub Users to Authorize as Committers' are not valid GitHub usernames or are Organizations: %s", String.join(",", invalidUsers)));
            }
        }

        if (StringUtils.isNotBlank(forkFrom)) {
            forkFrom = forkFrom.trim();

            Matcher m = Pattern.compile("https?://github\\.com/(\\S+)/(\\S+)", CASE_INSENSITIVE).matcher(forkFrom);
            if (m.matches()) {
                String owner = m.group(1);
                String repoName = m.group(2);

                if (forkFrom.endsWith(".git")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The origin repository '%s' ends in .git, please remove this", forkFrom));
                    forkFrom = forkFrom.substring(0, forkFrom.length() - 4);
                }

                if (!forkFrom.startsWith("https://")) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "The origin repository '%s' doesn't use https, please fix", forkFrom));
                    forkFrom = forkFrom.replace("http://", "https://");
                }

                GHRepository repo = null;
                try {
                    repo = gitHub.getRepository(owner + "/" + repoName);
                } catch (Exception e) {
                    hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, forkFrom));
                }

                if (repo != null) {
                    try {
                        GHContent readme = repo.getReadme();
                        if(readme == null) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Please add a readme file to your repo, GitHub provides an easy mechanism to do this from their user interface."));
                        }
                    } catch (IOException e) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Please add a readme file to your repo, GitHub provides an easy mechanism to do this from their user interface."));
                    }

                    try {
                        GHLicense license = repo.getLicense();
                        if(license == null) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Please add a license file to your repo, GitHub provides an easy mechanism to do this from their user interface."));
                        }
                    } catch(IOException e) {
                        hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Please add a license file to your repo, GitHub provides an easy mechanism to do this from their user interface."));
                    }

                    // check if the repo was originally forked from jenkinsci
                    try {
                        GHRepository parent = repo.getParent();
                        if (parent != null && parent.getFullName().startsWith("jenkinsci")) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Repository '%s' is currently showing as forked from a jenkinsci org repository, this relationship needs to be broken", forkFrom));
                        }
                    } catch (IOException e) {

                    }

                    // now need to check if there are any forks INTO jenkinsci already from this repo
                    try {
                        List<String> badForks = new ArrayList<>();
                        for (GHRepository fork : repo.listForks()) {
                            if (fork.getFullName().startsWith("jenkinsci")) {
                                badForks.add(fork.getFullName());
                            }
                        }

                        if (badForks.size() > 0) {
                            hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, "Repository '%s' already has the following forks in the jenkinsci org: %s", forkFrom, String.join(", ", badForks)));
                        }
                    } catch (Exception e) {
                        // need to try this out to see what type of exceptions might occur
                    }
                }
            } else {
                hostingIssues.add(new VerificationMessage(VerificationMessage.Severity.REQUIRED, HostingChecker.INVALID_FORK_FROM, forkFrom));
            }
        }
    }
}
