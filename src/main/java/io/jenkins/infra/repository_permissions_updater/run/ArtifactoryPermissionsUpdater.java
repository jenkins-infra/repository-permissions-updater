package io.jenkins.infra.repository_permissions_updater.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.jenkins.infra.repository_permissions_updater.ArtifactoryAPI;
import io.jenkins.infra.repository_permissions_updater.CryptoUtil;
import io.jenkins.infra.repository_permissions_updater.Definition;
import io.jenkins.infra.repository_permissions_updater.GitHubAPI;
import io.jenkins.infra.repository_permissions_updater.KnownUsers;
import io.jenkins.infra.repository_permissions_updater.TeamDefinition;
import io.jenkins.lib.support_log_formatter.SupportLogFormatter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

public class ArtifactoryPermissionsUpdater {

    /**
     * If enabled, will not send PUT/DELETE requests to Artifactory, only GET (i.e. not modifying).
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    /**
     * Directory containing the permissions definition files in YAML format
     */
    private static final File DEFINITIONS_DIR = new File(System.getProperty("definitionsDir", "./permissions"));

    /**
     * Temporary directory that this tool will write Artifactory API JSON payloads to. Must not exist prior to execution.
     */
    private static final File ARTIFACTORY_API_DIR = new File(System.getProperty("artifactoryApiTempDir", "./json"));

