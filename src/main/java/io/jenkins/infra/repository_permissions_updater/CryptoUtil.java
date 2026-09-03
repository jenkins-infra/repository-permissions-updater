package io.jenkins.infra.repository_permissions_updater;

import static com.neilalexander.jnacl.crypto.curve25519xsalsa20poly1305.*;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.kocakosm.jblake2.Blake2b;

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
    @NonNull
    public static String encryptSecret(@NonNull String plainText, @NonNull String base64PublicKey) {
        final byte[] plainTextBytes = plainText.getBytes(StandardCharsets.UTF_8);
        final byte[] publicKeyBytes = Base64.getDecoder().decode(base64PublicKey);
        if (publicKeyBytes.length != crypto_secretbox_PUBLICKEYBYTES) {
            throw new IllegalArgumentException("Public key must be " + crypto_secretbox_PUBLICKEYBYTES + " bytes");
        }

        final byte[] ephemeralPrivateKeyBytes = new byte[crypto_secretbox_SECRETKEYBYTES];
        final byte[] ephemeralPublicKeyBytes = new byte[crypto_secretbox_PUBLICKEYBYTES];
        crypto_box_keypair(ephemeralPublicKeyBytes, ephemeralPrivateKeyBytes);

        final byte[] nonce = new Blake2b(crypto_secretbox_NONCEBYTES)
                .update(ephemeralPublicKeyBytes)
                .update(publicKeyBytes)
                .digest();

        final byte[] buf = new byte[plainTextBytes.length + crypto_secretbox_ZEROBYTES];
        System.arraycopy(plainTextBytes, 0, buf, crypto_secretbox_ZEROBYTES, plainTextBytes.length);
        crypto_box(buf, buf, nonce, publicKeyBytes, ephemeralPrivateKeyBytes);

        final byte[] cipherTextResult =
                Arrays.copyOf(ephemeralPublicKeyBytes, buf.length + crypto_secretbox_BOXZEROBYTES);
        System.arraycopy(
                buf,
                crypto_secretbox_BOXZEROBYTES,
                cipherTextResult,
                crypto_secretbox_PUBLICKEYBYTES,
                plainTextBytes.length + crypto_secretbox_BOXZEROBYTES);
        return Base64.getEncoder().encodeToString(cipherTextResult);
    }

    private CryptoUtil() {
        /* prevent instantiation */
    }
}
