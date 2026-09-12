package io.jenkins.infra.repository_permissions_updater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
