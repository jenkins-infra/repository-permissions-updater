package io.jenkins.infra.repository_permissions_updater.github_team_sync;

public class AdditionalTeamDefinition {
    private String teamName;
    private Role role;

    public AdditionalTeamDefinition(String teamName, String role) {
        this.teamName = teamName;
        this.role = validateRole(role);
    }

    public String getName() {
        return teamName;
    }

    public Role getRole() {
        return role;
    }

    private Role validateRole(String role) {
        if (role == null) {
            return null;
        }
        try {
            return Role.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid team role: " + role);
        }
    }
}

