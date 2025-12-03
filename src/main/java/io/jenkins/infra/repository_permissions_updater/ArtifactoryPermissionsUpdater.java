package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jenkins.lib.support_log_formatter.SupportLogFormatter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;

public final class ArtifactoryPermissionsUpdater {

    /**
     * Directory containing the permissions definition files in YAML format
     */
    private static final File DEFINITIONS_DIR = new File(System.getProperty("definitionsDir", "./permissions"));

    /**
     * Temporary directory that this tool will write Artifactory API JSON payloads to. Must not exist prior to execution.
     */
    private static final File ARTIFACTORY_API_DIR = new File(System.getProperty("artifactoryApiTempDir", "./json"));

    /**
     * If enabled, will not send PUT/DELETE requests to Artifactory, only GET (i.e. not modifying).
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    /**
     * Set to true during development to prevent collisions with production behavior:
     *
     * - Different prefixes for groups and permission targets in {@link ArtifactoryAPI}.
     * - Different GitHub secret names in {@link #generateTokens(java.io.File)}
     * - Permissions are only granted for
     *
     */
    private static final boolean DEVELOPMENT = Boolean.getBoolean("development");

    /**
     * Loads all teams from the teams/ folder.
     * Always returns non null.
     */
    private static Map<String, Set<TeamDefinition>> loadTeams() throws IOException {
        Yaml yaml = new Yaml(new Constructor(TeamDefinition.class, new LoaderOptions()));
        File teamsDir = new File("teams/");

        Map<String, Set<TeamDefinition>> teams = new HashMap<>();

        for (File teamFile : Objects.requireNonNull(teamsDir.listFiles())) {
            try (InputStream is = Files.newInputStream(teamFile.toPath())) {
                TeamDefinition newTeam = yaml.loadAs(is, TeamDefinition.class);

                String expectedName = newTeam.getName() + ".yml";
                if (!teamFile.getName().equals(expectedName)) {
                    throw new IllegalArgumentException("team file should be named " + expectedName
                            + " instead of the current " + teamFile.getName());
                }

                teams.computeIfAbsent(newTeam.getName(), unused -> new HashSet<>())
                        .add(newTeam);
            } catch (YAMLException e) {
                throw new IOException("Failed to read " + teamFile.getName(), e);
            }
        }
        return teams;
    }

    /**
     * Checks if any developer has its name starting with `@`.
     * In which case, for `@some-team` it will replace it with the developers
     * listed for the team whose name equals `some-team` under the teams/ directory.
     */
    private static void expandTeams(Definition definition, Map<String, Set<TeamDefinition>> teamsByName) {
        Set<String> expandedDevelopers = new TreeSet<>();

        for (String developerName : definition.getDevelopers()) {
            if (developerName.startsWith("@")) {
                String teamName = developerName.substring(1);
                Set<TeamDefinition> teamDevs = teamsByName.get(teamName);
                if (teamDevs == null) {
                    throw new IllegalArgumentException("Team " + teamName + " not found!");
                }
                if (teamDevs.isEmpty()) {
                    throw new IllegalArgumentException("Team " + teamName + " is empty?!");
                }
                LOGGER.log(Level.INFO, "[{0}]: replacing {1} with {2}", new Object[] {
                    definition.getName(),
                    developerName,
                    teamDevs.stream()
                            .map(TeamDefinition::getDevelopers)
                            .flatMap(Stream::of)
                            .collect(Collectors.toList())
                });
                teamDevs.forEach(t -> expandedDevelopers.addAll(List.of(t.getDevelopers())));
            } else {
                expandedDevelopers.add(developerName);
            }
        }
        definition.setDevelopers(expandedDevelopers.toArray(new String[0]));
    }

    /**
     * Take the YAML permission definitions and convert them to Artifactory permissions API payloads.
     */
    private static void generateApiPayloads(File yamlSourceDirectory, File apiOutputDir) throws IOException {

        if (!yamlSourceDirectory.exists()) {
            throw new IOException("Directory " + DEFINITIONS_DIR + " does not exist");
        }
        if (apiOutputDir.exists()) {
            throw new IOException(apiOutputDir.getPath() + " already exists");
        }
        doGenerateApiPayloads(yamlSourceDirectory, apiOutputDir, ArtifactoryAPI.getInstance());
    }

