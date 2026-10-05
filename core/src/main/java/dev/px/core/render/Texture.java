package dev.px.core.render;

/**
 * An opaque handle to an image owned by the active {@link Render2D} backend.
 *
 * <p>Core never touches pixels. A backend returns whatever it needs (a GL texture
 * id, a NanoVG image handle, a Skija image) wrapped in an implementation of this,
 * and Core passes it straight back on draw.
 */
public interface Texture {

    int getWidth();

    int getHeight();

    /** @return whether the handle is still valid. False after the backend is torn down. */
    boolean isValid();

    /** Releases the underlying resource. Drawing with a disposed texture is a no-op. */
    void dispose();
}
