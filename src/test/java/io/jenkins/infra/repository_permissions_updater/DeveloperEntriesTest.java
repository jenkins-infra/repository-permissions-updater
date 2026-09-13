package io.jenkins.infra.repository_permissions_updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeveloperEntriesTest {

    @Test
    void extractLdapIdReturnsNullForGitHubOnlyEntry() {
        assertNull(DeveloperEntries.extractLdapId(Map.of("github", "someuser")));
    }

    @Test
    void extractLdapIdReturnsLdapForPlainStringAndMappingEntries() {
        assertEquals("alice", DeveloperEntries.extractLdapId("alice"));
        assertEquals("bob", DeveloperEntries.extractLdapId(Map.of("ldap", "bob", "github", "bob-gh")));
    }

    @Test
    void toLdapIdsSkipsGitHubOnlyEntries() {
        Object[] developers = {
            "alice", Map.of("ldap", "bob", "github", "bob-gh"), Map.of("github", "no-ldap-user"),
        };

        assertArrayEquals(new String[] {"alice", "bob"}, DeveloperEntries.toLdapIds(developers));
    }

    @Test
    void extractGitHubOnlyLoginsReturnsOnlyGitHubOnlyEntries() {
        Object[] developers = {
            "alice", Map.of("ldap", "bob", "github", "bob-gh"), Map.of("github", "no-ldap-user"),
        };

        assertEquals(Set.of("no-ldap-user"), DeveloperEntries.extractGitHubOnlyLogins(developers));
    }

    @Test
    void extractLdapOnlyIdsReturnsOnlyLdapOnlyEntries() {
        Object[] developers = {
            "alice",
            Map.of("ldap", "bob", "github", "bob-gh"),
            Map.of("github", "no-ldap-user"),
            Map.of("ldap", "excluded-from-github"),
        };

        assertEquals(Set.of("excluded-from-github"), DeveloperEntries.extractLdapOnlyIds(developers));
    }

    @Test
    void extractGitHubUsernamesDoesNotIncludeGitHubOnlyEntries() {
        Object[] developers = {
            "alice", Map.of("ldap", "bob", "github", "bob-gh"), Map.of("github", "no-ldap-user"),
        };

        assertEquals(Map.of("bob", "bob-gh"), DeveloperEntries.extractGitHubUsernames(developers));
    }

    @Test
    void extractDedupKeyUsesGitHubLoginWhenNoLdapIsPresent() {
        assertEquals("github:no-ldap-user", DeveloperEntries.extractDedupKey(Map.of("github", "no-ldap-user")));
        assertEquals("bob", DeveloperEntries.extractDedupKey(Map.of("ldap", "bob", "github", "bob-gh")));
        assertEquals("alice", DeveloperEntries.extractDedupKey("alice"));
    }

    @Test
    void extractTeamReferenceNameRecognizesBothLegacyAndTypedForms() {
        assertEquals("core", DeveloperEntries.extractTeamReferenceName("@core"));
        assertEquals("core", DeveloperEntries.extractTeamReferenceName(Map.of("team", "core")));
        assertNull(DeveloperEntries.extractTeamReferenceName("alice"));
        assertNull(DeveloperEntries.extractTeamReferenceName(Map.of("ldap", "alice", "github", "alice")));
    }

    @Test
    void expandTeamReferencesInlinesReferencedTeamMembers() {
        TeamDefinition core = new TeamDefinition();
        core.setName("core");
        core.setDevelopers(new Object[] {"alice", Map.of("ldap", "bob", "github", "bob-gh")});
        Map<String, Set<TeamDefinition>> teamsByName = Map.of("core", Set.of(core));

        Object[] expanded = DeveloperEntries.expandTeamReferences(
                "example.yml", new Object[] {"carol", Map.of("team", "core")}, teamsByName);

        assertEquals(3, expanded.length);
        assertEquals(Set.of("carol", "alice", "bob"), Set.of(DeveloperEntries.toLdapIds(expanded)));
    }

    @Test
    void expandTeamReferencesSupportsNestedTeamOfTeams() {
        TeamDefinition inner = new TeamDefinition();
        inner.setName("inner");
        inner.setDevelopers(new Object[] {"alice"});
        TeamDefinition outer = new TeamDefinition();
        outer.setName("outer");
        outer.setDevelopers(new Object[] {Map.of("team", "inner"), "bob"});
        Map<String, Set<TeamDefinition>> teamsByName = Map.of("inner", Set.of(inner), "outer", Set.of(outer));

        Object[] expanded = DeveloperEntries.expandTeamReferences(
                "example.yml", new Object[] {Map.of("team", "outer")}, teamsByName);

        assertEquals(Set.of("alice", "bob"), Set.of(DeveloperEntries.toLdapIds(expanded)));
    }

    @Test
    void expandTeamReferencesRejectsUnknownTeam() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DeveloperEntries.expandTeamReferences(
                        "example.yml", new Object[] {Map.of("team", "does-not-exist")}, Map.of()));
    }

    @Test
    void expandTeamReferencesRejectsCyclicTeamReferences() {
        TeamDefinition a = new TeamDefinition();
        a.setName("a");
        a.setDevelopers(new Object[] {Map.of("team", "b")});
        TeamDefinition b = new TeamDefinition();
        b.setName("b");
        b.setDevelopers(new Object[] {Map.of("team", "a")});
        Map<String, Set<TeamDefinition>> teamsByName = Map.of("a", Set.of(a), "b", Set.of(b));

        assertThrows(
                IllegalArgumentException.class,
                () -> DeveloperEntries.expandTeamReferences(
                        "example.yml", new Object[] {Map.of("team", "a")}, teamsByName));
    }
}
