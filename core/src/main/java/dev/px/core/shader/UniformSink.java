package dev.px.core.shader;

import dev.px.core.render.Texture;

/**
 * Receives the uniform values a {@link Uniforms} was filled with.
 *
 * <p>This is the seam that keeps GL out of Core. {@link Uniforms} records
 * <em>what</em> the shader should be given; the backend implements this to say
 * <em>how</em> &mdash; {@code glUniform*}, a game-specific uniform wrapper, or a
 * recorder in a test. {@link ShaderBackend} extends it, so a backend is one
 * class and the uniform calls land on the currently bound program exactly as GL
 * already models them.
 *
 * <p>The arrays handed to these methods are the recorder's own scratch buffers,
 * reused between frames. Read what you need and upload it; do not keep the
 * reference.
 *
 * <p>Deliberately four methods rather than one per GLSL type. A backend that
 * switched over a dozen {@code uniform1f} / {@code uniform3fv} / {@code
 * uniformMatrix4fv} variants would have to be updated every time Core learned a
 * new one; grouping by component count means {@code vec2} and {@code vec4}
 * arrive through the same call and a backend is written once.
 */
public interface UniformSink {

    /**
     * A float uniform: {@code float}, {@code vec2}, {@code vec3} or {@code vec4}.
     *
     * @param count how many entries of {@code values} are live, 1 to 4
     */
    void floats(String name, float[] values, int count);

    /**
     * An integer uniform: {@code int}, {@code ivec2}, {@code ivec3}, {@code ivec4},
     * or a {@code bool} recorded as 0 or 1.
     *
     * @param count how many entries of {@code values} are live, 1 to 4
     */
    void ints(String name, int[] values, int count);

    /**
     * A square matrix, column-major, the layout GL expects.
     *
     * @param order 2, 3 or 4, so {@code values} holds {@code order * order} floats
     */
    void matrix(String name, float[] values, int order);

    /**
     * A sampler: bind {@code texture} to {@code unit} and set the uniform to the
     * unit index.
     *
     * <p>Two operations behind one call because they are never useful apart, and
     * splitting them is how a texture ends up bound to a unit no uniform points
     * at. The {@link Texture} is the same handle
     * {@link dev.px.core.render.Render2D} draws with, so an image loaded for the
     * HUD can be sampled by a shader without a second copy.
     */
    void sampler(String name, Texture texture, int unit);
}
