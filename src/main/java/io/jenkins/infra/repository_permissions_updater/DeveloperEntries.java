package io.jenkins.infra.repository_permissions_updater;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for interpreting the polymorphic {@code developers} YAML list used by both
 * {@link Definition} and {@link TeamDefinition}. Each entry may be either:
 * <ul>
 *     <li>a plain string (legacy format): just a Jenkins community (LDAP) id, used for Artifactory
 *     permissions only; or</li>
 *     <li>a mapping with both {@code ldap} and {@code github} keys (new format): ties a developer's LDAP id
 *     to their GitHub login 1-to-1, so the same entry can be used for both Artifactory permissions and the
 *     (opt-in) GitHub permissions management feature.</li>
 * </ul>
 */
final class DeveloperEntries {

    private DeveloperEntries() {}

    /**
     * Extracts the Jenkins community (LDAP) id from a single {@code developers} entry.
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
        }
        throw new IllegalArgumentException(
                "Invalid 'developers' entry, expected a plain string or a {ldap, github} mapping, but got: " + entry);
    }

    /**
     * Extracts the LDAP id for every entry in {@code developers}, in order.
     */
    static String[] toLdapIds(Object[] developers) {
        String[] ids = new String[developers.length];
        for (int i = 0; i < developers.length; i++) {
            ids[i] = extractLdapId(developers[i]);
        }
        return ids;
    }

    /**
     * Builds an LDAP id -&gt; GitHub login map from every {@code {ldap, github}} mapping entry in
     * {@code developers}. Plain string entries contribute nothing here; callers that need a GitHub login for
     * every developer should fall back to treating the LDAP id as the GitHub login when it's absent from this
     * map.
     */
    static Map<String, String> extractGithubUsernames(Object[] developers) {
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
}
