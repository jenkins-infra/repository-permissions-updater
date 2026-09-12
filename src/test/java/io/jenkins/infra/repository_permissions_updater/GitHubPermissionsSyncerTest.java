package io.jenkins.infra.repository_permissions_updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests GitHub permissions diff generation and application: desired state (from opted-in YAML) vs. actual
 * state (mocked GitHub API), with no live network access. Covers both dry-run (report-only, no mutation) and
 * apply (dryRun=false, mutates via the mocked API) modes.
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
                manageGitHubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of("bob", "carol")));
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        new GitHubPermissionsSyncer(true).sync(permissions, teams, report);

        assertEquals("jenkinsci", stub.lastOrganization);
        assertEquals(Set.of("example-plugin-developers"), stub.lastTeamSlugs);

        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        JsonObject team = json.getAsJsonObject("example-plugin-developers");
        assertEquals("jenkinsci", team.get("organization").getAsString());
        assertEquals("[\"alice\"]", team.getAsJsonArray("toAdd").toString());
        assertEquals("[\"carol\"]", team.getAsJsonArray("toRemove").toString());
    }

    @Test
    void ldapGitHubMappingEntryAppliesToDesiredState() throws IOException {
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
                manageGitHubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of()));
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        new GitHubPermissionsSyncer(true).sync(permissions, teams, report);

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
        new GitHubPermissionsSyncer(true).sync(permissions, teams, report);

        assertTrue(stub.fetchCalls == 0, "Should not call the GitHub API when nothing has opted in");
        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        assertEquals(0, json.entrySet().size());
    }

    @Test
    void dryRunDoesNotMutateAnything() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "alice"
                  - "bob"
                manageGitHubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of("bob", "carol")));
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        new GitHubPermissionsSyncer(true).sync(permissions, teams, report);

        assertTrue(stub.added.isEmpty(), "Dry-run must not add anyone");
        assertTrue(stub.removed.isEmpty(), "Dry-run must not remove anyone");

        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        JsonObject team = json.getAsJsonObject("example-plugin-developers");
        assertTrue(team.get("dryRun").getAsBoolean());
        assertEquals("[\"alice\"]", team.getAsJsonArray("toAdd").toString());
        assertEquals("[\"carol\"]", team.getAsJsonArray("toRemove").toString());
    }

    @Test
    void applyingDiffAddsAndRemovesMembersViaApi() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "alice"
                  - "bob"
                manageGitHubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of("bob", "carol")));
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        new GitHubPermissionsSyncer(false).sync(permissions, teams, report);

        assertEquals(List.of("example-plugin-developers:alice"), stub.added);
        assertEquals(List.of("example-plugin-developers:carol"), stub.removed);

        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        JsonObject team = json.getAsJsonObject("example-plugin-developers");
        assertFalse(team.get("dryRun").getAsBoolean());
        assertFalse(team.has("errors"), "No errors expected when every mutation succeeds");
    }

    @Test
    void applyFailuresAreLoggedInReportButDoNotStopOtherMutations() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "alice"
                  - "bob"
                manageGitHubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub =
                new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of("bob", "carol", "dave")));
        stub.loginsToFailOn = Set.of("alice", "carol");
        GitHubTeamsAPI.INSTANCE = stub;

        File report = new File(Files.createTempDirectory("json").toFile(), "github-permissions-diff.json");
        new GitHubPermissionsSyncer(false).sync(permissions, teams, report);

        // "alice" (add) and "carol" (remove) fail; "dave" (remove) still succeeds despite those failures.
        assertTrue(stub.added.isEmpty());
        assertEquals(List.of("example-plugin-developers:dave"), stub.removed);

        JsonObject json = new Gson().fromJson(Files.readString(report.toPath()), JsonObject.class);
        JsonObject team = json.getAsJsonObject("example-plugin-developers");
        assertEquals(2, team.getAsJsonArray("errors").size());
    }

    @Test
    void writeReportFalseSkipsReportButStillApplies() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "alice"
                  - "bob"
                manageGitHubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of("bob", "carol")));
        GitHubTeamsAPI.INSTANCE = stub;

        // No report file is passed (and none should be needed) since writeReport is false.
        new GitHubPermissionsSyncer(false).sync(permissions, teams, null, false);

        assertEquals(List.of("example-plugin-developers:alice"), stub.added);
        assertEquals(List.of("example-plugin-developers:carol"), stub.removed);
    }

    @Test
    void writeReportFalseSkipsReportInDryRunToo() throws IOException {
        File permissions = Files.createTempDirectory("permissions").toFile();
        permissions.deleteOnExit();
        Files.writeString(new File(permissions, "plugin-example.yml").toPath(), """
                ---
                name: "example"
                github: "jenkinsci/example-plugin"
                developers:
                  - "alice"
                  - "bob"
                manageGitHubPermissions: true
                """);
        File teams = Files.createTempDirectory("teams").toFile();
        teams.deleteOnExit();

        StubGitHubTeamsAPI stub = new StubGitHubTeamsAPI(Map.of("example-plugin-developers", Set.of("bob", "carol")));
        GitHubTeamsAPI.INSTANCE = stub;

        new GitHubPermissionsSyncer(true).sync(permissions, teams, null, false);

        assertTrue(stub.added.isEmpty(), "Dry-run must not add anyone even when the report is skipped");
        assertTrue(stub.removed.isEmpty(), "Dry-run must not remove anyone even when the report is skipped");
    }

    @Test
    void slugifyMatchesGitHubTeamSlugConvention() {
        assertEquals("example-plugin-developers", GitHubPermissionsSyncer.slugify("example-plugin Developers"));
        assertEquals("core", GitHubPermissionsSyncer.slugify("core"));
    }

    private static class StubGitHubTeamsAPI extends GitHubTeamsAPI {
        private final Map<String, Set<String>> membersBySlug;
        String lastOrganization;
        Set<String> lastTeamSlugs;
        int fetchCalls;
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        Set<String> loginsToFailOn = Set.of();

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

        @Override
        public void addTeamMember(@NonNull String organization, @NonNull String teamSlug, @NonNull String login)
                throws IOException {
            if (loginsToFailOn.contains(login)) {
                throw new IOException("simulated failure adding " + login);
            }
            added.add(teamSlug + ":" + login);
        }

        @Override
        public void removeTeamMember(@NonNull String organization, @NonNull String teamSlug, @NonNull String login)
                throws IOException {
            if (loginsToFailOn.contains(login)) {
                throw new IOException("simulated failure removing " + login);
            }
            removed.add(teamSlug + ":" + login);
        }
    }
}
