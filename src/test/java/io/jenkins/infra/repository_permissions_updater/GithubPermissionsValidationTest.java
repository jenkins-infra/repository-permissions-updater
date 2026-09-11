package io.jenkins.infra.repository_permissions_updater;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the static (network-free) validation of the {@code developers} list's polymorphic entry shapes
 * (plain LDAP id strings, or {@code {ldap, github}} mappings) as well as the GitHub permissions management
 * fields ({@code manageGithubPermissions}, {@code repositoryTeam}, {@code additionalGithubTeams}). These
 * fields are opt-in and must not affect components that don't use them, and validation must not require any
 * GitHub API access so it stays safe for credential-free PR builds.
 */
class GithubPermissionsValidationTest {

    private static File writeDefinition(String yaml) throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        File file = new File(permissions, "plugin-example.yml");
        Files.writeString(file.toPath(), yaml);
        return permissions;
    }

    private static void generate(String yaml) throws IOException {
        File permissions = writeDefinition(yaml);
        File payloads = Files.createTempDirectory("json").toFile();
        payloads.deleteOnExit();
        ArtifactoryPermissionsUpdater.doGenerateApiPayloads(permissions, payloads, new NoopArtifactoryAPI());
    }

    @Test
    void unmanagedComponentIgnoresGithubFields() {
        assertDoesNotThrow(() -> generate("""
                ---
                name: "example"
                """));
    }

    @Test
    void managedComponentRequiresGithubRepo() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                manageGithubPermissions: true
                """));
        assertContainsCause(ex, "requires a GitHub repository");
    }

    @Test
    void validManagedComponentPasses() {
        assertDoesNotThrow(() -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - ldap: "timja"
                    github: "timja-gh"
                manageGithubPermissions: true
                additionalGithubTeams:
                  - name: "core"
                    role: "push"
                """));
    }

    @Test
    void legacyPlainStringDevelopersAreUsedAsGithubLoginsByDefault() {
        assertDoesNotThrow(() -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "timja"
                manageGithubPermissions: true
                """));
    }

    @Test
    void mixOfLegacyStringsAndLdapGithubMappingsIsAllowed() {
        assertDoesNotThrow(() -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "timja"
                  - ldap: "jetersen"
                    github: "jetersen"
                manageGithubPermissions: true
                """));
    }

    @Test
    void developerMappingMustSpecifyBothLdapAndGithub() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - ldap: "timja"
                manageGithubPermissions: true
                """));
        assertContainsCause(ex, "must specify exactly both 'ldap' and 'github'");
    }

    @Test
    void developerMappingRejectsUnknownKeys() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - ldap: "timja"
                    github: "timja"
                    extra: "nope"
                manageGithubPermissions: true
                """));
        assertContainsCause(ex, "must specify exactly both 'ldap' and 'github'");
    }

    @Test
    void duplicateLdapIdsAreRejected() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "timja"
                  - ldap: "timja"
                    github: "timja"
                manageGithubPermissions: true
                """));
        assertContainsCause(ex, "Duplicate developer");
    }

    @Test
    void duplicateGithubLoginsAreRejected() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - ldap: "usera"
                    github: "shared-gh"
                  - ldap: "userb"
                    github: "shared-gh"
                manageGithubPermissions: true
                """));
        assertContainsCause(ex, "Duplicate GitHub user name");
    }

    @Test
    void invalidGithubUsernameIsRejected() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - ldap: "timja"
                    github: "not a valid name!"
                manageGithubPermissions: true
                """));
        assertContainsCause(ex, "invalid GitHub user name");
    }

    @Test
    void unknownAdditionalTeamIsRejected() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                manageGithubPermissions: true
                additionalGithubTeams:
                  - name: "does-not-exist"
                    role: "push"
                """));
        assertContainsCause(ex, "references unknown team");
    }

    @Test
    void invalidRoleIsRejected() {
        IOException ex = assertThrows(IOException.class, () -> generate("""
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                manageGithubPermissions: true
                additionalGithubTeams:
                  - name: "core"
                    role: "owner"
                """));
        assertContainsCause(ex, "invalid 'role'");
    }

    private static void assertContainsCause(IOException ex, String expected) {
        Throwable cause = ex.getCause();
        assertNotNull(cause);
        assertTrue(
                cause.getMessage() != null && cause.getMessage().contains(expected),
                "Expected cause message to contain '" + expected + "' but was: " + cause.getMessage());
    }

    private static class NoopArtifactoryAPI extends ArtifactoryAPI {
        @Override
        public List<String> listGeneratedPermissionTargets() {
            return List.of();
        }

        @Override
        public void createOrReplacePermissionTarget(@NonNull String name, @NonNull File payloadFile) {}

        @Override
        public void deletePermissionTarget(@NonNull String target) {}

        @NonNull
        @Override
        public List<String> listGeneratedGroups() {
            return List.of();
        }

        @Override
        public void createOrReplaceGroup(String name, File payloadFile) {}

        @Override
        public void deleteGroup(String group) {}

        @Override
        public String generateTokenForGroup(String username, String group, long expiresInSeconds) {
            return "";
        }
    }
}
