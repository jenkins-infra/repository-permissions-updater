package io.jenkins.infra.repository_permissions_updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the report-only GitHub permissions diff generation: desired state (from opted-in YAML) vs. actual
 * state (mocked GitHub API), with no live network access and no mutation of any kind.
 */
class GitHubPermissionsSyncerTest {

    @AfterEach
    void resetGitHubTeamsApi() {
        GitHubTeamsAPI.INSTANCE = null;
    }

    @Test
    void computesAddAndRemoveDiffForOptedInComponent() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "alice"
                  - "bob"
                manageGithubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of("bob", "carol")));
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        GitHubPermissionsSyncer.generateDiffReport(permissions, teams, report);

        assertEquals("jenkinsci", stub.lastOrganization);
        assertEquals(Set.of("example-plugin-developers"), stub.lastTeamSlugs);

        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        JsonObject team = json.getAsJsonObject("example-plugin-developers");
        assertEquals("jenkinsci", team.get("organization").getAsString());
        assertEquals("[\"alice\"]", team.getAsJsonArray("toAdd").toString());
        assertEquals("[\"carol\"]", team.getAsJsonArray("toRemove").toString());
    }

    @Test
    void ldapGithubMappingEntryAppliesToDesiredState() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "alice"
                  - ldap: "someldapid"
                    github: "bob-gh"
                manageGithubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of()));
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        GitHubPermissionsSyncer.generateDiffReport(permissions, teams, report);

        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        JsonObject team = json.getAsJsonObject("example-plugin-developers");
        assertEquals("[\"alice\",\"bob-gh\"]", team.getAsJsonArray("toAdd").toString());
    }

    @Test
    void unmanagedComponentIsNotIncludedInReport() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "someuser"
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of());
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        GitHubPermissionsSyncer.generateDiffReport(permissions, teams, report);

        assertTrue(stub.fetchCalls == 0, "Should not call the GitHub API when nothing has opted in");
        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        assertEquals(0, json.entrySet().size());
    }

    @Test
    void slugifyMatchesGithubTeamSlugConvention() {
        assertEquals("example-plugin-developers", GitHubPermissionsSyncer.slugify("example-plugin Developers"));
        assertEquals("core", GitHubPermissionsSyncer.slugify("core"));
    }

    private static class StubGitHubTeamsAPI extends GitHubTeamsAPI {
        private final Map<String, Set<String>> membersBySlug;
        String lastOrganization;
        Set<String> lastTeamSlugs;
        int fetchCalls;

        StubGitHubTeamsAPI(Map<String, Set<String>> membersBySlug) {
            this.membersBySlug = membersBySlug;
        }

        @NonNull
        @Override
        public Map<String, Set<String>> fetchTeamMembers(@NonNull String organization, @NonNull Set<String> teamSlugs) {
            fetchCalls++;
            lastOrganization = organization;
            lastTeamSlugs = teamSlugs;
            return membersBySlug;
        }
    }
}
