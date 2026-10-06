package dev.px.core.movement.prediction;

import dev.px.core.math.Box;

/**
 * How fast an entity could possibly get anywhere: the fastest of what its rules
 * allow and what it has been seen to do, for {@link Prediction#earliestPossible}.
 */
final class Envelope {

    /** Ticks of falling simulated before a drop is called out of reach. */
    private static final int LONGEST_FALL = 400;

    private final Box box;
    private final double speed;
    private final double rise;
    private final double drop;
    private final double gravity;
    private final double drag;
    /** How fast it is already falling, blocks per tick; 0 when it is not. */
    private final double falling;

    /**
     * @param box     its box, where its futures start
     * @param speed   blocks per tick it could cover sideways
     * @param rise    blocks per tick it could climb
     * @param drop    blocks per tick it has been seen to drop; 0 if never
     * @param gravity the falling rules, for how fast an ordinary fall gets anywhere
     * @param velocityY its vertical velocity now: a fall already under way gets there sooner
     */
    Envelope(Box box, double speed, double rise, double drop, double gravity, double drag, double velocityY) {
        this.box = box;
        this.speed = speed;
        this.rise = rise;
        this.drop = drop;
        this.gravity = gravity;
        this.drag = drag;
        // Rising first only makes a fall later, so a fall from rest is the soonest from any upward start.
        this.falling = Math.max(0d, -velocityY);
    }

    int earliest(Box target, int age) {
        double sx = Math.max(0d, Math.max(target.getMinX() - box.getMaxX(), box.getMinX() - target.getMaxX()));
        double sz = Math.max(0d, Math.max(target.getMinZ() - box.getMaxZ(), box.getMinZ() - target.getMaxZ()));
        double across = Math.sqrt(sx * sx + sz * sz);
        long sideways = across <= 0d ? 0L : speed > 0d ? (long) Math.ceil(across / speed) : Long.MAX_VALUE;

        double down = box.getMinY() - target.getMaxY();
        double up = target.getMinY() - box.getMaxY();
        long vertical = 0L;
        if (down > 0d) {
            vertical = fall(down);
        } else if (up > 0d) {
            vertical = rise > 0d ? (long) Math.ceil(up / rise) : Long.MAX_VALUE;
        }
        long ticks = Math.max(sideways, vertical);
        if (ticks == Long.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, ticks - age));
    }

    /** @return the ticks to drop {@code distance}: an ordinary fall, or as fast as it has dropped, whichever is sooner */
    private long fall(double distance) {
        long ordinary = Long.MAX_VALUE;
        double velocity = -falling;
        double fallen = 0d;
        for (int tick = 1; tick <= LONGEST_FALL; tick++) {
            fallen -= velocity;
            if (fallen >= distance) {
                ordinary = tick;
                break;
            }
            velocity = (velocity - gravity) * drag;
        }
        long dropping = drop > 0d ? (long) Math.ceil(distance / drop) : Long.MAX_VALUE;
        return Math.min(ordinary, dropping);
    }
}
