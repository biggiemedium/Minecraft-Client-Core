package dev.px.core.shader;

import dev.px.core.util.Validate;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns the GLSL a client wrote into the GLSL a driver will accept.
 *
 * <p>Three chores, all of them pure text, none of them needing a GL context
 * &mdash; which is exactly why they belong in Core rather than in every client
 * that ever ships a shader:
 *
 * <ul>
 *   <li><b>{@code #include}</b>. GLSL has no such directive, so a lighting
 *       helper shared by four shaders is normally copied into all four. Includes
 *       are inlined here, resolved relative to the including file, and applied
 *       once each &mdash; a header pulled in twice would otherwise redeclare
 *       everything in it and fail to compile.
 *   <li><b>{@code #version}</b>. It must be the first non-comment line, which
 *       means an included header can never carry one and a file that does cannot
 *       have anything prepended to it. The first version directive found
 *       anywhere is hoisted to the top and later ones are dropped, so headers
 *       can declare the version they were written against without breaking the
 *       shaders that include them.
 *   <li><b>{@code #define}</b>. Compile-time constants from
 *       {@link ShaderSource}, injected below the version line, so sample counts
 *       and feature switches are configured in Java instead of by string
 *       concatenation at the call site.
 * </ul>
 *
 * <p>Line numbers in the assembled text match the listing
 * {@link #numbered(String)} produces, which is what {@link ShaderService} logs
 * when a compile fails &mdash; so a driver complaining about line 46 points at
 * something a reader can actually find.
 */
public final class ShaderPreprocessor {

    /** A cycle is already impossible, so this only catches a pathological tree. */
    public static final int MAX_INCLUDE_DEPTH = 32;

    private ShaderPreprocessor() {
    }

    /**
     * Assembles one stage.
     *
     * @param source the stage's own text, already read
     * @param defines compile-time constants, in declaration order; may be empty
     * @param loader how {@code #include} reads other files; may be null when the
     *        source has none
     * @param originPath the path {@code source} came from, so relative includes
     *        resolve against its folder; null for inline source
     * @throws ShaderException if an include is missing, unreadable, or nested
     *         beyond {@link #MAX_INCLUDE_DEPTH}
     */
    public static String process(String source, Map<String, String> defines,
                                 ShaderLoader loader, String originPath) {
        Validate.notNull(source, "source");

        StringBuilder body = new StringBuilder();
        String[] version = new String[1];

        Set<String> included = new LinkedHashSet<>();
        if (originPath != null) {
            included.add(ShaderIo.normalise(originPath));
        }
        inline(source, ShaderIo.parentOf(originPath), loader, included, 0, body, version);

        StringBuilder assembled = new StringBuilder();
        if (version[0] != null) {
            assembled.append(version[0]).append('\n');
        }
        if (defines != null) {
            for (Map.Entry<String, String> define : defines.entrySet()) {
                assembled.append("#define ").append(define.getKey());
                String value = define.getValue();
                if (value != null && !value.isEmpty()) {
                    assembled.append(' ').append(value);
                }
                assembled.append('\n');
            }
        }
        return assembled.append(body).toString();
    }

    /**
     * @return {@code glsl} with every line numbered, for a compile-failure log.
     *
     * <p>A driver reports errors by line and nothing else, and the line it means
     * is a line of the assembled source, which nobody has on disk. Printing the
     * listing alongside the error is the difference between a fixable message and
     * "0(46) : error C1503".
     */
    public static String numbered(String glsl) {
        if (glsl == null) {
            return "";
        }
        String[] lines = glsl.split("\r?\n", -1);
        int width = String.valueOf(lines.length).length();
        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String number = String.valueOf(i + 1);
            for (int pad = number.length(); pad < width; pad++) {
                listing.append(' ');
            }
            listing.append(number).append(" | ").append(lines[i]).append('\n');
        }
        return listing.toString();
    }

    // ------------------------------------------------------------ internals

    private static void inline(String source, String folder, ShaderLoader loader,
                               Set<String> included, int depth,
                               StringBuilder body, String[] version) {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new ShaderException("#include nested more than " + MAX_INCLUDE_DEPTH
                    + " deep; the last one was in " + (folder.isEmpty() ? "the root source" : folder));
        }
        for (String line : source.split("\r?\n", -1)) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#version")) {
                // Hoisted, and the line dropped rather than blanked: the header
                // puts one back, so the assembled line count is unchanged and the
                // driver's line numbers still match the listing.
                if (version[0] == null) {
                    version[0] = trimmed;
                }
                continue;
            }

            if (!trimmed.startsWith("#include")) {
                body.append(line).append('\n');
                continue;
            }

            String target = targetOf(trimmed);
            if (target == null) {
                throw new ShaderException("Malformed #include, expected a quoted path: " + trimmed);
            }

            String path = resolveInclude(folder, target, loader);
            if (!included.add(path)) {
                // Include-once. Recorded in the output so a reader of the listing
                // can see where the header actually came from.
                body.append("// #include \"").append(target).append("\" (already included)\n");
                continue;
            }
            if (loader == null) {
                throw new ShaderException("Shader includes \"" + target
                        + "\" but no ShaderLoader is installed to read it");
            }
            String text;
            try {
                text = loader.read(path);
            } catch (IOException e) {
                throw new ShaderException("Cannot read #include \"" + target + "\" (resolved to "
                        + path + ")", e);
            }
            body.append("// ---- begin ").append(path).append('\n');
            inline(text, ShaderIo.parentOf(path), loader, included, depth + 1, body, version);
            body.append("// ---- end ").append(path).append('\n');
        }
    }

    /**
     * Resolves an include against the including file's folder, then against the
     * loader root.
     *
     * <p>Both, because both spellings are natural: a sibling header is written
     * {@code "noise.glsl"} and a shared one {@code "lib/noise.glsl"}. Trying the
     * sibling first matches what every other language with includes does.
     */
    private static String resolveInclude(String folder, String target, ShaderLoader loader) {
        String flat = ShaderIo.normalise(target);
        if (folder.isEmpty() || loader == null) {
            return flat;
        }
        String sibling = ShaderIo.resolve(folder, flat);
        try {
            loader.read(sibling);
            return sibling;
        } catch (IOException notThere) {
            return flat;
        }
    }

    private static String targetOf(String line) {
        int start = line.indexOf('"');
        int end = start < 0 ? -1 : line.indexOf('"', start + 1);
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        start = line.indexOf('<');
        end = line.indexOf('>');
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        return null;
    }
}