    /**
     * Set to true during development to prevent collisions with production behavior:
     *
     * - Different prefixes for groups and permission targets in {@link ArtifactoryAPI}.
     * - Different GitHub secret names in {@link #generateTokens(java.io.File)}
     * - Permissions are only granted for
     *
     */
    private static final boolean DEVELOPMENT = Boolean.getBoolean("development");

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.getName());
    public static final String NEW_TEAM_FILE_NAME = "%s.yml";

    public static void main(String[] args) throws Exception {
        for (Handler h : Logger.getLogger("").getHandlers()) {
            if (h instanceof ConsoleHandler) {
                h.setFormatter(new SupportLogFormatter());
            }
        }

        if (DRY_RUN_MODE) {
            LOGGER.log(Level.INFO, "Running in dry run mode");
        }

        var artifactoryApi = ArtifactoryAPI.getInstance();

        /*
         * Generate JSON payloads from YAML permission definition files in DEFINITIONS_DIR and writes them to ARTIFACTORY_API_DIR.
         * Any problems with the input here are fatal so PR builds fails.
         */
        ArtifactoryPermissionsUpdater.generateApiPayloads(DEFINITIONS_DIR, ARTIFACTORY_API_DIR);
        /*
         * Submit generated Artifactory group JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        var groupsJsonDir = new File(ARTIFACTORY_API_DIR, "groups");
        submitArtifactoryObjects(groupsJsonDir, "group", artifactoryApi::createOrReplaceGroup);
        removeExtraArtifactoryObjects(
                groupsJsonDir, "group", artifactoryApi::listGeneratedGroups, artifactoryApi::deleteGroup);
        /*
         * Submit generated Artifactory permission target JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        var permissionTargetsJsonDir = new File(ARTIFACTORY_API_DIR, "permissions");
        submitArtifactoryObjects(
                permissionTargetsJsonDir, "permission target", artifactoryApi::createOrReplacePermissionTarget);
        removeExtraArtifactoryObjects(
                permissionTargetsJsonDir,
                "permission target",
                artifactoryApi::listGeneratedPermissionTargets,
                artifactoryApi::deletePermissionTarget);
        /*
         * For all CD-enabled GitHub repositories, obtain a token from Artifactory and attach it to a GH repo as secret.
         */
        generateTokens(new File(ARTIFACTORY_API_DIR, "cd.index.json"));
    }

    /**
     * Generates Artifactory access tokens for the Artifactory groups corresponding to the GitHub repo names, and then
     * attaches the token username and password to the GitHub repo as a secret.
     *
     * @param githubReposForCdIndex JSON file containing a list of GitHub repo names in the format 'orgname/reponame'
     */
    private static void generateTokens(File githubReposForCdIndex) throws IOException {
        List<String> repos = new Gson()
                .fromJson(Files.readString(githubReposForCdIndex.toPath(), StandardCharsets.UTF_8), List.class);
        for (var repo : repos) {
            LOGGER.log(Level.INFO, "Processing repository %s for CD".formatted(repo));
            var username = ArtifactoryAPI.toTokenUsername(repo);
            var groupName = ArtifactoryAPI.getInstance().toGeneratedGroupName(repo);
            var validFor = TimeUnit.MINUTES.toSeconds(Integer.getInteger("artifactoryTokenMinutesValid", 240));
            String token;
            try {
                if (DRY_RUN_MODE) {
                    LOGGER.log(
                            Level.INFO,
                            "Skipped creation of token for GitHub repo: '%s', Artifactory user: '%s', group name: '%s', valid for %d seconds"
                                    .formatted(repo, username, groupName, validFor));
                    continue;
                }
                token = ArtifactoryAPI.getInstance().generateTokenForGroup(username, groupName, validFor);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to generate token for %s".formatted(repo), ex);
                continue;
            }

            var publicKey = GitHubAPI.getInstance().getRepositoryPublicKey(repo);
            if (publicKey == null) {
                LOGGER.log(Level.WARNING, "Failed to retrieve public key for %s".formatted(repo));
                continue;
            }
            LOGGER.log(Level.INFO, "Public key of %s is %s".formatted(repo, publicKey));

            var encryptedUsername = CryptoUtil.encryptSecret(username, publicKey.getKey());
            var encryptedToken = CryptoUtil.encryptSecret(token, publicKey.getKey());
            LOGGER.log(
                    Level.INFO,
                    "Encrypted secrets are username:%s; token:%s".formatted(encryptedUsername, encryptedToken));

            var secretPrefix = System.getProperty("gitHubSecretNamePrefix", DEVELOPMENT ? "DEV_MAVEN_" : "MAVEN_");
            GitHubAPI.getInstance()
                    .createOrUpdateRepositorySecret(
                            secretPrefix + "USERNAME", encryptedUsername, repo, publicKey.getKeyId());
            GitHubAPI.getInstance()
                    .createOrUpdateRepositorySecret(secretPrefix + "TOKEN", encryptedToken, repo, publicKey.getKeyId());
        }
    }

    /**
     * Take the YAML permission definitions and convert them to Artifactory permissions API payloads.
     */
    private static void generateApiPayloads(File yamlSourceDirectory, File apiOutputDir) throws Exception {

        if (!yamlSourceDirectory.exists()) {
            throw new IOException("Directory %s does not exist".formatted(DEFINITIONS_DIR));
        }

        if (apiOutputDir.exists()) {
            throw new IOException(apiOutputDir.getPath() + " already exists");
        }
        doGenerateApiPayloads(yamlSourceDirectory, apiOutputDir, ArtifactoryAPI.getInstance());
    }

    static void doGenerateApiPayloads(File yamlSourceDirectory, File apiOutputDir, ArtifactoryAPI artifactoryAPI)
            throws Exception {
        Yaml yaml = new Yaml(new Constructor(Definition.class, new LoaderOptions()));
        Map<String, Set<TeamDefinition>> teamsByName = loadTeams();

        Map<String, Set<String>> pathsByGithub = new TreeMap<>();
        Map<String, List> issueTrackersByPlugin = new TreeMap<>();
        Map<String, List<Definition>> cdEnabledComponentsByGitHub = new TreeMap<>();
        Map<String, List<String>> maintainersByComponent = new HashMap<>();
        var files = yamlSourceDirectory.listFiles();
        if (files == null || files.length == 0) {
            throw new IOException("No YAML files found in " + yamlSourceDirectory.getAbsolutePath());
        }
        for (File file : files) {
            if (!file.getName().endsWith(".yml")) {
                throw new IOException(
                        "Unexpected file: `%s`. YAML files must end with `.yml`".formatted(file.getName()));
            }

            Definition definition;

            try {
                definition =
                        yaml.loadAs(Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8), Definition.class);

                enrichTeams(definition, teamsByName);

            } catch (Exception e) {
                throw new IOException("Failed to read ${file.name}", e);
            }

            if (definition.getGithub() != null && !definition.getGithub().isEmpty()) {
                if (!definition.isReleaseBlocked()) {
                    Set<String> paths = pathsByGithub.computeIfAbsent(definition.getGithub(), s -> new HashSet<>());
                    paths.addAll(Arrays.asList(definition.getPaths()));
                }
                if (definition.getCd() != null && definition.getCd().enabled) {
                    if (!definition.getGithub().matches("(jenkinsci|jenkins-infra)/.+")) {
                        throw new Exception("CD is only supported when the GitHub repository is in @jenkinsci");
                    }
                    if (definition.getDevelopers().length > 0) {
                        List<Definition> definitions =
                                cdEnabledComponentsByGitHub.getOrDefault(definition.getGithub(), new ArrayList<>());
                        if (definitions == null) {
                            definitions = new ArrayList<>();
                            cdEnabledComponentsByGitHub.put(definition.getGithub(), definitions);
                        }
                        LOGGER.log(
                                Level.INFO,
                                "CD-enabled component '%s' in repository '%s'"
                                        .formatted(definition.getName(), definition.getGithub()));
                        definitions.add(definition);
                    } else {
                        LOGGER.log(
                                Level.INFO,
                                "Skipping CD-enablement for '%s' in repository '%s' as it is unmaintained"
                                        .formatted(definition.getName(), definition.getGithub()));
                    }
                }
            } else {
                if (definition.getGithub() != null && definition.getCd().enabled) {
                    throw new Exception(
                            "Cannot have CD ('cd') enabled without specifying GitHub repository ('github'), for component: "
                                    + definition.getName());
                }
            }

            if (definition.getIssues() != null) {
                if (definition.getGithub() != null && !definition.getGithub().isEmpty()) {
                    ArrayList<String> names = new ArrayList<>(Arrays.asList(definition.getExtraNames()));
                    names.add(definition.getName());
                    for (String name : names) {
                        issueTrackersByPlugin.put(
                                name,
                                Arrays.stream(definition.getIssues())
                                        .filter(tracker -> tracker.isJira() || tracker.isGitHubIssues())
                                        .map(tracker -> {
                                            var ret = new HashMap<String, Object>();
                                            ret.put("type", tracker.getType());
                                            ret.put("reference", tracker.getReference());
                                            var viewUrl = tracker.getViewUrl();
                                            if (viewUrl != null) {
                                                ret.put("viewUrl", viewUrl);
                                            }
                                            var reportUrl = tracker.getReportUrl(name);
                                            if (reportUrl != null) {
                                                ret.put("reportUrl", reportUrl);
                                            }
                                            return ret;
                                        })
                                        .collect(Collectors.toList()));
                    }
                } else {
                    throw new Exception("Issue trackers ('issues') support requires GitHub repository ('github')");
                }
            }

            String artifactId = definition.getName();
            for (String path : definition.getPaths()) {
                String lastPathElement = path.substring(path.lastIndexOf('/') + 1);
                if (!lastPathElement.equals(artifactId) && !lastPathElement.contains("*")) {
                    // We could throw an exception here, but we actively abuse this for unusually structured components
                    LOGGER.log(Level.WARNING, "Unexpected path: " + path + " for artifact ID: " + artifactId);
                }
                String groupId = path.substring(0, path.lastIndexOf('/')).replace('/', '.');
                if (lastPathElement.contains("*")) {
                    for (String name : definition.getExtraNames()) {
                        addMaintainers(maintainersByComponent, groupId + ":" + name, definition);
                    }
                } else {
                    addMaintainers(maintainersByComponent, groupId + ":" + artifactId, definition);
                }
            }

            String fileBaseName = file.getName().replaceAll("\\.ya?ml$", "");

            String jsonName = artifactoryAPI.toGeneratedPermissionTargetName(fileBaseName);
            Path apiOutputDirPath = apiOutputDir.toPath();
            Path permissions = apiOutputDirPath.resolve("permissions");
            if (!Files.exists(permissions)) {
                Files.createDirectories(permissions);
            }
            Path outputFilePath = permissions.resolve("%s.json".formatted(jsonName));
            PermissionJson permission = new PermissionJson(
                    jsonName,
                    definition.isReleaseBlocked()
                            ? "blocked"
                            : Arrays.stream(definition.getPaths())
                                    .flatMap(path -> Stream.of(
                                            path + "/*/" + definition.getName() + "-*",
                                            path + "/*/maven-metadata.xml",
                                            path + "/*/maven-metadata.xml.*",
                                            path + "/maven-metadata.xml",
                                            path + "/maven-metadata.xml.*"))
                                    .collect(Collectors.joining(",")),
                    "",
                    DEVELOPMENT ? List.of("snapshots") : List.of("snapshots", "releases"),
                    buildPrincipals(definition, artifactoryAPI));

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String pretty = gson.toJson(permission);

            Files.writeString(outputFilePath, pretty, StandardCharsets.UTF_8);
        }

        cdEnabledComponentsByGitHub.forEach((githubRepo, components) -> {
            String groupName = artifactoryAPI.toGeneratedGroupName(githubRepo);
            Path apiOutputDirPath = apiOutputDir.toPath();
            Path groups = apiOutputDirPath.resolve("groups");
            if (!Files.exists(groups)) {
                try {
                    Files.createDirectories(groups);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to create directory for groups: " + groups, e);
                }
            }
            Path outputFilePath = groups.resolve("%s.json".formatted(groupName));
            record Group(String name, String description) {}
            Group group = new Group(groupName, "CD group with permissions to deploy from " + githubRepo);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try {
                gson.toJson(group, Files.newBufferedWriter(outputFilePath, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write group JSON to " + outputFilePath, e);
            }
        });

        var githubIndex = new GsonBuilder().setPrettyPrinting().create().toJson(pathsByGithub);
        Files.writeString(new File(apiOutputDir, "github.index.json").toPath(), githubIndex, StandardCharsets.UTF_8);

        var issuesIndex = new GsonBuilder().setPrettyPrinting().create().toJson(issueTrackersByPlugin);
        Files.writeString(new File(apiOutputDir, "issues.index.json").toPath(), issuesIndex, StandardCharsets.UTF_8);

        var cdRepos = new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(new ArrayList<>(cdEnabledComponentsByGitHub.keySet()));
        Files.writeString(new File(apiOutputDir, "cd.index.json").toPath(), cdRepos, StandardCharsets.UTF_8);

        var maintainers = new GsonBuilder().setPrettyPrinting().create().toJson(maintainersByComponent);
        Files.writeString(
                new File(apiOutputDir, "maintainers.index.json").toPath(), maintainers, StandardCharsets.UTF_8);
    }

    /**
     * Loads all teams from the teams/ folder.
     * Always returns non null.
     */
    private static Map<String, Set<TeamDefinition>> loadTeams() throws IOException {
        Yaml yaml = new Yaml(new Constructor(TeamDefinition.class, new LoaderOptions()));
        File teamsDir = new File("teams/");
        if (!teamsDir.exists()) {
            throw new FileNotFoundException("Teams directory does not exist: " + teamsDir.getAbsolutePath());
        }

        Set<TeamDefinition> teams = new HashSet<>();

        if (!teamsDir.isDirectory()) {
            throw new IOException("Teams directory is not a directory: " + teamsDir.getAbsolutePath());
        }
        File[] teamFiles = teamsDir.listFiles();
        if (teamFiles == null || teamFiles.length == 0) {
            throw new IOException("Teams directory is empty: " + teamsDir.getAbsolutePath());
        }
        for (File teamFile : teamFiles) {
            try {
                TeamDefinition newTeam = yaml.loadAs(
                        Files.newBufferedReader(teamFile.toPath(), StandardCharsets.UTF_8), TeamDefinition.class);
                teams.add(newTeam);

                String expectedName = NEW_TEAM_FILE_NAME.formatted(newTeam.getName());
                if (!teamFile.getName().equals(expectedName)) {
                    throw new Exception("team file should be named %s instead of the current %s"
                            .formatted(expectedName, teamFile.getName()));
                }
            } catch (Exception e) {
                throw new IOException("Failed to read %s".formatted(teamFile.getName()), e);
            }
        }
        return teams.stream()
                .collect(Collectors.groupingBy(
                        TeamDefinition::getName, Collectors.mapping(team -> team, Collectors.toSet())));
    }

    /**
     * Checks if any developer has its name starting with `@`.
     * In which case, for `@some-team` it will replace it with the developers
     * listed for the team whose name equals `some-team` under the teams/ directory.
     */
    private static void enrichTeams(Definition definition, Map<String, Set<TeamDefinition>> teamsByName)
            throws Exception {
        Set<String> developers = new HashSet<>(Arrays.asList(definition.getDevelopers()));
        Set<String> enrichedDevelopers = new HashSet<>();

        for (String developerName : developers) {
            if (developerName.startsWith("@")) {
                String teamName = developerName.substring(1);
                Set<String> teamDevs = teamsByName.get(teamName).stream()
                        .flatMap(ArtifactoryPermissionsUpdater::teamDefinitionToDevelopers)
                        .collect(Collectors.toSet());
                if (teamDevs.isEmpty()) {
                    throw new Exception("Team %s is empty?!".formatted(teamName));
                }
                LOGGER.log(
                        Level.INFO, "[%s]: replacing %s with %s".formatted(definition.getName(), teamName, teamDevs));
                enrichedDevelopers.addAll(teamDevs);
            } else {
                enrichedDevelopers.add(developerName);
            }
        }
        definition.setDevelopers(enrichedDevelopers.toArray(new String[0]));
    }

    private static Stream<String> teamDefinitionToDevelopers(TeamDefinition teamDefinition) {
        if (teamDefinition.getDevelopers() == null || teamDefinition.getDevelopers().length == 0) {
            LOGGER.log(
                    Level.WARNING, "Team %s has no developers defined, skipping".formatted(teamDefinition.getName()));
            return Stream.empty();
        } else {
            return Arrays.stream(teamDefinition.getDevelopers());
        }
    }

    private static void addMaintainers(
            Map<String, List<String>> maintainersByComponent, String key, Definition definition) {
        if (maintainersByComponent.containsKey(key)) {
            LOGGER.log(Level.WARNING, "Duplicate maintainers entry for component: " + key);
        }
        // A potential issue with this implementation is that groupId changes will result in lack of maintainer
        // information for the old groupId.
        // In practice this will probably not be a problem when path changes here and subsequent release are close
        // enough in time.
        // Alternatively, always keep the old groupId around for a while.
        maintainersByComponent.computeIfAbsent(
                key, unsued -> new ArrayList<>(Arrays.asList(definition.getDevelopers())));
    }

    private static PermissionJson.Principals buildPrincipals(Definition definition, ArtifactoryAPI artifactoryAPI)
            throws IOException {
        Map<String, List<String>> users;
        Map<String, List<String>> groups;

        if (definition.getDevelopers().length == 0) {
            users = Collections.emptyMap();
            groups = Collections.emptyMap();
            if (definition.getCd() != null && definition.getCd().enabled) {
                LOGGER.log(
                        Level.INFO,
                        "Skipping CD group definition for " + definition.getName() + " as there are no maintainers");
            }
        } else {
            if (definition.getCd() == null || !definition.getCd().exclusive) {
                users = new HashMap<>();
                for (String developer : definition.getDevelopers()) {
                    boolean existsInArtifactory = KnownUsers.existsInArtifactory(developer);
                    boolean existsInJira = KnownUsers.existsInJira(developer);

                    if (!existsInArtifactory && !existsInJira) {
                        reportChecksApiDetails(
                                developer + " needs to log in to Artifactory and Jira",
                                developer + " needs to log in to Artifactory and Jira...");
                        throw new IllegalStateException("User name not known to Artifactory and Jira: " + developer);
                    }
                    if (!existsInArtifactory) {
                        reportChecksApiDetails(
                                developer + " needs to log in to Artifactory",
                                developer + " needs to log in to Artifactory...");
                        throw new IllegalStateException("User name not known to Artifactory: " + developer);
                    }
                    if (!existsInJira) {
                        reportChecksApiDetails(
                                developer + " needs to log in to Jira", developer + " needs to log in to Jira...");
                        throw new IllegalStateException("User name not known to Jira: " + developer);
                    }
                    users.put(developer.toLowerCase(Locale.US), List.of("w", "n"));
                }
            } else {
                for (String developer : definition.getDevelopers()) {
                    boolean existsInJira = KnownUsers.existsInJira(developer);
                    if (!existsInJira) {
                        reportChecksApiDetails(
                                developer + " needs to log in to Jira", developer + " needs to log in to Jira...");
                        throw new IllegalStateException("User name not known to Jira: " + developer);
                    }
                }
                users = Collections.emptyMap();
            }
            if (definition.getCd() != null && definition.getCd().enabled) {
                groups = Map.of(artifactoryAPI.toGeneratedGroupName(definition.getGithub()), List.of("w", "n"));
            } else {
                groups = Collections.emptyMap();
            }
        }
        return new PermissionJson.Principals(users, groups);
    }

    // TODO It's a really weird decision to have this in the otherwise invocation agnostic standalone tool
    private static void reportChecksApiDetails(String errorMessage, String details) throws IOException {
        Files.writeString(Paths.get("checks-title.txt"), errorMessage, StandardCharsets.UTF_8);
        Files.writeString(Paths.get("checks-details.txt"), details, StandardCharsets.UTF_8);
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
        File[] files = payloadsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName().replace(".json", "");
                try {
                    creator.accept(name, file);
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Failed to create/replace group " + name, ex);
                }
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
    private static <T> void removeExtraArtifactoryObjects(
            File payloadsDir, String kind, Supplier<List<T>> lister, Consumer<T> deleter) {
        if (!payloadsDir.exists() || !payloadsDir.isDirectory()) {
            // TODO this will not remove objects if there would not be any left
            LOGGER.log(
                    Level.INFO,
                    "%s does not exist or is not a directory, skipping extra %ss removal".formatted(payloadsDir, kind));
            return;
        }
        LOGGER.log(Level.INFO, "Removing extra %ss from Artifactory...".formatted(kind));
        List<T> objects = new ArrayList<>();
        try {
            objects = (List<T>) lister.get();
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed listing %ss from Artifactory".formatted(kind), ex);
        }
        if (objects != null) {
            LOGGER.log(Level.INFO, "Discovered %d %ss".formatted(objects.size(), kind));

            objects.forEach(object -> {
                Path payloadsDirPath = payloadsDir.toPath();
                Path payloadPath = payloadsDirPath.resolve("%s.json".formatted(object));
                if (!Files.exists(payloadPath)) {
                    LOGGER.log(
                            Level.INFO,
                            "%s %s has no corresponding file, deleting...".formatted(capitalize(kind), object));
                    try {
                        deleter.accept(object);
                    } catch (Exception ex) {
                        LOGGER.log(
                                Level.WARNING, "Failed to delete %s %s from Artifactory".formatted(kind, object), ex);
                    }
                }
            });
        }
        LOGGER.log(Level.INFO, "Done removing extra %ss from Artifactory".formatted(kind));
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
