package io.jenkins.infra.repository_permissions_updater;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Access to current GitHub team membership -- reads to compute the diff between the desired state
 * (declared in YAML) and the actual state (on GitHub), and, when not running in dry-run mode, the mutating
 * calls used to reconcile that diff (add/remove team members).
 * <p>
 * All of this, reads and writes alike, only ever runs in the trusted, post-merge execution, never in
 * credential-free PR builds -- see the project README for why.
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

    /**
     * Adds a user to a team, with the default {@code member} role (never {@code maintainer} -- this project
     * never grants GitHub team maintainer/admin rights, only membership).
     *
     * @param organization the GitHub organization login (e.g. {@code "jenkinsci"})
     * @param teamSlug the team slug to add the member to
     * @param login the GitHub login to add
     */
    public abstract void addTeamMember(@NonNull String organization, @NonNull String teamSlug, @NonNull String login)
            throws IOException;

    /**
     * Removes a user from a team. Removing the last member of a team is allowed and is not treated as an
     * error -- org owners always retain the ability to restore access to any repo/team regardless of its
     * member count.
     *
     * @param organization the GitHub organization login (e.g. {@code "jenkinsci"})
     * @param teamSlug the team slug to remove the member from
     * @param login the GitHub login to remove
     */
    public abstract void removeTeamMember(@NonNull String organization, @NonNull String teamSlug, @NonNull String login)
            throws IOException;

    static synchronized GitHubTeamsAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GitHubTeamsAPIImpl();
        }
        return INSTANCE;
    }
}
