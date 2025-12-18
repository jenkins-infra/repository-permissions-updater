package io.jenkins.infra.repository_permissions_updater.cli;

import io.jenkins.infra.repository_permissions_updater.cli.commands.CheckHostingCommand;
import io.jenkins.infra.repository_permissions_updater.cli.commands.HostCommand;
import io.jenkins.infra.repository_permissions_updater.cli.commands.SyncCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for the Repository Permissions Updater CLI.
 * Provides unified access to all repository permissions operations.
 */
@Command(
        name = "rpu-cli",
        description = "Repository Permissions Updater - Manage Jenkins plugin permissions",
        mixinStandardHelpOptions = true,
        version = "1.0-SNAPSHOT",
        subcommands = {SyncCommand.class, CheckHostingCommand.class, HostCommand.class})
public class RepositoryPermissionsUpdaterCLI implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RepositoryPermissionsUpdaterCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // When no subcommand is specified, show usage
        CommandLine.usage(this, System.out);
    }
}
