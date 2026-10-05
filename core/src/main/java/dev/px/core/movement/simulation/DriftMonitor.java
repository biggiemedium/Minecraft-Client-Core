package dev.px.core.movement.simulation;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.collect.RollingAverage;
import dev.px.core.util.math.PhysicsProfile;
import lombok.Getter;
import lombok.Setter;

/**
 * Checks the simulation against reality, every tick, and says when to stop
 * trusting it.
 *
 * <p>{@link Simulation} models walking, falling and collision and openly does not
 * model water, ladders, elytra or half a dozen other branches. The danger is not
 * that those are missing &mdash; it is that a caller cannot tell when one of them
 * is happening, so a prediction goes from good to confidently wrong with nothing
 * to mark the transition.
 *
 * <p>This marks it. Each tick it simulates one step from the state it was last
 * given and compares that to where the player actually ended up. While the answer
 * is close the simulation is describing what the game is doing; the moment the
 * player steps into water, the error jumps and {@link #isReliable()} goes false.
 * A prediction can then decline to answer rather than guess, which is the
 * difference between a model that has gaps and a model that lies about them.
 *
 * <pre>{@code
 * // once a tick, in the adapter, with the input that was held since last tick
 * Core.simulation().observe(currentState, heldInput);
 *
 * if (Core.simulation().isReliable()) {
 *     MotionState landing = Core.simulation().landing(currentState, heldInput, 40);
 * }
 * }</pre>
 *
 * <p>Errors are tracked per axis, because the two fail for different reasons and
 * knowing which moved is most of the diagnosis. Vertical drift points at gravity,
 * drag or a collision shape; horizontal drift points at friction, the speed
 * attribute, or a movement branch that is not modelled. That split is not
 * hypothetical &mdash; it is what identifies a wrong
 * {@link PhysicsProfile#withMoveSpeedAttribute(double) speed attribute}, which shows up
 * as horizontal error with the vertical axis perfectly clean.
 *
 * <p>Not thread safe. Observe on the tick, from the game thread.
 */
public final class DriftMonitor {

    /** Ticks of error history kept. A second is long enough to see a trend. */
    public static final int DEFAULT_WINDOW = 20;

    /** Average error, in blocks per tick, at which the simulation stops being trusted. */
    public static final double DEFAULT_TOLERANCE = 0.01d;

    /**
     * A jump this large is a teleport, not a prediction failure.
     *
     * <p>Respawns, portals and server corrections move the player further in one
     * tick than any movement can. Folding one of those into the average would
     * report the simulation as broken for the next twenty ticks, so the sample is
     * dropped and the comparison starts again from the new position.
     */
    private static final double DISCONTINUITY_BLOCKS = 8d;

    /** Samples needed before {@link #isReliable()} will say yes. */
    private static final int MINIMUM_SAMPLES = 5;

    private final RollingAverage horizontal;
    private final RollingAverage vertical;

    /** The last observation, and the state the next prediction starts from. */
    @Getter
    private MotionState lastObserved;

    @Getter
    private double lastError;

    @Getter
    private double worstError;

    @Getter
    private long discontinuities;

    /**
     * Average error, in blocks per tick, below which the simulation counts as
     * describing reality.
     *
     * <p>A hundredth of a block a tick is a fifth of a block over a second, which
     * is tight enough that an unmodelled branch trips it immediately and loose
     * enough that floating-point and a two-percent constant do not.
     */
    @Getter
    @Setter
    private double tolerance = DEFAULT_TOLERANCE;

    public DriftMonitor() {
        this(DEFAULT_WINDOW);
    }

    public DriftMonitor(int window) {
        Validate.check(window >= 1, "window must be at least 1 tick, got " + window);
        this.horizontal = RollingAverage.of(window);
        this.vertical = RollingAverage.of(window);
    }

    /**
     * Records where the player actually is, and scores the last prediction.
     *
     * @param actual the state now
     * @param input the input that was held over the tick that just elapsed, not
     *        the one about to be held &mdash; this is scoring the step that
     *        already happened
     * @return whether a comparison was made. False on the first call, and after a
     *         discontinuity, because there is nothing to compare against
     */
    public boolean observe(MotionState actual, MovementInput input,
                           PhysicsProfile profile, CollisionSpace space) {
        Validate.notNull(actual, "actual");
        Validate.notNull(input, "input");

        MotionState previous = lastObserved;
        lastObserved = actual;
        if (previous == null) {
            return false;
        }
        if (previous.getPosition().distanceTo(actual.getPosition()) > DISCONTINUITY_BLOCKS) {
            discontinuities++;
            return false;
        }

        Vec3 predicted = Simulation.step(profile, previous, input, space).getPosition();
        Vec3 truth = actual.getPosition();

        double dx = predicted.getX() - truth.getX();
        double dy = predicted.getY() - truth.getY();
        double dz = predicted.getZ() - truth.getZ();

        horizontal.push(Math.sqrt(dx * dx + dz * dz));
        vertical.push(Math.abs(dy));

        lastError = Math.sqrt(dx * dx + dy * dy + dz * dz);
        worstError = Math.max(worstError, lastError);
        return true;
    }

    /** @return average horizontal error in blocks per tick. Friction, speed, or a branch. */
    public double getHorizontalError() {
        return horizontal.average();
    }

    /** @return average vertical error in blocks per tick. Gravity, drag, or a collision shape. */
    public double getVerticalError() {
        return vertical.average();
    }

    /** @return the two axes combined, which is the number {@link #isReliable()} judges. */
    public double getError() {
        double h = getHorizontalError();
        double v = getVerticalError();
        return Math.sqrt(h * h + v * v);
    }

    public int getSampleCount() {
        return horizontal.count();
    }

    /**
     * @return whether the simulation is currently describing what the game is
     *         doing
     *
     * <p>False until there is enough history to judge, so a caller that checks
     * this before predicting degrades to not predicting at startup rather than to
     * predicting badly.
     */
    public boolean isReliable() {
        return getSampleCount() >= MINIMUM_SAMPLES && getError() <= tolerance;
    }

    /** Forgets the history. Call on a world change, where nothing carries over. */
    public void reset() {
        horizontal.clear();
        vertical.clear();
        lastObserved = null;
        lastError = 0d;
        worstError = 0d;
    }

    @Override
    public String toString() {
        return String.format("DriftMonitor(h=%.5f, v=%.5f, worst=%.5f, n=%d, reliable=%s)",
                getHorizontalError(), getVerticalError(), worstError, getSampleCount(), isReliable());
    }
}
