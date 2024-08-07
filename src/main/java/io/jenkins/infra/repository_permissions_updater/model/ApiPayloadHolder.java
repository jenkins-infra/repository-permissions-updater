package io.jenkins.infra.repository_permissions_updater.model;

import io.jenkins.infra.repository_permissions_updater.Definition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record ApiPayloadHolder(
        Map<String, Set<String>> pathsByGithub,
        Map<String, List<Definition>> cdEnabledComponentsByGitHub,
        Map<String, List<Map<String, Object>>> issueTrackersByPlugin,
        Map<String, List<String>> maintainersByComponent
) {
    ApiPayloadHolder() {
        this(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new HashMap<>());
    }

    public static ApiPayloadHolder create() {
        return new ApiPayloadHolder();
    }
}
