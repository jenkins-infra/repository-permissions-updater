package io.jenkins.infra.repository_permissions_updater

import edu.umd.cs.findbugs.annotations.CheckForNull
import edu.umd.cs.findbugs.annotations.NonNull
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

@SuppressFBWarnings("LI_LAZY_INIT_STATIC") // Something related to Groovy
abstract class ArtifactoryAPI {
    private static final Logger LOGGER = Logger.getLogger(ArtifactoryAPI.class.getName())
    /**
     * URL to Artifactory
     */
    private static final String ARTIFACTORY_URL = System.getProperty('artifactoryUrl', 'https://repo.jenkins-ci.org')
    /**
     * URL to the permissions API of Artifactory
     */
    private static final String ARTIFACTORY_PERMISSIONS_API_URL = ARTIFACTORY_URL + '/api/security/permissions'
    /**
     * URL to the groups API of Artifactory
     */
    private static final String ARTIFACTORY_GROUPS_API_URL = ARTIFACTORY_URL + '/api/security/groups'
    /**
     * URL to the groups API of Artifactory
     */
    private static final String ARTIFACTORY_TOKEN_API_URL = ARTIFACTORY_URL + '/api/security/token'

    /**
     * True iff this is a dry-run (no API calls resulting in modifications)
     */
    public static final boolean DRY_RUN_MODE = Boolean.getBoolean('dryRun')

    /**
     * Prefix for permission target generated and maintained (i.e. possibly deleted) by this program.
     */
    private static final String ARTIFACTORY_OBJECT_NAME_PREFIX = System.getProperty('artifactoryObjectPrefix', Boolean.getBoolean('development') ? 'generateddev-' : 'generatedv2-')

    /**
     * List all permission targets whose name starts with the configured prefix.
     *
     * @see #toGeneratedPermissionTargetName(java.lang.String)
     * @link https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-GetPermissionTargets
     * @return all permission targets whose name starts with the configured prefix.
     */
    abstract List<String> listGeneratedPermissionTargets();
    /**
     * Creates or replaces a permission target.
     *
     * @param name the name of the permission target, used in URL
     * @param payloadFile {@see https://www.jfrog.com/confluence/display/RTF/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplacePermissionTarget}
     */
    abstract void createOrReplacePermissionTarget(@NonNull String name, @NonNull File payloadFile);

    /**
     * Deletes a permission target in Artifactory.
     *
     * @param target Name of the permssion target
     */
    abstract void deletePermissionTarget(@NonNull String target);

    /**
     * List all groups whose name starts with the configured prefix.
     *
     * @see #toGeneratedGroupName(java.lang.String)
     * @link https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-GetGroups
     * @return all groups whose name starts with the configured prefix.
     */
    abstract @NonNull List<String> listGeneratedGroups();

    /**
     * Creates or replaces a group.
     *
     * @param name the name of the group, used in URL
     * @param payloadFile {@see https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplaceGroup}
     */
    abstract void createOrReplaceGroup(String name, File payloadFile);
    abstract void deleteGroup(String group);

    /**
     * Generates a token scoped to the specified group.
     *
     * @link https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-CreateToken
     * @param group the group scope for the token
     * @return the token
     */
    abstract String generateTokenForGroup(String username, String group, long expiresInSeconds);

    /* Public instance-independent API */

    /**
     * Determines the name for the JSON API payload file, which is also used as the permission target name (with prefix).
     *
     * @param name the expected base name before transformation
     * @return the transformed name, including the prefix, and compatible with Artifactory
     */
    static @NonNull String toGeneratedPermissionTargetName(@NonNull String name) {
        return toGeneratedName(ARTIFACTORY_OBJECT_NAME_PREFIX, name)
    }

    /**
     * Determines the name for the JSON API payload file, which is also used as the group name (with prefix).
     *
     * @param name the expected base name before transformation
     * @return the transformed name, including the prefix, and compatible with Artifactory
     */
    static @NonNull String toGeneratedGroupName(String baseName) {
        // Add 'cd' to indicate this group is for CD only
        return toGeneratedName(ARTIFACTORY_OBJECT_NAME_PREFIX, "cd-" + baseName)
    }

    /**
     * Converts the provided base name (expected to be a GitHub repository name of the form 'org/name') to a user name
     * for a non-existing token user.
     *
     * @link https://www.jfrog.com/confluence/display/JFROG/Access+Tokens#AccessTokens-SupportAuthenticationforNon-ExistingUsers
     * @param baseName
     * @return
     */
    static @NonNull String toTokenUsername(String baseName) {
        return 'CD-for-' + baseName.replaceAll('[ /]', '__')
    }

