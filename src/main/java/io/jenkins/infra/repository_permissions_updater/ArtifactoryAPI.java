package io.jenkins.infra.repository_permissions_updater;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public abstract class ArtifactoryAPI {
    private static final Logger LOGGER = Logger.getLogger(ArtifactoryAPI.class.getName());
    /**
     * URL to Artifactory
     */
    private static final String ARTIFACTORY_URL = System.getProperty("artifactoryUrl", "https://repo.jenkins-ci.org");
    /**
     * URL to the permissions API of Artifactory
     */
    private static final String ARTIFACTORY_PERMISSIONS_API_URL = ARTIFACTORY_URL + "/api/security/permissions";
    /**
     * URL to the groups API of Artifactory
     */
    private static final String ARTIFACTORY_GROUPS_API_URL = ARTIFACTORY_URL + "/api/security/groups";
    /**
     * URL to the groups API of Artifactory
     */
    private static final String ARTIFACTORY_TOKEN_API_URL = ARTIFACTORY_URL + "/access/api/v1/tokens";

    /**
     * True if this is a dry-run (no API calls resulting in modifications)
     */
    public static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    /**
     * Prefix for permission target generated and maintained (i.e. possibly deleted) by this program.
     */
    private static final String ARTIFACTORY_OBJECT_NAME_PREFIX = System.getProperty("artifactoryObjectPrefix", Boolean.getBoolean("development") ? "generateddev-" : "generatedv2-");

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
        return toGeneratedName(ARTIFACTORY_OBJECT_NAME_PREFIX, name);
    }

    /**
     * Determines the name for the JSON API payload file, which is also used as the group name (with prefix).
     *
     * @param name the expected base name before transformation
     * @return the transformed name, including the prefix, and compatible with Artifactory
     */
    static @NonNull String toGeneratedGroupName(String baseName) {
        // Add 'cd' to indicate this group is for CD only
        return toGeneratedName(ARTIFACTORY_OBJECT_NAME_PREFIX, "cd-" + baseName);
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
        return "CD-for-" + baseName.replaceAll("[ /]", "__");
    }

    private static String sha256(String str) throws NoSuchAlgorithmException {
        LOGGER.log(Level.INFO, "Computing sha256 for string: " + str);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(str.getBytes(StandardCharsets.UTF_8));
        return Hex.encodeHexString(digest.digest());
    }

    private static String toGeneratedName(String prefix, String name) {
        name = prefix + name.replaceAll("[ /]", "_");
        if (name.length() > 64) {
            try {
                // Artifactory has an undocumented max length for permission target names of 64 chars (and possibly other types)
                // If length is exceeded, use 55 chars of the prefix+name, separator, and 8 hopefully unique chars (prefix of name's SHA-256)
                name = name.substring(0, 54) + '_' + sha256(name).substring(0, 7);
            } catch(NoSuchAlgorithmException e) {
                LOGGER.log(Level.WARNING, "NoSuchAlgorithException : " + e.getMessage());
            }
        }
        return name;
    }

    /* Singleton support */
    private static ArtifactoryAPI INSTANCE = null;
    static synchronized ArtifactoryAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ArtifactoryImpl();
        }
        return INSTANCE;
    }

    private static final class ArtifactoryImpl extends ArtifactoryAPI {
        private static final Logger LOGGER = Logger.getLogger(ArtifactoryImpl.class.getName());

        static {
            String token = System.getenv("ARTIFACTORY_TOKEN");
            if (token == null) {
                BEARER_TOKEN = null;
                if (!DRY_RUN_MODE) {
                    throw new IllegalStateException("ARTIFACTORY_TOKEN must be provided unless dry-run mode is used");
                }
            } else {
                if (System.getProperty("java.version").startsWith("1.")) {
                    // URLEncoder#encode(String, Charset) exists since Java 10
                    throw new IllegalStateException("You need at least Java 10 to run this unless dry-run mode is used");
                }
                BEARER_TOKEN = String.format("Bearer %s", token);
            }
        }

        private static final String BEARER_TOKEN;

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
            delete(ARTIFACTORY_PERMISSIONS_API_URL, target, "permission target");
        }

        @Override
        List<String> listGeneratedPermissionTargets() {
            return list(ARTIFACTORY_PERMISSIONS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX);
        }

        @Override
        List<String> listGeneratedGroups() {
            return list(ARTIFACTORY_GROUPS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX);
        }

        @Override
        void createOrReplaceGroup(String name, File payloadFile) {
            createOrReplace(ARTIFACTORY_GROUPS_API_URL, name, "group", payloadFile);
        }

        @Override
        void deleteGroup(String group) {
            delete(ARTIFACTORY_GROUPS_API_URL, group, "group");
        }

        @Override
        @CheckForNull String generateTokenForGroup(String username, String group, long expiresInSeconds)  {
            String verb = "POST", url;
            HashMap<String, String> params = new HashMap<String, String>();
            params.put("username", username);
            params.put("scope", "applied-permissions/groups:readers," + group);
            params.put("expires_in", String.valueOf(expiresInSeconds));
            String queryString = params.entrySet().stream().map(entry -> entry.getKey() + '=' + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
            LOGGER.log(Level.INFO, "Generating token with request payload: " + queryString);
            url = ARTIFACTORY_TOKEN_API_URL + '?' + queryString;
            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, String.format("Dry-run mode: Skipping %s call to %s", verb, url));
                return null;
            } else {
                try {
                    HttpURLConnection connection = withConnection(verb, url);
                    connection.setDoInput(true);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    BufferedReader responseReader = null;
                    if (connection.getResponseCode() >= 200 || connection.getResponseCode() < 399) {
                        LOGGER.log(Level.INFO, String.format("%s request to %s returned: HTTP %d %s", verb, url, connection.getResponseCode(), connection.getResponseMessage()));
                        responseReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), connection.getContentEncoding()));
                    } else {
                        LOGGER.log(Level.INFO, String.format("%s request to %s returned error: HTTP %d %s",  verb, url, connection.getResponseCode(), connection.getResponseMessage()));
                        responseReader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), connection.getContentEncoding()));
                    }
                    Stream<String> responseStream = responseReader.lines();
                    responseReader.close();
                    connection.disconnect();
                    String jsonString = responseStream.collect(Collectors.joining());
                    LOGGER.log(Level.INFO, "Response String : " + jsonString);
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode  = objectMapper.readTree(jsonString);
                    String accessToken = jsonNode.get("access_token").asText();
                    return accessToken;
                } catch(IOException e) {
                    LOGGER.log(Level.WARNING, String.format("IOException %s:%s  : %s", verb, url, e.getMessage()));
                }
                return null;
            }
        }    


        /**
         * 
         * @param apiUrl
         * @param prefix
         * @return
         */
        private static List<String> list(String apiUrl, String prefix) {
            String verb = "GET";
            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, String.format("Dry-run mode: Skipping %s call to %s", verb, apiUrl));
                return null;
            } else {
                try {

                    HttpURLConnection connection = withConnection(verb, apiUrl);
                    connection.setDoInput(true);
                    BufferedReader responseReader = null;
                    if (connection.getResponseCode() >= 200 || connection.getResponseCode() < 399) {
                        responseReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), connection.getContentEncoding()));
                    } else {
                        responseReader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), connection.getContentEncoding()));
                    }
                    Stream<String> responseStream = responseReader.lines();
                    responseReader.close();
                    connection.disconnect();
                    String jsonString = responseStream.collect(Collectors.joining());
                    LOGGER.log(Level.INFO, "Response Json : " + jsonString);
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode  = objectMapper.readTree(jsonString);
                    List<String> list = new ArrayList<String>();
                    jsonNode.elements().forEachRemaining((jsonObject) -> {
                        String permissionName = jsonObject.get("name").asText();
                        if (permissionName.startsWith(prefix)) {
                            list.add(permissionName);
                        }
                    });
                    return list;
                } catch(IOException e) {
                    LOGGER.log(Level.WARNING, String.format("IOException %s:%s  : %s", verb, apiUrl, e.getMessage()));
                }
                return null;
            }
        }



        /**
         *
         * @param apiUrl The API base URL (does not include trailing '/')
         * @param name this is the full object name as provided by {@link #toGeneratedName}.
         * @param kind the human readable kind of object for log messages
         * @param payloadFile the file containing the payload
         */
        @SuppressFBWarnings("DM_DEFAULT_ENCODING")
        private static void createOrReplace(String apiUrl, String name, String kind, File payloadFile) {
            String verb = "PUT";
            String url = apiUrl + '/' + name;
            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, String.format("Dry-run mode: Skipping %s call to %s", verb, url));
                return;
            } else {
                try {
                    HttpURLConnection connection = withConnection(verb, url);
                    connection.setDoInput(true);
                    connection.setDoOutput(true);
                    OutputStreamWriter osw = new OutputStreamWriter(connection.getOutputStream());
                    String payloadFileContent = FileUtils.readFileToString(payloadFile, "utf-8");
                    LOGGER.log(Level.INFO, "Payload File Content : " + payloadFileContent);
                    osw.write(payloadFileContent);
                    osw.close();
                    BufferedReader responseReader = null;
                    if (connection.getResponseCode() >= 200 || connection.getResponseCode() < 399) {
                        responseReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    } else {
                        responseReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    }
                    Stream<String> responseStream = responseReader.lines();
                    String responseString = responseStream.collect(Collectors.joining());
                    responseReader.close();
                    connection.disconnect();
                    LOGGER.log(Level.INFO, responseString);
                } catch(IOException e) {
                    LOGGER.log(Level.WARNING, String.format("IOException %s:%s  : %s", verb, apiUrl, e.getMessage()));
                }
            }
        }

        /**
         * Deletes the specified {@code name} using {@code apiUrl}.
         * @param apiUrl the base URL to the deletion API
         * @param name the name of the object to delete
         * @param kind the human-readable kind of object being deleted (for a log message)
         */
        private static void delete(String apiUrl, String name, String kind) {
            String verb = "DELETE";
            String url = apiUrl + '/' + name;
            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, String.format("Dry-run mode: Skipping %s call to %s", verb, url));
                return;
            } else {
                try {
                    HttpURLConnection connection = withConnection(verb, url);
                    connection.setDoInput(true);
                    connection.setDoOutput(true);
                    BufferedReader responseReader = null;
                    if (connection.getResponseCode() >= 200 || connection.getResponseCode() < 399) {
                        responseReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), connection.getContentEncoding()));
                    } else {
                        responseReader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), connection.getContentEncoding()));
                    }
                    Stream<String> responseStream = responseReader.lines();
                    String responseString = responseStream.collect(Collectors.joining());
                    LOGGER.log(Level.INFO, responseString);
                    responseReader.close();
                    connection.disconnect();
                } catch(IOException e) {
                    LOGGER.log(Level.WARNING, String.format("IOException %s:%s  : %s", verb, apiUrl, e.getMessage()));
                }
            }
        }
        @SuppressFBWarnings("URLCONNECTION_SSRF_FD")
        private static HttpURLConnection withConnection(String verb, String url) {
            LOGGER.log(Level.INFO,  String.format("Sending %s to %s", verb, url));
            HttpURLConnection connection = null;
            try {
                URL _url = new URL(url);
                connection = (HttpURLConnection) _url.openConnection();
                connection.setRequestMethod(verb);
                if (!BEARER_TOKEN.isBlank()) {
                    connection.addRequestProperty("Authorization", BEARER_TOKEN);
                }
            } catch (ProtocolException e) {
                LOGGER.log(Level.WARNING, String.format("ProtocolException while creating connection to %s:%s  : %s", verb, url, e.getMessage()));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, String.format("IOException while creating connection to %s:%s  : %s", verb, url, e.getMessage()));
            }
            return connection;
        }
    }
}
