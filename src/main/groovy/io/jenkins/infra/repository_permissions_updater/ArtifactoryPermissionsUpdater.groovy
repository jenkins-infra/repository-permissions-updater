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
     * URL to the permissions API of Artifactory
     */
    private static final String ARTIFACTORY_PERMISSIONS_API_URL = 'https://repo.jenkins-ci.org/api/security/permissions'

    /**
     * Prefix for permission target generated and maintained (i.e. possibly deleted) by this program.
     */
    private static final String ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX = 'generated-'

    /* Because Jackson isn't groovy enough, data type to deserialize YAML permission definitions to */
    private static class Definition {
        private String name = ""
        private String[] paths = new String[0]
        private String[] developers = new String[0]
        private String[] contributors = new String[0]

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

        String[] getContributors() {
            return contributors
        }

        void setContributors(String[] contributors) {
            this.contributors = contributors
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
    private static void generateApiPayloads(File yamlSourceDirectory, File apiOutputDir) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())

        if (!yamlSourceDirectory.exists()) {
            throw new IllegalStateException("Directory ${yamlSourceDirectory} does not exist")
        }

        if (apiOutputDir.exists()) {
            throw new IllegalStateException(apiOutputDir.path + " already exists")
        }

        yamlSourceDirectory.eachFile { file ->
            if (!file.name.endsWith('.yml')) {
                LOGGER.log(Level.INFO, "Skipping ${file.name}, not a YAML file")
                return
            }

            Definition definition

            try {
                definition = mapper.readValue(new FileReader(file), Definition.class)
            } catch (JsonProcessingException e) {
                LOGGER.log(Level.WARNING, "Failed to read ${file.name}, skipping:" + e.getMessage());
                return
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
                            ["$developer": ["w", "n"]]
                        }
                    }
                    groups([:])
                }
            }

            String pretty = json.toPrettyString()

            try {
                outputFile.parentFile.mkdirs()
                outputFile.text = pretty
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to write to ${outputFile.name}, skipping:" + e);
            }

            if (definition.contributors.length > 0) {
                fileBaseName = file.name.replaceAll('\\.ya?ml$', '')
                outputFile = new File(apiOutputDir, getApiPayloadFileName('contrib-'+fileBaseName) + '.json')
                json = new JsonBuilder()

                jsonName = ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX + getApiPayloadFileName(fileBaseName)

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
                    repositories(['snapshots'])
                    principals {
                        users definition.contributors.collectEntries { developer ->
                            ["$developer": ["w", "n"]]
                        }
                        groups([:])
                    }
                }

                pretty = json.toPrettyString()

                try {
                    outputFile.parentFile.mkdirs()
                    outputFile.text = pretty
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to write to ${outputFile.name}, skipping:" + e);
                }


            }
        }
    }

    /**
     * Takes a directory with Artifactory permissions API payloads and submits them to Artifactory, creating/updating
     * affected permission targets.
     *
     * @param jsonApiFileDir
     * @param dryRun if {@code true} then just log the operations rather than perform them
     */
    private static void submitPermissionTargets(File jsonApiFileDir, boolean dryRun) {
        jsonApiFileDir.eachFile { file ->
            if (!file.name.endsWith('.json')) {
                return
            }

            LOGGER.log(Level.INFO, "Processing ${file.name}")

            // https://www.jfrog.com/confluence/display/RTF/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplacePermissionTarget
            // https://www.jfrog.com/confluence/display/RTF/Security+Configuration+JSON

            try {
                URL apiUrl = new URL(ARTIFACTORY_PERMISSIONS_API_URL + '/' + ARTIFACTORY_PERMISSIONS_TARGET_NAME_PREFIX + file.name.replace('.json', ''))
                if (dryRun) {
                    LOGGER.log(Level.INFO, "Would PUT {0}", apiUrl);
                    return
                }
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
     * @param dryRun if {@code true} then just log the operations rather than perform them
     */
    private static void deletePermissionsTarget(String target, boolean dryRun) {
        try {
            URL apiUrl = new URL(ARTIFACTORY_PERMISSIONS_API_URL + '/' + target)
            if (dryRun) {
                LOGGER.log(Level.INFO, "Would DELETE {0}", apiUrl);
                return
            }

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
     * @param dryRun if {@code true} then just log the operations rather than perform them
     */
    private static void removeExtraPermissionTargets(File jsonApiFileDir, boolean dryRun) {
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
                    deletePermissionsTarget(target, dryRun)
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
    public static void main(String[] args) {
        if (!System.getProperty('java.util.logging.SimpleFormatter.format')) {
            System.setProperty('java.util.logging.SimpleFormatter.format',
                    '%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s: %5$s%6$s%n');
        }
        def defDefnDir = System.getProperty('definitionsDir', './permissions');
        def defWorkDir = System.getProperty('artifactoryApiTempDir', './json');
        def cli = new CliBuilder(usage: 'repository-permissions-updater -dhwto')
        cli.with {
            h longOpt: 'help', 'Show usage information'
            d longOpt: 'definitionDir', args:1, argName: 'dir', "Definitions directory (defaults to ${defDefnDir})"
            w longOpt: 'workDir', args:1, argName: 'dir', "Work directory (defaults to ${defWorkDir})"
            t longOpt: 'dryRun', 'Enable read only dry run mode'
            o longOpt: 'offline', 'Off-line mode (implies dry run but does not perform any remote operations)'
        }
        def options = cli.parse(args)
        if (!options) {
            return
        }
        if (options.h) {
            cli.usage()
            return
        }
        boolean dryRun = options.t
        boolean offline = false
        if (!System.getenv("ARTIFACTORY_USERNAME")) {
            LOGGER.log(Level.WARNING, "Environment variable 'ARTIFACTORY_USERNAME' not configured, forcing --offline")
            dryRun = true
            offline = true
        }
        if (!System.getenv('ARTIFACTORY_PASSWORD')) {
            LOGGER.log(Level.WARNING, "Environment variable 'ARTIFACTORY_PASSWORD' not configured, forcing --offline")
            dryRun = true
            offline = true
        }
        if (!offline) {
            /* Make sure all Artifactory API requests are properly authenticated */
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            System.getenv("ARTIFACTORY_USERNAME"),
                            System.getenv("ARTIFACTORY_PASSWORD").toCharArray());
                }
            });
        }

        File definitionsDir = new File(options.d?:defDefnDir)
        File workDir = new File(options.w?:defWorkDir)
        generateApiPayloads(definitionsDir, workDir)
        submitPermissionTargets(workDir, dryRun)
        if (!offline) {
            removeExtraPermissionTargets(workDir, dryRun)
        }
    }

    private static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.name)
}