    private static String sha256(String str) {
        LOGGER.log(Level.INFO, "Computing sha256 for string: " + str)
        MessageDigest digest = MessageDigest.getInstance("SHA-256")
        digest.update(str.bytes)
        return digest.digest().encodeHex().toString()
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

        static {
            String username = System.getenv("ARTIFACTORY_USERNAME")
            String password = System.getenv("ARTIFACTORY_PASSWORD")
            if (username == null || password == null) {
                AUTHENTICATOR = null
                if (!DRY_RUN_MODE) {
                    throw new IllegalStateException("ARTIFACTORY_USERNAME and ARTIFACTORY_PASSWORD must be provided unless dry-run mode is used")
                }
            } else {
                if (System.getProperty("java.version").startsWith("1.")) {
                    // HttpUrlConnection#setAuthenticator exists since Java 9
                    throw new IllegalStateException("You need at least Java 9 to run this unless dry-run mode is used")
                }
                AUTHENTICATOR = new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password.toCharArray())
                    }
                }
            }
        }

        private static final Authenticator AUTHENTICATOR

        /**
         * Creates or replaces a permission target based on the provided payload.
         * @oaram name the name of the permission target
         * @param payloadFile the file containing the API payload.
         */
        @Override
        void createOrReplacePermissionTarget(String name, File payloadFile) {
            createOrReplace(ARTIFACTORY_PERMISSIONS_API_URL, name, "permission target", payloadFile)
        }

        @Override
        void deletePermissionTarget(String target) {
            delete(ARTIFACTORY_PERMISSIONS_API_URL, target, "permission target")
        }

        @Override
        List<String> listGeneratedPermissionTargets() {
            return list(ARTIFACTORY_PERMISSIONS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX)
        }

        @Override
        List<String> listGeneratedGroups() {
            return list(ARTIFACTORY_GROUPS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX)
        }

        @Override
        void createOrReplaceGroup(String name, File payloadFile) {
            createOrReplace(ARTIFACTORY_GROUPS_API_URL, name, "group", payloadFile)
        }

        @Override
        void deleteGroup(String group) {
            delete(ARTIFACTORY_GROUPS_API_URL, group, "group")
        }

        @Override
        @CheckForNull String generateTokenForGroup(String username, String group, long expiresInSeconds) {
            withConnection('POST', ARTIFACTORY_TOKEN_API_URL) {
                setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
                setDoOutput(true)
                OutputStreamWriter osw = new OutputStreamWriter(getOutputStream())
                def params = [
                        'username': username,
                        'scope': 'member-of-groups:readers,' + group,
                        'expires_in': expiresInSeconds
                ].collect { k, v -> k  + '=' + URLEncoder.encode((String)v, StandardCharsets.UTF_8) }.join('&')
                LOGGER.log(Level.INFO, "Generating token with request payload: " + params)
                osw.write(params)
                osw.close()
                String text = getInputStream().getText()
                def json = new JsonSlurper().parseText(text)
                return json.access_token
            }
        }

        private static List<String> list(String apiUrl, String prefix) {
            withConnection('GET', apiUrl) {
                connect()
                String text = getInputStream().getText()
                def json = new JsonSlurper().parseText(text)
                return json.collect { (String) it.name }.findAll {
                    it.startsWith(prefix)
                }
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
            withConnection('PUT', apiUrl + '/' + name) {
                setDoOutput(true)
                OutputStreamWriter osw = new OutputStreamWriter(getOutputStream())
                osw.write(payloadFile.text)
                osw.close()
            }
        }

        /**
         * Deletes the specified {@code name} using {@code apiUrl}.
         * @param apiUrl the base URL to the deletion API
         * @param name the name of the object to delete
         * @param kind the human-readable kind of object being deleted (for a log message)
         */
        private static void delete(String apiUrl, String name, String kind) {
            withConnection('DELETE', apiUrl + '/' + name) {
                String response = getInputStream().text
                LOGGER.log(Level.INFO, response)
            }
        }

        private static withConnection = { String verb, String url, Closure<?> closure ->
            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, "Dry-run mode: Skipping ${verb} call to ${url}")
                return
            }
            LOGGER.log(Level.INFO, "Sending ${verb} to ${url}")

            HttpURLConnection conn = null
            try {
                URL _url = new URL(url)
                conn = (HttpURLConnection) _url.openConnection()
                conn.setAuthenticator(AUTHENTICATOR)
                conn.setRequestMethod(verb)

                closure.setDelegate(conn)
                closure.call()
            } finally {
                if (conn.responseCode < 200 || conn.responseCode > 399) {
                    LOGGER.log(Level.INFO, "${verb} request to ${url} returned error: HTTP ${conn.responseCode} ${conn.responseMessage}")
                } else {
                    LOGGER.log(Level.INFO, "${verb} request to ${url} returned: HTTP ${conn.responseCode} ${conn.responseMessage}")
                }
            }
        }
    }
}
