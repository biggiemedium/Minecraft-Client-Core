package dev.px.core.movement.simulation;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.math.MovementMath;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One tick of movement, run forwards.
 *
 * <p>{@link MovementMath#predict} says of itself that it is "a trajectory, not a
 * simulation": it carries motion forward and applies gravity, and walks straight
 * through the floor. This is the version that collides. Given where something is,
 * how fast it is going, what keys are held and a world to hit, it produces the
 * next tick &mdash; which is what answers where a fall lands, whether a jump
 * clears a gap, and where a player will be when an arrow arrives.
 *
 * <pre>{@code
 * MotionState now = MotionState.of(position, velocity, onGround);
 * MovementInput held = MovementInput.forward(yaw).withSprint(true);
 *
 * MotionState landing = Simulation.simulate(now, held, 20, world);
 * List<MotionState> path = Simulation.trace(now, held, 20, world);   // every tick of it
 * }</pre>
 *
 * <h2>Order of operations</h2>
 *
 * <p>Reproduced from the game rather than invented, because a plausible-looking
 * reordering gives answers that are almost right, which is the worst kind:
 *
 * <ol>
 *   <li>Friction for this tick, from the block underfoot &mdash; computed
 *       <em>before</em> moving and applied <em>after</em>, which is why a player
 *       who steps off ice keeps sliding for one more tick.
 *   <li>Jump, if held and on the ground, plus the horizontal kick a sprint jump
 *       gets.
 *   <li>Input becomes acceleration and is added to velocity. Ground acceleration
 *       scales against the cube of friction; air acceleration is a flat constant.
 *   <li>Move, sweeping <b>Y first, then X, then Z</b>, clipping against every
 *       obstacle on each axis and zeroing the velocity component that was
 *       blocked.
 *   <li>Step up, if the horizontal move was blocked and the thing was on the
 *       ground.
 *   <li>Gravity and air drag on the vertical, then friction on the horizontal.
 * </ol>
 *
 * <p>The axis order is the part everyone gets wrong. Sweeping X and Z before Y
 * lets a falling player clip the lip of a block they should have landed on; doing
 * all three at once lets them slide along walls they should have stopped against.
 *
 * <h2>What this models, and what it does not</h2>
 *
 * <p><b>Modelled:</b> walking, sprinting, sneaking, jumping and sprint-jumping,
 * gravity, air drag, ground friction with per-block slipperiness, axis-separated
 * collision, and stepping up ledges.
 *
 * <p><b>Not modelled:</b> water and lava, ladders and vines, cobwebs, slime and
 * bouncing, elytra, riding anything, knockback, levitation and slow falling, and
 * the sneak clamp that stops a sneaking player walking off an edge. Potion
 * effects are not modelled either, but they compose: hand in a
 * {@link PhysicsProfile} already adjusted for them.
 *
 * <p>This is a faithful reproduction of the common case, not a bit-exact
 * reimplementation of the game's movement code. Treat a twenty-tick prediction as
 * a good estimate, not as ground truth, and check the exclusion list above before
 * trusting it somewhere unusual.
 *
 * <p><b>Complexity.</b> One {@link CollisionSpace#boxesIn} call per tick, then
 * O(obstacles) per axis. A prediction of {@code n} ticks is {@code n} of those.
 */
public final class Simulation {

    /**
     * Grown around the swept region before obstacles are collected.
     *
     * <p>Covers the boxes a sweep can touch after the first axis has already
     * moved the hitbox, which the un-grown region does not quite reach.
     */
    private static final double COLLECT_MARGIN = 1.0E-7d;

    private Simulation() {
    }

    // ----------------------------------------------------------- one tick

    /** One tick, using {@link MovementMath#getProfile()}. */
    public static MotionState step(MotionState state, MovementInput input, CollisionSpace space) {
        return step(MovementMath.getProfile(), state, input, space);
    }

    /**
     * Advances one tick.
     *
     * @param profile the movement constants, including the hitbox being moved
     * @param space the world to collide against; {@link CollisionSpace#empty()}
     *        gives a ballistic trajectory
     * @return the state at the start of the next tick
     */
    public static MotionState step(PhysicsProfile profile, MotionState state,
                                   MovementInput input, CollisionSpace space) {
        Validate.notNull(profile, "profile");
        Validate.notNull(state, "state");
        Validate.notNull(input, "input");
        Validate.notNull(space, "space");

        Vec3 position = state.getPosition();
        Vec3 velocity = state.getVelocity();
        boolean onGround = state.isOnGround();

        // (1) Friction is decided before anything moves, and damps the result at
        // the end. Reading it after the move would use next tick's ground.
        double slipperiness = onGround
                ? space.slipperinessAt(position.add(0d, -1d, 0d))
                : 1d;
        double friction = onGround
                ? slipperiness * profile.getGroundFriction()
                : profile.getGroundFriction();

        // (2) Jump.
        if (input.isJump() && onGround) {
            double kickX = 0d;
            double kickZ = 0d;
            if (input.isSprint()) {
                double radians = Math.toRadians(input.getYaw());
                kickX = -Math.sin(radians) * profile.getSprintJumpBoost();
                kickZ = Math.cos(radians) * profile.getSprintJumpBoost();
            }
            velocity = Vec3.of(velocity.getX() + kickX, profile.getJumpVelocity(),
                    velocity.getZ() + kickZ);
            onGround = false;
        }

        // (3) Input becomes acceleration.
        velocity = velocity.add(acceleration(profile, input, onGround, friction));

        // (4) and (5) Move, sweeping one axis at a time, then try to step up.
        Swept swept = move(profile, position, velocity, onGround, space);
        position = swept.position;
        velocity = swept.velocity;
        onGround = swept.onGround;

        // (6) Gravity and drag, then friction for next tick.
        velocity = Vec3.of(
                velocity.getX() * friction,
                (velocity.getY() - profile.getGravity()) * profile.getDrag(),
                velocity.getZ() * friction);

        return MotionState.of(position, velocity, onGround);
    }

    // ---------------------------------------------------------- many ticks

    /** Runs {@code ticks} ticks with the same input held throughout. */
    public static MotionState simulate(MotionState state, MovementInput input,
                                       int ticks, CollisionSpace space) {
        return simulate(MovementMath.getProfile(), state, input, ticks, space);
    }

    /**
     * Runs {@code ticks} ticks with the same input held throughout.
     *
     * <p>Holding the input constant is the assumption that limits how far this is
     * worth trusting. For the player's own movement over a few ticks it is
     * usually true. For anything with a mind of its own it is a guess that gets
     * worse every tick &mdash; see {@link MotionTrack#extrapolate} for the other
     * way of answering that question.
     */
    public static MotionState simulate(PhysicsProfile profile, MotionState state,
                                       MovementInput input, int ticks, CollisionSpace space) {
        Validate.check(ticks >= 0, "ticks must not be negative, got " + ticks);
        MotionState current = state;
        for (int tick = 0; tick < ticks; tick++) {
            current = step(profile, current, input, space);
        }
        return current;
    }

    /** Every intermediate state, for drawing a predicted path or finding when it lands. */
    public static List<MotionState> trace(MotionState state, MovementInput input,
                                          int ticks, CollisionSpace space) {
        return trace(MovementMath.getProfile(), state, input, ticks, space);
    }

    /**
     * @return {@code ticks} states, the first being one tick after {@code state}
     *
     * <p>Excludes the starting state, so {@code trace(...).get(n - 1)} is the same
     * answer {@code simulate(..., n, ...)} gives.
     */
    public static List<MotionState> trace(PhysicsProfile profile, MotionState state,
                                          MovementInput input, int ticks, CollisionSpace space) {
        Validate.check(ticks >= 0, "ticks must not be negative, got " + ticks);
        if (ticks == 0) {
            return Collections.emptyList();
        }
        List<MotionState> path = new ArrayList<>(ticks);
        MotionState current = state;
        for (int tick = 0; tick < ticks; tick++) {
            current = step(profile, current, input, space);
            path.add(current);
        }
        return path;
    }

    /**
     * @return the first state in a trace that is on the ground, or null
     *
     * <p>Where a fall or a jump ends up. Null means it was still airborne after
     * {@code ticks}, which for a drop into the void is the honest answer.
     */
    public static MotionState landing(MotionState state, MovementInput input,
                                      int ticks, CollisionSpace space) {
        MotionState current = state;
        for (int tick = 0; tick < ticks; tick++) {
            current = step(current, input, space);
            if (current.isOnGround()) {
                return current;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ internals

    /**
     * @return the velocity the held keys add this tick
     *
     * <p>On the ground this scales against the cube of friction, which is what
     * makes ice slow to accelerate on and slow to stop on while ending at the same
     * top speed. In the air it is a small flat amount, which is why air control is
     * weak but not absent.
     */
    private static Vec3 acceleration(PhysicsProfile profile, MovementInput input,
                                     boolean onGround, double friction) {
        double forward = input.getForward();
        double strafe = input.getStrafe();
        if (input.isSneak()) {
            // Vanilla scales the keys, not the speed, which is why sneaking and
            // sprinting together is slower than either alone rather than cancelling.
            forward *= profile.getSneakMultiplier();
            strafe *= profile.getSneakMultiplier();
        }

        double rate;
        if (onGround) {
            // The attribute, not the walking speed. They are different quantities
            // and mixing them up is the difference between 4.4 blocks a second and
            // eleven -- see PhysicsProfile#moveSpeedAttribute.
            double base = profile.getMoveSpeedAttribute();
            if (input.isSprint()) {
                base *= profile.getSprintMultiplier();
            }
            rate = base * (profile.getGroundAccelerationBase() / (friction * friction * friction));
        } else {
            rate = profile.getAirAcceleration() + (input.isSprint() ? profile.getSprintAirBonus() : 0d);
        }
        return MovementMath.velocity(input.getYaw(), forward, strafe, rate);
    }

    /**
     * Sweeps the hitbox through {@code velocity}, one axis at a time.
     *
     * <p>Obstacles are collected once, over the whole region the box could reach
     * including the step-up retry, so a tick costs one call into the world however
     * many sweeps it takes.
     */
    private static Swept move(PhysicsProfile profile, Vec3 position, Vec3 velocity,
                              boolean wasOnGround, CollisionSpace space) {
        double width = profile.getHitboxWidth();
        double height = profile.getHitboxHeight();
        double stepHeight = profile.getStepHeight();

        double wantX = velocity.getX();
        double wantY = velocity.getY();
        double wantZ = velocity.getZ();

        Box start = Box.around(position, width, height);
        Box region = start.stretch(wantX, wantY, wantZ);
        if (wasOnGround && stepHeight > 0d) {
            // The retry lifts the box before moving it, so those blocks have to be
            // in the list the retry is checked against.
            region = region.stretch(0d, stepHeight, 0d);
        }
        List<Box> obstacles = space.boxesIn(region.expand(COLLECT_MARGIN));
        if (obstacles == null || obstacles.isEmpty()) {
            return new Swept(position.add(wantX, wantY, wantZ), velocity, false);
        }

        double movedY = clampY(obstacles, start, wantY);
        Box box = start.offset(0d, movedY, 0d);

        double movedX = clampX(obstacles, box, wantX);
        box = box.offset(movedX, 0d, 0d);

        double movedZ = clampZ(obstacles, box, wantZ);
        box = box.offset(0d, 0d, movedZ);

        boolean blocked = movedX != wantX || movedZ != wantZ;
        if (blocked && wasOnGround && stepHeight > 0d) {
            Stepped stepped = stepUp(obstacles, start, wantX, wantZ, stepHeight);
            if (stepped != null
                    && stepped.x * stepped.x + stepped.z * stepped.z > movedX * movedX + movedZ * movedZ) {
                box = stepped.box;
                movedX = stepped.x;
                movedZ = stepped.z;
                movedY = stepped.y;
            }
        }

        // A blocked axis loses its velocity; the others keep theirs.
        Vec3 resolved = Vec3.of(
                movedX != wantX ? 0d : velocity.getX(),
                movedY != wantY ? 0d : velocity.getY(),
                movedZ != wantZ ? 0d : velocity.getZ());

        // On the ground means: was moving down, and something stopped it.
        boolean onGround = wantY < 0d && movedY != wantY;

        Vec3 landed = Vec3.of(
                (box.getMinX() + box.getMaxX()) / 2d,
                box.getMinY(),
                (box.getMinZ() + box.getMaxZ()) / 2d);
        return new Swept(landed, resolved, onGround);
    }

    /**
     * Retries the horizontal move from a lifted position, then settles back down.
     *
     * <p>How a player walks up a slab instead of into it. Only attempted from the
     * ground, so a jump that clips a ledge is not silently converted into standing
     * on it.
     */
    private static Stepped stepUp(List<Box> obstacles, Box start,
                                  double wantX, double wantZ, double stepHeight) {
        double up = clampY(obstacles, start, stepHeight);
        if (up <= 0d) {
            return null;
        }
        Box box = start.offset(0d, up, 0d);

        double x = clampX(obstacles, box, wantX);
        box = box.offset(x, 0d, 0d);

        double z = clampZ(obstacles, box, wantZ);
        box = box.offset(0d, 0d, z);

        // Back down onto whatever is now underneath.
        double down = clampY(obstacles, box, -up);
        box = box.offset(0d, down, 0d);

        return new Stepped(box, x, up + down, z);
    }

    private static double clampY(List<Box> obstacles, Box moving, double dy) {
        double clamped = dy;
        for (int i = 0; i < obstacles.size(); i++) {
            clamped = clampY(obstacles.get(i), moving, clamped);
        }
        return clamped;
    }

    private static double clampX(List<Box> obstacles, Box moving, double dx) {
        double clamped = dx;
        for (int i = 0; i < obstacles.size(); i++) {
            clamped = clampX(obstacles.get(i), moving, clamped);
        }
        return clamped;
    }

    private static double clampZ(List<Box> obstacles, Box moving, double dz) {
        double clamped = dz;
        for (int i = 0; i < obstacles.size(); i++) {
            clamped = clampZ(obstacles.get(i), moving, clamped);
        }
        return clamped;
    }

    /**
     * @return how far {@code moving} may travel along Y before it touches
     *         {@code solid}, or {@code dy} if it never does
     *
     * <p>The other two axes are tested for overlap first: a box beside this one is
     * not in the way however far down we go. Overlap is strict, so boxes that
     * merely touch edge to edge do not block each other.
     */
    private static double clampY(Box solid, Box moving, double dy) {
        if (moving.getMaxX() <= solid.getMinX() || moving.getMinX() >= solid.getMaxX()) {
            return dy;
        }
        if (moving.getMaxZ() <= solid.getMinZ() || moving.getMinZ() >= solid.getMaxZ()) {
            return dy;
        }
        if (dy > 0d && moving.getMaxY() <= solid.getMinY()) {
            double gap = solid.getMinY() - moving.getMaxY();
            return gap < dy ? gap : dy;
        }
        if (dy < 0d && moving.getMinY() >= solid.getMaxY()) {
            double gap = solid.getMaxY() - moving.getMinY();
            return gap > dy ? gap : dy;
        }
        return dy;
    }

    private static double clampX(Box solid, Box moving, double dx) {
        if (moving.getMaxY() <= solid.getMinY() || moving.getMinY() >= solid.getMaxY()) {
            return dx;
        }
        if (moving.getMaxZ() <= solid.getMinZ() || moving.getMinZ() >= solid.getMaxZ()) {
            return dx;
        }
        if (dx > 0d && moving.getMaxX() <= solid.getMinX()) {
            double gap = solid.getMinX() - moving.getMaxX();
            return gap < dx ? gap : dx;
        }
        if (dx < 0d && moving.getMinX() >= solid.getMaxX()) {
            double gap = solid.getMaxX() - moving.getMinX();
            return gap > dx ? gap : dx;
        }
        return dx;
    }

    private static double clampZ(Box solid, Box moving, double dz) {
        if (moving.getMaxX() <= solid.getMinX() || moving.getMinX() >= solid.getMaxX()) {
            return dz;
        }
        if (moving.getMaxY() <= solid.getMinY() || moving.getMinY() >= solid.getMaxY()) {
            return dz;
        }
        if (dz > 0d && moving.getMaxZ() <= solid.getMinZ()) {
            double gap = solid.getMinZ() - moving.getMaxZ();
            return gap < dz ? gap : dz;
        }
        if (dz < 0d && moving.getMinZ() >= solid.getMaxZ()) {
            double gap = solid.getMaxZ() - moving.getMinZ();
            return gap > dz ? gap : dz;
        }
        return dz;
    }

    /** The result of one move: where it ended up and what survived of the velocity. */
    private static final class Swept {

        private final Vec3 position;
        private final Vec3 velocity;
        private final boolean onGround;

        private Swept(Vec3 position, Vec3 velocity, boolean onGround) {
            this.position = position;
            this.velocity = velocity;
            this.onGround = onGround;
        }
    }

    /** The result of a step-up attempt, kept only if it travelled further. */
    private static final class Stepped {

        private final Box box;
        private final double x;
        private final double y;
        private final double z;

        private Stepped(Box box, double x, double y, double z) {
            this.box = box;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
