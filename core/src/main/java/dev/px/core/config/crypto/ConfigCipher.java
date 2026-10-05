package dev.px.core.config.crypto;

import java.security.GeneralSecurityException;

/**
 * Encrypts a config section's file. Optional: nothing is encrypted unless you
 * give a section a cipher with {@link dev.px.core.config.ConfigService#encrypt}.
 *
 * <pre>{@code
 * SecretKey key = AesGcmCipher.deriveKey(passphrase, salt);          // your key, from wherever you keep it
 * Core.config().encrypt("accounts", AesGcmCipher.of(key));
 * }</pre>
 *
 * <p>Core ships {@link AesGcmCipher}, built on the JDK alone, and decides nothing
 * else. <b>The key is yours</b>: where it comes from decides what the encryption
 * is worth, and only you know what your users will accept.
 *
 * <ul>
 *   <li>A passphrase the player types, run through
 *       {@link AesGcmCipher#deriveKey}, protects the file from anyone without it.
 *   <li>A key from the operating system's credential store, behind your own
 *       implementation of this interface, protects it from other users of the
 *       machine.
 *   <li>A key saved in a file beside the configs only stops the file being read
 *       by accident &mdash; pasted into a support channel, synced to the wrong
 *       place. Anything that can read one can read the other.
 * </ul>
 *
 * <p>Implement this yourself to use any other scheme. {@code context} names where
 * the file lives, such as {@code "shared/accounts"}; an implementation that can
 * bind it into the ciphertext should, so a file copied into another section's
 * place does not decrypt there.
 */
public interface ConfigCipher {

    /** @return what to write to disk; any bytes your {@link #decrypt} accepts */
    byte[] encrypt(byte[] plaintext, String context) throws GeneralSecurityException;

    /**
     * @return the plaintext {@link #encrypt} was given
     * @throws GeneralSecurityException when the data was not written with this
     *         key, or was altered; Core then keeps the file aside and leaves the
     *         section at its defaults
     */
    byte[] decrypt(byte[] ciphertext, String context) throws GeneralSecurityException;
}
