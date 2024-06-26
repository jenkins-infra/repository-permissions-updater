package io.jenkins.infra.repository_permissions_updater.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import groovy.json.JsonBuilder;
import io.jenkins.infra.repository_permissions_updater.ArtifactoryAPI;
import io.jenkins.infra.repository_permissions_updater.CryptoUtil;
import io.jenkins.infra.repository_permissions_updater.Definition;
import io.jenkins.infra.repository_permissions_updater.GitHubAPI;
import io.jenkins.infra.repository_permissions_updater.JiraAPI;
import io.jenkins.infra.repository_permissions_updater.KnownUsers;
import io.jenkins.infra.repository_permissions_updater.TeamDefinition;
import io.jenkins.lib.support_log_formatter.SupportLogFormatter;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ArtifactoryPermissionsUpdater {
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

    private static final Gson gson = new Gson();

    /**
     * Loads all teams from the teams/ folder.
     * Always returns non null.
     */
    private static Map<String, Set<TeamDefinition>> loadTeams() throws Exception {
        Yaml yaml = new Yaml(new Constructor(TeamDefinition.class, new LoaderOptions()));
        File teamsDir = new File("teams/");

        Set<TeamDefinition> teams = new HashSet<>();

        File[] teamFiles = teamsDir.listFiles();
        if (teamFiles != null) {
            for (File teamFile : teamFiles) {
                try {
                    TeamDefinition newTeam = yaml.loadAs(new FileReader(teamFile, StandardCharsets.UTF_8), TeamDefinition.class);
                    teams.add(newTeam);

                    String expectedName = newTeam.getName() + ".yml";
                    if (!teamFile.getName().equals(expectedName)) {
                        throw new Exception("team file should be named " + expectedName + " instead of the current " + teamFile.getName());
                    }
                } catch (YAMLException e) {
                    throw new IOException("Failed to read " + teamFile.getName(), e);
                }
            }
        }

        Map<String, Set<TeamDefinition>> result = new HashMap<>();
        teams.forEach(team -> result.put(team.getName(), Set.of(team)));

        return result;
    }

    /**
     * Checks if any developer has its name starting with `@`.
     * In which case, for `@some-team` it will replace it with the developers
     * listed for the team whose name equals `some-team` under the teams/ directory.
     */
    private static void expandTeams(Definition definition, Map<String, Set<TeamDefinition>> teamsByName) throws Exception {
        String[] developers = definition.getDevelopers();
        Set<String> expandedDevelopers = new HashSet<>();

        for (String developerName : developers) {
            if (developerName.startsWith("@")) {
                String teamName = developerName.substring(1);
                Set<TeamDefinition> team = teamsByName.get(teamName);
                if (team == null) {
                    throw new Exception("Team " + teamName + " not found!");
                }
                Set<String> teamDevs = team.stream().flatMap(td -> Arrays.stream(td.getDevelopers())).collect(Collectors.toSet());
                if (teamDevs.isEmpty()) {
                    throw new Exception("Team " + teamName + " is empty?!");
                }
                LOGGER.log(Level.INFO, "[" + definition.getName() + "]: replacing " + developerName + " with " + teamDevs);
                expandedDevelopers.addAll(teamDevs);
            } else {
                expandedDevelopers.add(developerName);
            }
        }
        definition.setDevelopers(expandedDevelopers.toArray(String[]::new));
    }

    /**
     * Take the YAML permission definitions and convert them to Artifactory permissions API payloads.
     */
    private static void generateApiPayloads(File yamlSourceDirectory, File apiOutputDir) throws Exception {
        Yaml yaml = new Yaml(new Constructor(Definition.class, new LoaderOptions()));

        if (!yamlSourceDirectory.exists()) {
            throw new IOException("Directory " + DEFINITIONS_DIR + " does not exist");
        }

        if (apiOutputDir.exists()) {
            throw new IOException(apiOutputDir.getPath() + " already exists");
        }

        Map<String, Set<TeamDefinition>> teamsByName = loadTeams();

        Map<String, Set<String>> pathsByGithub = new TreeMap<>();
        Map<String, List<Map<String, Object>>> issueTrackersByPlugin = new TreeMap<>();
        Map<String, List<Definition>> cdEnabledComponentsByGitHub = new TreeMap<>();
        Map<String, List<String>> maintainersByComponent = new HashMap<>();

        File[] files = yamlSourceDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().endsWith(".yml")) {
                    throw new IOException("Unexpected file: `" + file.getName() + "`. YAML files must end with `.yml`");
                }

                Definition definition;
                try {
                    definition = yaml.loadAs(new FileReader(file, StandardCharsets.UTF_8), Definition.class);
                    expandTeams(definition, teamsByName);
                } catch (Exception e) {
                    throw new IOException("Failed to read " + file.getName(), e);
                }

                if (definition.getGithub() != null) {
                    Set<String> paths = pathsByGithub.computeIfAbsent(definition.getGithub(), k -> new TreeSet<>());
                    paths.addAll(Arrays.asList(definition.getPaths()));

                    if (definition.getCd() != null && definition.getCd().enabled) {
                        if (!definition.getGithub().matches("(jenkinsci)/.+")) {
                            throw new Exception("CD is only supported when the GitHub repository is in @jenkinsci");
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
                        throw new Exception("Cannot have CD ('cd') enabled without specifying GitHub repository ('github')");
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
                        throw new Exception("Issue trackers ('issues') support requires GitHub repository ('github')");
                    }
                }

                String artifactId = definition.getName();
                for (String path : definition.getPaths()) {
                    if (!path.substring(path.lastIndexOf('/') + 1).equals(artifactId)) {
                        LOGGER.log(Level.WARNING, "Unexpected path: " + path + " for artifact ID: " + artifactId);
                    }
                    String groupId = path.substring(0, path.lastIndexOf('/')).replace('/', '.');

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
                    throw new IOException("Not allowed to navigate outside of the current folder");
                }
                JsonBuilder json = new JsonBuilder();

                json.call("name", jsonName);
                json.call("includesPattern", Arrays.stream(definition.getPaths()).flatMap(path -> Arrays.stream(new String[]{
                        path + "/*/" + definition.getName() + "-*",
                        path + "/*/maven-metadata.xml", // used for SNAPSHOTs
                        path + "/*/maven-metadata.xml.*",
                        path + "/maven-metadata.xml",
                        path + "/maven-metadata.xml.*"
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

                Files.createDirectories(permissionPath);
                Files.createFile(outputFile);
                Files.writeString(outputFile, pretty);
            }
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

    /**
     * Takes a directory with Artifactory API payloads and submits them to the appropriate Artifactory API,
     * creating/updating the specified objects identified through the file name.
     *
     * @param payloadsDir the directory containing the payload file for the objects matching the file name without .json extension
     * @param kind the kind of object to create (used for log messages only)
     * @param creator the closure called to create or update an object. Takes two arguments, the {@code String} name and {@code File} payload file.
     */
    private static void submitArtifactoryObjects(File payloadsDir, String kind, BiConsumer<String, File> creator) {
        LOGGER.log(Level.INFO, "Submitting " + kind + "s...");
        if (!payloadsDir.exists() || !payloadsDir.isDirectory()) {
            LOGGER.log(Level.INFO, payloadsDir + " does not exist or is not a directory, skipping " + kind + " submission");
            return;
        }
        File[] files = payloadsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().endsWith(".json")) {
                    continue;
                }

                String name = file.getName().replace(".json", "");
                try {
                    creator.accept(name, file);
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Failed to create/replace group " + name, ex);
                }
            }
        }
        LOGGER.log(Level.INFO, "Done submitting " + kind + "s");
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
    private static void removeExtraArtifactoryObjects(File payloadsDir, String kind, Supplier<List<String>> lister, Consumer<String> deleter) throws IOException {
        if (!payloadsDir.exists() || !payloadsDir.isDirectory()) {
            LOGGER.log(Level.INFO, payloadsDir + " does not exist or is not a directory, skipping extra " + kind + "s removal");
            return;
        }
        LOGGER.log(Level.INFO, "Removing extra " + kind + "s from Artifactory...");
        List<String> objects = new ArrayList<>();
        try {
            objects = lister.get();
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed listing " + kind + "s from Artifactory", ex);
        }
        if (objects != null) {
            LOGGER.log(Level.INFO, "Discovered " + objects.size() + " " + kind + "s");

            for (String object : objects) {
                Path objectPath = payloadsDir.toPath().resolve(object + ".json");
                if (!objectPath.normalize().startsWith(payloadsDir.toPath())) {
                    throw new IOException("Not allowed to navigate outside of the current folder");
                }
                if (Files.notExists(objectPath)) {
                    LOGGER.log(Level.INFO, kind.substring(0, 1).toUpperCase() + kind.substring(1) + " " + object + " has no corresponding file, deleting...");
                    try {
                        deleter.accept(object);
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Failed to delete " + kind + " " + object + " from Artifactory", ex);
                    }
                }
            }
        }
        LOGGER.log(Level.INFO, "Done removing extra " + kind + "s from Artifactory");
    }

    /**
     * Generates Artifactory access tokens for the Artifactory groups corresponding to the GitHub repo names, and then
     * attaches the token username and password to the GitHub repo as a secret.
     *
     * @param githubReposForCdIndex JSON file containing a list of GitHub repo names in the format 'orgname/reponame'
     */
    private static void generateTokens(File githubReposForCdIndex) throws IOException {
        JsonElement jsonElement = gson.fromJson(new InputStreamReader(new FileInputStream(githubReposForCdIndex), StandardCharsets.UTF_8), JsonElement.class);
        JsonArray repos = jsonElement.getAsJsonArray();
        for (JsonElement repoElement : repos) {
            var repo = repoElement.getAsString();
            LOGGER.log(Level.INFO, "Processing repository " + repo + " for CD");
            String username = ArtifactoryAPI.toTokenUsername(repo);
            String groupName = ArtifactoryAPI.toGeneratedGroupName(repo);
            long validFor = TimeUnit.MINUTES.toSeconds(Integer.getInteger("artifactoryTokenMinutesValid", 240));
            String token;
            try {
                if (DRY_RUN_MODE) {
                    LOGGER.log(Level.INFO, "Skipped creation of token for GitHub repo: '" + repo + "', Artifactory user: '" + username + "', group name: '" + groupName + "', valid for " + validFor + " seconds");
                    return;
                }
                token = ArtifactoryAPI.getInstance().generateTokenForGroup(username, groupName, validFor);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to generate token for " + repo, ex);
                return;
            }

            GitHubAPI.GitHubPublicKey publicKey = GitHubAPI.getInstance().getRepositoryPublicKey(repo);
            if (publicKey == null) {
                LOGGER.log(Level.WARNING, "Failed to retrieve public key for " + repo);
                return;
            }
            LOGGER.log(Level.INFO, "Public key of " + repo + " is " + publicKey);

            String encryptedUsername = CryptoUtil.encryptSecret(username, publicKey.getKey());
            String encryptedToken = CryptoUtil.encryptSecret(token, publicKey.getKey());
            LOGGER.log(Level.INFO, "Encrypted secrets are username:" + encryptedUsername + "; token:" + encryptedToken);

            GitHubAPI.getInstance().createOrUpdateRepositorySecret(System.getProperty("gitHubSecretNamePrefix", DEVELOPMENT ? "DEV_MAVEN_" : "MAVEN_") + "USERNAME", encryptedUsername, repo, publicKey.getKeyId());
            GitHubAPI.getInstance().createOrUpdateRepositorySecret(System.getProperty("gitHubSecretNamePrefix", DEVELOPMENT ? "DEV_MAVEN_" : "MAVEN_") + "TOKEN", encryptedToken, repo, publicKey.getKeyId());
        }
    }

    public static void main(String[] args) throws Exception {
        for (Handler h : Logger.getLogger("").getHandlers()) {
            if (h instanceof ConsoleHandler) {
                ((ConsoleHandler) h).setFormatter(new SupportLogFormatter());
            }
        }

        if (DRY_RUN_MODE) {
            LOGGER.log(Level.INFO, "Running in dry run mode");
        }
        ArtifactoryAPI artifactoryApi = ArtifactoryAPI.getInstance();
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
        submitArtifactoryObjects(groupsJsonDir, "group", artifactoryApi::createOrReplaceGroup);
        removeExtraArtifactoryObjects(groupsJsonDir, "group", artifactoryApi::listGeneratedGroups, artifactoryApi::deleteGroup);
        /*
         * Submit generated Artifactory permission target JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        File permissionTargetsJsonDir = new File(ARTIFACTORY_API_DIR, "permissions");
        submitArtifactoryObjects(permissionTargetsJsonDir, "permission target", artifactoryApi::createOrReplacePermissionTarget);
        removeExtraArtifactoryObjects(permissionTargetsJsonDir, "permission target", artifactoryApi::listGeneratedPermissionTargets, artifactoryApi::deletePermissionTarget);
        /*
         * For all CD-enabled GitHub repositories, obtain a token from Artifactory and attach it to a GH repo as secret.
         */
        generateTokens(new File(ARTIFACTORY_API_DIR, "cd.index.json"));
    }

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.getName());
}
