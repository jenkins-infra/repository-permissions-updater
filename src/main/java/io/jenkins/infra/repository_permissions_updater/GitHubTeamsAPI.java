package io.jenkins.infra.repository_permissions_updater;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Read-only access to current GitHub team membership, used to compute the diff between the desired state
 * (declared in YAML) and the actual state (on GitHub), for the GitHub permissions report.
 * <p>
 * This is intentionally read-only: see the README/plan for why GitHub permission reconciliation (including
 * these reads) only ever runs in the trusted, post-merge execution, never in credential-free PR builds.
 */
public abstract class GitHubTeamsAPI {

    static GitHubTeamsAPI INSTANCE = null;

    /**
     * Fetches the current members (GitHub login names) of the given teams within an organization.
     * Implementations should batch this into as few HTTP requests as possible, since the managed set can
     * span thousands of teams.
     *
     * @param organization the GitHub organization login (e.g. {@code "jenkinsci"})
     * @param teamSlugs the team slugs to fetch membership for; teams not found are simply absent from the result
     * @return a map from team slug to the set of member logins; teams with no members or that don't exist
     *         are mapped to an empty set rather than omitted, so callers can distinguish "no members" from
     *         "not queried"
     */
    @NonNull
    public abstract Map<String, Set<String>> fetchTeamMembers(
            @NonNull String organization, @NonNull Set<String> teamSlugs) throws IOException;

    static synchronized GitHubTeamsAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GitHubTeamsAPIImpl();
        }
        return INSTANCE;
    }
}
