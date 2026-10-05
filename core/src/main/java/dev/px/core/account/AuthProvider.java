package dev.px.core.account;

/**
 * Performs logins and applies the resulting session to the game.
 *
 * <p>Both halves are outside Core on purpose. The OAuth flow needs an HTTP client
 * and a browser handoff, and applying a session means writing the game's own
 * session object, which differs by version.
 *
 * <p>Implementations are called from a background thread and may block.
 */
public interface AuthProvider {

    /**
     * Runs the interactive login flow for an account.
     *
     * <p>Called off the game thread, so it may open a browser and wait.
     */
    AuthResult authenticate(Account account);

    /** Builds an offline session. No network involved. */
    AuthResult authenticateOffline(String username);

    /**
     * Applies a session to the running game.
     *
     * <p>Must be called on the game thread; {@link AccountService} arranges that.
     */
    void apply(Session session);
}
