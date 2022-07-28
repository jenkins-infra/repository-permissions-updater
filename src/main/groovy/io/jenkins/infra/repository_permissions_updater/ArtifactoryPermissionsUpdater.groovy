package io.jenkins.infra.repository_permissions_updater

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.io.FileType
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import io.jenkins.lib.support_log_formatter.SupportLogFormatter
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.constructor.Constructor

import java.util.concurrent.TimeUnit
import java.util.logging.ConsoleHandler
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.Logger

@SuppressFBWarnings("SE_NO_SERIALVERSIONID") // all closures are Serializable...
class ArtifactoryPermissionsUpdater {

    /**
     * Directory containing the permissions definition files in YAML format
     */
    private static final File DEFINITIONS_DIR = new File(System.getProperty('definitionsDir', './permissions'))

    /**
     * Temporary directory that this tool will write Artifactory API JSON payloads to. Must not exist prior to execution.
     */
    private static final File ARTIFACTORY_API_DIR = new File(System.getProperty('artifactoryApiTempDir', './json'))

    /**
     * If enabled, will not send PUT/DELETE requests to Artifactory, only GET (i.e. not modifying).
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean('dryRun')

    /**
     * Set to true during development to prevent collisions with production behavior:
     *
     * - Different prefixes for groups and permission targets in {@link ArtifactoryAPI}.
     * - Different GitHub secret names in {@link #generateTokens(java.io.File)}
     * - Permissions are only granted for
     *
     */
    private static final boolean DEVELOPMENT = Boolean.getBoolean('development')

    /**
     * Loads all teams from the teams/ folder.
     * Always returns non null.
     */
    private static Map<String, Set<TeamDefinition>>  loadTeams() {
        Yaml yaml = new Yaml(new Constructor(TeamDefinition.class))
        File teamsDir = new File('teams/')

        Set<TeamDefinition> teams = [] as Set

        teamsDir.eachFile { teamFile ->
            try {
                TeamDefinition newTeam = yaml.loadAs(new FileReader(teamFile), TeamDefinition.class)
                teams.add(newTeam)

                String expectedName = "${newTeam.name}.yml"
                if(teamFile.name != expectedName ) {
                    throw new Exception("team file should be named $expectedName instead of the current ${teamFile.name}")
                }
            } catch (YAMLException e) {
                throw new IOException("Failed to read ${teamFile.name}", e)
            }
        }
        Map<String, Set<TeamDefinition>> result = [:]
        // TODO: lamba-ify more the conversion
        teams.each { team ->
            result.put(team.name, team)
        }
        return result

    }

    /**
     * Checks if any developer has its name starting with `@`.
     * In which case, for `@some-team` it will replace it with the developers
     * listed for the team whose name equals `some-team` under the teams/ directory.
     */
    private static void expandTeams(Definition definition, Map<String, Set<TeamDefinition>> teamsByName) {
        Set<String> developers = definition.developers
        Set<String> expandedDevelopers = [] as Set

        developers.each { developerName ->
            if (developerName.startsWith('@')) {
                String teamName = developerName.substring(1)
                Set teamDevs = teamsByName.get(teamName)?.developers
                if (teamDevs == null ) {
                    throw new Exception("Team $teamName not found!")
                }
                if (teamDevs.isEmpty()) {
                    throw new Exception("Team $teamName is empty?!")
                }
                LOGGER.log(Level.INFO, "[$definition.name]: replacing $developerName with $teamDevs")
                expandedDevelopers.addAll(teamDevs.toArray())
            } else {
                expandedDevelopers.add(developerName)
            }
        }
        definition.developers = expandedDevelopers
    }

