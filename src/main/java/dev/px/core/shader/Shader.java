package dev.px.core.shader;

/**
 * An opaque handle to a compiled program owned by the active {@link ShaderBackend}.
 *
 * <p>The same arrangement as {@link dev.px.core.render.Texture} and
 * {@link dev.px.core.render.font.Font}: Core never touches a GL object. A
 * backend returns whatever it needs behind this interface &mdash; a program id,
 * a cache of uniform locations, a wrapper around the game's own shader class
 * &mdash; and Core hands it straight back when something asks to draw with it.
 *
 * <p>Handles are owned by {@link ShaderService}, which disposes every one of
 * them on shutdown and on {@link ShaderService#reload()}. A client that compiles
 * through the service never calls {@link #dispose()} itself.
 */
public interface Shader {

    /** The name the shader was registered under. */
    String getName();

    /**
     * @return whether the program is still usable.
     *
     * <p>False after {@link #dispose()}, and false for a program whose context
     * has gone away underneath it &mdash; a resource reload on some versions
     * destroys GL objects without telling anyone. {@link ShaderService} checks
     * this before every bind and recompiles rather than binding a dead program.
     */
    boolean isValid();

    /** Releases the program. Must be safe to call twice. */
    void dispose();
}
