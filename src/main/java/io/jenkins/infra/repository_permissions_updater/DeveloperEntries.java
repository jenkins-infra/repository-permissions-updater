package io.jenkins.infra.repository_permissions_updater;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for interpreting the polymorphic {@code developers} YAML list used by both
 * {@link Definition} and {@link TeamDefinition}. Each entry may be either:
 * <ul>
 *     <li>a plain string (legacy format): just a Jenkins community (LDAP) id, used for Artifactory
 *     permissions only; or</li>
 *     <li>a mapping with both {@code ldap} and {@code github} keys (new format): ties a developer's LDAP id
 *     to their GitHub login 1-to-1, so the same entry can be used for both Artifactory permissions and the
 *     (opt-in) GitHub permissions management feature; or</li>
 *     <li>a mapping with only a {@code github} key (no {@code ldap}): a developer with a GitHub login but no
 *     Jenkins community (LDAP) account, used for GitHub permissions management only. Useful for backfilling
 *     existing GitHub team membership that hasn't yet been (or never will be) tied to an LDAP account.</li>
 * </ul>
 */
final class DeveloperEntries {

    private DeveloperEntries() {}

    /**
     * Extracts the Jenkins community (LDAP) id from a single {@code developers} entry, or {@code null} if
     * the entry is a GitHub-only mapping (has a {@code github} key but no {@code ldap} key).
     */
    static String extractLdapId(Object entry) {
        if (entry instanceof String s) {
            return s;
        }
        if (entry instanceof Map<?, ?> map) {
            Object ldap = map.get("ldap");
            if (ldap != null) {
                return ldap.toString();
            }
            if (map.get("github") != null) {
                return null;
            }
        }
        throw new IllegalArgumentException(
                "Invalid 'developers' entry, expected a plain string, a {ldap, github} mapping, or a "
                        + "{github} mapping, but got: " + entry);
    }

    /**
     * Extracts a stable dedup key for a single {@code developers} entry: the LDAP id when present, otherwise
     * (for a GitHub-only mapping entry) a key derived from its GitHub login. Used where entries need to be
     * deduplicated (e.g. {@code @team} expansion) even though GitHub-only entries have no LDAP id to key on.
     */
    static String extractDedupKey(Object entry) {
        String ldapId = extractLdapId(entry);
        if (ldapId != null) {
            return ldapId;
        }
        Map<?, ?> map = (Map<?, ?>) entry;
        return "github:" + map.get("github");
    }

    /**
     * Extracts the LDAP id for every entry in {@code developers} that has one, in order. GitHub-only mapping
     * entries (no {@code ldap} key) are skipped -- they have no Jenkins community account and so are not
     * relevant to Artifactory permissions or any other LDAP-id-keyed logic.
     */
    static String[] toLdapIds(Object[] developers) {
        String[] ids = new String[developers.length];
        int count = 0;
        for (Object developer : developers) {
            String ldapId = extractLdapId(developer);
            if (ldapId != null) {
                ids[count++] = ldapId;
            }
        }
        if (count == ids.length) {
            return ids;
        }
        String[] trimmed = new String[count];
        System.arraycopy(ids, 0, trimmed, 0, count);
        return trimmed;
    }

    /**
     * Builds an LDAP id -&gt; GitHub login map from every {@code {ldap, github}} mapping entry in
     * {@code developers}. Plain string entries and GitHub-only mapping entries contribute nothing here;
     * callers that need a GitHub login for every developer should fall back to treating the LDAP id as the
     * GitHub login when it's absent from this map.
     */
    static Map<String, String> extractGitHubUsernames(Object[] developers) {
        Map<String, String> logins = new LinkedHashMap<>();
        for (Object entry : developers) {
            if (entry instanceof Map<?, ?> map) {
                Object ldap = map.get("ldap");
                Object github = map.get("github");
                if (ldap != null && github != null) {
                    logins.put(ldap.toString(), github.toString());
                }
            }
        }
        return logins;
    }

    /**
     * Extracts the GitHub login from every GitHub-only mapping entry ({@code {github: ...}}, no
     * {@code ldap} key) in {@code developers}. These are developers with no Jenkins community (LDAP)
     * account, so they only ever show up here, never in {@link #toLdapIds(Object[])} or
     * {@link #extractGitHubUsernames(Object[])}.
     */
    static Set<String> extractGitHubOnlyLogins(Object[] developers) {
        Set<String> logins = new LinkedHashSet<>();
        for (Object entry : developers) {
            if (entry instanceof Map<?, ?> map && map.get("ldap") == null && map.get("github") != null) {
                logins.add(map.get("github").toString());
            }
        }
        return logins;
    }
}
