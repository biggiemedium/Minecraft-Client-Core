package dev.px.core.network.server;

import dev.px.core.util.Validate;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * The brand a server sent, and what it means.
 *
 * <p>A proxy puts itself in the brand alongside the server behind it, and the
 * two proxies in common use write it differently:
 *
 * <pre>
 * "Paper"                                        server Paper
 * "BungeeCord (git:...) &lt;- Paper"                proxy BungeeCord, server Paper
 * "Waterfall (git:...) &lt;- Purpur"                proxy Waterfall,  server Purpur
 * "Paper (Velocity)"                             proxy Velocity,   server Paper
 * </pre>
 *
 * <p>{@link #getSoftware()} is always the server behind any proxy, since that is
 * what runs the world the player is in; {@link #getProxy()} is the proxy, or
 * null when there is none.
 */
@Getter
@EqualsAndHashCode
public final class ServerBrand {

    private static final String BUNGEE_ARROW = "<-";

    /** Exactly what the server sent. */
    private final String raw;

    /** The server the player is on, behind any proxy. */
    private final ServerSoftware software;

    /** The proxy in front of it, or null. */
    private final ServerSoftware proxy;

    private ServerBrand(String raw, ServerSoftware software, ServerSoftware proxy) {
        this.raw = raw;
        this.software = software;
        this.proxy = proxy;
    }

    public static ServerBrand parse(String raw) {
        Validate.notNull(raw, "raw");
        String brand = raw.trim();

        int arrow = brand.indexOf(BUNGEE_ARROW);
        if (arrow >= 0) {
            ServerSoftware front = ServerSoftware.identify(brand.substring(0, arrow));
            ServerSoftware behind = ServerSoftware.identify(brand.substring(arrow + BUNGEE_ARROW.length()));
            return new ServerBrand(raw, behind, front.isProxy() ? front : ServerSoftware.BUNGEECORD);
        }

        int open = brand.lastIndexOf('(');
        if (open > 0 && brand.endsWith(")")) {
            ServerSoftware suffix = ServerSoftware.identify(brand.substring(open + 1, brand.length() - 1));
            if (suffix.isProxy()) {
                return new ServerBrand(raw, ServerSoftware.identify(brand.substring(0, open)), suffix);
            }
        }

        ServerSoftware only = ServerSoftware.identify(brand);
        return only.isProxy()
                ? new ServerBrand(raw, ServerSoftware.UNKNOWN, only)
                : new ServerBrand(raw, only, null);
    }

    public boolean isProxied() {
        return proxy != null;
    }

    @Override
    public String toString() {
        return raw;
    }
}
