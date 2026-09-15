package dev.px.core.social;

import dev.px.core.registry.Named;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * A remembered player.
 *
 * <p>Keyed on the account name rather than a UUID: a client only ever sees names
 * in the scoreboard, in chat, and on a nametag, and resolving a UUID needs a
 * network call that a targeting check cannot afford to make.
 */
@Getter
@RequiredArgsConstructor
@EqualsAndHashCode(of = "name")
public final class Friend implements Named {

    private final String name;

    /** Optional display name shown in place of the real one. */
    @Setter
    private String alias;

    public String getDisplayName() {
        return alias == null || alias.isEmpty() ? name : alias;
    }
}
