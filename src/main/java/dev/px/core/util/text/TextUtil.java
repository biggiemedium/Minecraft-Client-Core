package dev.px.core.util.text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * String helpers for labels, chat output and command handling.
 *
 * <p>Deliberately not a home for anything a font can answer. Width-aware
 * truncation lives on {@link dev.px.core.render.font.Font#truncate}, because only
 * the font knows how wide a character is; what is here is either
 * character-counted or takes the measuring function as an argument, so none of
 * it needs a render backend to be installed.
 *
 * <p><b>Complexity.</b> The shaping and formatting helpers are O(n) in the text.
 * The two that are not: {@link #levenshtein} is O(|a| · |b|) time in O(|b|) space,
 * and {@link #closest} is that once per candidate.
 *
 * <p>Several of these exist because Java 8 does not have them and Core targets
 * Java 8 &mdash; {@link #repeat} is {@code String.repeat} from Java 11.
 */
public final class TextUtil {

    private static final String[] ROMAN_NUMERALS =
            { "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I" };
    private static final int[] ROMAN_VALUES =
            { 1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1 };

    private TextUtil() {
    }

    // -------------------------------------------------------------- shaping

    /**
     * @return {@code SOME_MODE} as {@code "Some Mode"}
     *
     * <p>Every GUI that shows an enum needs this, and writing display names by
     * hand next to the constants means they drift apart the first time one is
     * renamed. {@link dev.px.core.setting.impl.EnumSetting} labels its options
     * through here.
     */
    public static String prettify(String constantName) {
        if (constantName == null || constantName.isEmpty()) {
            return "";
        }
        String[] words = constantName.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder text = new StringBuilder(constantName.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }

    public static String prettify(Enum<?> constant) {
        return constant == null ? "" : prettify(constant.name());
    }

    public static String capitalize(String text) {
        return text == null || text.isEmpty()
                ? text
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Java 8 has no {@code String.repeat}. */
    public static String repeat(String text, int times) {
        if (text == null || times <= 0) {
            return "";
        }
        StringBuilder repeated = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            repeated.append(text);
        }
        return repeated.toString();
    }

    public static String repeat(char character, int times) {
        return repeat(String.valueOf(character), times);
    }

    /**
     * @return {@code text} cut to {@code maxLength} characters with an ellipsis
     *
     * <p>Counts characters, not pixels. For a label that must fit a panel use
     * {@link dev.px.core.render.font.Font#truncate}; use this for a log line, a
     * chat message, or anywhere there is no font to ask.
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return maxLength <= 3 ? text.substring(0, Math.max(0, maxLength)) : text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Breaks {@code text} into lines no wider than {@code maxWidth}.
     *
     * <p>Takes the measuring function rather than a {@code Font} so this stays
     * usable with no render backend installed, and so the same code can wrap to a
     * character count in a test:
     *
     * <pre>{@code
     * TextUtil.wrap(message, panelWidth, font::widthOf);
     * }</pre>
     *
     * <p>One {@code measure} call per word, so O(w) measurements for w words.
     *
     * <p>Words longer than the line are left overlong rather than broken mid-word;
     * a URL that does not fit is still more useful whole than in pieces.
     */
    public static List<String> wrap(String text, double maxWidth, ToDoubleFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() == 0) {
                line.append(word);
                continue;
            }
            String candidate = line + " " + word;
            if (measure.applyAsDouble(candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    // ------------------------------------------------------------ numbers

    /** @return {@code 1234567} as {@code "1,234,567"}. */
    public static String commas(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** @return the value at a fixed number of decimal places, locale-independent. */
    public static String decimals(double value, int places) {
        return String.format(Locale.ROOT, "%." + Math.max(0, places) + "f", value);
    }

    /**
     * @return a byte count as {@code "1.4 MB"}
     *
     * <p>Powers of 1024, labelled the way a file manager labels them.
     */
    public static String bytes(long count) {
        if (count < 1024L) {
            return count + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double scaled = count;
        int unit = -1;
        while (scaled >= 1024d && unit < units.length - 1) {
            scaled /= 1024d;
            unit++;
        }
        return decimals(scaled, scaled >= 100d ? 0 : 1) + " " + units[unit];
    }

    /**
     * @return a duration as {@code "2h 04m 09s"}, dropping empty leading units
     *
     * <p>For session uptime, a countdown, or how long a config took to load. Under
     * a second it reports milliseconds, since "0s" is not an answer.
     */
    public static String duration(long millis) {
        if (millis < 1000L) {
            return millis + "ms";
        }
        long seconds = millis / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainder = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, remainder);
        }
        return minutes > 0L
                ? String.format(Locale.ROOT, "%dm %02ds", minutes, remainder)
                : remainder + "s";
    }

    /**
     * @return {@code 2} as {@code "II"}, or an empty string for anything below 1
     *
     * <p>Potion levels are written this way everywhere the game writes them, so a
     * status-effect HUD that prints "Speed 2" looks wrong beside the game's own.
     */
    public static String roman(int value) {
        if (value < 1) {
            return "";
        }
        StringBuilder numeral = new StringBuilder();
        int remaining = value;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (remaining >= ROMAN_VALUES[i]) {
                remaining -= ROMAN_VALUES[i];
                numeral.append(ROMAN_NUMERALS[i]);
            }
        }
        return numeral.toString();
    }

    // ------------------------------------------------------------ matching

    public static boolean startsWithIgnoreCase(String text, String prefix) {
        return text != null && prefix != null
                && text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * @return how many single-character edits turn {@code a} into {@code b}
     *
     * <p>O(|a| · |b|) time, O(|b|) space:
     *
     * <pre>
     * D[i][j] = min( D[i-1][j] + 1,                        deletion
     *                D[i][j-1] + 1,                        insertion
     *                D[i-1][j-1] + (a[i] = b[j] ? 0 : 1) ) substitution
     * </pre>
     *
     * <p>The distance behind a "did you mean" suggestion. Iterative with two rows
     * rather than a full matrix, because the inputs are command and module names
     * and allocating a table per keystroke to compare two short words is waste.
     */
    public static int levenshtein(String a, String b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        String left = a.toLowerCase(Locale.ROOT);
        String right = b.toLowerCase(Locale.ROOT);
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * @return the closest option to {@code input}, or {@code null} if none is close
     *
     * <p>O(k · |input| · |option|) over k candidates, with the threshold
     * {@code max(2, ⌈(|input| + 2) / 3⌉)}.
     *
     * <p>"Close" is a third of the input's length, rounded up and never below two
     * edits: a typo in {@code sprint} should still find it, but an unrelated word
     * should not suggest anything at all. Suggesting nothing is a better answer
     * than suggesting the wrong thing confidently.
     */
    public static String closest(String input, Collection<String> options) {
        if (isBlank(input) || options == null || options.isEmpty()) {
            return null;
        }
        int limit = Math.max(2, (input.length() + 2) / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String option : options) {
            int distance = levenshtein(input, option);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = option;
            }
        }
        return bestDistance <= limit ? best : null;
    }
}