    @SuppressFBWarnings(
            value = {"NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", "VA_FORMAT_STRING_USES_NEWLINE"},
            justification = "TODO needs triage")
    protected static void doGenerateApiPayloads(
            File yamlSourceDirectory, File apiOutputDir, ArtifactoryAPI artifactoryAPI) throws IOException {
        Yaml yaml = new Yaml(new Constructor(Definition.class, new LoaderOptions()));
        Map<String, Set<TeamDefinition>> teamsByName = loadTeams();

        Map<String, Set<String>> pathsByGithub = new TreeMap<>();
        Map<String, List<Map<String, String>>> issueTrackersByPlugin = new TreeMap<>();
        Map<String, List<Definition>> cdEnabledComponentsByGitHub = new TreeMap<>();
        Map<String, List<String>> maintainersByComponent = new HashMap<>();

        Gson gson = new GsonBuilder().disableHtmlEscaping().create();

        for (File file : Objects.requireNonNull(yamlSourceDirectory.listFiles())) {
            if (!file.getName().endsWith(".yml")) {
                throw new IOException("Unexpected file: `" + file.getName() + "`. YAML files must end with `.yml`");
            }

            Definition definition;

            try (InputStream is = Files.newInputStream(file.toPath())) {
                definition = yaml.loadAs(is, Definition.class);

                expandTeams(definition, teamsByName);

            } catch (Exception e) {
                throw new IOException("Failed to read " + file.getName(), e);
            }

            if (definition.getGithub() != null) {
                if (!definition.isReleaseBlocked()) {
                    Set<String> paths =
                            pathsByGithub.computeIfAbsent(definition.getGithub(), unused -> new TreeSet<>());
                    paths.addAll(List.of(definition.getPaths()));
                }
                if (definition.getCd() != null && definition.getCd().enabled) {
                    if (!definition.getGithub().matches("(jenkinsci|jenkins-infra)/.+")) {
                        throw new IllegalArgumentException(
                                "CD is only supported when the GitHub repository is in @jenkinsci");
                    }
                    if (definition.getDevelopers().length > 0) {
                        List<Definition> definitions = cdEnabledComponentsByGitHub.get(definition.getGithub());
                        if (definitions == null || definitions.isEmpty()) {
                            definitions = new ArrayList<>();
                            cdEnabledComponentsByGitHub.put(definition.getGithub(), definitions);
                        }
                        LOGGER.log(
                                Level.INFO,
                                "CD-enabled component '" + definition.getName() + "' in repository '"
                                        + definition.getGithub() + "'");
                        definitions.add(definition);
                    } else {
                        LOGGER.log(
                                Level.INFO,
                                "Skipping CD-enablement for '" + definition.getName() + "' in repository '"
                                        + definition.getGithub() + "' as it is unmaintained");
                    }
                }
            } else {
                if (definition.getCd() != null && definition.getCd().enabled) {
                    throw new IllegalArgumentException(
                            "Cannot have CD ('cd') enabled without specifying GitHub repository ('github'), for component: "
                                    + definition.getName());
                }
            }

            if (definition.getIssues() != null && definition.getIssues().length > 0) {
                if (definition.getGithub() != null) {
                    List<String> names = new ArrayList<>(List.of(definition.getExtraNames()));
                    names.add(definition.getName());
                    for (String name : names) {
                        List<Map<String, String>> trackers = Stream.of(definition.getIssues())
                                .filter(t -> t.isJira() || t.isGitHubIssues())
                                .map(t -> {
                                    Map<String, String> m = new LinkedHashMap<>();
                                    m.put("type", t.getType());
                                    m.put("reference", t.getReference());
                                    if (t.getViewUrl() != null) {
                                        m.put("viewUrl", t.getViewUrl());
                                    }
                                    String reportUrl = t.getReportUrl(name);
                                    if (reportUrl != null) {
                                        m.put("reportUrl", reportUrl);
                                    }
                                    return m;
                                })
                                .collect(Collectors.toList());
                        issueTrackersByPlugin.put(name, trackers);
                    }
                } else {
                    throw new IllegalArgumentException(
                            "Issue trackers ('issues') support requires GitHub repository ('github')");
                }
            }

            String artifactId = definition.getName();
            for (String path : definition.getPaths()) {
                String lastPathElement = path.substring(path.lastIndexOf("/") + 1);
                if (!lastPathElement.equals(artifactId) && !lastPathElement.contains("*")) {
                    // We could throw an exception here, but we actively abuse this for unusually structured components
                    LOGGER.log(Level.WARNING, "Unexpected path: " + path + " for artifact ID: " + artifactId);
                }

                String groupId = path.substring(0, path.lastIndexOf("/")).replace("/", ".");
                if (lastPathElement.contains("*")) {
                    for (String name : definition.getExtraNames()) {
                        addMaintainers(maintainersByComponent, groupId + ":" + name, definition);
                    }

                } else {
                    addMaintainers(maintainersByComponent, groupId + ":" + artifactId, definition);
                }
            }

            String jsonName = artifactoryAPI.toGeneratedPermissionTargetName(
                    file.getName().replaceAll("\\.ya?ml$", ""));

            JsonObject perm = new JsonObject();
            perm.addProperty("name", jsonName);

            // includes / excludes
            if (definition.isReleaseBlocked()) {
                perm.addProperty("includesPattern", "blocked");
            } else {
                List<String> patterns = new ArrayList<>();
                for (String path : definition.getPaths()) {
                    patterns.add(path + "/*/" + definition.getName() + "-*");
                    patterns.add(path + "/*/maven-metadata.xml");
                    patterns.add(path + "/*/maven-metadata.xml.*");
                    patterns.add(path + "/maven-metadata.xml");
                    patterns.add(path + "/maven-metadata.xml.*");
                }
                perm.addProperty("includesPattern", String.join(",", patterns));
            }
            perm.addProperty("excludesPattern", "");

            JsonArray repos = new JsonArray();
            repos.add("snapshots");
            if (!DEVELOPMENT) {
                repos.add("releases");
            }
            perm.add("repositories", repos);

            JsonObject usersJson = new JsonObject();
            JsonObject groupsJson = new JsonObject();

            if (definition.getDevelopers().length == 0) {
                if (definition.getCd() != null && definition.getCd().enabled) {
                    LOGGER.log(
                            Level.INFO,
                            "Skipping CD group definition for " + definition.getName()
                                    + " as there are no maintainers");
                }
            } else {
                if (definition.getCd() == null || !definition.getCd().exclusive) {
                    for (String dev : definition.getDevelopers()) {
                        boolean inArtifactory = KnownUsers.existsInArtifactory(dev);
                        boolean inJira = KnownUsers.existsInJira(dev);

                        if (!inArtifactory && !inJira) {
                            reportChecksApiDetails(dev + " needs to log in to Artifactory and Jira", """
                                    %s needs to log in to [Artifactory](https://repo.jenkins-ci.org/) and [Jira](https://issues.jenkins.io/).

                                    We resync our Artifactory and Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.
                                    The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

                                    Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
                                    """.formatted(dev)
                                    .stripIndent());
                            throw new IllegalStateException("User name not known to Artifactory and Jira: " + dev);
                        }
                        if (!inArtifactory) {
                            reportChecksApiDetails(dev + " needs to log in to Artifactory", """
                                    %s needs to log in to [Artifactory](https://repo.jenkins-ci.org/).

                                    We resync our Artifactory user list every 2 hours, so you will need to wait some time before rebuilding your pull request.
                                    The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

                                    Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
                                    """.formatted(dev)
                                    .stripIndent());
                            throw new IllegalStateException("User name not known to Artifactory: " + dev);
                        }
                        if (!inJira) {
                            reportChecksApiDetails(dev + " needs to log in to Jira", """
                                    %s needs to log in to [Jira](https://issues.jenkins.io/)

                                    We resync our Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.
                                    The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

                                    Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
                                    """.formatted(dev)
                                    .stripIndent());
                            throw new IllegalStateException("User name not known to Jira: " + dev);
                        }

                        JsonArray rights = new JsonArray();
                        rights.add("w");
                        rights.add("n");
                        usersJson.add(dev.toLowerCase(Locale.US), rights);
                    }
                } else {
                    for (String dev : definition.getDevelopers()) {
                        if (!KnownUsers.existsInJira(dev)) {
                            reportChecksApiDetails(dev + " needs to log in to Jira", """
                                    %s needs to log in to [Jira](https://issues.jenkins.io/)

                                    We resync our Jira user list every 2 hours, so you will need to wait some time before rebuilding your pull request.
                                    The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

                                    Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
                                    """.formatted(dev)
                                    .stripIndent());
                            throw new IllegalStateException("User name not known to Jira: " + dev);
                        }
                    }
                }
            }

            if (definition.getCd() != null && definition.getCd().enabled && definition.getDevelopers().length != 0) {
                JsonArray rights = new JsonArray();
                rights.add("w");
                rights.add("n");
                groupsJson.add(artifactoryAPI.toGeneratedGroupName(definition.getGithub()), rights);
            }

            JsonObject principals = new JsonObject();
            principals.add("users", usersJson);
            principals.add("groups", groupsJson);
            perm.add("principals", principals);

            Path permFile = apiOutputDir.toPath().resolve("permissions").resolve(jsonName + ".json");
            Files.createDirectories(permFile.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(permFile);
                    JsonWriter jw = gson.newJsonWriter(bw)) {
                jw.setIndent("    ");
                gson.toJson(perm, perm.getClass(), jw);
            }
        }

        for (String githubRepo : cdEnabledComponentsByGitHub.keySet()) {
            String groupName = artifactoryAPI.toGeneratedGroupName(githubRepo);
            JsonObject group = new JsonObject();
            group.addProperty("name", groupName);
            group.addProperty("description", "CD group with permissions to deploy from " + githubRepo);

            Path groupFile = apiOutputDir.toPath().resolve("groups").resolve(groupName + ".json");
            Files.createDirectories(groupFile.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(groupFile);
                    JsonWriter jw = gson.newJsonWriter(bw)) {
                jw.setIndent("    ");
                gson.toJson(group, group.getClass(), jw);
            }
        }

        writePrettyJson(apiOutputDir.toPath().resolve("github.index.json"), pathsByGithub, gson);
        writePrettyJson(apiOutputDir.toPath().resolve("issues.index.json"), issueTrackersByPlugin, gson);
        writePrettyJson(
                apiOutputDir.toPath().resolve("cd.index.json"),
                new ArrayList<>(cdEnabledComponentsByGitHub.keySet()),
                gson);
        writePrettyJson(apiOutputDir.toPath().resolve("maintainers.index.json"), maintainersByComponent, gson);
    }

