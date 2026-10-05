package dev.px.core.movement.rotation;

import dev.px.core.math.Vec2;
import dev.px.core.util.Validate;
import dev.px.core.util.math.RotationMath;
import lombok.Getter;

/**
 * One module's claim on where the player looks.
 *
 * <p>A claim, not a command: several can be live at once and
 * {@link RotationService} resolves them. What a request carries is everything the
 * service needs to arbitrate and to turn &mdash; where, how badly, how fast, and
 * for how long.
 *
 * <pre>{@code
 * // the common case has a shorthand on the service and needs none of this
 * rotations.request(this, eye.rotationTo(target), RotationPriority.HIGH, 30f);
 *
 * // the long form, for anything else
 * rotations.request(this, RotationRequest.at(eye.rotationTo(target))
 *         .priority(RotationPriority.HIGHEST)
 *         .step(30f)
 *         .hold(3)
 *         .mode(RotationMode.SILENT));
 * }</pre>
 *
 * <h2>Holding, and why it expires</h2>
 *
 * <p>A request lasts {@link #hold(int)} ticks and is refreshed by asking
 * again. The default is one tick, so a module keeps the rotation by continuing to
 * want it and loses it by going quiet &mdash; which is what happens when it is
 * switched off, returns early, or throws.
 *
 * <p>That is deliberate. The alternative, acquire-and-release, has one failure
 * mode that matters: a module disabled while holding the rotation never releases,
 * and the player's head stays locked with nothing to blame. There is a
 * {@link RotationService#release} for letting go early, but nothing depends on it
 * being called.
 *
 * <p>Immutable. The fluent methods return copies, so a module can keep a template
 * and vary only the target.
 */
@Getter
public final class RotationRequest {

    /**
     * A step large enough to reach any rotation in one tick.
     *
     * <p>The default, because most callers have already smoothed their target
     * &mdash; often with {@link RotationMath#step} &mdash; and a second limit
     * applied on top of the first is a turn that never quite arrives.
     */
    public static final float SNAP = 360f;

    private final Vec2 target;
    private final int priority;
    private final float maxStep;
    private final int holdTicks;
    private final RotationMode mode;

    private RotationRequest(Vec2 target, int priority, float maxStep, int holdTicks, RotationMode mode) {
        this.target = target;
        this.priority = priority;
        this.maxStep = maxStep;
        this.holdTicks = holdTicks;
        this.mode = mode;
    }

    /**
     * A request to look at {@code target}, at {@link RotationPriority#NORMAL},
     * reached this tick, expiring at the end of it, with the camera following.
     */
    public static RotationRequest at(Vec2 target) {
        Validate.notNull(target, "target");
        return new RotationRequest(RotationMath.normalize(target),
                RotationPriority.NORMAL, SNAP, 1, RotationMode.CLIENT);
    }

    public static RotationRequest at(float yaw, float pitch) {
        return at(Vec2.rotation(yaw, pitch));
    }

    /** Higher wins. See {@link RotationPriority} for the conventional values. */
    public RotationRequest priority(int priority) {
        return new RotationRequest(target, priority, maxStep, holdTicks, mode);
    }

    /**
     * Caps how far the head turns in one tick, in degrees.
     *
     * <p>Applied per axis, taking the short way round on yaw. {@link #SNAP}, the
     * default, means no cap.
     */
    public RotationRequest step(float maxStep) {
        Validate.check(maxStep > 0f, "step must be greater than zero, got " + maxStep);
        return new RotationRequest(target, priority, maxStep, holdTicks, mode);
    }

    /**
     * How many ticks this claim survives without being asked for again.
     *
     * <p>One, the default, means "while I keep asking". Raise it for work that
     * spans a known number of ticks and should not be dropped if one of them is
     * skipped; there is no way to hold indefinitely, on purpose.
     */
    public RotationRequest hold(int ticks) {
        Validate.check(ticks >= 1, "hold must be at least one tick, got " + ticks);
        return new RotationRequest(target, priority, maxStep, ticks, mode);
    }

    public RotationRequest mode(RotationMode mode) {
        Validate.notNull(mode, "mode");
        return new RotationRequest(target, priority, maxStep, holdTicks, mode);
    }

    /** Same claim, new target. What a module varies every tick. */
    public RotationRequest retarget(Vec2 target) {
        Validate.notNull(target, "target");
        return new RotationRequest(RotationMath.normalize(target), priority, maxStep, holdTicks, mode);
    }

    @Override
    public String toString() {
        return "RotationRequest(yaw=" + target.getYaw() + ", pitch=" + target.getPitch()
                + ", priority=" + priority + ", step=" + (maxStep >= SNAP ? "snap" : maxStep)
                + ", hold=" + holdTicks + ", " + mode + ")";
    }
}
