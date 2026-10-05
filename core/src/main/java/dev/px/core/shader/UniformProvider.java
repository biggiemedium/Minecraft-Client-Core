package dev.px.core.shader;

/**
 * Fills in uniform values.
 *
 * <p>Used for both halves of a pass's inputs, because they are the same shape.
 * {@link ShaderService#addGlobalUniforms} takes one to contribute values every
 * shader gets &mdash; a camera matrix, a palette, the client's own clock &mdash;
 * and {@link ShaderService#use(String, UniformProvider, Runnable)} takes one for
 * the values this draw needs:
 *
 * <pre>{@code
 * Core.shaders().use("glow", u -> u.set("uRadius", radius)
 *                                  .set("uColour", theme.getPrimary()),
 *         () -> Render.rect(x, y, width, height, Color.WHITE));
 * }</pre>
 *
 * <p>Called once per pass, immediately before the uniforms are uploaded, so
 * reading a value that changes per frame here is correct and reading one that
 * never changes is merely wasteful.
 */
@FunctionalInterface
public interface UniformProvider {

    void contribute(Uniforms uniforms);
}