    /**
     * Take the YAML permission definitions and convert them to Artifactory permissions API payloads.
     */
    private static void generateApiPayloads(File yamlSourceDirectory, File apiOutputDir) throws IOException {
        Yaml yaml = new Yaml(new Constructor(Definition.class))

        if (!yamlSourceDirectory.exists()) {
            throw new IOException("Directory ${DEFINITIONS_DIR} does not exist")
        }

        if (apiOutputDir.exists()) {
            throw new IOException(apiOutputDir.path + " already exists")
        }

        Map<String, Set<TeamDefinition>> teamsByName = loadTeams()

        Map<String, Set<String>> pathsByGithub = new TreeMap()
        Map<String, List> issueTrackersByPlugin = new TreeMap()
        Map<String, List<Definition>> cdEnabledComponentsByGitHub = new TreeMap<>()
        Map<String, List<String>> maintainersByComponent = new HashMap<>()

        yamlSourceDirectory.eachFile { file ->
            if (!file.name.endsWith('.yml')) {
                throw new IOException("Unexpected file: `${file.name}`. YAML files must end with `.yml`")
            }

            Definition definition

            try {
                definition = yaml.loadAs(new FileReader(file), Definition.class)

                expandTeams(definition, teamsByName)

            } catch (Exception e) {
                throw new IOException("Failed to read ${file.name}", e)
            }

            if (definition.github) {
                Set<String> paths = pathsByGithub[definition.github]
                if (!paths) {
                    paths = new TreeSet()
                    pathsByGithub[definition.github] = paths
                }
                paths.addAll(definition.paths)

                if (definition.cd && definition.getCd().enabled) {
                    if (!definition.github.matches('(jenkinsci|stapler)/.+')) {
                        throw new Exception("CD is only supported when the GitHub repository is in @jenkinsci or @stapler")
                    }
                    List<Definition> definitions = cdEnabledComponentsByGitHub[definition.github]
                    if (!definitions) {
                        definitions = new ArrayList<>()
                        cdEnabledComponentsByGitHub[definition.github] = definitions
                    }
                    LOGGER.log(Level.INFO, "CD-enabled component '${definition.name}' in repository '${definition.github}'")
                    definitions.add(definition)
                }
            } else {
                if (definition.cd && definition.getCd().enabled) {
                    throw new Exception("Cannot have CD ('cd') enabled without specifying GitHub repository ('github')")
                }
            }

            if (definition.issues) {
                if (definition.github) {
                    issueTrackersByPlugin.put(definition.name, definition.issues.collect { tracker ->
                        if (tracker.isJira() || tracker.isGitHubIssues()) {
                            def ret = [type: tracker.getType(), reference: tracker.getReference()]
                            def viewUrl = tracker.getViewUrl(JiraAPI.getInstance())
                            if (viewUrl) {
                                ret += [ viewUrl: viewUrl ]
                            }
                            def reportUrl = tracker.getReportUrl(JiraAPI.getInstance())
                            if (reportUrl) {
                                ret += [ reportUrl: reportUrl ]
                            }
                            return ret
                        }
                        return null
                    }.findAll { it != null })
                } else {
                    throw new Exception("Issue trackers ('issues') support requires GitHub repository ('github')")
                }
            }

            String artifactId = definition.name
            for (String path : definition.paths) {
                if (path.substring(path.lastIndexOf('/') + 1) != artifactId) {
                    // We could throw an exception here, but we actively abuse this for unusually structured components
                    LOGGER.log(Level.WARNING, "Unexpected path: " + path + " for artifact ID: " + artifactId)
                }
                String groupId = path.substring(0, path.lastIndexOf('/')).replace('/', '.')

                String key = groupId + ":" + artifactId

                if (maintainersByComponent.containsKey(key)) {
                    LOGGER.log(Level.WARNING, "Duplicate maintainers entry for component: " + key)
                }
                // A potential issue with this implementation is that groupId changes will result in lack of maintainer information for the old groupId.
                // In practice this will probably not be a problem when path changes here and subsequent release are close enough in time.
                // Alternatively, always keep the old groupId around for a while.
                maintainersByComponent.computeIfAbsent(key, { _ -> new ArrayList<>(Arrays.asList(definition.developers)) })
            }

            String fileBaseName = file.name.replaceAll('\\.ya?ml$', '')

            String jsonName = ArtifactoryAPI.getInstance().toGeneratedPermissionTargetName(fileBaseName)
            File outputFile = new File(new File(apiOutputDir, 'permissions'), jsonName + '.json')
            JsonBuilder json = new JsonBuilder()


            json {
                name jsonName
                includesPattern definition.paths.collect { path ->
                    [
                            path + '/*/' + definition.name + '-*',
                            path + '/*/maven-metadata.xml', // used for SNAPSHOTs
                            path + '/*/maven-metadata.xml.*',
                            path + '/maven-metadata.xml',
                            path + '/maven-metadata.xml.*'
                    ]
                }.flatten().join(',')
                excludesPattern ''
                repositories(DEVELOPMENT ? ['snapshots'] : [ 'snapshots', 'releases' ])
                principals {
                    if (definition.developers.length == 0) {
                        users [:]
                    } else {
                        users definition.developers.collectEntries { developer ->
                            def existsInArtifactory = KnownUsers.existsInArtifactory(developer)
                            def existsInJira = KnownUsers.existsInJira(developer) || JiraAPI.getInstance().isUserPresent(developer)

                            if (!existsInArtifactory && !existsInJira) {
                                reportChecksApiDetails(developer + " needs to log in to Artifactory and Jira",
                                """
                                ${developer} needs to log in to [Artifactory](https://repo.jenkins-ci.org/) and [Jira](https://issues.jenkins.io/).

                                We resync our Artifactory list hourly, so you will need to wait some time before rebuilding your pull request.
                                The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

                                Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
                                """.stripIndent())
                                throw new IllegalStateException("User name not known to Artifactory and Jira: " + developer)
                            }

                            if (!existsInArtifactory) {
                                reportChecksApiDetails(developer + " needs to log in to Artifactory",
                                        """
                                ${developer} needs to log in to [Artifactory](https://repo.jenkins-ci.org/).

                                We resync our Artifactory list hourly, so you will need to wait some time before rebuilding your pull request.
                                The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

                                Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
                                """.stripIndent())
                                throw new IllegalStateException("User name not known to Artifactory: " + developer)
                            }

                            if (!existsInJira) {
                                reportChecksApiDetails(developer + " needs to log in to Jira",
                                        """
                                ${developer} needs to log in to [Jira](https://issues.jenkins.io/).
                                """.stripIndent())
                                throw new IllegalStateException("User name not known to Jira: " + developer)
                            }
                            [(developer.toLowerCase(Locale.US)): ["w", "n"]]
                        }
                    }
                    if (definition.cd?.enabled) {
                        groups([(ArtifactoryAPI.getInstance().toGeneratedGroupName(definition.github)): ["w", "n"]])
                    } else {
                        groups([:])
                    }
                }
            }

            String pretty = json.toPrettyString()

            outputFile.parentFile.mkdirs()
            outputFile.text = pretty
        }

        cdEnabledComponentsByGitHub.each { githubRepo, components ->
            def groupName = ArtifactoryAPI.getInstance().toGeneratedGroupName(githubRepo)
            File outputFile = new File(new File(apiOutputDir, 'groups'), groupName + '.json')
            JsonBuilder json = new JsonBuilder()

            json {
                name groupName
                description "CD group with permissions to deploy from ${githubRepo}"
            }

            String pretty = json.toPrettyString()

            outputFile.parentFile.mkdirs()
            outputFile.text = pretty
        }

        def githubIndex = new JsonBuilder()
        githubIndex(pathsByGithub)
        new File(apiOutputDir, 'github.index.json').text = githubIndex.toPrettyString()

        def issuesIndex = new JsonBuilder()
        issuesIndex(issueTrackersByPlugin)
        new File(apiOutputDir, 'issues.index.json').text = issuesIndex.toPrettyString()

        def cdRepos = new JsonBuilder()
        cdRepos(cdEnabledComponentsByGitHub.keySet().toList())
        new File(apiOutputDir, 'cd.index.json').text = cdRepos.toPrettyString()

        def maintainers = new JsonBuilder()
        maintainers(maintainersByComponent)
        new File(apiOutputDir, 'maintainers.index.json').text = maintainers.toPrettyString()
    }

