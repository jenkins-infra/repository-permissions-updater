package io.jenkins.infra.repository_permissions_updater;

import java.util.Map;
import java.util.Set;

public class TeamDefinition {

    private String name = "";

    /**
     * Each entry is either a plain string (legacy format: a Jenkins community/LDAP id, used for Artifactory
     * permissions), a mapping with both {@code ldap} and {@code github} keys (ties an LDAP id to a GitHub
     * login 1-to-1, so the two can differ when needed), or a mapping with only a {@code github} key (a
     * developer with a GitHub login but no Jenkins community/LDAP account -- GitHub permissions management
     * only, useful for backfill). See {@link #getDevelopers()}, {@link #getGitHubUsernames()}, and
     * {@link #getGitHubOnlyUsernames()}.
     */
    private Object[] developers = new Object[0];

    /**
     * Opt-in flag: if {@code true}, RPU will reconcile GitHub team membership for this cross-repository
     * team, using {@link #developers} (see {@link #getGitHubUsernames()} for how GitHub logins are
     * resolved), when granted to a repository via {@link Definition.AdditionalGitHubTeam}. Defaults to
     * {@code false}.
     */
    private boolean manageGitHubTeam;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Object[] getDevelopers() {
        return developers.clone();
    }

    public void setDevelopers(Object[] developers) {
        this.developers = developers.clone();
    }

    /**
     * Returns the Jenkins community (LDAP) id for every {@link #developers} entry (see
     * {@link Definition#getDeveloperIds()} for details).
     */
    public String[] getDeveloperIds() {
        return DeveloperEntries.toLdapIds(developers);
    }

    public boolean isManageGitHubTeam() {
        return manageGitHubTeam;
    }

    public void setManageGitHubTeam(boolean manageGitHubTeam) {
        this.manageGitHubTeam = manageGitHubTeam;
    }

    /**
     * Returns the GitHub login to use for each {@link #developers} entry declared using the
     * {@code {ldap, github}} mapping form, keyed by that entry's LDAP id. Entries declared as a plain string
     * are not included here -- callers should treat the LDAP id itself as the GitHub login for those.
     */
    public Map<String, String> getGitHubUsernames() {
        return DeveloperEntries.extractGitHubUsernames(developers);
    }

    /**
     * Returns the GitHub login for each {@link #developers} entry declared using the GitHub-only mapping
     * form ({@code {github: ...}}, no {@code ldap} key) -- developers with a GitHub login but no Jenkins
     * community (LDAP) account, so they never appear in {@link #getDeveloperIds()}.
     */
    public Set<String> getGitHubOnlyUsernames() {
        return DeveloperEntries.extractGitHubOnlyLogins(developers);
    }
}
