package dev.px.core.network.server;

import dev.px.core.util.Validate;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where the player is connected, and what that server has said about itself.
 *
 * <p>Immutable: {@link ServerService} swaps in a new one as things are learned,
 * so a reference read once stays consistent however long it is held.
 */
@Getter
@EqualsAndHashCode
public final class ServerInfo {

    public static final int DEFAULT_PORT = 25565;

    /** Not connected to anything. */
    public static final ServerInfo DISCONNECTED =
            new ServerInfo(false, "", "", DEFAULT_PORT, null, Collections.<String>emptySet());

    private final boolean connected;

    /** The address as the adapter gave it, e.g. {@code "mc.hypixel.net:25565"}. Empty in singleplayer. */
    private final String address;

    /** Lower-case host with no port or trailing dot, e.g. {@code "mc.hypixel.net"}. Empty in singleplayer. */
    private final String host;

    private final int port;

    /** What the server says it runs, or null until it says. */
    private final ServerBrand brand;

    /** Plugin channels the server registered, in the order it registered them. */
    private final Set<String> channels;

    private ServerInfo(boolean connected, String address, String host, int port,
                       ServerBrand brand, Set<String> channels) {
        this.connected = connected;
        this.address = address;
        this.host = host;
        this.port = port;
        this.brand = brand;
        this.channels = channels;
    }

    /** @param address {@code host}, {@code host:port} or {@code [ipv6]:port}; empty or null for singleplayer */
    public static ServerInfo connected(String address) {
        String given = address == null ? "" : address.trim();
        String host = given;
        int port = DEFAULT_PORT;

        if (given.startsWith("[")) {
            int close = given.indexOf(']');
            if (close > 0) {
                host = given.substring(1, close);
                port = parsePort(given.substring(close + 1), port);
            }
        } else {
            int colon = given.indexOf(':');
            // Exactly one colon is host:port. More than one is a bare IPv6 address.
            if (colon >= 0 && colon == given.lastIndexOf(':')) {
                host = given.substring(0, colon);
                port = parsePort(given.substring(colon), port);
            }
        }

        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return new ServerInfo(true, given, host, port, null, Collections.<String>emptySet());
    }

    public boolean isSingleplayer() {
        return connected && host.isEmpty();
    }

    public boolean isMultiplayer() {
        return connected && !host.isEmpty();
    }

    /**
     * @return whether the player is on {@code domain} or a subdomain of it:
     *         {@code "hypixel.net"} matches {@code mc.hypixel.net}, and not
     *         {@code nothypixel.net}
     */
    public boolean isOn(String domain) {
        Validate.notNull(domain, "domain");
        String wanted = domain.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || host.isEmpty()) {
            return false;
        }
        return host.equals(wanted) || host.endsWith("." + wanted);
    }

    /** @return the brand's software, or {@link ServerSoftware#UNKNOWN} before the brand arrives */
    public ServerSoftware getSoftware() {
        return brand != null ? brand.getSoftware() : ServerSoftware.UNKNOWN;
    }

    /** @return whether the server registered {@code channel}, ignoring case */
    public boolean hasChannel(String channel) {
        for (String registered : channels) {
            if (registered.equalsIgnoreCase(channel)) {
                return true;
            }
        }
        return false;
    }

    public ServerInfo withBrand(ServerBrand brand) {
        return new ServerInfo(connected, address, host, port, brand, channels);
    }

    /** @return a copy with {@code added} registered as well as the channels already known */
    public ServerInfo withChannels(Collection<String> added) {
        Set<String> merged = new LinkedHashSet<>(channels);
        merged.addAll(added);
        return new ServerInfo(connected, address, host, port, brand, Collections.unmodifiableSet(merged));
    }

    private static int parsePort(String suffix, int fallback) {
        if (!suffix.startsWith(":") || suffix.length() < 2) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(suffix.substring(1));
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        if (!connected) {
            return "ServerInfo(disconnected)";
        }
        if (isSingleplayer()) {
            return "ServerInfo(singleplayer" + (brand != null ? ", " + brand : "") + ")";
        }
        return "ServerInfo(" + host + ":" + port + (brand != null ? ", " + brand : "") + ")";
    }
}
