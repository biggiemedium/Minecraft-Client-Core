package dev.px.core.network.server;

import dev.px.core.util.Validate;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collections;

/**
 * The brand a server sent, and which of your registered {@link ServerSoftware}
 * it names.
 *
 * <p>{@link #getSoftware()} is the server that runs the world the player is in,
 * behind any proxy; {@link #getProxy()} is the proxy in front of it, or null.
 * Both are found by matching the whole brand against what was registered, so a
 * brand naming nothing registered is {@link ServerSoftware#UNKNOWN}, and the raw
 * text is always kept.
 */
@Getter
@EqualsAndHashCode
public final class ServerBrand {

    /** Exactly what the server sent. */
    private final String raw;

    /** The server the player is on, behind any proxy; {@link ServerSoftware#UNKNOWN} when nothing registered matched. */
    private final ServerSoftware software;

    /** The proxy in front of it, or null. */
    private final ServerSoftware proxy;

    private ServerBrand(String raw, ServerSoftware software, ServerSoftware proxy) {
        this.raw = raw;
        this.software = software;
        this.proxy = proxy;
    }

    /**
     * @param known the software to recognise, in priority order: where the brand
     *        names two servers, or two proxies, the first wins
     */
    public static ServerBrand parse(String raw, Iterable<ServerSoftware> known) {
        Validate.notNull(raw, "raw");
        Validate.notNull(known, "known");
        ServerSoftware software = null;
        ServerSoftware proxy = null;
        for (ServerSoftware candidate : known) {
            if (!candidate.matches(raw)) {
                continue;
            }
            if (candidate.isProxy()) {
                proxy = proxy == null ? candidate : proxy;
            } else {
                software = software == null ? candidate : software;
            }
        }
        return new ServerBrand(raw, software != null ? software : ServerSoftware.UNKNOWN, proxy);
    }

    /** A brand recognising nothing: only the raw text. */
    public static ServerBrand of(String raw) {
        return parse(raw, Collections.<ServerSoftware>emptyList());
    }

    public boolean isProxied() {
        return proxy != null;
    }

    @Override
    public String toString() {
        return raw;
    }
}
