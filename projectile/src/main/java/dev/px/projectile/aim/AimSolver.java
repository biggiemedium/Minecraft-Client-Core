package dev.px.projectile.aim;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.MathUtil;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Lookahead;
import dev.px.core.util.Validate;
import dev.px.projectile.Launch;
import dev.px.projectile.LaunchRules;
import dev.px.projectile.Shooter;
import dev.px.projectile.flight.Flight;
import dev.px.projectile.flight.Hit;
import dev.px.projectile.flight.Motion;
import dev.px.projectile.flight.Trajectory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The rotation that lands a projectile on a point, or on someone where they
 * will be when it gets there.
 *
 * <pre>{@code
 * AimSolver bowAim = AimSolver.builder(arrowFlight, bow)
 *         .lookahead(Lookahead.predicted(Core.prediction()))   // where they will be; where they are unless set
 *         .aimPoint(AimPoint.CENTRE)
 *         .arcs(Arc.LOW, Arc.HIGH)                             // flat, or over the wall if flat is blocked
 *         .build();
 *
 * Aim shot = bowAim.at(you, target);
 * if (shot != null) {
 *     Core.rotations().request(this, shot.getRotation());
 * } else {
 *     log(bowAim.getLastStats());                          // out of range, blocked, or would not settle
 * }
 * }</pre>
 *
 * <p>For a point: it turns to face it, then finds the pitch at which the
 * projectile passes through it, flying its {@link Motion} through nothing with
 * the launch your {@link LaunchRules} give. If the shooter's own movement pushes
 * the path sideways, it turns again by how far it missed, until it does not.
 * The low and high arcs are the two pitches either side of the one that carries
 * highest over the point.
 *
 * <p>For a target: it asks your {@link Lookahead} where the target will be when
 * the projectile arrives, aims there, and asks again with the new flight time,
 * until the time stops changing. Then it flies the aim through the world with
 * its {@link Flight}, and keeps it only if the projectile reaches the target
 * before anything else stops it; otherwise it tries the next arc.
 *
 * <p>It aims with the power your launch rules give now: for a bow, re-aim each
 * tick as it draws. Turning, and deciding when to let go, are yours.
 *
 * <p>Remembers its last stats between calls, otherwise holds nothing. Game thread only.
 */
public final class AimSolver {

    /** How many times the target's position is looked up before an aim gives up settling: a tuning knob. */
    public static final int DEFAULT_MAX_ROUNDS = 8;

    /** Degrees between the pitches tried first, before the search narrows. */
    private static final double SCAN_STEP = 3d;
    /** Halvings when narrowing a pitch down. */
    private static final int REFINE_STEPS = 30;
    /** Turns allowed to correct a sideways miss. */
    private static final int YAW_ROUNDS = 6;
    /** A sideways miss small enough to stop turning, in degrees. */
    private static final double YAW_TOLERANCE = 1e-4d;

    private final Flight flight;
    private final LaunchRules launching;
    private final Lookahead<Object> lookahead;
    private final AimPoint aimPoint;
    private final List<Arc> arcs;
    private final IntSupplier delay;
    private final int maxRounds;

    private AimStats lastStats;
    private int motions;
    private int flights;
    private int rounds;
    private int blocked;

    private AimSolver(Builder builder) {
        this.flight = builder.flight;
        this.launching = builder.launching;
        this.lookahead = builder.lookahead;
        this.aimPoint = builder.aimPoint;
        this.arcs = builder.arcs;
        this.delay = builder.delay;
        this.maxRounds = builder.maxRounds;
    }

    /**
     * @param flight    how the projectile flies and what it can hit
     * @param launching how it leaves the shooter
     */
    public static Builder builder(Flight flight, LaunchRules launching) {
        return new Builder(flight, launching);
    }

    /** @return what the last aim found and why; {@code null} before the first */
    public AimStats getLastStats() {
        return lastStats;
    }

    /**
     * @return the aim that lands it on {@code point}, passing nothing that stops
     *         it first; {@code null} if there is none, and {@link #getLastStats()}
     *         says why
     */
    public Aim at(Shooter shooter, Vec3 point) {
        Validate.notNull(shooter, "shooter");
        Validate.notNull(point, "point");
        begin();
        int wait = Math.max(0, delay.getAsInt());
        int limit = flight.currentMaxTicks();
        for (Arc arc : arcs) {
            Solution solution = solve(shooter, point, arc, limit);
            if (solution == null) {
                continue;
            }
            Aim aim = check(shooter, solution, arc, point, null, null, wait);
            if (aim != null) {
                return finish(AimStats.Outcome.AIMED, aim);
            }
            blocked++;
        }
        return finish(blocked > 0 ? AimStats.Outcome.BLOCKED : AimStats.Outcome.OUT_OF_RANGE, null);
    }

