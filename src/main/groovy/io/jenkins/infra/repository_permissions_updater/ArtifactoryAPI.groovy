package io.jenkins.infra.repository_permissions_updater

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.json.JsonSlurper

import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

@SuppressFBWarnings("LI_LAZY_INIT_STATIC") // Something related to Groovy
abstract class ArtifactoryAPI {

    /**
     * URL to the permissions API of Artifactory
     */
    private static final String ARTIFACTORY_PERMISSIONS_API_URL = 'https://repo.jenkins-ci.org/api/security/permissions'
    /**
     * URL to the groups API of Artifactory
     */
    private static final String ARTIFACTORY_GROUPS_API_URL = 'https://repo.jenkins-ci.org/api/security/groups'

    /**
     * True iff this is a dry-run (no API calls resulting in modifications)
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean('dryRun')

    /**
     * Prefix for permission target generated and maintained (i.e. possibly deleted) by this program.
     */
    private static final String ARTIFACTORY_PERMISSION_TARGET_NAME_PREFIX = 'generatedv2-'
    private static final String ARTIFACTORY_GROUP_NAME_PREFIX = 'generatedv2-'

    abstract List<String> listGeneratedPermissionTargets();
    /**
     * Creates or replaces a permission target
     *
     * @param name the name of the permission target, used in URL
     * @param payloadFile {@see https://www.jfrog.com/confluence/display/RTF/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplacePermissionTarget}
     */
    abstract void createOrReplacePermissionTarget(String name, File payloadFile);

    /**
     * Deletes a permission target in Artifactory.
     *
     * @param target Name of the permssion target
     */
    abstract void deletePermissionTarget(String target);

    /**
     * Determines the name for the JSON API payload file, which is also used as the permission target name (with prefix)
     * @param name the expected base name before transformation
     * @return the transformed name, including the prefix, and compatible with Artifactory
     */
    abstract String toGeneratedPermissionTargetName(String baseName);

    abstract List<String> listGeneratedGroups();

    /**
     * Created or replaces a group
     *
     * @param name the name of the group, used in URL
     * @param payloadFile {@see https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplaceGroup}
     */
    abstract void createOrReplaceGroup(String name, File payloadFile);
    abstract void deleteGroup(String group);

    /**
     * Determines the name for the JSON API payload file, which is also used as the group name (with prefix)
     * @param name the expected base name before transformation
     * @return the transformed name, including the prefix, and compatible with Artifactory
     */
    abstract String toGeneratedGroupName(String baseName);