    // TODO It's a really weird decision to have this in the otherwise invocation agnostic standalone tool
    private static void reportChecksApiDetails(String errorMessage, String details) {
        new File('checks-title.txt').text = errorMessage
        new File('checks-details.txt').text = details
    }

    /**
     * Takes a directory with Artifactory API payloads and submits them to the appropriate Artifactory API,
     * creating/updating the specified objects identified through the file name.
     *
     * @param payloadsDir the directory containing the payload file for the objects matching the file name without .json extension
     * @param kind the kind of object to create (used for log messages only)
     * @param creator the closure called to create or update an object. Takes two arguments, the {@code String} name and {@code File} payload file.
     */
    private static void submitArtifactoryObjects(File payloadsDir, String kind, Closure creator) {
        LOGGER.log(Level.INFO, "Submitting ${kind}s...")
        if (!payloadsDir.exists() || !payloadsDir.isDirectory()) {
            LOGGER.log(Level.INFO, "${payloadsDir} does not exist or is not a directory, skipping ${kind} submission")
            return
        }
        payloadsDir.eachFile(FileType.FILES) { file ->
            if (!file.name.endsWith('.json')) {
                return
            }

            def name = file.name.replace('.json', '')
            try {
                creator.call(name, file)
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to create/replace group ${name}", ex)
            }
        }
        LOGGER.log(Level.INFO, "Done submitting ${kind}s")
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
    private static void removeExtraArtifactoryObjects(File payloadsDir, String kind, Closure lister, Closure deleter) {
        if (!payloadsDir.exists() || !payloadsDir.isDirectory()) {
            // TODO this will not remove objects if there would not be any left
            LOGGER.log(Level.INFO, "${payloadsDir} does not exist or is not a directory, skipping extra ${kind}s removal")
            return
        }
        LOGGER.log(Level.INFO, "Removing extra ${kind}s from Artifactory...")
        def objects = []
        try {
            objects = lister.call()
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed listing ${kind}s from Artifactory", ex)
        }
        if (objects != null) {
            LOGGER.log(Level.INFO, "Discovered ${objects.size()} ${kind}s")

            objects.each { object ->
                if (!new File(payloadsDir, object + '.json').exists()) {
                    LOGGER.log(Level.INFO, "${kind.capitalize()} ${object} has no corresponding file, deleting...")
                    try {
                        deleter.call(object)
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Failed to delete ${kind} ${object} from Artifactory", ex)
                    }
                }
            }
        }
        LOGGER.log(Level.INFO, "Done removing extra ${kind}s from Artifactory")
    }

    /**
     * Generates Artifactory access tokens for the Artifactory groups corresponding to the GitHub repo names, and then
     * attaches the token username and password to the GitHub repo as a secret.
     *
     * @param githubReposForCdIndex JSON file containing a list of GitHub repo names in the format 'orgname/reponame'
     */
    private static void generateTokens(File githubReposForCdIndex) {
        def repos = new JsonSlurper().parse(githubReposForCdIndex)
        repos.each { repo ->
            LOGGER.log(Level.INFO, "Processing repository ${repo} for CD")
            def username = ArtifactoryAPI.toTokenUsername((String) repo)
            def groupName = ArtifactoryAPI.getInstance().toGeneratedGroupName((String) repo)
            def validFor = TimeUnit.MINUTES.toSeconds(Integer.getInteger('artifactoryTokenMinutesValid', 240))
            def token
            try {
                if (DRY_RUN_MODE) {
                    LOGGER.log(Level.INFO, "Skipped creation of token for GitHub repo: '${repo}', Artifactory user: '${username}', group name: '${groupName}', valid for ${validFor} seconds")
                    return
                }
                token = ArtifactoryAPI.getInstance().generateTokenForGroup(username, groupName, validFor)
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Failed to generate token for ${repo}", ex)
                return
            }

            GitHubAPI.GitHubPublicKey publicKey = GitHubAPI.getInstance().getRepositoryPublicKey((String) repo)
            if (publicKey == null) {
                LOGGER.log(Level.WARNING, "Failed to retrieve public key for ${repo}")
                return
            }
            LOGGER.log(Level.INFO, "Public key of ${repo} is ${publicKey}")

            def encryptedUsername = CryptoUtil.encryptSecret(username, publicKey.key)
            def encryptedToken = CryptoUtil.encryptSecret(token, publicKey.key)
            LOGGER.log(Level.INFO, "Encrypted secrets are username:${encryptedUsername}; token:${encryptedToken}")

            GitHubAPI.getInstance().createOrUpdateRepositorySecret(System.getProperty('gitHubSecretNamePrefix', DEVELOPMENT ? 'DEV_MAVEN_' : 'MAVEN_') + 'USERNAME', encryptedUsername, (String) repo, publicKey.keyId)
            GitHubAPI.getInstance().createOrUpdateRepositorySecret(System.getProperty('gitHubSecretNamePrefix', DEVELOPMENT ? 'DEV_MAVEN_' : 'MAVEN_') + 'TOKEN', encryptedToken, (String) repo, publicKey.keyId)
        }
    }

    static void main(String[] args) throws IOException {
        for (Handler h : Logger.getLogger("").getHandlers()) {
            if (h instanceof ConsoleHandler) {
                ((ConsoleHandler) h).setFormatter(new SupportLogFormatter())
            }
        }

        if (DRY_RUN_MODE) {
            LOGGER.log(Level.INFO, 'Running in dry run mode')
        }
        def artifactoryApi = ArtifactoryAPI.getInstance()
        /*
         * Generate JSON payloads from YAML permission definition files in DEFINITIONS_DIR and writes them to ARTIFACTORY_API_DIR.
         * Any problems with the input here are fatal so PR builds fails.
         */
        generateApiPayloads(DEFINITIONS_DIR, ARTIFACTORY_API_DIR)
        /*
         * Submit generated Artifactory group JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        def groupsJsonDir = new File(ARTIFACTORY_API_DIR, "groups")
        submitArtifactoryObjects(groupsJsonDir, 'group', artifactoryApi.&createOrReplaceGroup)
        removeExtraArtifactoryObjects(groupsJsonDir, 'group', artifactoryApi.&listGeneratedGroups, artifactoryApi.&deleteGroup)
        /*
         * Submit generated Artifactory permission target JSON payloads to Artifactory, and delete generated groups no longer relevant.
         * Any problems here are logged to allow troubleshooting.
         */
        def permissionTargetsJsonDir = new File(ARTIFACTORY_API_DIR, "permissions")
        submitArtifactoryObjects(permissionTargetsJsonDir, 'permission target', artifactoryApi.&createOrReplacePermissionTarget)
        removeExtraArtifactoryObjects(permissionTargetsJsonDir, 'permission target', artifactoryApi.&listGeneratedPermissionTargets, artifactoryApi.&deletePermissionTarget)
        /*
         * For all CD-enabled GitHub repositories, obtain a token from Artifactory and attach it to a GH repo as secret.
         */
        generateTokens(new File(ARTIFACTORY_API_DIR, "cd.index.json"))
    }

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.name)
}
