package dev.px.projectile.flight;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.projectile.ProjectileRules;
import dev.px.projectile.StepOrder;

/**
 * One projectile moving by its rules, a tick at a time, through nothing: no
 * blocks, no entities.
 *
 * <pre>{@code
 * Motion motion = Motion.of(arrow, launch.getPosition(), launch.getVelocity());
 * while (motion.getY() > groundY) {
 *     motion.tick();
 * }
 * }</pre>
 *
 * <p>The cheap half of a {@link Flight}: the same steps in the same order, with
 * nothing to hit, for when only the motion is wanted &mdash; the aim solver tries
 * hundreds of these to find an angle before it flies the one it picked through
 * the world. Each tick accelerates, drags and moves in the order the rules say;
 * drag is asked for where the projectile was when the tick began.
 *
 * <p>Changes as it ticks: not a value. Game thread only.
 */
public final class Motion {

    /** Stops a move part of the way along: where the world is in the way. */
    interface Collider {

        /** @return how far along the move from {@code (x1,y1,z1)} to {@code (x2,y2,z2)} it is stopped, 0 to 1; NaN if not */
        double clip(double x1, double y1, double z1, double x2, double y2, double z2);
    }

    private final ProjectileRules rules;
    private final StepOrder.Step[] steps;
    private double x;
    private double y;
    private double z;
    private double velocityX;
    private double velocityY;
    private double velocityZ;
    private int ticks;
    /** How far a change of 1 block per tick in the starting velocity has moved the position so far. */
    private double reach;
    /** How much of that change the velocity still carries. */
    private double carried = 1d;

    private Motion(ProjectileRules rules, Vec3 position, Vec3 velocity) {
        this.rules = rules;
        this.steps = rules.getOrder().getSteps().toArray(new StepOrder.Step[0]);
        this.x = position.getX();
        this.y = position.getY();
        this.z = position.getZ();
        this.velocityX = velocity.getX();
        this.velocityY = velocity.getY();
        this.velocityZ = velocity.getZ();
    }

    /** @return a projectile at {@code position} with {@code velocity}, about to tick */
    public static Motion of(ProjectileRules rules, Vec3 position, Vec3 velocity) {
        return new Motion(Validate.notNull(rules, "rules"), Validate.notNull(position, "position"),
                Validate.notNull(velocity, "velocity"));
    }

    /**
     * A projectile seen moving {@code lastMove} in the tick that brought it to
     * {@code position}: its velocity is that move, carried through whatever of
     * the tick came after the move.
     *
     * <p>For a projectile seen by its positions rather than its velocity. The
     * game's own velocity, where your client can read it, is exact; this is as
     * good as the positions are.
     */
    public static Motion following(ProjectileRules rules, Vec3 position, Vec3 lastMove) {
        Validate.notNull(rules, "rules");
        Validate.notNull(position, "position");
        Validate.notNull(lastMove, "lastMove");
        double vx = lastMove.getX();
        double vy = lastMove.getY();
        double vz = lastMove.getZ();
        // Drag is asked for where the tick began, which is where the move began.
        double drag = rules.getDrag().at(position.getX() - vx, position.getY() - vy, position.getZ() - vz);
        boolean after = false;
        for (StepOrder.Step step : rules.getOrder().getSteps()) {
            if (step == StepOrder.Step.POSITION) {
                after = true;
            } else if (after && step == StepOrder.Step.DRAG) {
                vx *= drag;
                vy *= drag;
                vz *= drag;
            } else if (after) {
                vy -= rules.getGravity();
            }
        }
        return new Motion(rules, position, Vec3.of(vx, vy, vz));
    }

    /** Moves it one tick by its rules, through nothing. */
    public void tick() {
        tick(null);
    }

    /**
     * Moves it one tick, stopping where {@code collider} says the move is stopped.
     *
     * @return how far along this tick's move it was stopped, 0 to 1; NaN if it was not
     */
    double tick(Collider collider) {
        double startX = x;
        double startY = y;
        double startZ = z;
        ticks++;
        for (StepOrder.Step step : steps) {
            switch (step) {
                case ACCELERATION:
                    velocityY -= rules.getGravity();
                    break;
                case DRAG:
                    double drag = rules.getDrag().at(startX, startY, startZ);
                    velocityX *= drag;
                    velocityY *= drag;
                    velocityZ *= drag;
                    carried *= drag;
                    break;
                default:
                    double stopped = collider == null ? Double.NaN
                            : collider.clip(x, y, z, x + velocityX, y + velocityY, z + velocityZ);
                    double moved = Double.isNaN(stopped) ? 1d : stopped;
                    x += velocityX * moved;
                    y += velocityY * moved;
                    z += velocityZ * moved;
                    reach += carried * moved;
                    if (!Double.isNaN(stopped)) {
                        return stopped;
                    }
                    break;
            }
        }
        return Double.NaN;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public Vec3 getPosition() {
        return Vec3.of(x, y, z);
    }

    /** @return in blocks per tick */
    public Vec3 getVelocity() {
        return Vec3.of(velocityX, velocityY, velocityZ);
    }

    /** @return the ticks it has moved */
    public int getTicks() {
        return ticks;
    }

    /**
     * @return how far its position has moved, on each axis, for each block per
     *         tick its starting velocity was off on that axis. Exact while drag
     *         is the same everywhere it went; what a launch's
     *         {@linkplain dev.px.projectile.Launch#getSpread() spread} is scaled by
     */
    public double getReach() {
        return reach;
    }

    @Override
    public String toString() {
        return String.format("Motion(%s, tick %d at %.3f, %.3f, %.3f)", rules.getName(), ticks, x, y, z);
    }
}
