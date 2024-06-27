package io.jenkins.infra.repository_permissions_updater.launcher;

import groovy.json.JsonBuilder;
import io.jenkins.infra.repository_permissions_updater.ArtifactoryAPI;
import io.jenkins.infra.repository_permissions_updater.Definition;
import io.jenkins.infra.repository_permissions_updater.JiraAPI;
import io.jenkins.infra.repository_permissions_updater.KnownUsers;
import io.jenkins.infra.repository_permissions_updater.TeamDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

class PayloadHelper {
    /**
     * Directory containing the permissions definition files in YAML format
     */
    private static final Path DEFINITIONS_DIR = Path.of(System.getProperty("definitionsDir", "./permissions"));

    static final Logger LOGGER = Logger.getLogger(PayloadHelper.class.getName());
    /**
     * Set to true during development to prevent collisions with production behavior:
     *
     * - Different prefixes for groups and permission targets in {@link ArtifactoryAPI}.
     * - Different GitHub secret names in {@link ArtifactoryPermissionsUpdater#generateTokens(java.io.File)}
     * - Permissions are only granted for
     *
     */
    private static final boolean DEVELOPMENT = Boolean.getBoolean("development");


    /**
     * Take the YAML permission definitions and convert them to Artifactory permissions API payloads.
     */
    static void generateApiPayloads(File apiOutputDir) throws Exception {
        Yaml yaml = new Yaml(new Constructor(Definition.class, new LoaderOptions()));

        if (Files.notExists(DEFINITIONS_DIR)) {
            throw new IOException("Directory " + DEFINITIONS_DIR + " does not exist");
        }

        if (apiOutputDir.exists()) {
            throw new IOException(apiOutputDir.getPath() + " already exists");
        }

        Map<String, TeamDefinition> teamsByName = TeamsHelper.loadTeams();

        Map<String, Set<String>> pathsByGithub = new TreeMap<>();
        Map<String, List<Map<String, Object>>> issueTrackersByPlugin = new TreeMap<>();
        Map<String, List<Definition>> cdEnabledComponentsByGitHub = new TreeMap<>();
        Map<String, List<String>> maintainersByComponent = new HashMap<>();
        try (var files = Files.list(DEFINITIONS_DIR)) {
            files.forEach(path -> {
                var file = path.toFile();
                if (!file.getName().endsWith(".yml")) {
                    throw new RuntimeException("Unexpected file: `" + file.getName() + "`. YAML files must end with `.yml`");
                }

                Definition definition;
                try {
                    definition = yaml.loadAs(new FileReader(file, StandardCharsets.UTF_8), Definition.class);
                    TeamsHelper.expandTeams(definition, teamsByName);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to read " + file.getName(), e);
                }

                if (definition.getGithub() != null) {
                    Set<String> paths = pathsByGithub.computeIfAbsent(definition.getGithub(), k -> new TreeSet<>());
                    paths.addAll(Arrays.asList(definition.getPaths()));

                    if (definition.getCd() != null && definition.getCd().enabled) {
                        if (!definition.getGithub().matches("(jenkinsci)/.+")) {
                            throw new RuntimeException("CD is only supported when the GitHub repository is in @jenkinsci");
                        }
                        if (!Arrays.asList(definition.getDevelopers()).isEmpty()) {
                            List<Definition> definitions = cdEnabledComponentsByGitHub.computeIfAbsent(definition.getGithub(), k -> new ArrayList<>());
                            LOGGER.log(Level.INFO, "CD-enabled component '" + definition.getName() + "' in repository '" + definition.getGithub() + "'");
                            definitions.add(definition);
                        } else {
                            LOGGER.log(Level.INFO, "Skipping CD-enablement for '" + definition.getName() + "' in repository '" + definition.getGithub() + "' as it is unmaintained");
                        }
                    }
                } else {
                    if (definition.getCd() != null && definition.getCd().enabled) {
                        throw new RuntimeException("Cannot have CD ('cd') enabled without specifying GitHub repository ('github')");
                    }
                }

                if (definition.getIssues() != null && definition.getIssues().length != 0) {
                    if (definition.getGithub() != null && !definition.getGithub().isEmpty()) {
                        issueTrackersByPlugin.put(definition.getName(), Arrays.stream(definition.getIssues()).map(tracker -> {
                            Map<String, Object> ret = new HashMap<>();
                            if (tracker.isJira() || tracker.isGitHubIssues()) {
                                ret.put("type", tracker.getType());
                                ret.put("reference", tracker.getReference());
                                String viewUrl;
                                try {
                                    viewUrl = tracker.getViewUrl(JiraAPI.getInstance());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                if (viewUrl != null) {
                                    ret.put("viewUrl", viewUrl);
                                }
                                String reportUrl = null;
                                try {
                                    reportUrl = tracker.getReportUrl(JiraAPI.getInstance());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                if (reportUrl != null) {
                                    ret.put("reportUrl", reportUrl);
                                }
                                return ret;
                            }
                            return null;
                        }).filter(Objects::nonNull).toList());
                    } else {
                        throw new RuntimeException("Issue trackers ('issues') support requires GitHub repository ('github')");
                    }
                }

                String artifactId = definition.getName();
                for (String definitionPath : definition.getPaths()) {
                    if (!definitionPath.substring(definitionPath.lastIndexOf('/') + 1).equals(artifactId)) {
                        LOGGER.log(Level.WARNING, "Unexpected path: " + definitionPath + " for artifact ID: " + artifactId);
                    }
                    String groupId = definitionPath.substring(0, definitionPath.lastIndexOf('/')).replace('/', '.');

                    String key = groupId + ":" + artifactId;

                    if (maintainersByComponent.containsKey(key)) {
                        LOGGER.log(Level.WARNING, "Duplicate maintainers entry for component: " + key);
                    }
                    maintainersByComponent.computeIfAbsent(key, k -> new ArrayList<>(Arrays.asList(definition.getDevelopers())));
                }

                String fileBaseName = file.getName().replaceAll("\\.ya?ml$", "");

                String jsonName = ArtifactoryAPI.toGeneratedPermissionTargetName(fileBaseName);
                var permissionPath = Path.of(artifactId, "permissions");
                Path outputFile = permissionPath.resolve(jsonName + ".json");
                if (!outputFile.normalize().startsWith(permissionPath)) {
                    throw new RuntimeException("Not allowed to navigate outside of the current folder");
                }
                JsonBuilder json = new JsonBuilder();

                json.call("name", jsonName);
                json.call("includesPattern", Arrays.stream(definition.getPaths()).flatMap(definitionPath -> Arrays.stream(new String[]{
                        definitionPath + "/*/" + definition.getName() + "-*",
                        definitionPath + "/*/maven-metadata.xml", // used for SNAPSHOTs
                        definitionPath + "/*/maven-metadata.xml.*",
                        definitionPath + "/maven-metadata.xml",
                        definitionPath + "/maven-metadata.xml.*"
                })).collect(Collectors.joining(",")));
                json.call("excludesPattern", "");
                json.call("repositories", DEVELOPMENT ? Collections.singletonList("snapshots") : Arrays.asList("snapshots", "releases"));
                json.call("principals", new HashMap<String, Object>() {{
                    if (Arrays.asList(definition.getDevelopers()).isEmpty()) {
                        put("users", Collections.emptyMap());
                        put("groups", Collections.emptyMap());
                        if (definition.getCd() != null && definition.getCd().enabled) {
                            LOGGER.log(Level.INFO, "Skipping CD group definition for " + definition.getName() + " as there are no maintainers");
                        }
                    } else {
                        if (definition.getCd() == null || !definition.getCd().exclusive) {
                            put("users", Arrays.stream(definition.getDevelopers()).collect(Collectors.toMap(
                                    developer -> {
                                        if (!KnownUsers.existsInArtifactory(developer) && !KnownUsers.existsInJira(developer) && !JiraAPI.getInstance().isUserPresent(developer)) {
                                            reportChecksApiDetails(developer + " needs to log in to Artifactory and Jira",
                                                    developer + " needs to log in to [Artifactory](https://repo.jenkins-ci.org/) and [Jira](https://issues.jenkins.io/).\n" +
                                                            "\nWe resync our Artifactory and Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.\n" +
                                                            "The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.\n" +
                                                            "\nAlternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.\n"
                                            );
                                            throw new IllegalStateException("User name not known to Artifactory and Jira: " + developer);
                                        }
                                        if (!KnownUsers.existsInArtifactory(developer)) {
                                            reportChecksApiDetails(developer + " needs to log in to Artifactory",
                                                    developer + " needs to log in to [Artifactory](https://repo.jenkins-ci.org/).\n" +
                                                            "\nWe resync our Artifactory user list every 2 hours, so you will need to wait some time before rebuilding your pull request.\n" +
                                                            "The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.\n" +
                                                            "\nAlternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.\n"
                                            );
                                            throw new IllegalStateException("User name not known to Artifactory: " + developer);
                                        }
                                        if (!KnownUsers.existsInJira(developer)) {
                                            reportChecksApiDetails(developer + " needs to log in to Jira",
                                                    developer + " needs to log in to [Jira](https://issues.jenkins.io/)\n" +
                                                            "\nWe resync our Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.\n" +
                                                            "The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.\n" +
                                                            "\nAlternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.\n"
                                            );
                                            throw new IllegalStateException("User name not known to Jira: " + developer);
                                        }
                                        return developer.toLowerCase(Locale.US);
                                    },
                                    developer -> Arrays.asList("w", "n")
                            )));
                        } else {
                            for (String developer : definition.getDevelopers()) {
                                if (!KnownUsers.existsInJira(developer) && !JiraAPI.getInstance().isUserPresent(developer)) {
                                    reportChecksApiDetails(developer + " needs to log in to Jira",
                                            developer + " needs to log in to [Jira](https://issues.jenkins.io/)\n" +
                                                    "\nWe resync our Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.\n" +
                                                    "The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.\n" +
                                                    "\nAlternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.\n"
                                    );
                                    throw new IllegalStateException("User name not known to Jira: " + developer);
                                }
                            }
                            put("users", Collections.emptyMap());
                        }
                        if (definition.getCd() != null && definition.getCd().enabled) {
                            put("groups", Collections.singletonMap(ArtifactoryAPI.toGeneratedGroupName(definition.getGithub()), Arrays.asList("w", "n")));
                        } else {
                            put("groups", Collections.emptyMap());
                        }
                    }
                }});

                String pretty = json.toPrettyString();

                try {
                    Files.createDirectories(permissionPath);
                    Files.createFile(outputFile);
                    Files.writeString(outputFile, pretty);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }


            });

        }

        for (Map.Entry<String, List<Definition>> entry : cdEnabledComponentsByGitHub.entrySet()) {
            String githubRepo = entry.getKey();
            List<Definition> components = entry.getValue();
            String groupName = ArtifactoryAPI.toGeneratedGroupName(githubRepo);
            var groupsPath = apiOutputDir.toPath().resolve("groups");
            Path outputFile = groupsPath.resolve(groupName + ".json");
            if (!outputFile.normalize().startsWith(groupsPath)) {
                throw new IOException("Not allowed to navigate outside of the current folder");
            }
            JsonBuilder json = new JsonBuilder();

            json.call("name", groupName);
            json.call("description", "CD group with permissions to deploy from " + githubRepo);

            String pretty = json.toPrettyString();

            Files.createDirectories(groupsPath);
            Files.createFile(outputFile);
            Files.writeString(outputFile, pretty);
        }

        JsonBuilder githubIndex = new JsonBuilder();
        githubIndex.call(pathsByGithub);
        try {
            Files.writeString(new File(apiOutputDir, "github.index.json").toPath(), githubIndex.toPrettyString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        JsonBuilder issuesIndex = new JsonBuilder();
        issuesIndex.call(issueTrackersByPlugin);
        try {
            Files.writeString(new File(apiOutputDir, "issues.index.json").toPath(), issuesIndex.toPrettyString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        JsonBuilder cdRepos = new JsonBuilder();
        cdRepos.call(cdEnabledComponentsByGitHub.keySet().toArray());
        try {
            Files.writeString(new File(apiOutputDir, "cd.index.json").toPath(), cdRepos.toPrettyString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        JsonBuilder maintainers = new JsonBuilder();
        maintainers.call(maintainersByComponent);
        try {
            Files.writeString(new File(apiOutputDir, "maintainers.index.json").toPath(), maintainers.toPrettyString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO It's a really weird decision to have this in the otherwise invocation agnostic standalone tool
    private static void reportChecksApiDetails(String errorMessage, String details) {
        try {
            Files.writeString(new File("checks-title.txt").toPath(), errorMessage);
            Files.writeString(new File("checks-details.txt").toPath(), details);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
