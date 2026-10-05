package dev.px.core.movement.simulation;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.collect.CircularQueue;
import dev.px.core.util.math.PhysicsProfile;
import lombok.Getter;

import java.util.function.DoubleFunction;

/**
 * Measures the simulation against a real game, and finds the constant that makes
 * it fit.
 *
 * <p>{@link PhysicsProfile} ships the numbers Minecraft has used for most of its
 * life. They will not be exactly right on every version, and they are not exactly
 * right even on the one they came from: the model reproduces the game's formula
 * with the game's documented constants and still lands about two percent fast
 * horizontally. Two percent is small, systematic, and not something Core can
 * resolve by guessing harder.
 *
 * <p>So it is measured instead. The adapter records what actually happened over a
 * few hundred ticks, this replays each of those transitions through
 * {@link Simulation}, and the difference is a number rather than an opinion. Then
 * {@link #tune} scans one profile field for the value that minimises it.
 *
 * <pre>{@code
 * // collected from the running game, one entry per tick
 * calibration.record(before, heldInput, after);
 *
 * // how far off are we?
 * System.out.println(calibration.check(PhysicsProfile.vanilla(), world).describe());
 *
 * // what would fix it?
 * PhysicsProfile base = PhysicsProfile.vanilla();
 * PhysicsCalibration.Tuning best = calibration.tune(
 *         base::withMoveSpeedAttribute, 0.08d, 0.12d, 41, world);
 * System.out.println(best.describe());
 * }</pre>
 *
 * <p>This is a development tool, not something a shipped client runs. It holds
 * samples, replays them once per candidate value, and is meant to be driven from
 * a debug command while someone walks around in a straight line.
 *
 * <p><b>Sample quality is everything.</b> Record from a clean situation &mdash;
 * flat ground, no water, no ladders, no lag spikes &mdash; because a sample taken
 * during an unmodelled branch is noise the scan will happily fit a constant to.
 * {@link DriftMonitor} is the right way to find a clean stretch.
 */
public final class PhysicsCalibration {

    /** Samples kept. A minute at twenty ticks, which is more than a fit needs. */
    public static final int DEFAULT_CAPACITY = 1200;

    private final CircularQueue<Sample> samples;

    public PhysicsCalibration() {
        this(DEFAULT_CAPACITY);
    }

    public PhysicsCalibration(int capacity) {
        Validate.check(capacity >= 1, "capacity must be at least 1, got " + capacity);
        this.samples = CircularQueue.of(capacity);
    }

    /**
     * Records one observed transition.
     *
     * @param before the state at the start of the tick
     * @param input what was held during it
     * @param after where the game actually put them
     */
    public PhysicsCalibration record(MotionState before, MovementInput input, MotionState after) {
        Validate.notNull(before, "before");
        Validate.notNull(input, "input");
        Validate.notNull(after, "after");
        samples.add(new Sample(before, input, after));
        return this;
    }

    public int size() {
        return samples.size();
    }

    public void clear() {
        samples.clear();
    }

    /** @return how well {@code profile} reproduces what was recorded. */
    public Report check(PhysicsProfile profile, CollisionSpace space) {
        Validate.notNull(profile, "profile");
        Validate.notNull(space, "space");

        double horizontal = 0d;
        double vertical = 0d;
        double worst = 0d;
        int count = 0;

        for (Sample sample : samples) {
            Vec3 predicted = Simulation.step(profile, sample.before, sample.input, space).getPosition();
            Vec3 truth = sample.after.getPosition();

            double dx = predicted.getX() - truth.getX();
            double dy = predicted.getY() - truth.getY();
            double dz = predicted.getZ() - truth.getZ();

            horizontal += Math.sqrt(dx * dx + dz * dz);
            vertical += Math.abs(dy);
            worst = Math.max(worst, Math.sqrt(dx * dx + dy * dy + dz * dz));
            count++;
        }

        if (count == 0) {
            return new Report(0, 0d, 0d, 0d);
        }
        return new Report(count, horizontal / count, vertical / count, worst);
    }

    /**
     * Scans one profile field for the value that best reproduces the samples.
     *
     * <p>A plain linear sweep rather than an optimiser: the search space is one
     * dimension, the cost is dominated by replaying the samples, and a sweep
     * cannot converge on a local minimum that is not there. Narrow the range and
     * call it again to refine &mdash; two passes of forty steps resolve a constant
     * far past the precision the samples justify.
     *
     * @param vary builds a profile from a candidate value, usually a method
     *        reference such as {@code base::withMoveSpeedAttribute}
     * @param steps how many values to try across the range, at least two
     */
    public Tuning tune(DoubleFunction<PhysicsProfile> vary, double min, double max,
                       int steps, CollisionSpace space) {
        Validate.notNull(vary, "vary");
        Validate.check(steps >= 2, "steps must be at least 2, got " + steps);
        Validate.check(max > min, "max must be greater than min, got " + min + ".." + max);

        double bestValue = min;
        Report best = null;
        for (int step = 0; step < steps; step++) {
            double value = min + (max - min) * step / (steps - 1);
            Report report = check(vary.apply(value), space);
            if (best == null || report.getMeanError() < best.getMeanError()) {
                best = report;
                bestValue = value;
            }
        }
        return new Tuning(bestValue, best);
    }

    /** One observed transition, kept verbatim so it can be replayed against any profile. */
    private static final class Sample {

        private final MotionState before;
        private final MovementInput input;
        private final MotionState after;

        private Sample(MotionState before, MovementInput input, MotionState after) {
            this.before = before;
            this.input = input;
            this.after = after;
        }
    }

    /** How far a profile was from the recorded truth. */
    @Getter
    public static final class Report {

        private final int samples;
        private final double horizontalError;
        private final double verticalError;
        private final double worstError;

        Report(int samples, double horizontalError, double verticalError, double worstError) {
            this.samples = samples;
            this.horizontalError = horizontalError;
            this.verticalError = verticalError;
            this.worstError = worstError;
        }

        /** @return the two axes combined, which is what {@link #tune} minimises. */
        public double getMeanError() {
            return Math.sqrt(horizontalError * horizontalError + verticalError * verticalError);
        }

        /** @return a line fit for a debug command or a log. */
        public String describe() {
            return String.format(
                    "%d samples: mean %.6f b/t (h %.6f, v %.6f), worst %.6f",
                    samples, getMeanError(), horizontalError, verticalError, worstError);
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** The best value a scan found, and how well it did. */
    @Getter
    public static final class Tuning {

        private final double value;
        private final Report report;

        Tuning(double value, Report report) {
            this.value = value;
            this.report = report;
        }

        public String describe() {
            return String.format("best %.6f -> %s", value, report.describe());
        }

        @Override
        public String toString() {
            return describe();
        }
    }
}
