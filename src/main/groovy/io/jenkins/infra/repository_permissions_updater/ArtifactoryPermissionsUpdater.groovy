package io.jenkins.infra.repository_permissions_updater

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

public class ArtifactoryPermissionsUpdater {

    /**
     * Directory containing the permissions definition files in YAML format
     */
    private static final File DEFINITIONS_DIR = new File(System.getProperty('definitionsDir', './permissions'))

    /**
     * Temporary directory that this tool will write Artifactory API JSON payloads to. Must not exist prior to execution.
     */
    private static final File ARTIFACTORY_API_DIR = new File(System.getProperty('artifactoryApiTempDir', './json'))

    /**
     * URL to JSON with a list of valid Artifactory user names.
     */
    private static final String ARTIFACTORY_USER_NAMES_URL = System.getProperty('artifactoryUserNamesJsonListUrl')

    /**
     * URL to the permissions API of Artifactory
     */
    private static final String ARTIFACTORY_PERMISSIONS_API_URL = 'https://repo.jenkins-ci.org/api/security/permissions'

    /**
     * Prefix for permission target generated and maintained (i.e. possibly deleted) by this program.
     */
    private static final String ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX = 'generated-'

    /**
     * If enabled, will not send PUT/DELETE requests to Artifactory, only GET (i.e. not modifying).
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean('dryRun')

    static {
        /* Make sure all Artifactory API requests are properly authenticated */
        Authenticator.setDefault (new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        System.getenv("ARTIFACTORY_USERNAME"),
                        System.getenv("ARTIFACTORY_PASSWORD").toCharArray());
            }
        });
    }

    /* Because Jackson isn't groovy enough, data type to deserialize YAML permission definitions to */
    private static class Definition {
        private String name = ""
        private String[] paths = new String[0]
        private String[] developers = new String[0]
        String github
        Object security // unused, just metadata for Jenkins security team

        String getName() {
            return name
        }

        void setName(String name) {
            this.name = name
        }

        String[] getPaths() {
            return paths
        }

        void setPaths(String[] paths) {
            this.paths = paths
        }

        String[] getDevelopers() {
            return developers
        }

        void setDevelopers(String[] developers) {
            this.developers = developers
        }
    }

    private static String md5(String str) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5")
            digest.update(str.bytes)
            return digest.digest().encodeHex().toString()
        } catch (Exception e) {
            return '0000000000000000'
        }
    }

    /**
     * Determines the name for the JSON API payload file, which is also used as the permission target name (with prefix)
     * @param pluginName
     * @return
     */
    private static String getApiPayloadFileName(String fileBaseName) {
        String name = fileBaseName
        if ((ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX + name).length() > 64) {
            // Artifactory has an undocumented max length for permission target names of 64 chars
            // If length is exceeded, use 55 chars of the name, separator, and 8 chars (half of name's MD5)
            name = name.substring(0, 54 - ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX .length()) + '_' + md5(fileBaseName).substring(0, 7)
        }
        return name
    }

    /**
     * Take the YAML permission definitions and convert them to Artifactory permissions API payloads.
     */
    private static void generateApiPayloads(File yamlSourceDirectory, File apiOutputDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())

        if (!yamlSourceDirectory.exists()) {
            throw new IOException("Directory ${DEFINITIONS_DIR} does not exist")
        }

        def knownUsers = []
        if (ARTIFACTORY_USER_NAMES_URL) {
            knownUsers = new JsonSlurper().parse(new URL(ARTIFACTORY_USER_NAMES_URL))
        }

        if (apiOutputDir.exists()) {
            throw new IOException(apiOutputDir.path + " already exists")
        }

        Map<String, Set<String>> pathsByGithub = new TreeMap()

        yamlSourceDirectory.eachFile { file ->
            if (!file.name.endsWith('.yml')) {
                throw new IOException("Unexpected file: ${file.name}")
            }

            Definition definition

            try {
                definition = mapper.readValue(new FileReader(file), Definition.class)
            } catch (JsonProcessingException e) {
                throw new IOException("Failed to read ${file.name}", e);
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

            File outputFile = new File(apiOutputDir, getApiPayloadFileName(fileBaseName) + '.json')
            JsonBuilder json = new JsonBuilder()

            String jsonName = ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX + getApiPayloadFileName(fileBaseName)

            json {
                name jsonName
                includesPattern definition.paths.collect { path ->
                    [
                            path + '/*/' + definition.name + '-*',
                            path + '/*/maven-metadata.xml', // used for SNAPSHOTs
                            path + '/*/maven-metadata.xml.sha1',
                            path + '/*/maven-metadata.xml.md5',
                            path + '/maven-metadata.xml',
                            path + '/maven-metadata.xml.sha1',
                            path + '/maven-metadata.xml.md5'
                    ]
                }.flatten().join(',')
                excludesPattern ''
                repositories([ 'releases', 'snapshots' ])
                principals {
                    if (definition.developers.length == 0) {
                        users [:]
                    } else {
                        users definition.developers.collectEntries { developer ->
                            if (!knownUsers.contains(developer.toLowerCase())) {
                                throw new IllegalStateException("User name not known to Artifactory: " + developer)
                            }
                            ["${developer.toLowerCase()}": ["w", "n"]]
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

            LOGGER.log(Level.INFO, "Processing ${file.name}")

            // https://www.jfrog.com/confluence/display/RTF/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplacePermissionTarget
            // https://www.jfrog.com/confluence/display/RTF/Security+Configuration+JSON

            try {
                URL apiUrl = new URL(ARTIFACTORY_PERMISSIONS_API_URL + '/' + ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX + file.name.replace('.json', ''))

                HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection()
                conn.setRequestMethod('PUT')
                conn.setDoOutput(true)
                OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream())
                osw.write(file.text)
                osw.close()

                if (conn.getResponseCode() <= 200 || 299 <= conn.getResponseCode()) {
                    // failure
                    String error = conn.getErrorStream().text
                    LOGGER.log(Level.WARNING, "Failed to submit permissions target for ${file.name}: ${error}")
                }
            } catch (MalformedURLException mfue) {
                LOGGER.log(Level.WARNING, "Not a valid URL for ${file.name}", mfue)
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Failed sending PUT for ${file.name}", ioe)
            }
        }
    }

    /**
     * Deletes a permission target in Artifactory.
     *
     * @param target Name of the permssion target
     */
    private static void deletePermissionsTarget(String target) {
        try {
            URL apiUrl = new URL(ARTIFACTORY_PERMISSIONS_API_URL + '/' + target)

            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection()
            conn.setRequestMethod('DELETE')
            String response = conn.getInputStream().text
            LOGGER.log(Level.INFO, response)
        } catch (MalformedURLException mfue) {
            LOGGER.log(Level.WARNING, "Not a valid URL for ${target}", mfue)
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "Failed sending DELETE for ${target}", ioe)
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
        try {
            URL apiUrl = new URL(ARTIFACTORY_PERMISSIONS_API_URL)
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection()
            conn.setRequestMethod('GET')
            conn.connect()
            String text = conn.getInputStream().getText()

            def json = new JsonSlurper().parseText(text)

            def permissionTargets = json.collect { (String) it.name }

            permissionTargets.each { target ->
                if (!target.startsWith(ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX)) {
                    // don't touch manually maintained permission targets
                    return
                }

                String fileName = target.replace(ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX, '')

                if (!new File(jsonApiFileDir, fileName + '.json').exists()) {
                    LOGGER.log(Level.INFO, "Permission target ${target} has no corresponding file, deleting...")
                    deletePermissionsTarget(target)
                }
            }

        } catch (MalformedURLException mfue) {
            LOGGER.log(Level.WARNING, "Not a valid URL for ${ARTIFACTORY_PERMISSIONS_API_URL}", mfue)
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "Failed sending GET for ${ARTIFACTORY_PERMISSIONS_API_URL}", ioe)
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
    public static void main(String[] args) throws IOException {
        if (DRY_RUN_MODE) System.err.println("Running in dry run mode")
        generateApiPayloads(DEFINITIONS_DIR, ARTIFACTORY_API_DIR)
        if (DRY_RUN_MODE) {
            System.err.println("Payloads generated in " + ARTIFACTORY_API_DIR + ". Nothing was sent.")
            return
        }
        submitPermissionTargets(ARTIFACTORY_API_DIR)
        removeExtraPermissionTargets(ARTIFACTORY_API_DIR)
    }

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.name)
}
