package io.jenkins.infra.repository_permissions_updater.hosting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class RequirementsTest {

    @Test
    void validateLowestParentPom() {
        String suffix = Requirements.LOWEST_PARENT_POM_VERSION.suffix();
        String[] segments = suffix.split("\\.");
        assertThat(segments.length).isEqualTo(2);
        assertThat(segments[0]).containsOnlyDigits();
    }
}
