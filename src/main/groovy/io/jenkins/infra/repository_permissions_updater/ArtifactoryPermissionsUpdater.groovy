package io.jenkins.infra.repository_permissions_updater

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.json.JsonBuilder

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
     * Take the YAML permission definitions and convert them to Artifactory permissions API payloads.
     */
    private static void generateApiPayloads(File yamlSourceDirectory, File apiOutputDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())

        if (!yamlSourceDirectory.exists()) {
            throw new IOException("Directory ${DEFINITIONS_DIR} does not exist")
        }

        if (apiOutputDir.exists()) {
            throw new IOException(apiOutputDir.path + " already exists")
        }

        Map<String, Set<String>> pathsByGithub = new TreeMap()

        yamlSourceDirectory.eachFile { file ->
            if (!file.name.endsWith('.yml')) {
                throw new IOException("Unexpected file: `${file.name}`. YAML files must end with `.yml`")
            }

            Definition definition

            try {
                definition = mapper.readValue(new FileReader(file), Definition.class)
            } catch (JsonProcessingException e) {
                throw new IOException("Failed to read ${file.name}", e)
            }

            if (definition.github) {
                Set<String> paths = pathsByGithub[definition.github]
                if (!paths) {
                    paths = new TreeSet()
                    pathsByGithub[definition.github] = paths
                }
                paths.addAll(definition.paths)
            }

            String fileBaseName = file.name.replaceAll('\\.ya?ml$', '')

            String jsonName = ArtifactoryAPI.getInstance().toGeneratedPermissionTargetName(fileBaseName)
            File outputFile = new File(apiOutputDir, jsonName + '.json')
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
                repositories([ 'releases', 'snapshots' ])
                principals {
                    if (definition.developers.length == 0) {
                        users [:]
                    } else {
                        users definition.developers.collectEntries { developer ->
                            if (!KnownUsers.exists(developer.toLowerCase())) {
                                reportChecksApiDetails(developer + " needs to login to artifactory", 
                                """
                                ${developer} needs to login to [artifactory](https://repo.jenkins-ci.org/).

                                We resync our artifactory list hourly, so you will need to wait some time before rebuilding your pull request.
                                The easiest way to trigger a rebuild is to close your pull request, wait a few seconds and then reopen it.

                                Alternatively the hosting team can re-trigger it if you post a comment saying you have now logged in.
                                """.stripIndent())
                                throw new IllegalStateException("User name not known to Artifactory: " + developer)
                            }
                            [(developer.toLowerCase(Locale.US)): ["w", "n"]]
                        }
                    }
                    groups([:])
                }
            }

            String pretty = json.toPrettyString()

            outputFile.parentFile.mkdirs()
            outputFile.text = pretty
        }

        def githubIndex = new JsonBuilder()
        githubIndex(pathsByGithub)
        new File(apiOutputDir, 'github.index.json').text = githubIndex.toPrettyString()
    }

    // TODO It's a really weird decision to have this in the otherwise invocation agnostic standalone tool
    private static void reportChecksApiDetails(String errorMessage, String details) {
        new File('checks-title.txt').text = errorMessage
        new File('checks-details.txt').text = details
    }

    /**
     * Takes a directory with Artifactory permissions API payloads and submits them to Artifactory, creating/updating
     * affected permission targets.
     *
     * @param jsonApiFileDir
     */
    private static void submitPermissionTargets(File jsonApiFileDir) {
        jsonApiFileDir.eachFile { file ->
            if (file.name == 'github.index.json') {
                return
            }
            if (!file.name.endsWith('.json'))
                return

            ArtifactoryAPI.getInstance().createOrReplacePermissionTarget(file.name.replace('.json', ''), file)
        }
    }

    /**
     * Compares the list of permission targets returned from Artifactory with the list of permission target JSON payload
     * files in the specified directory, and deletes all permission targets that match the required 'generated' prefix
     * and that have no corresponding payload file.
     *
     * @param jsonApiFileDir
     */
    private static void removeExtraPermissionTargets(File jsonApiFileDir) {
        def permissionTargets = ArtifactoryAPI.getInstance().listGeneratedPermissionTargets()

        permissionTargets.each { target ->
            if (!new File(jsonApiFileDir, target + '.json').exists()) {
                LOGGER.log(Level.INFO, "Permission target ${target} has no corresponding file, deleting...")
                ArtifactoryAPI.getInstance().deletePermissionTarget(target)
            }
        }
    }

    /**
     * Update Artifactory permission targets:
     *
     * 1. Generate JSON payloads from YAML permission definition files.
     * 2. Submit generated JSON payloads to Artifactory.
     * 3. Remove all generated permission targets in Artifactory that have no corresponding generated JSON payload file.
     *
     * @param args unused
     */
    static void main(String[] args) throws IOException {
        if (DRY_RUN_MODE) {
            System.err.println("Running in dry run mode")
        }
        generateApiPayloads(DEFINITIONS_DIR, ARTIFACTORY_API_DIR)
        submitPermissionTargets(ARTIFACTORY_API_DIR)
        removeExtraPermissionTargets(ARTIFACTORY_API_DIR)
    }

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.name)
}
