package io.jenkins.infra.repository_permissions_updater.helper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.jenkins.infra.repository_permissions_updater.ArtifactoryAPI;
import io.jenkins.infra.repository_permissions_updater.Definition;
import io.jenkins.infra.repository_permissions_updater.JiraAPI;
import io.jenkins.infra.repository_permissions_updater.KnownUsers;
import io.jenkins.infra.repository_permissions_updater.TeamDefinition;
import io.jenkins.infra.repository_permissions_updater.model.ApiPayloadHolder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

final class PayloadHelperImpl implements PayloadHelper {

    /**
     * Directory containing the permissions definition files in YAML format
     */
    private static final Path DEFINITIONS_DIR = Path.of(System.getProperty("definitionsDir", "./permissions"));

    static final Logger LOGGER = Logger.getLogger(PayloadHelperImpl.class.getName());

    private static final Gson GSON = new Gson();
    /**
     * Set to true during development to prevent collisions with production behavior:
     *
     * - Different prefixes for groups and permission targets in {@link ArtifactoryAPI}.
     * - Different GitHub secret names in {@link ArtifactoryHelper#generateTokens(File)}
     * - Permissions are only granted for
     *
     */
    private static final boolean DEVELOPMENT = Boolean.getBoolean("development");

    private static final Yaml YAML_DEFINITION = new Yaml(new Constructor(Definition.class, new LoaderOptions()));
    private static final String NEEDS_LOGGED_IN_JIRA_AND_ARTIFACTORY =  "%s needs to log in to Artifactory and Jira";
    private static final String TEXT_LOGGED_IN_JIRA_AND_ARTIFACTORY = """
             %s needs to log in to [Artifactory](https://repo.jenkins-ci.org/) and [Jira](https://issues.jenkins.io/).

            We resync our Artifactory and Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.
            The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

            Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
            """;
    private static final String NEEDS_LOGGED_IN_ARTIFACTORY =  "%s needs to log in to Artifactory";
    private static final String TEXT_LOGGED_IN_ARTIFACTORY = """
            %s needs to log in to [Artifactory](https://repo.jenkins-ci.org/).

            We resync our Artifactory user list every 2 hours, so you will need to wait some time before rebuilding your pull request.
            The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

            Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
            """;
    private static final String NEEDS_LOGGED_IN_JIRA =  "%s needs to log in to Jira";
    private static final String TEXT_LOGGED_IN_JIRA = """
            %s needs to log in to [Jira](https://issues.jenkins.io/)

            We resync our Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.
            The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

            Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
            """;

    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:*.yml");


    private final Path apiOutputDir;
    private final ApiPayloadHolder apiPayloadHolder;
    private final Map<String, TeamDefinition> teamsByName;

    PayloadHelperImpl(Path apiOutputDir) throws Exception {
        this.apiOutputDir = apiOutputDir;
        apiPayloadHolder = ApiPayloadHolder.create();
        teamsByName = TeamsHelper.loadTeams();
    }

    @Override
    public void run() throws Exception {
        if (Files.notExists(DEFINITIONS_DIR)) {
            throw new IOException("Directory " + DEFINITIONS_DIR + " does not exist");
        }

        if (Files.exists(apiOutputDir)) {
            throw new IOException(apiOutputDir + " already exists");
        }
        Flux.fromStream(Files.list(DEFINITIONS_DIR))
                .doOnError(this::logException)
                .onErrorResume(throwable -> {
                    this.logException(throwable);
                    return Mono.empty();
                })
                .filter(this::isYamlFile)
                .mapNotNull(this::convertIntoDefinition)
                .mapNotNull(this::expandDefinitionWithTeams)
                .doOnNext(this::fillGithubBased)
                .doOnNext(this::fillPluginBased)
                .doOnNext(this::fillComponentBased)
                .doOnNext(this::writeBack)
                .doOnComplete(this::generateFilePayload)
                .subscribe();
    }

    private boolean isYamlFile(Path path) {
        if (!matcher.matches(path.getFileName())) {
            throw new RuntimeException("Unexpected file: `" + path.getFileName() + "`. YAML files must end with `.yml`");
        }
        return true;
    }

    private Definition convertIntoDefinition(Path path) {
        Definition definition;
        try {
            definition = YAML_DEFINITION.loadAs(Files.newBufferedReader(path, StandardCharsets.UTF_8), Definition.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + path.getFileName(), e);
        }
        definition.setPermissionFile(path);
        return definition;
    }

    private Definition expandDefinitionWithTeams(Definition definition) {
        TeamsHelper.expandTeams(definition, teamsByName);
        return definition;
    }