    private static void writePrettyJson(Path target, Object content, Gson gson) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent()));
        try (BufferedWriter bw = Files.newBufferedWriter(target);
                JsonWriter jw = gson.newJsonWriter(bw)) {
            jw.setIndent("    ");
            gson.toJson(content, content.getClass(), jw);
        }
    }

    // TODO It's a really weird decision to have this in the otherwise invocation agnostic standalone tool
    private static void reportChecksApiDetails(String errorMessage, String details) throws IOException {
        Files.writeString(
                Path.of("checks-title.txt"),
                errorMessage,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(
                Path.of("checks-details.txt"),
                details,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void addMaintainers(
            Map<String, List<String>> maintainersByComponent, String key, Definition definition) {
        if (maintainersByComponent.containsKey(key)) {
            LOGGER.log(Level.WARNING, "Duplicate maintainers entry for component: {0}", key);
        }
        // A potential issue with this implementation is that groupId changes will result in lack of maintainer
        // information for the old groupId.
        // In practice this will probably not be a problem when path changes here and subsequent release are close
        // enough in time.
        // Alternatively, always keep the old groupId around for a while.
        maintainersByComponent.computeIfAbsent(key, unused -> List.of(definition.getDevelopers()));
    }

    /**
     * Takes a directory with Artifactory API payloads and submits them to the appropriate Artifactory API,
     * creating/updating the specified objects identified through the file name.
     *
     * @param payloadsDir the directory containing the payload file for the objects matching the file name without .json extension
     * @param kind the kind of object to create (used for log messages only)
     * @param creator the closure called to create or update an object. Takes two arguments, the {@code String} name and {@code File} payload file.
     */
    private static void submitArtifactoryObjects(File payloadsDir, String kind, BiConsumer<String, File> creator) {
        LOGGER.log(Level.INFO, "Submitting {0}s...", kind);
        if (!payloadsDir.exists() || !payloadsDir.isDirectory()) {
            LOGGER.log(Level.INFO, "{0} does not exist or is not a directory, skipping {1} submission", new Object[] {
                payloadsDir, kind
            });
            return;
        }
        for (File file : Objects.requireNonNull(payloadsDir.listFiles((d, n) -> n.endsWith(".json")))) {
            String name = file.getName().replace(".json", "");
            try {
                creator.accept(name, file);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to create/replace {0} {1}", new Object[] {kind, name, ex});
            }
        }
        LOGGER.log(Level.INFO, "Done submitting {0}s", kind);
    }

    /**
     * Compares the list of (generated) objects returned from Artifactory using the specified {@code lister} with the
     * list of JSON payload files in the specified directory, and deletes all objects using {@code deleter} that match
     * and that have no corresponding payload file.
     *
     * @param payloadsDir the directory containing payload files whose file names correspond to object names (.json extension is ignored)
     * @param kind the kind of object to remove (used for log messages only)
     * @param lister no-argument closure returning a list of {@code String} names of objects
     * @param deleter removes the specified object identified through the single {@code String} argument
     */
    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "intentional")
    private static void removeExtraArtifactoryObjects(
            File payloadsDir, String kind, Supplier<List<String>> lister, Consumer<String> deleter) {
        if (!payloadsDir.exists() || !payloadsDir.isDirectory()) {
            // TODO this will not remove objects if there would not be any left
            LOGGER.log(
                    Level.INFO,
                    "{0} does not exist or is not a directory, skipping extra {1}s removal",
                    new Object[] {payloadsDir, kind});
            return;
        }
        LOGGER.log(Level.INFO, "Removing extra {0}s from Artifactory...", kind);
        List<String> objects;
        try {
            objects = lister.get();
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed listing {0}s from Artifactory", new Object[] {kind, ex});
            return;
        }
        if (objects != null && !objects.isEmpty()) {
            LOGGER.log(Level.INFO, "Discovered {0} {1}s", new Object[] {objects.size(), kind});
            for (String object : objects) {
                if (!new File(payloadsDir, object + ".json").exists()) {
                    LOGGER.log(
                            Level.INFO, "{0} {1} has no corresponding file, deleting...", new Object[] {kind, object});
                    try {
                        deleter.accept(object);
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Failed to delete {0} {1} from Artifactory", new Object[] {
                            kind, object, ex
                        });
                    }
                }
            }
        }
        LOGGER.log(Level.INFO, "Done removing extra {0}s from Artifactory", kind);
    }

    /**
     * Generates Artifactory access tokens for the Artifactory groups corresponding to the GitHub repo names, and then
     * attaches the token username and password to the GitHub repo as a secret.
     *
     * @param githubReposForCdIndex JSON file containing a list of GitHub repo names in the format 'orgname/reponame'
     */
    private static void generateTokens(File githubReposForCdIndex) throws IOException {
        JsonArray repos;
        try (BufferedReader br = Files.newBufferedReader(githubReposForCdIndex.toPath())) {
            repos = new Gson().fromJson(br, JsonArray.class);
        }

        for (JsonElement element : repos) {
            String repo = element.getAsString();
            LOGGER.log(Level.INFO, "Processing repository {0} for CD", repo);

            String username = ArtifactoryAPI.toTokenUsername(repo);
            String groupName = ArtifactoryAPI.toGeneratedGroupName(repo);
            long validFor = TimeUnit.MINUTES.toSeconds(Integer.getInteger("artifactoryTokenMinutesValid", 240));

            String token;
            try {
                if (DRY_RUN_MODE) {
                    LOGGER.log(
                            Level.INFO,
                            "Skipped creation of token for GitHub repo: ''{0}'', Artifactory user: ''{1}'', group name: ''{2}'', valid for {3} seconds",
                            new Object[] {repo, username, groupName, validFor});
                    continue;
                }
                token = ArtifactoryAPI.getInstance().generateTokenForGroup(username, groupName, validFor);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to generate token for {0}", new Object[] {repo, ex});
                continue;
            }

            GitHubAPI.GitHubPublicKey publicKey = GitHubAPI.getInstance().getRepositoryPublicKey(repo);
            if (publicKey == null) {
                LOGGER.log(Level.WARNING, "Failed to retrieve public key for {0}", repo);
                continue;
            }
            LOGGER.log(Level.INFO, "Public key of {0} is {1}", new Object[] {repo, publicKey});

            String encryptedUsername = CryptoUtil.encryptSecret(username, publicKey.getKey());
            String encryptedToken = CryptoUtil.encryptSecret(token, publicKey.getKey());
            LOGGER.log(Level.INFO, "Encrypted secrets are username:{0}; token:{1}", new Object[] {
                encryptedUsername, encryptedToken
            });

            GitHubAPI.getInstance()
                    .createOrUpdateRepositorySecret(
                            System.getProperty("gitHubSecretNamePrefix", DEVELOPMENT ? "DEV_MAVEN_" : "MAVEN_")
                                    + "USERNAME",
                            encryptedUsername,
                            repo,
                            publicKey.getKeyId());
            GitHubAPI.getInstance()
                    .createOrUpdateRepositorySecret(
                            System.getProperty("gitHubSecretNamePrefix", DEVELOPMENT ? "DEV_MAVEN_" : "MAVEN_")
                                    + "TOKEN",
                            encryptedToken,
                            repo,
                            publicKey.getKeyId());
        }
    }

    public static void syncPermissions() throws IOException {
        for (Handler h : Logger.getLogger("").getHandlers()) {
            if (h instanceof ConsoleHandler) {
                ((ConsoleHandler) h).setFormatter(new SupportLogFormatter());
            }
        }

        if (DRY_RUN_MODE) {
            LOGGER.log(Level.INFO, "Running in dry run mode");
        }
        ArtifactoryAPI artifactory = ArtifactoryAPI.getInstance();

        /*
         * Generate JSON payloads from YAML permission definition files in DEFINITIONS_DIR and writes them to ARTIFACTORY_API_DIR.
         * Any problems with the input here are fatal so PR builds fails.
         */
        generateApiPayloads(DEFINITIONS_DIR, ARTIFACTORY_API_DIR);
        /*
         * Submit generated Artifactory group JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        File groupsJsonDir = new File(ARTIFACTORY_API_DIR, "groups");
        submitArtifactoryObjects(groupsJsonDir, "group", artifactory::createOrReplaceGroup);
        removeExtraArtifactoryObjects(
                groupsJsonDir, "group", artifactory::listGeneratedGroups, artifactory::deleteGroup);
        /*
         * Submit generated Artifactory permission target JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        File permissionTargetsJsonDir = new File(ARTIFACTORY_API_DIR, "permissions");
        submitArtifactoryObjects(
                permissionTargetsJsonDir, "permission target", artifactory::createOrReplacePermissionTarget);
        removeExtraArtifactoryObjects(
                permissionTargetsJsonDir,
                "permission target",
                artifactory::listGeneratedPermissionTargets,
                artifactory::deletePermissionTarget);

        /*
         * For all CD-enabled GitHub repositories, obtain a token from Artifactory and attach it to a GH repo as secret.
         */
        generateTokens(new File(ARTIFACTORY_API_DIR, "cd.index.json"));
    }

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.getName());
}
