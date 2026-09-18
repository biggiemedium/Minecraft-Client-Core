package dev.px.core.shader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Path and stream chores shared by {@link ShaderLoader} and
 * {@link ShaderPreprocessor}.
 *
 * <p>A class rather than static methods on the interface because Core targets
 * Java 8, where an interface cannot hide a helper: every static it declares is
 * public API, and neither of these is something a client should ever call.
 */
final class ShaderIo {

    private ShaderIo() {
    }

    /**
     * Normalises a resource path: forward slashes, no leading or trailing one.
     *
     * <p>So that a root and a path join the same way whoever wrote them, and a
     * Windows path typed with backslashes still resolves against a jar.
     */
    static String normalise(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.replace('\\', '/').trim();
        int start = 0;
        int end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '/') {
            start++;
        }
        while (end > start && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(start, end);
    }

    /** Joins a root and a path, either of which may be empty. */
    static String resolve(String root, String path) {
        String left = normalise(root);
        String right = normalise(path);
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + "/" + right;
    }

    /** @return everything the path segment before the last slash, or "" at the root. */
    static String parentOf(String path) {
        String normalised = normalise(path);
        int slash = normalised.lastIndexOf('/');
        return slash < 0 ? "" : normalised.substring(0, slash);
    }

    /** Reads a stream as UTF-8 and closes it. */
    static String readFully(InputStream stream) throws IOException {
        StringBuilder text = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        try {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, read);
            }
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failed close on a read-only stream.
            }
        }
        return text.toString();
    }
}