    private void fillGithubBased(Definition definition) {
        if (definition.getGithub() != null) {
            Set<String> paths = apiPayloadHolder.pathsByGithub().computeIfAbsent(definition.getGithub(), k -> new TreeSet<>());
            paths.addAll(Arrays.asList(definition.getPaths()));

            if (definition.getCd() != null && definition.getCd().enabled) {
                if (!definition.getGithub().matches("(jenkinsci)/.+")) {
                    throw new RuntimeException("CD is only supported when the GitHub repository is in @jenkinsci");
                }
                if (!Arrays.asList(definition.getDevelopers()).isEmpty()) {
                    List<Definition> definitions = apiPayloadHolder.cdEnabledComponentsByGitHub().computeIfAbsent(definition.getGithub(), k -> new ArrayList<>());
                    LOGGER.log(Level.INFO, "CD-enabled component '{0}' in repository '{1}'", new Object[]{definition.getName(), definition.getGithub() });
                    definitions.add(definition);
                } else {
                    LOGGER.log(Level.INFO, "Skipping CD-enablement for '{0}' in repository '{1}' as it is unmaintained", new Object[]{definition.getName(), definition.getGithub()});
                }
            }
        } else {
            if (definition.getCd() != null && definition.getCd().enabled) {
                throw new RuntimeException("Cannot have CD ('cd') enabled without specifying GitHub repository ('github')");
            }
        }
    }

