package dev.px.core.util.math;

import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * The movement constants {@link MovementMath} and
 * {@link dev.px.core.movement.simulation.Simulation} work from.
 *
 * <p>Most of these were {@code public static final} fields on
 * {@link MovementMath}, which was wrong in a way that only shows up later: they
 * are not universal truths, they are one game version's numbers. Walking speed,
 * friction and the effect multipliers have all moved between versions, and a
 * server with custom attributes moves them again. Baking them into the helper
 * meant a client on 1.21 silently got 1.8's answers, with no way to say otherwise
 * short of forking the class.
 *
 * <p>So they are a value now. {@link #vanilla()} is the default and is what every
 * {@link MovementMath} call uses unless told otherwise, which keeps the common
 * case a one-argument call:
 *
 * <pre>{@code
 * double speed = MovementMath.walkSpeed(2, 0);                    // the default profile
 *
 * PhysicsProfile slippery = PhysicsProfile.vanilla().withGroundFriction(0.98d);
 * double onIce = MovementMath.friction(slippery, motionX, 1d);    // one call, one profile
 *
 * MovementMath.setProfile(PhysicsProfile.vanilla().withWalkSpeed(0.2806d));  // client-wide
 * }</pre>
 *
 * <h2>Potion effects</h2>
 *
 * <p>Not fields here, because they are not constants &mdash; they are whatever
 * the player is holding right now. Fold them in when you build the profile, using
 * the helpers that already know the arithmetic:
 *
 * <pre>{@code
 * PhysicsProfile hasted = PhysicsProfile.vanilla()
 *         .withWalkSpeed(MovementMath.walkSpeed(2, 0))            // Speed II
 *         .withJumpVelocity(MovementMath.jumpVelocity(1));        // Jump Boost I
 * }</pre>
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Water, lava, cobwebs, ladders, slime, elytra and the per-block slipperiness
 * table. Those are not single constants &mdash; they are branches in the game's
 * movement code, and the honest place for them is a simulation that models the
 * branches, not a bag of numbers that pretends they are all multipliers.
 * {@link MovementMath#friction} takes slipperiness as an argument and
 * {@link dev.px.core.movement.simulation.CollisionSpace} answers for it per
 * position, for exactly that reason.
 *
 * <p>Immutable, and safely publishable: the fields are written once during
 * construction and never afterwards, and {@code with*} returns a copy rather than
 * mutating. They are not {@code final} only because a copy-and-adjust constructor
 * is the one way to keep fourteen {@code with*} methods from each restating
 * fourteen arguments.
 */
@Getter
public final class PhysicsProfile {

    /**
     * The values Minecraft has used for most of its life, and the ones the old
     * constants held.
     *
     * <p>Accurate for 1.8 and close enough for everything since. A client that
     * measures a difference on its target version should say so with
     * {@link #withWalkSpeed} rather than assume this is exact.
     */
    private static final PhysicsProfile VANILLA = new PhysicsProfile();

    // ---- walking and falling ------------------------------------------------

    /**
     * Top speed of a walking player with no effects, in blocks per tick.
     *
     * <p>4.317 blocks a second over twenty ticks. <b>This is a result, not an
     * input:</b> it is what a readout should show and what
     * {@link MovementMath#walkSpeed} returns, and it is <em>not</em> what drives
     * the simulation &mdash; see {@link #moveSpeedAttribute}, which is the number
     * the game's own formula takes.
     *
     * <p>It was 0.2873 until the two were told apart. That figure is neither
     * walking speed nor sprinting speed; it is the horizontal speed a
     * sprint-jumping player reaches, which is a useful number to have and a
     * misleading one to call "walk speed".
     */
    private double walkSpeed = 0.21585d;

    /** Upward velocity a jump starts with, in blocks per tick. */
    private double jumpVelocity = 0.42d;

    /** Velocity lost to gravity each tick, before drag. */
    private double gravity = 0.08d;

    /** Vertical velocity retained each tick. Air drag. */
    private double drag = 0.98d;

    /** Horizontal velocity retained each tick on normal ground. */
    private double groundFriction = 0.91d;

    // ---- effects ------------------------------------------------------------

    /** Fraction each level of Speed adds. */
    private double speedPerLevel = 0.2d;

    /** Fraction each level of Slowness removes. */
    private double slownessPerLevel = 0.15d;

    /** Blocks per tick each level of Jump Boost adds to the initial jump. */
    private double jumpBoostPerLevel = 0.1d;

    // ---- simulation ---------------------------------------------------------

    /**
     * The game's movement-speed attribute, which is what actually drives
     * acceleration.
     *
     * <p><b>0.1, not 0.2873.</b> The trap here cost this class a bug worth
     * describing: {@link #walkSpeed} is a speed in blocks per tick and this is an
     * attribute that gets multiplied by {@link #groundAccelerationBase} over the
     * cube of friction. They are different quantities with confusingly similar
     * names &mdash; the game calls both of them "walk speed" in different places
     * &mdash; and feeding the first into the formula that wants the second makes
     * a simulated player travel roughly two and a half times too fast.
     *
     * <p>The two are consistent by construction: with friction at 0.546 the
     * steady state this produces is 4.4 blocks a second against a measured 4.317,
     * and multiplying by {@link #sprintMultiplier} gives 5.7 against 5.612.
     */
    private double moveSpeedAttribute = 0.1d;

    /**
     * The scaling the game applies to ground acceleration, against the cube of the
     * current friction.
     *
     * <p>A magic number in the game's own source, and reproduced rather than
     * derived: acceleration is {@code walkSpeed * base / friction³}, which is what
     * makes a player accelerate to the same top speed on dirt and on ice while
     * taking very different times to get there.
     */
    private double groundAccelerationBase = 0.16277136d;

    /** Horizontal acceleration available in mid-air, where friction does not apply. */
    private double airAcceleration = 0.02d;

    /** Extra air acceleration while sprinting. */
    private double sprintAirBonus = 0.006d;

    /** Multiplier on walking speed while sprinting. */
    private double sprintMultiplier = 1.3d;

    /** Multiplier the game applies to the input keys while sneaking. */
    private double sneakMultiplier = 0.3d;

    /** Horizontal kick along the facing yaw when a jump starts while sprinting. */
    private double sprintJumpBoost = 0.2d;

    /** How high a ledge can be and still be walked up rather than into. */
    private double stepHeight = 0.6d;

    /** Slipperiness of an ordinary block, used when nothing says otherwise. */
    private double defaultSlipperiness = 0.6d;

    /** Width of the simulated hitbox, in blocks. */
    private double hitboxWidth = 0.6d;

    /** Height of the simulated hitbox, in blocks. */
    private double hitboxHeight = 1.8d;

    private PhysicsProfile() {
    }

    /** Copy-and-adjust. The only way a profile is ever derived from another. */
    private PhysicsProfile(PhysicsProfile source) {
        this.walkSpeed = source.walkSpeed;
        this.jumpVelocity = source.jumpVelocity;
        this.gravity = source.gravity;
        this.drag = source.drag;
        this.groundFriction = source.groundFriction;
        this.speedPerLevel = source.speedPerLevel;
        this.slownessPerLevel = source.slownessPerLevel;
        this.jumpBoostPerLevel = source.jumpBoostPerLevel;
        this.moveSpeedAttribute = source.moveSpeedAttribute;
        this.groundAccelerationBase = source.groundAccelerationBase;
        this.airAcceleration = source.airAcceleration;
        this.sprintAirBonus = source.sprintAirBonus;
        this.sprintMultiplier = source.sprintMultiplier;
        this.sneakMultiplier = source.sneakMultiplier;
        this.sprintJumpBoost = source.sprintJumpBoost;
        this.stepHeight = source.stepHeight;
        this.defaultSlipperiness = source.defaultSlipperiness;
        this.hitboxWidth = source.hitboxWidth;
        this.hitboxHeight = source.hitboxHeight;
    }

    /** @return the shared vanilla profile. Immutable, so the same instance every time. */
    public static PhysicsProfile vanilla() {
        return VANILLA;
    }

    public PhysicsProfile withWalkSpeed(double walkSpeed) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.walkSpeed = positive(walkSpeed, "walk speed");
        return copy;
    }

    public PhysicsProfile withJumpVelocity(double jumpVelocity) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.jumpVelocity = jumpVelocity;
        return copy;
    }

    public PhysicsProfile withGravity(double gravity) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.gravity = gravity;
        return copy;
    }

    /** Drag is a fraction retained per tick, so it belongs in 0..1. */
    public PhysicsProfile withDrag(double drag) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.drag = fraction(drag, "drag");
        return copy;
    }

    /** Friction is a fraction retained per tick, so it belongs in 0..1. */
    public PhysicsProfile withGroundFriction(double groundFriction) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.groundFriction = fraction(groundFriction, "ground friction");
        return copy;
    }

    public PhysicsProfile withSpeedPerLevel(double speedPerLevel) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.speedPerLevel = speedPerLevel;
        return copy;
    }

    public PhysicsProfile withSlownessPerLevel(double slownessPerLevel) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.slownessPerLevel = slownessPerLevel;
        return copy;
    }

    public PhysicsProfile withJumpBoostPerLevel(double jumpBoostPerLevel) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.jumpBoostPerLevel = jumpBoostPerLevel;
        return copy;
    }

    /** The attribute the simulation accelerates from. See {@link #moveSpeedAttribute}. */
    public PhysicsProfile withMoveSpeedAttribute(double moveSpeedAttribute) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.moveSpeedAttribute = positive(moveSpeedAttribute, "move speed attribute");
        return copy;
    }

    public PhysicsProfile withGroundAccelerationBase(double groundAccelerationBase) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.groundAccelerationBase = positive(groundAccelerationBase, "ground acceleration base");
        return copy;
    }

    public PhysicsProfile withAirAcceleration(double airAcceleration) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.airAcceleration = airAcceleration;
        return copy;
    }

    public PhysicsProfile withSprintAirBonus(double sprintAirBonus) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.sprintAirBonus = sprintAirBonus;
        return copy;
    }

    public PhysicsProfile withSprintMultiplier(double sprintMultiplier) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.sprintMultiplier = positive(sprintMultiplier, "sprint multiplier");
        return copy;
    }

    public PhysicsProfile withSneakMultiplier(double sneakMultiplier) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.sneakMultiplier = fraction(sneakMultiplier, "sneak multiplier");
        return copy;
    }

    public PhysicsProfile withSprintJumpBoost(double sprintJumpBoost) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.sprintJumpBoost = sprintJumpBoost;
        return copy;
    }

    /** Zero disables stepping, so the simulated player walks into ledges instead of up them. */
    public PhysicsProfile withStepHeight(double stepHeight) {
        Validate.check(stepHeight >= 0d, "step height must not be negative, got " + stepHeight);
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.stepHeight = stepHeight;
        return copy;
    }

    public PhysicsProfile withDefaultSlipperiness(double defaultSlipperiness) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.defaultSlipperiness = positive(defaultSlipperiness, "default slipperiness");
        return copy;
    }

    /** The simulated hitbox. Change it to simulate something that is not a player. */
    public PhysicsProfile withHitbox(double width, double height) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.hitboxWidth = positive(width, "hitbox width");
        copy.hitboxHeight = positive(height, "hitbox height");
        return copy;
    }

    @Override
    public String toString() {
        return "PhysicsProfile(walk=" + walkSpeed + ", attribute=" + moveSpeedAttribute + ", jump=" + jumpVelocity
                + ", gravity=" + gravity + ", drag=" + drag + ", friction=" + groundFriction
                + ", step=" + stepHeight + ", hitbox=" + hitboxWidth + "x" + hitboxHeight + ")";
    }

    private static double positive(double value, String what) {
        Validate.check(value > 0d, what + " must be greater than zero, got " + value);
        return value;
    }

    /**
     * Checked rather than clamped: a friction of 1.2 is a typo that would make the
     * player accelerate forever, and silently correcting it hides the mistake.
     */
    private static double fraction(double value, String what) {
        Validate.check(value >= 0d && value <= 1d,
                what + " is a fraction and must be between 0 and 1, got " + value);
        return value;
    }
}
