package io.jenkins.infra.repository_permissions_updater;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 *     existing GitHub team membership that hasn't yet been (or never will be) tied to an LDAP account; or</li>
 *     <li>a mapping with only an {@code ldap} key (no {@code github}): a developer who should be excluded
 *     from GitHub permissions management entirely -- unlike a plain string, their LDAP id is <em>not</em>
 *     assumed to also be their GitHub login. Useful when someone's LDAP id happens to look like a GitHub
 *     login that either doesn't belong to them or shouldn't be granted GitHub access; or</li>
 *     <li>a mapping with only a {@code team} key, e.g. {@code {team: "cloudbees-developers"}}: an explicit,
 *     typed reference to a cross-repository {@code teams/*.yml} team, expanded (recursively, since a
 *     referenced team's own {@code developers} may itself contain team references) into that team's
 *     {@code developers} entries. This is the typed equivalent of the legacy {@code "@team-name"}
 *     plain-string reference, usable even once {@code manageGitHubPermissions}/{@code manageGitHubTeam} has
 *     banned plain-string entries.</li>
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
     * Resolves the GitHub login for every entry in {@code developers}, entry by entry, lower-cased:
     * <ul>
     *     <li>an entry with a {@code github} key ({@code {ldap, github}} or {@code {github}}) uses that login --
     *     its LDAP id, if any, is never consulted;</li>
     *     <li>an LDAP-only mapping entry ({@code {ldap}}, no {@code github}) is excluded from GitHub
     *     permissions management and contributes nothing;</li>
     *     <li>a plain string entry (legacy format) is assumed to be both the LDAP id and the GitHub login.</li>
     * </ul>
     * Team references are expected to have been expanded already (see {@link #expandTeamReferences}).
     */
    static Set<String> extractGitHubLogins(Object[] developers) {
        Set<String> logins = new TreeSet<>();
        for (Object entry : developers) {
            String login = null;
            if (entry instanceof Map<?, ?> map) {
                Object github = map.get("github");
                if (github != null) {
                    login = github.toString();
                }
            } else if (entry != null) {
                login = entry.toString();
            }
            if (login != null && !login.isBlank()) {
                logins.add(login.toLowerCase(Locale.ROOT));
            }
        }
        return logins;
    }

    /**
     * Returns the team name referenced by a single {@code developers} entry, if it is a team reference --
     * either the legacy {@code "@team-name"} plain-string form, or the explicit {@code {team: "team-name"}}
     * mapping form (the typed equivalent, usable even once {@code manageGitHubPermissions}/
     * {@code manageGitHubTeam} has banned plain-string entries) -- or {@code null} if the entry is not a
     * team reference.
     */
    static String extractTeamReferenceName(Object entry) {
        if (entry instanceof String s && s.startsWith("@")) {
            return s.substring(1);
        }
        if (entry instanceof Map<?, ?> map && map.size() == 1 && map.containsKey("team")) {
            Object team = map.get("team");
            return team == null ? null : team.toString();
        }
        return null;
    }

    /**
     * Recursively expands every team-reference entry ({@code "@team-name"} or {@code {team: "team-name"}})
     * in {@code developers} into the referenced {@code teams/*.yml} team's own {@code developers} entries
     * (which may themselves contain further team references, expanded in turn), deduplicating by
     * {@link #extractDedupKey(Object)} (first occurrence wins). Detects and rejects cyclic team references.
     * Non-team-reference entries (plain strings, {@code {ldap, github}}/{@code {github}}/{@code {ldap}}
     * mappings) pass through unchanged.
     *
     * @param contextName a human-readable name (e.g. the source file name) used only for error messages.
     */
    static Object[] expandTeamReferences(
            String contextName, Object[] developers, Map<String, Set<TeamDefinition>> teamsByName) {
        Map<String, Object> expandedByKey = new LinkedHashMap<>();
        expandInto(contextName, developers, teamsByName, new LinkedHashSet<>(), expandedByKey);
        return expandedByKey.values().toArray();
    }

    private static void expandInto(
            String contextName,
            Object[] developers,
            Map<String, Set<TeamDefinition>> teamsByName,
            Set<String> visiting,
            Map<String, Object> expandedByKey) {
        for (Object entry : developers) {
            String teamName = extractTeamReferenceName(entry);
            if (teamName == null) {
                expandedByKey.putIfAbsent(extractDedupKey(entry), entry);
                continue;
            }
            if (!visiting.add(teamName)) {
                throw new IllegalArgumentException("Cyclic team reference to '" + teamName
                        + "' detected while expanding developers for " + contextName);
            }
            Set<TeamDefinition> teamDevs = teamsByName.get(teamName);
            if (teamDevs == null) {
                throw new IllegalArgumentException("Team " + teamName + " not found!");
            }
            if (teamDevs.isEmpty()) {
                throw new IllegalArgumentException("Team " + teamName + " is empty?!");
            }
            for (TeamDefinition teamDev : teamDevs) {
                expandInto(contextName, teamDev.getDevelopers(), teamsByName, visiting, expandedByKey);
            }
            visiting.remove(teamName);
        }
    }
}
