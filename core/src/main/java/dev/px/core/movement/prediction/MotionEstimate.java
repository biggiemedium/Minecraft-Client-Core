package dev.px.core.movement.prediction;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;

import java.util.Locale;

/**
 * What an entity is doing, as far as its movement shows: where it is, how it is
 * moving in the simulation's terms, and the keys that best explain how it got
 * there.
 *
 * <p>Nobody tells the client what another player is pressing. What it does know
 * is where they were each tick, so {@link PredictionService} tries every input
 * they could have held &mdash; standing, walking, sprinting or sneaking, straight
 * or diagonal, jumping or not &mdash; through the real movement rules, and keeps
 * whichever lands closest to where they actually went. {@link #getError()} says
 * how close that was: near zero when the rules explain the move, large when
 * something they do not model happened &mdash; knockback, water, a teleport.
 *
 * <p>The keys come back facing the way they moved, not the way they look: which
 * way their head points does not change where they go.
 *
 * <p>In the air the keys barely matter, so a jump's keys are carried from the
 * tick it took off: someone sprint-jumping is still sprint-jumping at the top of
 * the arc.
 *
 * <p>Immutable.
 */
public final class MotionEstimate {

    private final int ticksAgo;
    private final MotionState state;
    private final MovementInput input;
    private final double error;
    private final boolean known;
    private final Vec3 move;

    MotionEstimate(int ticksAgo, MotionState state, MovementInput input, double error, boolean known, Vec3 move) {
        this.ticksAgo = ticksAgo;
        this.state = state;
        this.input = input;
        this.error = error;
        this.known = known;
        this.move = move;
    }

    /**
     * @return how long ago this describes: 0 when this tick's position was news,
     *         more when the server has not sent one since
     */
    public int getTicksAgo() {
        return ticksAgo;
    }

    /**
     * @return where it was and how it was moving, as a {@code Simulation} steps
     *         from: the velocity is what carries into the next tick, after drag
     *         and friction, not the distance last moved
     */
    public MotionState getState() {
        return state;
    }

    /** @return the keys it is holding, as best they can be told; none when too little has been seen */
    public MovementInput getInput() {
        return input;
    }

    /**
     * @return how far, in blocks, the best-fitting keys missed where it went over
     *         the last tick; NaN when too little has been seen to fit any
     */
    public double getError() {
        return error;
    }

    /**
     * @return how far it moved per tick between the last two positions that were
     *         news: the distance, not the velocity the rules carry
     */
    public Vec3 getMove() {
        return move;
    }

    /** @return whether enough has been seen to fit keys at all */
    public boolean isFitted() {
        return !Double.isNaN(error);
    }

    /**
     * @return whether the keys are known: fitted on the ground, or in the air with
     *         the tick it took off still in its history. In the air without it,
     *         whether it jumped is a guess, and no jump is assumed
     */
    public boolean isKnown() {
        return known;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MotionEstimate(%s, %s, error %.4f)", state, input, error);
    }
}
