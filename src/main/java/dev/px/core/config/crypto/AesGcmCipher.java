package dev.px.core.config.crypto;

import dev.px.core.util.Validate;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-GCM, from the JDK: authenticated encryption for a config section.
 *
 * <pre>{@code
 * // a key from a passphrase the player chose, with a salt you keep (it need not be secret)
 * byte[] salt = AesGcmCipher.newSalt();
 * SecretKey key = AesGcmCipher.deriveKey(passphrase, salt);
 * Core.config().encrypt("accounts", AesGcmCipher.of(key));
 * }</pre>
 *
 * <p>Each write uses a fresh random nonce, and the section's location is bound
 * in as associated data, so a file that is altered, or moved into another
 * section's place, fails to decrypt rather than loading something else.
 *
 * <p>File format: one version byte, the 12-byte nonce, then the ciphertext with
 * its 16-byte tag.
 *
 * <p>Where the key comes from is yours to decide; see {@link ConfigCipher}. On
 * Java 8 builds before 8u161, a 256-bit key needs the unlimited-strength policy
 * installed; a 128-bit key works everywhere.
 *
 * <p>Thread-safe.
 */
public final class AesGcmCipher implements ConfigCipher {

    /** PBKDF2-HMAC-SHA256 rounds for {@link #deriveKey(char[], byte[])}, per current OWASP guidance. */
    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    private AesGcmCipher(SecretKey key) {
        this.key = key;
    }

    /** @param key an AES key of 128, 192 or 256 bits */
    public static AesGcmCipher of(SecretKey key) {
        Validate.notNull(key, "key");
        Validate.check("AES".equalsIgnoreCase(key.getAlgorithm()), "an AES key is needed, got " + key.getAlgorithm());
        byte[] encoded = key.getEncoded();
        if (encoded != null) {
            checkLength(encoded.length);
        }
        return new AesGcmCipher(key);
    }

    /** @param key 16, 24 or 32 bytes; copied */
    public static AesGcmCipher of(byte[] key) {
        Validate.notNull(key, "key");
        checkLength(key.length);
        return new AesGcmCipher(new SecretKeySpec(key.clone(), "AES"));
    }

    /** @return a new random 256-bit key, for you to store somewhere */
    public static SecretKey generateKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256, RANDOM);
        return generator.generateKey();
    }

    /** @return 16 random bytes: a salt for {@link #deriveKey}. Store it beside the config; it is not secret */
    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /** A 256-bit key from a passphrase, with {@link #DEFAULT_ITERATIONS} rounds. Slow on purpose. */
    public static SecretKey deriveKey(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        return deriveKey(passphrase, salt, DEFAULT_ITERATIONS);
    }

    /**
     * A 256-bit key from a passphrase, by PBKDF2-HMAC-SHA256. The same passphrase,
     * salt and rounds always give the same key.
     */
    public static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        Validate.notNull(passphrase, "passphrase");
        Validate.notNull(salt, "salt");
        Validate.check(salt.length >= 8, "a salt of at least 8 bytes is needed");
        Validate.check(iterations > 0, "iterations must be positive");
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(derived, "AES");
            } finally {
                Arrays.fill(derived, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    @Override
    public byte[] encrypt(byte[] plaintext, String context) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associated(context));
        byte[] sealed = cipher.doFinal(plaintext);
        byte[] out = new byte[1 + NONCE_BYTES + sealed.length];
        out[0] = VERSION;
        System.arraycopy(nonce, 0, out, 1, NONCE_BYTES);
        System.arraycopy(sealed, 0, out, 1 + NONCE_BYTES, sealed.length);
        return out;
    }

    @Override
    public byte[] decrypt(byte[] data, String context) throws GeneralSecurityException {
        if (data.length < 1 + NONCE_BYTES + TAG_BITS / 8 || data[0] != VERSION) {
            throw new GeneralSecurityException("not an AesGcmCipher file, or one from a newer version");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 1, NONCE_BYTES));
        cipher.updateAAD(associated(context));
        return cipher.doFinal(data, 1 + NONCE_BYTES, data.length - 1 - NONCE_BYTES);
    }

    private static byte[] associated(String context) {
        return (context == null ? "" : context).getBytes(StandardCharsets.UTF_8);
    }

    private static void checkLength(int bytes) {
        Validate.check(bytes == 16 || bytes == 24 || bytes == 32,
                "an AES key is 16, 24 or 32 bytes, got " + bytes);
    }
}
