package io.jenkins.infra.repository_permissions_updater.cli.commands;

import io.jenkins.infra.repository_permissions_updater.hosting.Hoster;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Command to process an approved hosting request.
 * Wraps the functionality of {@link Hoster}.
 */
@Command(name = "host", description = "Process an approved plugin hosting request", mixinStandardHelpOptions = true)
public class HostCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "GitHub issue number of the hosting request to process")
    private int issueNumber;

    @Override
    public Integer call() throws Exception {
        new Hoster().run(issueNumber);
        return 0;
    }
}
