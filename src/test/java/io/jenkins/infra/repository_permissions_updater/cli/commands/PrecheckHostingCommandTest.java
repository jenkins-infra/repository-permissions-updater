package io.jenkins.infra.repository_permissions_updater.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrecheckHostingCommandTest {

    @Test
    void repositoryNameFromUrl() {
        assertThat(PrecheckHostingCommand.repositoryName("https://github.com/me/cool-plugin"))
                .isEqualTo("cool-plugin");
        assertThat(PrecheckHostingCommand.repositoryName("https://github.com/me/cool-plugin.git"))
                .isEqualTo("cool-plugin");
        assertThat(PrecheckHostingCommand.repositoryName(" https://github.com/me/cool-plugin/ "))
                .isEqualTo("cool-plugin");
        assertThat(PrecheckHostingCommand.repositoryName("git@github.com:me/cool-plugin.git"))
                .isEmpty();
    }
}