    private void fillPluginBased(Definition definition) {
        if (definition.getIssues() != null && definition.getIssues().length != 0) {
            if (definition.getGithub() != null && !definition.getGithub().isEmpty()) {
                apiPayloadHolder.issueTrackersByPlugin().put(definition.getName(), Arrays.stream(definition.getIssues()).map(tracker -> {
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
                        String reportUrl;
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
    }

    private void fillComponentBased(Definition definition) {
        String artifactId = definition.getName();
        for (String definitionPath : definition.getPaths()) {
            if (!definitionPath.substring(definitionPath.lastIndexOf('/') + 1).equals(artifactId)) {
                LOGGER.log(Level.WARNING, "Unexpected path: {} for artifact ID: {}", new Object[]{definitionPath, artifactId});
            }
            String groupId = definitionPath.substring(0, definitionPath.lastIndexOf('/')).replace('/', '.');

            String key = groupId + ":" + artifactId;

            if (apiPayloadHolder.maintainersByComponent().containsKey(key)) {
                LOGGER.log(Level.WARNING, "Duplicate maintainers entry for component: {}", new Object[]{key});
            }
            apiPayloadHolder.maintainersByComponent().computeIfAbsent(key, k -> new ArrayList<>(Arrays.asList(definition.getDevelopers())));
        }
    }

    private void writeBack(Definition definition) {
        String artifactId = definition.getName();

        String fileBaseName = definition.getPermissionFile().getFileName().toString().replaceAll("\\.ya?ml$", "");

        String jsonName = ArtifactoryAPI.toGeneratedPermissionTargetName(fileBaseName);
        var permissionPath = Path.of(artifactId, "permissions");
        Path outputFile = permissionPath.resolve(jsonName + ".json");
        if (!outputFile.normalize().startsWith(permissionPath)) {
            throw new RuntimeException("Not allowed to navigate outside of the current folder");
        }
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", jsonName);
        jsonObject.addProperty("includesPattern", Arrays.stream(definition.getPaths()).flatMap(definitionPath -> Arrays.stream(new String[]{
                definitionPath + "/*/" + definition.getName() + "-*",
                definitionPath + "/*/maven-metadata.xml", // used for SNAPSHOTs
                definitionPath + "/*/maven-metadata.xml.*",
                definitionPath + "/maven-metadata.xml",
                definitionPath + "/maven-metadata.xml.*"
        })).collect(Collectors.joining(",")));
        jsonObject.addProperty("excludesPattern", "");
        JsonArray jsonElements = new JsonArray();
        (DEVELOPMENT ? Collections.singletonList("snapshots") : Arrays.asList("snapshots", "releases")).forEach(jsonElements::add);
        jsonObject.add("repositories", jsonElements);
        jsonObject.add("principals", GSON.toJsonTree(this.createPrincipals(definition)));

        try {
            Files.createDirectories(permissionPath);
            Files.createFile(outputFile);
            Files.writeString(outputFile, GSON.toJson(jsonObject));
        } catch (IOException e) {
            this.logException(e);
        }
    }

    private void generateFilePayload() {
        for (Map.Entry<String, List<Definition>> entry : apiPayloadHolder.cdEnabledComponentsByGitHub().entrySet()) {
            String githubRepo = entry.getKey();
            String groupName = ArtifactoryAPI.toGeneratedGroupName(githubRepo);
            var groupsPath = apiOutputDir.resolve("groups");
            Path outputFile = groupsPath.resolve(groupName + ".json");
            if (!outputFile.normalize().startsWith(apiOutputDir.toAbsolutePath())) {
                throw new RuntimeException("Not allowed to navigate outside of the current folder");
            }
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("name", groupName);
            jsonObject.addProperty("description", "CD group with permissions to deploy from " + githubRepo);

            try {
                Files.createDirectories(groupsPath);
                Files.createFile(outputFile);
                Files.writeString(outputFile, GSON.toJson(jsonObject));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

        try {
            Files.writeString(apiOutputDir.resolve("github.index.json"), GSON.toJson(apiPayloadHolder.pathsByGithub()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            Files.writeString(apiOutputDir.resolve( "issues.index.json"), GSON.toJson(apiPayloadHolder.issueTrackersByPlugin()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            Files.writeString(apiOutputDir.resolve( "cd.index.json"), GSON.toJson(apiPayloadHolder.cdEnabledComponentsByGitHub().keySet().toArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            Files.writeString(apiOutputDir.resolve("maintainers.index.json"), GSON.toJson(apiPayloadHolder.maintainersByComponent()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, Object> createPrincipals(Definition definition) {
        var result = new HashMap<String, Object>();
        if (Arrays.asList(definition.getDevelopers()).isEmpty()) {
            result.put("users", Collections.emptyMap());
            result.put("groups", Collections.emptyMap());
            if (definition.getCd() != null && definition.getCd().enabled) {
                LOGGER.log(Level.INFO, "Skipping CD group definition for " + definition.getName() + " as there are no maintainers");
            }
        } else {
            if (definition.getCd() == null || !definition.getCd().exclusive) {
                result.put("users", Arrays.stream(definition.getDevelopers()).collect(Collectors.toMap(this::mapDeveloper, developer -> Arrays.asList("w", "n"))));
            } else {
                for (String developer : definition.getDevelopers()) {
                    if (!KnownUsers.existsInJira(developer) && !JiraAPI.getInstance().isUserPresent(developer)) {
                        reportChecksApiDetails(TEXT_LOGGED_IN_JIRA.formatted(developer), TEXT_LOGGED_IN_JIRA.formatted(developer));
                        throw new RuntimeException("User name not known to Jira: " + developer);
                    }
                }
                result.put("users", Collections.emptyMap());
            }
            if (definition.getCd() != null && definition.getCd().enabled) {
                result.put("groups", Collections.singletonMap(ArtifactoryAPI.toGeneratedGroupName(definition.getGithub()), Arrays.asList("w", "n")));
            } else {
                result.put("groups", Collections.emptyMap());
            }
        }
        return result;
    }

    private void logException(Throwable throwable) {
        LOGGER.log(Level.SEVERE, "Some exception has been thrown", throwable);
    }

    private String mapDeveloper(String developer) {
        if (!KnownUsers.existsInArtifactory(developer) && !KnownUsers.existsInJira(developer) && !JiraAPI.getInstance().isUserPresent(developer)) {
            this.reportChecksApiDetails(NEEDS_LOGGED_IN_JIRA_AND_ARTIFACTORY.formatted(developer), TEXT_LOGGED_IN_JIRA_AND_ARTIFACTORY.formatted(developer));
            throw new IllegalStateException("User name not known to Artifactory and Jira: " + developer);
        }
        if (!KnownUsers.existsInArtifactory(developer)) {
            this.reportChecksApiDetails(NEEDS_LOGGED_IN_ARTIFACTORY.formatted(developer), TEXT_LOGGED_IN_ARTIFACTORY.formatted(developer));
            throw new IllegalStateException("User name not known to Artifactory: " + developer);
        }
        if (!KnownUsers.existsInJira(developer)) {
            this.reportChecksApiDetails(NEEDS_LOGGED_IN_JIRA.formatted(developer), TEXT_LOGGED_IN_JIRA.formatted(developer));
            throw new IllegalStateException("User name not known to Jira: " + developer);
        }
        return developer.toLowerCase(Locale.US);
    }

    // TODO It's a really weird decision to have this in the otherwise invocation agnostic standalone tool
    private void reportChecksApiDetails(String errorMessage, String details) {
        try {
            Files.writeString(new File("checks-title.txt").toPath(), errorMessage);
            Files.writeString(new File("checks-details.txt").toPath(), details);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


}
