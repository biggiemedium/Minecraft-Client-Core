package dev.px.core.movement.rotation;

import dev.px.core.math.Vec2;

/**
 * Reads and writes the player's rotation.
 *
 * <p>Two methods, and they are the only part of rotation handling that needs the
 * game. {@link RotationService} decides <em>what</em> the rotation should be
 * &mdash; arbitration between modules, stepping, sensitivity snapping, easing
 * back on release &mdash; and this decides <em>how</em> it is written, which
 * differs by version and by whether the client wants the camera to move.
 *
 * <p>The same split as {@link dev.px.core.util.spatial.PathSpace} and
 * {@link dev.px.core.shader.ShaderBackend}: the algorithm stays in Core, the
 * game-shaped part is one small interface. Note it lives here and not on
 * {@link dev.px.core.platform.Platform}, which stays free of player, world and
 * packets.
 *
 * <pre>{@code
 * public final class PlayerRotationSink implements RotationSink {
 *
 *     public Vec2 getRotation() {
 *         EntityPlayerSP player = mc.thePlayer;
 *         return player == null ? Vec2.ZERO : Vec2.rotation(player.rotationYaw, player.rotationPitch);
 *     }
 *
 *     public void apply(Vec2 rotation, RotationMode mode) {
 *         if (mode == RotationMode.CLIENT) {
 *             mc.thePlayer.rotationYaw = rotation.getYaw();
 *             mc.thePlayer.rotationPitch = rotation.getPitch();
 *         } else {
 *             pendingPacketRotation = rotation;      // written into C03 on send
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Both methods are called on the game thread, once per tick at most, and must
 * not block. {@link #getRotation()} is called more than once per tick and should
 * be a field read rather than anything that computes.
 */
public interface RotationSink {

    /**
     * @return where the player is actually looking right now
     *
     * <p>The rotation the camera shows, not whatever was last sent to the server.
     * This is both the starting point when a rotation is first acquired and the
     * destination it eases back to when the last one is released, so returning a
     * stale or synthetic value makes turns start and end in the wrong place.
     *
     * <p>Must never return null. With no player in the world, return
     * {@link Vec2#ZERO} or the last known rotation.
     */
    Vec2 getRotation();

    /**
     * Writes a resolved rotation.
     *
     * <p>Called only while {@link RotationService#isActive()} &mdash; while some
     * module holds the rotation, and through the easing that follows the last
     * release. It is never called to hand control back; the easing arriving at the
     * player's own rotation is what that looks like.
     *
     * <p>Already normalised: yaw wrapped to -180..180 and pitch clamped to
     * &plusmn;90. No further conditioning is needed or wanted.
     */
    void apply(Vec2 rotation, RotationMode mode);
}
