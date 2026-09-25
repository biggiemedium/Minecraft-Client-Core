package dev.px.core.network.server;

import java.util.Locale;

/**
 * What a server brand says the server is running.
 *
 * <p>Read from the brand the server sends on join, so it is what the server
 * chooses to say: most never change it, and a network that does will usually
 * show as {@link #UNKNOWN} rather than as something it is not.
 */
public enum ServerSoftware {

    VANILLA("Vanilla", Type.VANILLA, "vanilla"),

    // Forks before their parents: "Purpur" is also a Paper, and must match first.
    FOLIA("Folia", Type.PLUGIN, "folia"),
    PURPUR("Purpur", Type.PLUGIN, "purpur"),
    PUFFERFISH("Pufferfish", Type.PLUGIN, "pufferfish"),
    PAPER("Paper", Type.PLUGIN, "paper"),
    SPIGOT("Spigot", Type.PLUGIN, "spigot"),
    CRAFTBUKKIT("CraftBukkit", Type.PLUGIN, "craftbukkit", "bukkit"),

    NEOFORGE("NeoForge", Type.MODDED, "neoforge"),
    FORGE("Forge", Type.MODDED, "forge", "fml"),
    FABRIC("Fabric", Type.MODDED, "fabric"),
    QUILT("Quilt", Type.MODDED, "quilt"),

    VELOCITY("Velocity", Type.PROXY, "velocity"),
    WATERFALL("Waterfall", Type.PROXY, "waterfall"),
    BUNGEECORD("BungeeCord", Type.PROXY, "bungeecord"),

    /** A brand that names none of the above. */
    UNKNOWN("Unknown", Type.UNKNOWN);

    private final String displayName;
    private final Type type;
    private final String[] keywords;

    ServerSoftware(String displayName, Type type, String... keywords) {
        this.displayName = displayName;
        this.type = type;
        this.keywords = keywords;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Type getType() {
        return type;
    }

    /** @return whether this sits in front of other servers rather than running a world */
    public boolean isProxy() {
        return type == Type.PROXY;
    }

    /**
     * @return the software a piece of brand text names, or {@link #UNKNOWN}
     *
     * <p>Matches whole words, case-insensitively, so {@code "Paper"} and
     * {@code "paper (git-123)"} are Paper and {@code "Newspaper"} is not.
     */
    public static ServerSoftware identify(String text) {
        if (text == null || text.isEmpty()) {
            return UNKNOWN;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (ServerSoftware software : values()) {
            for (String keyword : software.keywords) {
                if (containsWord(lower, keyword)) {
                    return software;
                }
            }
        }
        return UNKNOWN;
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int at = text.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            int end = at + word.length();
            boolean startsWord = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean endsWord = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startsWord && endsWord) {
                return true;
            }
            from = at + 1;
        }
    }

    public enum Type {
        /** The unmodified game server. */
        VANILLA,
        /** Bukkit and its forks: plugins, not client mods. */
        PLUGIN,
        /** A mod loader on the server, which often wants the matching mods on the client. */
        MODDED,
        /** A proxy in front of the real servers; the brand usually names the one behind it too. */
        PROXY,
        UNKNOWN
    }
}
