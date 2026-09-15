package dev.px.core.account;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The outcome of a login attempt.
 *
 * <p>A result object rather than an exception because a failed login is an
 * ordinary outcome that the UI displays, not a fault. The message is meant to be
 * shown to the user directly.
 */
@Getter
@RequiredArgsConstructor
public final class AuthResult {

    private final boolean successful;
    private final String message;
    private final Session session;

    public static AuthResult success(Session session) {
        return new AuthResult(true, "Logged in as " + session.getUsername(), session);
    }

    public static AuthResult failure(String message) {
        return new AuthResult(false, message, null);
    }
}
