package dev.px.core.account;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The result of a successful login, ready to be applied to the running game.
 *
 * <p>Core never applies it: swapping the session in place is version-specific,
 * so that is {@link AuthProvider#apply}. This type only carries the values
 * across the boundary.
 *
 * <p>The access token is held in memory only. It is deliberately excluded from
 * {@link #toString()} so it cannot reach a log file.
 */
@Getter
@RequiredArgsConstructor
public final class Session {

    private final String username;
    private final String uuid;
    private final String accessToken;
    private final AccountType type;

    @Override
    public String toString() {
        return "Session(" + username + ", " + type + ")";
    }
}
