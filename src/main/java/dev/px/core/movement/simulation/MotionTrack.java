package dev.px.core.movement.simulation;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * One tracked thing's recent movement.
 *
 * <p>A bounded ring of samples and the answers derived from them. Bounded because
 * the alternative is a list that grows for as long as the client is open; a ring
 * of the last {@code n} ticks costs the same memory on minute one and hour six,
 * and nothing useful is ever asked about a position from ten seconds ago.
 *
 * <h2>Measured, not read</h2>
 *
 * <p>Velocity here is the difference between the last two positions, not a
 * motion field. For the local player those agree. For anything else they do not:
 * the client is not told another entity's velocity, only where the server says it
 * is, so measuring the delta is the only honest answer &mdash; and it is the one
 * that already includes whatever the server did to them.
 *
 * <p>That also means a sample is only as good as when it was taken. Record on the
 * tick, not on the frame: positions read during rendering are interpolated, which
 * is to say smoothed and slightly behind, and differencing them gives a velocity
 * that is both damped and late.
 */
public final class MotionTrack {

    /** What this track is for. An opaque key; Core never looks inside it. */
    @Getter
    private final Object key;

    private final Vec3[] positions;
    private final long[] ticks;
    private final boolean[] grounded;

    /** Index the next sample goes to. */
    private int head;

    @Getter
    private int sampleCount;

    MotionTrack(Object key, int capacity) {
        this.key = key;
        this.positions = new Vec3[capacity];
        this.ticks = new long[capacity];
        this.grounded = new boolean[capacity];
    }

    void record(Vec3 position, boolean onGround, long tick) {
        positions[head] = position;
        ticks[head] = tick;
        grounded[head] = onGround;
        head = (head + 1) % positions.length;
        if (sampleCount < positions.length) {
            sampleCount++;
        }
    }

    /** How many ticks of history this track can hold. */
    public int getCapacity() {
        return positions.length;
    }

    /** @return the most recent position, or null if nothing has been recorded. */
    public Vec3 getPosition() {
        return positionAgo(0);
    }

    /**
     * @param ticksAgo 0 for the newest sample, 1 for the one before it
     * @return that position, or null if the track does not go back that far
     */
    public Vec3 positionAgo(int ticksAgo) {
        if (ticksAgo < 0 || ticksAgo >= sampleCount) {
            return null;
        }
        return positions[indexAgo(ticksAgo)];
    }

    /** @return whether the most recent sample was on the ground. */
    public boolean isOnGround() {
        return sampleCount > 0 && grounded[indexAgo(0)];
    }

    /** @return the tick the newest sample was recorded on, or -1. */
    public long getLastTick() {
        return sampleCount == 0 ? -1L : ticks[indexAgo(0)];
    }

    /**
     * @return velocity in blocks per tick, from the last two samples
     *
     * <p>{@link Vec3#ZERO} with fewer than two samples. Divided by the gap between
     * their ticks, so a sample missed to lag reports the average over the gap
     * rather than a doubled speed.
     */
    public Vec3 getVelocity() {
        return averageVelocity(1);
    }

    /**
     * @return velocity averaged over the last {@code ticks} samples
     *
     * <p>Smoother, and later. Worth it for something that stutters; not worth it
     * for something that turns, because the average keeps pointing where it used
     * to go.
     */
    public Vec3 averageVelocity(int ticks) {
        Validate.check(ticks >= 1, "ticks must be at least 1, got " + ticks);
        int span = Math.min(ticks, sampleCount - 1);
        if (span < 1) {
            return Vec3.ZERO;
        }
        Vec3 newest = positions[indexAgo(0)];
        Vec3 oldest = positions[indexAgo(span)];
        long elapsed = this.ticks[indexAgo(0)] - this.ticks[indexAgo(span)];
        if (elapsed <= 0L) {
            return Vec3.ZERO;
        }
        return newest.subtract(oldest).scale(1d / elapsed);
    }

    /** @return the newest sample as a state a {@link Simulation} can step. */
    public MotionState toState() {
        Vec3 position = getPosition();
        if (position == null) {
            return null;
        }
        return MotionState.of(position, getVelocity(), isOnGround());
    }

    /**
     * @return where this would be in {@code ticks}, continuing in a straight line
     *
     * <p>No collision, no gravity, no assumption that anything keeps its footing
     * &mdash; the current velocity, extended. That is the right model for one or
     * two ticks of lag compensation and the wrong one for anything longer: a
     * player can change direction on any tick, and this cannot know that they did.
     *
     * <p>For the local player, where the input is known, {@link Simulation}
     * answers the same question far better.
     */
    public Vec3 extrapolate(int ticks) {
        Vec3 position = getPosition();
        if (position == null) {
            return null;
        }
        return position.add(getVelocity().scale(ticks));
    }

    /** @return how many ticks have passed since the last sample. */
    public long ticksSince(long now) {
        return sampleCount == 0 ? Long.MAX_VALUE : now - getLastTick();
    }

    private int indexAgo(int ticksAgo) {
        return ((head - 1 - ticksAgo) % positions.length + positions.length) % positions.length;
    }

    @Override
    public String toString() {
        return "MotionTrack(" + key + ", samples=" + sampleCount + ", at=" + getPosition() + ")";
    }
}
