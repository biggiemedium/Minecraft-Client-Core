package dev.px.core.movement.rotation;

/**
 * What a resolved rotation is allowed to move.
 *
 * <p>Core does not know the difference &mdash; it computes one rotation either
 * way and tags it. The tag travels to {@link RotationSink#apply}, which is where
 * the client decides what to write, because only the client knows what "the
 * camera" and "what the server is told" are on its version.
 */
public enum RotationMode {

    /**
     * Turn the player's head. The camera moves and the server sees it, because
     * they are the same rotation.
     */
    CLIENT,

    /**
     * Send the rotation without moving the camera.
     *
     * <p>The sink writes it into the outgoing position packet and leaves the
     * player's view alone. {@link RotationService} still eases back to the camera
     * rotation when the last request is released, so the server-side head does
     * not snap.
     */
    SILENT
}
