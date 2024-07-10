package io.jenkins.infra.repository_permissions_updater.helper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.jenkins.infra.repository_permissions_updater.ArtifactoryAPI;
import io.jenkins.infra.repository_permissions_updater.CryptoUtil;
import io.jenkins.infra.repository_permissions_updater.GitHubAPI;
import io.jenkins.infra.repository_permissions_updater.launcher.ArtifactoryPermissionsUpdater;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ArtifactoryHelper {

    /**
     * If enabled, will not send PUT/DELETE requests to Artifactory, only GET (i.e. not modifying).
     */
    private static final boolean DRY_RUN_MODE = Boolean.getBoolean("dryRun");

    private static final Gson gson = new Gson();
    static final Logger LOGGER = Logger.getLogger(ArtifactoryPermissionsUpdater.class.getName());

    /**
     * Set to true during development to prevent collisions with production behavior:
     *
     * - Different prefixes for groups and permission targets in {@link ArtifactoryAPI}.
     * - Different GitHub secret names in {@link #generateTokens(java.io.File)}
     * - Permissions are only granted for
     *
     */
    private static final boolean DEVELOPMENT = Boolean.getBoolean("development");

    /**
     * Takes a directory with Artifactory API payloads and submits them to the appropriate Artifactory API,
     * creating/updating the specified objects identified through the file name.
     *
     * @param payloadsDir the directory containing the payload file for the objects matching the file name without .json extension
     * @param kind the kind of object to create (used for log messages only)
     * @param creator the closure called to create or update an object. Takes two arguments, the {@code String} name and {@code File} payload file.
     */
    public static void submitArtifactoryObjects(File payloadsDir, String kind, BiConsumer<String, File> creator) {
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
     * Generates Artifactory access tokens for the Artifactory groups corresponding to the GitHub repo names, and then
     * attaches the token username and password to the GitHub repo as a secret.
     *
     * @param githubReposForCdIndex JSON file containing a list of GitHub repo names in the format 'orgname/reponame'
     */
    public static void generateTokens(File githubReposForCdIndex) throws IOException {
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
    public static void removeExtraArtifactoryObjects(File payloadsDir, String kind, Supplier<List<String>> lister, Consumer<String> deleter) throws IOException {
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
}