    /**
     * @return the aim that lands it on {@code target} where it will be when it
     *         arrives, passing nothing that stops it first; {@code null} if
     *         there is none, and {@link #getLastStats()} says why
     */
    public Aim at(Shooter shooter, Tracked<?> target) {
        Validate.notNull(shooter, "shooter");
        Validate.notNull(target, "target");
        begin();
        int wait = Math.max(0, delay.getAsInt());
        int limit = flight.currentMaxTicks();
        boolean unsettled = false;
        for (Arc arc : arcs) {
            int guess = wait;
            Set<Integer> guessed = new HashSet<>();
            Solution solution = null;
            Tracked<?> ahead = null;
            Vec3 point = null;
            boolean settled = false;
            for (int round = 0; round < maxRounds; round++) {
                rounds++;
                guessed.add(guess);
                ahead = lookahead.at(target, guess);
                point = aimPoint.on(ahead, shooter.getEye());
                solution = solve(shooter, point, arc, limit);
                if (solution == null) {
                    break;
                }
                int arrival = wait + solution.tick;
                // Settled, or flicking between two neighbouring ticks: either is as good as the other.
                if (arrival == guess || (guessed.contains(arrival) && Math.abs(arrival - guess) <= 1)) {
                    settled = true;
                    break;
                }
                guess = arrival;
            }
            if (solution == null) {
                continue;
            }
            if (!settled) {
                unsettled = true;
                continue;
            }
            Aim aim = check(shooter, solution, arc, point, ahead, target, wait);
            if (aim != null) {
                return finish(AimStats.Outcome.AIMED, aim);
            }
            blocked++;
        }
        AimStats.Outcome outcome = blocked > 0 ? AimStats.Outcome.BLOCKED
                : unsettled ? AimStats.Outcome.NO_CONVERGENCE : AimStats.Outcome.OUT_OF_RANGE;
        return finish(outcome, null);
    }

    // -------------------------------------------------------------- solving

    /** An angle found through nothing, and where along its path it reaches the point. */
    private static final class Solution {
        final Vec2 rotation;
        final Vec3 point;
        final int tick;
        final double fraction;

        Solution(Vec2 rotation, Vec3 point, int tick, double fraction) {
            this.rotation = rotation;
            this.point = point;
            this.tick = tick;
            this.fraction = fraction;
        }
    }

    /** Where a path passes the point's distance: how high above it, and when. */
    private static final class Crossing {
        final double height;
        final Vec3 point;
        final Vec3 origin;
        final int tick;
        final double fraction;

        Crossing(double height, Vec3 point, Vec3 origin, int tick, double fraction) {
            this.height = height;
            this.point = point;
            this.origin = origin;
            this.tick = tick;
            this.fraction = fraction;
        }
    }

    /** @return the angle on {@code arc} that passes through {@code point}; null if none reaches it */
    private Solution solve(Shooter shooter, Vec3 point, Arc arc, int limit) {
        double yaw = yawTo(shooter.getEye(), point);
        Crossing crossing = null;
        double pitch = 0d;
        for (int round = 0; round < YAW_ROUNDS; round++) {
            double found = solvePitch(shooter, point, yaw, arc, limit);
            if (Double.isNaN(found)) {
                return null;
            }
            pitch = found;
            crossing = cross(shooter, point, yaw, pitch, limit);
            // The shooter's own movement can carry the path sideways: turn by the miss.
            double miss = MathUtil.wrapDegrees(yawTo(crossing.origin, point) - yawTo(crossing.origin, crossing.point));
            if (Math.abs(miss) < YAW_TOLERANCE) {
                break;
            }
            yaw += miss;
        }
        return new Solution(rotation(yaw, pitch), crossing.point, crossing.tick, crossing.fraction);
    }

