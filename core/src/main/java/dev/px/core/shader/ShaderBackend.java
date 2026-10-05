package dev.px.core.shader;

import java.util.Map;

/**
 * The pluggable shader backend: the one class that owns OpenGL.
 *
 * <p>Everything else in this package is text, values and lifecycle. This is the
 * seam where a program is actually created and bound, and it is the client's to
 * implement &mdash; Core does not link GL, does not know which binding the host
 * uses, and does not know whether the client is on immediate-mode 2.1 or a core
 * profile. Write it once against your GL of choice and every shader the client
 * registers goes through it.
 *
 * <p>A sketch, with LWJGL:
 *
 * <pre>{@code
 * public final class GlShaderBackend implements ShaderBackend {
 *
 *     public Shader compile(ShaderSource source, Map<ShaderStage, String> glsl) {
 *         int program = glCreateProgram();
 *         for (Map.Entry<ShaderStage, String> stage : glsl.entrySet()) {
 *             int id = glCreateShader(typeOf(stage.getKey()));
 *             glShaderSource(id, stage.getValue());
 *             glCompileShader(id);
 *             if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
 *                 throw new ShaderException(stage.getKey() + ": " + glGetShaderInfoLog(id));
 *             }
 *             glAttachShader(program, id);
 *             glDeleteShader(id);
 *         }
 *         glLinkProgram(program);
 *         ...
 *         return new GlShader(source.getName(), program);   // caches locations
 *     }
 *
 *     public void bind(Shader shader)   { glUseProgram(((GlShader) shader).id); }
 *     public void unbind()              { glUseProgram(0); }
 *
 *     public void floats(String name, float[] values, int count) {
 *         switch (count) { case 1: glUniform1f(location(name), values[0]); break; ... }
 *     }
 *     ...
 * }
 * }</pre>
 *
 * <h2>Why it extends {@link UniformSink}</h2>
 *
 * <p>Uniforms in GL are set on whichever program is currently bound, and the
 * uniform calls need exactly the state a backend already holds: the program, its
 * cached locations, its texture units. Splitting them into a second object would
 * mean handing that state across, for no gain. So {@link ShaderService} binds,
 * walks the {@link Uniforms} straight into this, and unbinds.
 *
 * <p>Locations belong here too. Looking one up by name every frame is a driver
 * round trip per uniform; caching them on the {@link Shader} is the backend's job
 * because only the backend knows what a location is.
 */
public interface ShaderBackend extends UniformSink {

    /**
     * Builds a program.
     *
     * <p>Called once per shader, lazily, the first time something draws with it,
     * and again after {@link ShaderService#reload()}. It is therefore always
     * called on whichever thread is rendering, which is the thread that owns the
     * GL context.
     *
     * @param source the registration it came from &mdash; the name for error
     *        messages, the defines for diagnostics
     * @param glsl the final text per stage: includes already inlined, version
     *        hoisted, defines injected. Compile it as given
     * @return a valid handle; returning null or an invalid one is treated as a
     *         failure and reported the same way as throwing
     * @throws Exception with the driver's info log in the message. The service
     *         logs it once, with the numbered source, and marks the shader failed
     *         rather than retrying it every frame
     */
    Shader compile(ShaderSource source, Map<ShaderStage, String> glsl) throws Exception;

    /**
     * Makes {@code shader} the active program.
     *
     * <p>Only ever called with a handle this backend returned, and only after
     * {@link Shader#isValid()} has been checked.
     */
    void bind(Shader shader);

    /**
     * Restores whatever was active before any shader was bound &mdash; program 0
     * on a compatibility profile, the game's own shader on a core one.
     *
     * <p>{@link ShaderService} tracks nesting itself and re-binds the outer
     * program, so this is only called when the outermost pass ends. It must leave
     * the GL state the game expects, because the next thing to draw is the game.
     */
    void unbind();

    /**
     * @return whether this backend can compile the stage at all.
     *
     * <p>Consulted only for the message when a compile fails, so a client on a
     * 2.1 context that registers a geometry stage is told why rather than being
     * left with a driver error. Defaults to claiming vertex and fragment, which
     * every GL that has shaders supports.
     */
    default boolean supports(ShaderStage stage) {
        return stage == ShaderStage.VERTEX || stage == ShaderStage.FRAGMENT;
    }
}
