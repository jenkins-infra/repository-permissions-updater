package io.jenkins.infra.repository_permissions_updater.run;

import java.util.List;
import java.util.Map;

public record PermissionJson(
        String name,
        String includesPattern,
        String excludesPattern,
        List<String> repositories,
        Principals principals
) {
    public record Principals(
            Map<String, List<String>> users,
            Map<String, List<String>> groups
    ) {}
}