    /** @return the pitch on {@code arc}, at this yaw, whose path passes through the point's height; NaN if none */
    private double solvePitch(Shooter shooter, Vec3 point, double yaw, Arc arc, int limit) {
        int samples = (int) Math.round(180d / SCAN_STEP) + 1;
        double[] heights = new double[samples];
        int best = 0;
        for (int i = 0; i < samples; i++) {
            heights[i] = height(cross(shooter, point, yaw, pitchAt(i), limit));
            if (heights[i] > heights[best]) {
                best = i;
            }
        }
        if (heights[best] == Double.NEGATIVE_INFINITY) {
            return Double.NaN;
        }
        // The pitch that carries highest over the point lies between the best sample's neighbours.
        double low = pitchAt(Math.max(best - 1, 0));
        double high = pitchAt(Math.min(best + 1, samples - 1));
        double peak = pitchAt(best);
        double peakHeight = heights[best];
        double ratio = (Math.sqrt(5d) - 1d) / 2d;
        for (int i = 0; i < REFINE_STEPS; i++) {
            double left = high - ratio * (high - low);
            double right = low + ratio * (high - low);
            double leftHeight = height(cross(shooter, point, yaw, left, limit));
            double rightHeight = height(cross(shooter, point, yaw, right, limit));
            if (leftHeight >= rightHeight) {
                high = right;
                if (leftHeight > peakHeight) {
                    peak = left;
                    peakHeight = leftHeight;
                }
            } else {
                low = left;
                if (rightHeight > peakHeight) {
                    peak = right;
                    peakHeight = rightHeight;
                }
            }
        }
        if (peakHeight < 0d) {
            return Double.NaN;
        }
        // Pitch grows downward, so the low arc lies on the larger pitches past the peak.
        double over;
        double under;
        if (arc == Arc.LOW) {
            int j = best + 1;
            while (j < samples && heights[j] >= 0d) {
                j++;
            }
            if (j == samples) {
                return Double.NaN;
            }
            over = Math.max(peak, pitchAt(j - 1));
            under = pitchAt(j);
        } else {
            int j = best - 1;
            while (j >= 0 && heights[j] >= 0d) {
                j--;
            }
            if (j < 0) {
                return Double.NaN;
            }
            over = Math.min(peak, pitchAt(j + 1));
            under = pitchAt(j);
        }
        for (int i = 0; i < REFINE_STEPS; i++) {
            double middle = (over + under) / 2d;
            if (height(cross(shooter, point, yaw, middle, limit)) >= 0d) {
                over = middle;
            } else {
                under = middle;
            }
        }
        return over;
    }

