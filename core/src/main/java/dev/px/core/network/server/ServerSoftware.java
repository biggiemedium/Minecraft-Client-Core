package dev.px.core.network.server;

import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A piece of server software you want recognised from the brand a server sends.
 *
 * <p>Core ships none. Server software comes and goes faster than any library, so
 * a built-in list would go stale and name the wrong thing with confidence. You
 * register what your client cares about, with the words its brand contains:
 *
 * <pre>{@code
 * Core.server().registerSoftware(
 *         ServerSoftware.of("Purpur"),                   // matches the word "purpur"
 *         ServerSoftware.of("Paper"),
 *         ServerSoftware.of("Forge", "forge", "fml"),    // any of several words
 *         ServerSoftware.proxy("Velocity"),              // sits in front of other servers
 *         ServerSoftware.proxy("BungeeCord"));
 * }</pre>
 *
 * <p>A brand is matched against every registered piece of software, by whole
 * word and ignoring case, so {@code "Paper"} and {@code "paper (git-123)"} are
 * Paper and {@code "Newspaper"} is not. A proxy and the server behind it are
 * found independently, which is why no proxy's brand format needs knowing:
 * {@code "BungeeCord (git:...) <- Paper"} and {@code "Paper (Velocity)"} both name
 * one of each. Where a brand names two servers, the one registered first wins,
 * so register a fork before the software it forked.
 *
 * <p>Immutable. Equal when the name and whether it is a proxy are.
 */
public final class ServerSoftware {

    /** What a brand that names nothing registered is. */
    public static final ServerSoftware UNKNOWN = new ServerSoftware("Unknown", false, new String[0]);

    private final String name;
    private final boolean proxy;
    private final String[] keywords;

    private ServerSoftware(String name, boolean proxy, String[] keywords) {
        this.name = name;
        this.proxy = proxy;
        this.keywords = keywords;
    }

    /**
     * Server software that runs a world.
     *
     * @param keywords words its brand contains; just its name, lower-cased, when none are given
     */
    public static ServerSoftware of(String name, String... keywords) {
        return create(name, false, keywords);
    }

    /** A proxy: it sits in front of other servers, and its brand usually names the one behind it too. */
    public static ServerSoftware proxy(String name, String... keywords) {
        return create(name, true, keywords);
    }

    private static ServerSoftware create(String name, boolean proxy, String[] keywords) {
        Validate.notBlank(name, "name");
        String[] words = keywords.length == 0 ? new String[] { name } : keywords.clone();
        for (int i = 0; i < words.length; i++) {
            Validate.notBlank(words[i], "keyword");
            words[i] = words[i].trim().toLowerCase(Locale.ROOT);
        }
        return new ServerSoftware(name, proxy, words);
    }

    public String getName() {
        return name;
    }

    /** @return whether this sits in front of other servers rather than running a world */
    public boolean isProxy() {
        return proxy;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    public List<String> getKeywords() {
        return Collections.unmodifiableList(Arrays.asList(keywords));
    }

    /** @return whether {@code text} contains one of this software's words, as a whole word, ignoring case */
    public boolean matches(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (containsWord(lower, keyword)) {
                return true;
            }
        }
        return false;
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ServerSoftware)) {
            return false;
        }
        ServerSoftware that = (ServerSoftware) other;
        return proxy == that.proxy && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + (proxy ? 1 : 0);
    }

    @Override
    public String toString() {
        return proxy ? name + " (proxy)" : name;
    }
}
