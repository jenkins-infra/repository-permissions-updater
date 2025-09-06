package io.jenkins.infra.repository_permissions_updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

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
     * True iff this is a dry-run (no API calls resulting in modifications)
     */
    public static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    /**
     * Prefix for permission target generated and maintained (i.e. possibly deleted) by this program.
     */
    private static final String ARTIFACTORY_OBJECT_NAME_PREFIX = System.getProperty(
            "artifactoryObjectPrefix", Boolean.getBoolean("development") ? "generateddev-" : "generatedv2-");

    /**
     * List all permission targets whose name starts with the configured prefix.
     *
     * @see #toGeneratedPermissionTargetName(java.lang.String)
     * @link https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-GetPermissionTargets
     * @return all permission targets whose name starts with the configured prefix.
     */
    @NonNull
    public abstract List<String> listGeneratedPermissionTargets();

    /**
     * Creates or replaces a permission target.
     *
     * @param name the name of the permission target, used in URL
     * @param payloadFile {@see https://www.jfrog.com/confluence/display/RTF/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplacePermissionTarget}
     */
    public abstract void createOrReplacePermissionTarget(@NonNull String name, @NonNull File payloadFile);

    /**
     * Deletes a permission target in Artifactory.
     *
     * @param target Name of the permssion target
     */
    public abstract void deletePermissionTarget(@NonNull String target);

    /**
     * List all groups whose name starts with the configured prefix.
     *
     * @see #toGeneratedGroupName(java.lang.String)
     * @link https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-GetGroups
     * @return all groups whose name starts with the configured prefix.
     */
    @NonNull
    public abstract List<String> listGeneratedGroups();

    /**
     * Creates or replaces a group.
     *
     * @param name the name of the group, used in URL
     * @param payloadFile {@see https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-CreateorReplaceGroup}
     */
    public abstract void createOrReplaceGroup(@NonNull String name, @NonNull File payloadFile);

    public abstract void deleteGroup(@NonNull String group);

    /**
     * Generates a token scoped to the specified group.
     *
     * @link https://www.jfrog.com/confluence/display/JFROG/Artifactory+REST+API#ArtifactoryRESTAPI-CreateToken
     * @param group the group scope for the token
     * @return the token
     */
    @CheckForNull
    public abstract String generateTokenForGroup(
            @NonNull String username, @NonNull String group, long expiresInSeconds);

    /* Public instance-independent API */

    /**
     * Determines the name for the JSON API payload file, which is also used as the permission target name (with prefix).
     *
     * @param name the expected base name before transformation
     * @return the transformed name, including the prefix, and compatible with Artifactory
     */
    @NonNull
    public static String toGeneratedPermissionTargetName(@NonNull String name) {
        return toGeneratedName(ARTIFACTORY_OBJECT_NAME_PREFIX, name);
    }

    /**
     * Determines the name for the JSON API payload file, which is also used as the group name (with prefix).
     *
     * @param baseName the expected base name before transformation
     * @return the transformed name, including the prefix, and compatible with Artifactory
     */
    @NonNull
    public static String toGeneratedGroupName(@NonNull String baseName) {
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
    @NonNull
    public static String toTokenUsername(@NonNull String baseName) {
        return "CD-for-" + baseName.replaceAll("[ /]", "__");
    }

    private static String sha256(String str) {
        LOGGER.log(Level.INFO, "Computing sha256 for string: " + str);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        digest.update(str.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String toGeneratedName(String prefix, String name) {
        name = prefix + name.replaceAll("[ /]", "_");
        if (name.length() > 64) {
            // Artifactory has an undocumented max length for permission target names of 64 chars (and possibly other
            // types). If length is exceeded, use 55 chars of the prefix+name, separator, and 8 hopefully unique chars
            // (prefix of name's SHA-256).
            name = name.substring(0, 54) + '_' + sha256(name).substring(0, 7);
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

        private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        private static final Gson GSON = new Gson();

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
                    throw new IllegalStateException(
                            "You need at least Java 10 to run this unless dry-run mode is used");
                }

                BEARER_TOKEN = "Bearer " + token;
            }
        }

        private static final String BEARER_TOKEN;

        /**
         * Creates or replaces a permission target based on the provided payload.
         * @param name the name of the permission target
         * @param payloadFile the file containing the API payload.
         */
        @Override
        public void createOrReplacePermissionTarget(String name, File payloadFile) {
            createOrReplace(ARTIFACTORY_PERMISSIONS_API_URL, name, "permission target", payloadFile);
        }

        @Override
        public void deletePermissionTarget(String target) {
            delete(ARTIFACTORY_PERMISSIONS_API_URL, target, "permission target");
        }

        @NonNull
        @Override
        public List<String> listGeneratedPermissionTargets() {
            return list(ARTIFACTORY_PERMISSIONS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX);
        }

        @NonNull
        @Override
        public List<String> listGeneratedGroups() {
            return list(ARTIFACTORY_GROUPS_API_URL, ARTIFACTORY_OBJECT_NAME_PREFIX);
        }

        @Override
        public void createOrReplaceGroup(String name, File payloadFile) {
            createOrReplace(ARTIFACTORY_GROUPS_API_URL, name, "group", payloadFile);
        }

        @Override
        public void deleteGroup(String group) {
            delete(ARTIFACTORY_GROUPS_API_URL, group, "group");
        }

        @CheckForNull
        @Override
        public String generateTokenForGroup(@NonNull String username, @NonNull String group, long expiresInSeconds) {
            String params = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "&scope="
                    + URLEncoder.encode("applied-permissions/groups:readers," + group, StandardCharsets.UTF_8)
                    + "&expires_in="
                    + URLEncoder.encode(String.valueOf(expiresInSeconds), StandardCharsets.UTF_8);
            LOGGER.log(Level.INFO, "Generating token with request payload: " + params);

            return withRequest(
                    "POST",
                    ARTIFACTORY_TOKEN_API_URL,
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    BodyPublishers.ofString(params),
                    body -> {
                        JsonObject json = GSON.fromJson(body, JsonObject.class);
                        return json.get("access_token").getAsString();
                    });
        }

        private static List<String> list(String apiUrl, String prefix) {
            List<String> result = new ArrayList<>();
            withRequest("GET", apiUrl, Map.of(), BodyPublishers.noBody(), response -> {
                JsonArray root = GSON.fromJson(response, JsonArray.class);
                for (JsonElement element : root) {
                    if (element.isJsonObject()) {
                        JsonElement name = element.getAsJsonObject().get("name");
                        if (name != null && name.isJsonPrimitive()) {
                            String str = name.getAsString();
                            if (str.startsWith(prefix)) {
                                result.add(str);
                            }
                        }
                    }
                }
            });
            return result;
        }

        /**
         *
         * @param apiUrl The API base URL (does not include trailing '/')
         * @param name this is the full object name as provided by {@link #toGeneratedName}.
         * @param kind the human readable kind of object for log messages
         * @param payloadFile the file containing the payload
         */
        private static void createOrReplace(String apiUrl, String name, String kind, File payloadFile) {
            BodyPublisher body;
            try {
                body = BodyPublishers.ofFile(payloadFile.toPath());
            } catch (FileNotFoundException e) {
                throw new UncheckedIOException(e);
            }

            withRequest(
                    "PUT",
                    apiUrl + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8),
                    Map.of("Content-Type", "application/json"),
                    body);
        }

        /**
         * Deletes the specified {@code name} using {@code apiUrl}.
         * @param apiUrl the base URL to the deletion API
         * @param name the name of the object to delete
         * @param kind the human-readable kind of object being deleted (for a log message)
         */
        private static void delete(String apiUrl, String name, String kind) {
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
            withRequest("DELETE", apiUrl + '/' + encoded, Map.of(), BodyPublishers.noBody(), response -> {
                LOGGER.log(Level.INFO, response);
            });
        }

        private static void withRequest(String verb, String url, Map<String, String> headers, BodyPublisher body) {
            withRequest(verb, url, headers, body, response -> null);
        }

        private static void withRequest(
                String verb, String url, Map<String, String> headers, BodyPublisher body, Consumer<String> handler) {
            withRequest(verb, url, headers, body, response -> {
                handler.accept(response);
                return null;
            });
        }

        private static String withRequest(
                String verb,
                String url,
                Map<String, String> headers,
                BodyPublisher bodyPublisher,
                Function<String, String> handler) {

            if (DRY_RUN_MODE) {
                LOGGER.log(Level.INFO, "Dry-run mode: Skipping {0} call to {1}", new Object[] {verb, url});
                return null;
            }
            LOGGER.log(Level.INFO, "Sending {0} to {1}", new Object[] {verb, url});

            try {
                HttpRequest.Builder builder =
                        HttpRequest.newBuilder(URI.create(url)).method(verb, bodyPublisher);
                if (BEARER_TOKEN != null) {
                    builder.header("Authorization", BEARER_TOKEN);
                }
                headers.forEach(builder::header);

                HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());

                int code = response.statusCode();
                String body = response.body();
                if (code < 200 || code > 399) {
                    LOGGER.log(Level.INFO, "{0} request to {1} returned error: HTTP {2} {3}", new Object[] {
                        verb, url, code, body
                    });
                } else {
                    LOGGER.log(
                            Level.INFO, "{0} request to {1} returned: HTTP {2} {3}", new Object[] {verb, url, code, body
                            });
                }
                return handler.apply(response.body());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("HTTP call interrupted", e);
            }
        }
    }
}