    /**
     * Flies one angle through nothing to where it has come as far, along the
     * line from where it starts to the point, as the point is.
     *
     * @return where it passes, or null when it never gets that far: it heads
     *         away, or falls below the point first, or runs out of ticks
     */
    private Crossing cross(Shooter shooter, Vec3 point, double yaw, double pitch, int limit) {
        motions++;
        Launch launch = launching.launch(shooter, rotation(yaw, pitch));
        Vec3 origin = launch.getPosition();
        double dx = point.getX() - origin.getX();
        double dz = point.getZ() - origin.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1e-6d) {
            return null;
        }
        double towardX = dx / distance;
        double towardZ = dz / distance;
        Vec3 velocity = launch.getVelocity();
        if (velocity.getX() * towardX + velocity.getZ() * towardZ <= 1e-12d) {
            return null;
        }
        boolean falls = flight.getRules().getGravity() >= 0d;
        Motion motion = Motion.of(flight.getRules(), origin, velocity);
        double lastX = origin.getX();
        double lastY = origin.getY();
        double lastZ = origin.getZ();
        double lastAlong = 0d;
        for (int tick = 1; tick <= limit; tick++) {
            motion.tick();
            double along = (motion.getX() - origin.getX()) * towardX + (motion.getZ() - origin.getZ()) * towardZ;
            if (along >= distance) {
                double fraction = (distance - lastAlong) / (along - lastAlong);
                Vec3 at = Vec3.of(lastX + (motion.getX() - lastX) * fraction,
                        lastY + (motion.getY() - lastY) * fraction,
                        lastZ + (motion.getZ() - lastZ) * fraction);
                return new Crossing(at.getY() - point.getY(), at, origin, tick, fraction);
            }
            // Falling and already below it, it can only pass lower still.
            if (falls && motion.getY() < point.getY() && motion.getY() < lastY) {
                return null;
            }
            lastX = motion.getX();
            lastY = motion.getY();
            lastZ = motion.getZ();
            lastAlong = along;
        }
        return null;
    }

    // ------------------------------------------------------------- checking

    /**
     * Flies the solution through the world.
     *
     * @return the aim if the projectile reaches the point, or the target's box,
     *         before anything else stops it; null if something does
     */
    private Aim check(Shooter shooter, Solution solution, Arc arc, Vec3 point, Tracked<?> ahead,
                      Tracked<?> target, int wait) {
        flights++;
        Launch launch = launching.launch(shooter, solution.rotation);
        Object handle = target != null ? target.get() : null;
        Trajectory path = flight.launch(launch, entity -> handle != null && entity.get() == handle);
        int tick = solution.tick;
        double fraction = solution.fraction;
        Vec3 impact = solution.point;
        if (ahead != null) {
            // The first move around the arrival that meets the target's box.
            Box box = ahead.getBox().expand(flight.getRules().getEntityMargin());
            Motion motion = Motion.of(flight.getRules(), launch.getPosition(), launch.getVelocity());
            motions++;
            Vec3 last = motion.getPosition();
            tick = -1;
            for (int i = 1; i <= solution.tick + 1; i++) {
                motion.tick();
                Vec3 now = motion.getPosition();
                if (i >= solution.tick - 1) {
                    double at = box.clip(last, now);
                    if (!Double.isNaN(at)) {
                        tick = i;
                        fraction = at;
                        impact = last.lerp(now, at);
                        break;
                    }
                }
                last = now;
            }
            if (tick < 0) {
                return null;
            }
        }
        Hit hit = path.getHit();
        boolean reached = !hit.isHit() ? path.getTicks() >= tick
                : hit.getTick() > tick || (hit.getTick() == tick && hit.getFraction() >= fraction - 1e-9d);
        if (!reached) {
            return null;
        }
        return new Aim(solution.rotation, arc, point, ahead, impact, wait + tick, launch, path);
    }

    // ------------------------------------------------------------- internals

    private void begin() {
        motions = 0;
        flights = 0;
        rounds = 0;
        blocked = 0;
    }

    private Aim finish(AimStats.Outcome outcome, Aim aim) {
        lastStats = new AimStats(outcome, motions, flights, rounds, blocked);
        return aim;
    }

    private static double pitchAt(int sample) {
        return Math.min(90d, -90d + sample * SCAN_STEP);
    }

    private static double height(Crossing crossing) {
        return crossing == null ? Double.NEGATIVE_INFINITY : crossing.height;
    }

    private static Vec2 rotation(double yaw, double pitch) {
        return Vec2.rotation((float) MathUtil.wrapDegrees(yaw), (float) pitch);
    }

    /** @return the yaw that faces from {@code from} to {@code to}, by the game's convention */
    private static double yawTo(Vec3 from, Vec3 to) {
        return Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX())) - 90d;
    }

    public static final class Builder {

        private final Flight flight;
        private final LaunchRules launching;
        private Lookahead<Object> lookahead = Lookahead.none();
        private AimPoint aimPoint = AimPoint.CENTRE;
        private List<Arc> arcs = Collections.singletonList(Arc.LOW);
        private IntSupplier delay = () -> 0;
        private int maxRounds = DEFAULT_MAX_ROUNDS;

        private Builder(Flight flight, LaunchRules launching) {
            this.flight = Validate.notNull(flight, "flight");
            this.launching = Validate.notNull(launching, "launching");
        }

        /** Where a target will be when the projectile arrives; where it is now unless set. */
        public Builder lookahead(Lookahead<Object> lookahead) {
            this.lookahead = Validate.notNull(lookahead, "lookahead");
            return this;
        }

        /** Where on a target to aim; {@link AimPoint#CENTRE} unless set. */
        public Builder aimPoint(AimPoint aimPoint) {
            this.aimPoint = Validate.notNull(aimPoint, "aimPoint");
            return this;
        }

        /** The arcs to try, in order, until one gets through; {@link Arc#LOW} alone unless set. */
        public Builder arcs(Arc... arcs) {
            Validate.check(arcs.length > 0, "at least one arc is needed");
            this.arcs = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(arcs)));
            return this;
        }

        /**
         * Ticks between the aim and the projectile leaving, such as a turn still
         * to make, added to how far ahead a target is looked up. None unless set.
         */
        public Builder delay(IntSupplier ticks) {
            this.delay = Validate.notNull(ticks, "delay");
            return this;
        }

        /** How many times a moving target is looked up before giving up; {@link #DEFAULT_MAX_ROUNDS} unless set. */
        public Builder maxRounds(int rounds) {
            Validate.check(rounds >= 1, "maxRounds must be at least 1");
            this.maxRounds = rounds;
            return this;
        }

        public AimSolver build() {
            return new AimSolver(this);
        }
    }
}
