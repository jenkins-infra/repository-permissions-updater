package io.jenkins.infra.repository_permissions_updater;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class ArtifactoryAPI {
    /* Singleton support */
    private static ArtifactoryAPI INSTANCE = null;
    private static final String ARTIFACTORY_OBJECT_NAME_PREFIX = System.getProperty("artifactoryObjectPrefix", Boolean.getBoolean("development") ? "generateddev-" : "generatedv2-");
    private static final Logger LOGGER = Logger.getLogger(ArtifactoryAPI.class.getName());

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
     * @param baseName the expected base name before transformation
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

    private static String sha256(String str) {
        LOGGER.log(Level.INFO, "Computing sha256 for string: " + str);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            LOGGER.log(Level.SEVERE, "Could not find SHA-256 algorithm.", e);
            return null;
        }
        digest.update(str.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte aByte : digest.digest()) {
            result.append(String.format("%02X", aByte));
        }
        return result.toString();
    }

    private static String toGeneratedName(String prefix, String name) {
        name = prefix + name.replaceAll("[ /]", "_");
        if (name.length() > 64) {
            // Artifactory has an undocumented max length for permission target names of 64 chars (and possibly other types)
            // If length is exceeded, use 55 chars of the prefix+name, separator, and 8 hopefully unique chars (prefix of name's SHA-256)
            name = name.substring(0, 54) + '_' + sha256(name).substring(0, 7);
        }
        return name;
    }

    static synchronized ArtifactoryAPI getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ArtifactoryImpl();
        }
        return INSTANCE;
    }
}
