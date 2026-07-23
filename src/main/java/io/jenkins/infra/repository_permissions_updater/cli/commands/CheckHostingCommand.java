package io.jenkins.infra.repository_permissions_updater.cli.commands;

import io.jenkins.infra.repository_permissions_updater.hosting.HostingChecker;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Command to check a hosting request.
 * Configuration is via system properties if needed.
 */
@Command(
        name = "check-hosting",
        description = "Check a plugin hosting request for compliance",
        mixinStandardHelpOptions = true)
public class CheckHostingCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "GitHub issue number of the hosting request")
    private int issueNumber;

    @Override
    public Integer call() throws Exception {
        new HostingChecker().checkRequest(issueNumber);
        return 0;
    }
}
