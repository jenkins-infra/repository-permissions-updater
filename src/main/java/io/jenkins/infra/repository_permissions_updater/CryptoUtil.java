package io.jenkins.infra.repository_permissions_updater;

import com.goterl.lazycode.lazysodium.LazySodium;
import com.goterl.lazycode.lazysodium.LazySodiumJava;
import com.goterl.lazycode.lazysodium.SodiumJava;
import com.goterl.lazycode.lazysodium.interfaces.Box;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CryptoUtil {

    /**
     * Encrypts a secret using the specified public key for use in GitHub.
     *
     * @link https://docs.github.com/en/free-pro-team@latest/rest/reference/actions#create-or-update-a-repository-secret
     * @link https://docs.github.com/en/free-pro-team@latest/actions/reference/encrypted-secrets
     *
     * @param plainText the plaintext to encrypt
     * @param base64PublicKey the public key, base64 encoded
     * @return the ciphertext, or {@code null} if it failed
     */
    @CheckForNull
    public static String encryptSecret(@NonNull String plainText, @NonNull String base64PublicKey) {
        final byte[] plainTextBytes = plainText.getBytes(StandardCharsets.UTF_8);
        final byte[] publicKeyBytes = Base64.getDecoder().decode(base64PublicKey);
        final int cipherMessageLength = plainTextBytes.length + Box.SEALBYTES;
        final byte[] cipherTextResult = new byte[cipherMessageLength];

        final SodiumJava sodium = new SodiumJava();
        final LazySodium lazySodium = new LazySodiumJava(sodium);
        if (!lazySodium.cryptoBoxSeal(cipherTextResult, plainTextBytes, plainTextBytes.length, publicKeyBytes)) {
            LOGGER.log(Level.INFO, "Failed to encrypt");
            return null;
        }
        return Base64.getEncoder().encodeToString(cipherTextResult);
    }

    private CryptoUtil() { /* prevent instantiation */ }
    private static final Logger LOGGER = Logger.getLogger(CryptoUtil.class.getName());
}
