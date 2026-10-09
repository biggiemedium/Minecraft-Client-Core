package dev.px.navigation.plan;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.navigation.Gap;
import dev.px.core.util.math.PhysicsProfile;

import java.util.Collections;
import java.util.List;

/**
 * The fastest the player could close a {@link Gap}, for the planner's heuristic.
 *
 * <p>Across, up and down are each covered at their own top speed, and the gap
 * takes at least as long as the slowest of the three. The same reasoning as
 * Core's possible bound for other entities: a floor, never a guess, so the
 * search it guides still finds the quickest route.
 */
final class Pace {

    /** Ticks of falling simulated before a drop is called out of reach. */
    private static final int LONGEST_FALL = 400;

    /** Ticks of sprint-jumping measured for the top speed across. */
    private static final int MEASURED_TICKS = 120;

    /** Blocks a tick across, at most. */
    private final double across;
    /** Blocks a tick up, at most: a jump's first tick, or a step up. */
    private final double rise;
    private final double gravity;
    private final double drag;

    Pace(double across, double rise, double gravity, double drag) {
        this.across = across;
        this.rise = rise;
        this.gravity = gravity;
        this.drag = drag;
    }

    /**
     * @return the pace the profile's rules allow on ordinary ground, measured by
     *         sprint-jumping and sprinting across a flat floor until the speed
     *         settles
     */
    static Pace of(PhysicsProfile profile) {
        double top = Math.max(measure(profile, MovementInput.forward(0f).withSprint(true).withJump(true)),
                measure(profile, MovementInput.forward(0f).withSprint(true)));
        double rise = Math.max(profile.getJumpVelocity(), profile.getStepHeight());
        return new Pace(top, rise, profile.getGravity(), profile.getDrag());
    }

    /** @return the same pace with a different top speed across */
    Pace withAcross(double blocksPerTick) {
        return new Pace(blocksPerTick, rise, gravity, drag);
    }

    double getAcross() {
        return across;
    }

    /**
     * @param gap       how far there is to go, at least
     * @param velocityY how fast it is rising or falling now: a fall already under
     *                  way arrives sooner
     * @return the fewest ticks it could take
     */
    double ticks(Gap gap, double velocityY) {
        double ticks = across > 0d ? gap.getAcross() / across : 0d;
        if (gap.getRise() > 0d && rise > 0d) {
            ticks = Math.max(ticks, gap.getRise() / rise);
        }
        if (gap.getDrop() > 0d) {
            ticks = Math.max(ticks, fall(gap.getDrop(), velocityY));
        }
        return ticks;
    }

    /** @return the ticks an ordinary fall takes to drop {@code distance}, from {@code velocityY} */
    private double fall(double distance, double velocityY) {
        // Rising first only makes a fall later, so a fall from rest is the soonest from any upward start.
        double velocity = Math.min(0d, velocityY);
        double fallen = 0d;
        for (int tick = 1; tick <= LONGEST_FALL; tick++) {
            fallen -= velocity;
            if (fallen >= distance) {
                return tick - 1 + (distance - (fallen + velocity)) / -velocity;
            }
            velocity = (velocity - gravity) * drag;
        }
        return LONGEST_FALL;
    }

    private static double measure(PhysicsProfile profile, MovementInput input) {
        CollisionSpace floor = Pace::floor;
        MotionState state = MotionState.at(Vec3.ZERO);
        double fastest = 0d;
        for (int tick = 0; tick < MEASURED_TICKS; tick++) {
            MotionState next = Simulation.step(profile, state, input, floor);
            fastest = Math.max(fastest, next.getPosition().horizontalDistanceTo(state.getPosition()));
            state = next;
        }
        return fastest;
    }

    /** An endless flat floor with its top at y = 0. */
    private static List<Box> floor(Box region) {
        if (region.getMinY() > 0d) {
            return Collections.emptyList();
        }
        return Collections.singletonList(Box.of(region.getMinX() - 1d, -1d, region.getMinZ() - 1d,
                region.getMaxX() + 1d, 0d, region.getMaxZ() + 1d));
    }
}
