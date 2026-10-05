package dev.px.core.util.text;

import dev.px.core.render.Color;
import lombok.Getter;

/**
 * The legacy section-sign formatting codes, as an enum.
 *
 * <p>Version-independent in the way that matters: the codes themselves have not
 * changed since alpha, and stripping or writing one is character arithmetic, not
 * a call into the game. Core needs them because chat text arrives full of them
 * &mdash; measuring a line, matching a player name, or logging what was said all
 * want the codes gone first, and a HUD that mirrors chat wants them turned into
 * real colours.
 *
 * <p><b>Complexity.</b> {@link #strip}, {@link #translate} and {@link #lastColor}
 * are a single O(n) pass. {@link #byCode} and {@link #nearest} scan a fixed
 * twenty-two and sixteen entries, so both are O(1); {@link #isFormatted} is O(n)
 * but allocates, since it strips to compare.
 *
 * <p>The RGB values are the vanilla palette. {@link #getColor()} is null for the
 * five style codes, which have no colour of their own; {@link #isColor()} says
 * which is which.
 */
@Getter
public enum ChatColor {

    BLACK('0', 0x000000),
    DARK_BLUE('1', 0x0000AA),
    DARK_GREEN('2', 0x00AA00),
    DARK_AQUA('3', 0x00AAAA),
    DARK_RED('4', 0xAA0000),
    DARK_PURPLE('5', 0xAA00AA),
    GOLD('6', 0xFFAA00),
    GRAY('7', 0xAAAAAA),
    DARK_GRAY('8', 0x555555),
    BLUE('9', 0x5555FF),
    GREEN('a', 0x55FF55),
    AQUA('b', 0x55FFFF),
    RED('c', 0xFF5555),
    LIGHT_PURPLE('d', 0xFF55FF),
    YELLOW('e', 0xFFFF55),
    WHITE('f', 0xFFFFFF),

    OBFUSCATED('k'),
    BOLD('l'),
    STRIKETHROUGH('m'),
    UNDERLINE('n'),
    ITALIC('o'),
    RESET('r');

    /** The character the game reads as "a formatting code follows". */
    public static final char SECTION = '§';

    /** The character a user types instead, because the section sign is not on a keyboard. */
    public static final char ALTERNATE = '&';

    private final char code;
    private final Color color;

    ChatColor(char code, int rgb) {
        this.code = code;
        this.color = Color.rgb(rgb);
    }

    ChatColor(char code) {
        this.code = code;
        this.color = null;
    }

    /** @return whether this code sets a colour, as opposed to a style. */
    public boolean isColor() {
        return color != null;
    }

    /** @return whether this code sets a style, {@link #RESET} included. */
    public boolean isFormat() {
        return color == null;
    }

    /** @return the two-character sequence to write into a message. */
    @Override
    public String toString() {
        return SECTION + String.valueOf(code);
    }

    // -------------------------------------------------------------- lookup

    /** @return the code with that character, or {@code null}. Case-insensitive. */
    public static ChatColor byCode(char code) {
        char lower = Character.toLowerCase(code);
        for (ChatColor value : values()) {
            if (value.code == lower) {
                return value;
            }
        }
        return null;
    }

    /**
     * @return the code whose colour is closest to {@code color}
     *
     * <pre>
     * argmin over the sixteen of  (ΔR)² + (ΔG)² + (ΔB)²
     * </pre>
     *
     * <p>For the reverse direction: a user picks an arbitrary colour in a setting
     * and it has to be sent to the server, which only understands sixteen.
     * Nearest by squared RGB distance, which is crude but predictable.
     */
    public static ChatColor nearest(Color color) {
        ChatColor closest = WHITE;
        int best = Integer.MAX_VALUE;
        for (ChatColor value : values()) {
            if (!value.isColor()) {
                continue;
            }
            int red = value.color.getRed() - color.getRed();
            int green = value.color.getGreen() - color.getGreen();
            int blue = value.color.getBlue() - color.getBlue();
            int distance = red * red + green * green + blue * blue;
            if (distance < best) {
                best = distance;
                closest = value;
            }
        }
        return closest;
    }

    // ------------------------------------------------------------ rewriting

    /**
     * @return {@code text} with every formatting code removed
     *
     * <p>Run this before measuring chat text or comparing a name: the codes are
     * invisible on screen but they are still characters, so a coloured name that
     * looks eight characters wide measures as twelve.
     */
    public static String strip(String text) {
        if (text == null || text.indexOf(SECTION) < 0) {
            return text;
        }
        StringBuilder stripped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == SECTION && i + 1 < text.length() && byCode(text.charAt(i + 1)) != null) {
                i++;
                continue;
            }
            stripped.append(character);
        }
        return stripped.toString();
    }

    /**
     * @return {@code text} with {@code &} codes rewritten as section signs
     *
     * <p>What a user types in a config or a command becomes what the game renders.
     * A doubled {@code &&} escapes to a single literal ampersand, so a message can
     * still contain one.
     */
    public static String translate(String text) {
        if (text == null || text.indexOf(ALTERNATE) < 0) {
            return text;
        }
        StringBuilder translated = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character != ALTERNATE || i + 1 >= text.length()) {
                translated.append(character);
                continue;
            }
            char following = text.charAt(i + 1);
            if (following == ALTERNATE) {
                translated.append(ALTERNATE);
                i++;
            } else if (byCode(following) != null) {
                translated.append(SECTION).append(Character.toLowerCase(following));
                i++;
            } else {
                translated.append(character);
            }
        }
        return translated.toString();
    }

    /** @return whether {@code text} contains at least one formatting code. */
    public static boolean isFormatted(String text) {
        return text != null && !text.equals(strip(text));
    }

    /** @return the colour in effect at the end of {@code text}, or {@code null} if none was set. */
    public static Color lastColor(String text) {
        if (text == null) {
            return null;
        }
        Color current = null;
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != SECTION) {
                continue;
            }
            ChatColor found = byCode(text.charAt(i + 1));
            if (found == RESET) {
                current = null;
            } else if (found != null && found.isColor()) {
                current = found.color;
            }
        }
        return current;
    }
}
