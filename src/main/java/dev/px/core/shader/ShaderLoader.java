package dev.px.core.shader;

import dev.px.core.util.Validate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Where GLSL text comes from.
 *
 * <p>Reading a text file is plain Java, so unlike a font or a texture this needs
 * neither the game nor the render backend &mdash; which is why Core does it
 * rather than making every client write the same resource-stream boilerplate. A
 * client supplies its own only when the source lives somewhere unusual: inside a
 * resource pack, behind a mod loader's asset API, or fetched at runtime.
 *
 * <pre>{@code
 * // shipped in the jar under assets/leapfrog/shaders/
 * shaders.setLoader(ShaderLoader.classpath("assets/leapfrog/shaders"));
 *
 * // during development, prefer a folder you can edit with the game running
 * shaders.setLoader(ShaderLoader.directory(devFolder)
 *         .orElse(ShaderLoader.classpath("assets/leapfrog/shaders")));
 * }</pre>
 *
 * <p>Paths are forward-slash separated and relative to whatever root the loader
 * was built with, on every platform. This is also what {@code #include} resolves
 * through, so a header shared by several shaders is read the same way they are.
 */
@FunctionalInterface
public interface ShaderLoader {

    /**
     * @return the text at {@code path}
     * @throws IOException if it is missing or unreadable; {@link ShaderService}
     *         reports it with the shader named and marks that shader failed
     */
    String read(String path) throws IOException;

    /**
     * Tries this loader first and {@code fallback} second.
     *
     * <p>The development arrangement: an editable folder over the shaders baked
     * into the jar, so saving a file and calling {@link ShaderService#reload()}
     * shows the change without a rebuild, while a shipped client that has no such
     * folder silently uses the jar.
     */
    default ShaderLoader orElse(ShaderLoader fallback) {
        Validate.notNull(fallback, "fallback");
        ShaderLoader first = this;
        return path -> {
            try {
                return first.read(path);
            } catch (IOException missing) {
                return fallback.read(path);
            }
        };
    }

    /** Reads from the classpath, relative to {@code root}. */
    static ShaderLoader classpath(String root) {
        return classpath(ShaderLoader.class.getClassLoader(), root);
    }

    /**
     * Reads from a specific classloader.
     *
     * <p>Worth knowing about on a modded setup: the loader that can see a mod's
     * own assets is the mod's, and the default above is Core's.
     */
    static ShaderLoader classpath(ClassLoader classLoader, String root) {
        Validate.notNull(classLoader, "class loader");
        String prefix = ShaderIo.normalise(root);
        return path -> {
            String resource = ShaderIo.resolve(prefix, path);
            InputStream stream = classLoader.getResourceAsStream(resource);
            if (stream == null) {
                throw new IOException("No shader resource on the classpath at " + resource);
            }
            return ShaderIo.readFully(stream);
        };
    }

    /** Reads from a directory on disk, relative to {@code root}. */
    static ShaderLoader directory(File root) {
        Validate.notNull(root, "root");
        return path -> {
            File file = new File(root, ShaderIo.normalise(path));
            if (!file.isFile()) {
                throw new IOException("No shader file at " + file.getAbsolutePath());
            }
            return ShaderIo.readFully(new FileInputStream(file));
        };
    }

    /**
     * Reads from a map of path to text.
     *
     * <p>For tests, and for a client that generates GLSL at runtime and wants
     * {@code #include} to be able to reach it.
     */
    static ShaderLoader of(Map<String, String> sources) {
        Validate.notNull(sources, "sources");
        Map<String, String> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            copy.put(ShaderIo.normalise(entry.getKey()), entry.getValue());
        }
        return path -> {
            String text = copy.get(ShaderIo.normalise(path));
            if (text == null) {
                throw new IOException("No shader source registered under " + path);
            }
            return text;
        };
    }

    /** A loader that has nothing, so every path-backed shader reports a clear failure. */
    static ShaderLoader none() {
        return path -> {
            throw new IOException("No ShaderLoader installed, so " + path + " cannot be read;"
                    + " call ShaderService.setLoader(...) or give the stage inline source");
        };
    }
}