    /* Singleton support */
    private static ArtifactoryAPI INSTANCE = null
    static synchronized ArtifactoryAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ArtifactoryImpl()
        }
        return INSTANCE
    }

    @SuppressFBWarnings("SE_NO_SERIALVERSIONID") // Closures are serializable
    private static final class ArtifactoryImpl extends ArtifactoryAPI {
        private static final Logger LOGGER = Logger.getLogger(ArtifactoryImpl.class.getName())

        private static final class AuthenticatorImpl extends Authenticator {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        System.getenv("ARTIFACTORY_USERNAME"),
                        System.getenv("ARTIFACTORY_PASSWORD").toCharArray())
            }
        }

        private static final Authenticator AUTHENTICATOR = new AuthenticatorImpl()

        /**
         * Creates or replaces a permission target based on the provided payload.
         * @oaram name the name of the permission target
         * @param payloadFile the file containing the API payload.
         */
        @Override
        void createOrReplacePermissionTarget(String name, File payloadFile) {
            createOrReplace(ARTIFACTORY_PERMISSIONS_API_URL, name, "permission target", payloadFile);
        }

        @Override
        void deletePermissionTarget(String target) {
            delete(ARTIFACTORY_PERMISSIONS_API_URL, target, "permission target")
        }

        @Override
        List<String> listGeneratedPermissionTargets() {
            return list(ARTIFACTORY_PERMISSIONS_API_URL, ARTIFACTORY_PERMISSION_TARGET_NAME_PREFIX)
        }

        @Override
        String toGeneratedPermissionTargetName(String name) {
            return toGeneratedName(ARTIFACTORY_PERMISSION_TARGET_NAME_PREFIX, name)
        }

        @Override
        List<String> listGeneratedGroups() {
            return list(ARTIFACTORY_GROUPS_API_URL, ARTIFACTORY_GROUP_NAME_PREFIX)
        }

        @Override
        void createOrReplaceGroup(String name, File payloadFile) {
            createOrReplace(ARTIFACTORY_GROUPS_API_URL, name, "group", payloadFile);
        }

        @Override
        void deleteGroup(String group) {
            delete(ARTIFACTORY_GROUPS_API_URL, group, "group")
        }

        @Override
        String toGeneratedGroupName(String baseName) {
            // Add 'cd' to indicate this group is for CD only
            return toGeneratedName(ARTIFACTORY_GROUP_NAME_PREFIX, "cd-" + baseName)
        }

        private static List<String> list(String apiUrl, String prefix) {
            // https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-GetGroups
            URL url = new URL(apiUrl)
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            conn.setAuthenticator(AUTHENTICATOR)
            conn.setRequestMethod('GET')
            conn.connect()
            String text = conn.getInputStream().getText()

            def json = new JsonSlurper().parseText(text)

            return json.collect { (String) it.name }.findAll {
                it.startsWith(prefix)
            }
        }

        /**
         *
         * @param apiUrl The API base URL (does not include trailing '/')
         * @param name this is the full object name as provided by {@link #toGeneratedName}.
         * @param kind the human readable kind of object for log messages
         * @param payloadFile the file containing the payload
         */
        private static void createOrReplace(String apiUrl, String name, String kind, File payloadFile) {
            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, "Dry-run mode: Skipping PUT call for ${kind} ${name} at ${apiUrl}/${name} with payload from ${payloadFile.name}")
                return
            }
            LOGGER.log(Level.INFO, "Processing ${kind} file ${payloadFile.name}")

            // https://www.jfrog.com/confluence/display/RTF/Security+Configuration+JSON

            try {
                URL url = new URL(apiUrl + '/' + name)

                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                conn.setAuthenticator(AUTHENTICATOR)
                conn.setRequestMethod('PUT')
                conn.setDoOutput(true)
                OutputStreamWriter osw = new OutputStreamWriter(conn.getOutputStream())
                osw.write(payloadFile.text)
                osw.close()

                if (conn.getResponseCode() <= 200 || 299 <= conn.getResponseCode()) {
                    // failure
                    String error = conn.getErrorStream().text
                    LOGGER.log(Level.WARNING, "Failed to submit permissions target for ${name}: ${error}")
                }
            } catch (MalformedURLException ex) {
                LOGGER.log(Level.WARNING, "Not a valid URL for ${payloadFile.name}", ex)
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Failed sending PUT for ${payloadFile.name}", ioe)
            }
        }

        /**
         * Deletes the specified {@code name} using {@code apiUrl}.
         * @param apiUrl the base URL to the deletion API
         * @param name the name of the object to delete
         * @param kind the human-readable kind of object being deleted (for a log message)
         */
        private static void delete(String apiUrl, String name, String kind) {
            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, "Dry-run mode: Skipping DELETE call for ${kind} ${name} at ${apiUrl}/${name}")
                return
            }
            LOGGER.log(Level.INFO, "Deleting ${kind} ${name}")
            try {
                URL url = new URL(apiUrl + '/' + name)

                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                conn.setAuthenticator(AUTHENTICATOR)
                conn.setRequestMethod('DELETE')
                String response = conn.getInputStream().text
                LOGGER.log(Level.INFO, response)
            } catch (MalformedURLException ex) {
                LOGGER.log(Level.WARNING, "Not a valid URL for ${name}", ex)
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Failed sending DELETE for ${name}", ioe)
            }
        }

        private static String toGeneratedName(String prefix, String name) {
            name = prefix + name.replaceAll('[ /]', '_')
            if (name.length() > 64) {
                // Artifactory has an undocumented max length for permission target names of 64 chars (and possibly other types)
                // If length is exceeded, use 55 chars of the prefix+name, separator, and 8 hopefully unique chars (prefix of name's SHA-256)
                name = name.substring(0, 54) + '_' + sha256(name).substring(0, 7)
            }
            return name
        }

        private static String sha256(String str) {
            LOGGER.log(Level.INFO, "Computing sha256 for string: " + str)
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256")
                digest.update(str.bytes)
                return digest.digest().encodeHex().toString()
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to compute SHA-256 digest", e)
                return '00000000000000000000000000000000'
            }
        }
    }
}
