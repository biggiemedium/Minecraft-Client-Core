package dev.px.core.util.math;

import dev.px.core.util.Validate;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

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
 * <h2>Where the numbers come from</h2>
 *
 * <p>Every default is a figure from the Minecraft Wiki, checked on 2026-10-06,
 * and each field says which page. A few constants the wiki does not state
 * outright &mdash; the air acceleration, the sprint-jump kick, the ground
 * acceleration base &mdash; are the game's own, and are pinned instead by the
 * speeds the wiki <em>measures</em>: the suite holds walking, sprinting,
 * sneaking, sprint-jumping, ice, blue ice, slime, jump heights with and without
 * Jump Boost and terminal fall speed to within a tenth of a percent of the wiki,
 * and a wrong constant moves at least one of them.
 *
 * <p><b>These are defaults, not facts Core depends on.</b> When a version changes
 * one, build a profile that says so; nothing else changes. Since 1.20.5 most of
 * them are also per-player attributes a server can change &mdash;
 * {@code movement_speed}, {@code jump_strength}, {@code gravity},
 * {@code step_height}, {@code scale} and, since 1.21, {@code sneaking_speed}
 * (<a href="https://minecraft.wiki/w/Attribute">Attribute</a>) &mdash; so on those versions read
 * them from the player rather than trusting the default.
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
     * The values Minecraft has used for most of its life: the same in 1.8 and in
     * 1.21, as the wiki gives them. A client that measures a difference on its
     * target version, or a server that changes an attribute, should say so with a
     * {@code with*} rather than assume this is exact.
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
     *
     * <p>Source: 4.317 blocks a second, <a href="https://minecraft.wiki/w/Walking">Walking</a>;
     * 0.098 / (1 - 0.546), <a href="https://minecraft.wiki/w/Player#Movement_speed">Player &sect; Movement speed</a>.
     */
    private double walkSpeed = 0.21585d;

    /**
     * Upward velocity a jump starts with, in blocks per tick.
     *
     * <p>Source: the {@code jump_strength} attribute's default,
     * <a href="https://minecraft.wiki/w/Attribute">Attribute</a>; it gives the 1.2522-block jump of
     * <a href="https://minecraft.wiki/w/Jumping">Jumping</a>.
     */
    private double jumpVelocity = 0.42d;

    /**
     * Velocity lost to gravity each tick, before drag.
     *
     * <p>Source: the {@code gravity} attribute's default,
     * <a href="https://minecraft.wiki/w/Attribute">Attribute</a>; acceleration -0.08,
     * <a href="https://minecraft.wiki/w/Entity#Motion">Entity &sect; Motion</a>.
     */
    private double gravity = 0.08d;

    /**
     * Vertical velocity retained each tick. Air drag.
     *
     * <p>Source: vertical drag 0.98, <a href="https://minecraft.wiki/w/Entity#Motion">Entity &sect; Motion</a>,
     * which with gravity gives its terminal velocity of 78.4 blocks a second.
     */
    private double drag = 0.98d;

    /**
     * Horizontal velocity retained each tick in the air, and on the ground before
     * the block's slipperiness multiplies it: 0.91 &times; 0.6 is the 0.546 a
     * walking player is slowed by.
     *
     * <p>Source: horizontal drag 0.91, and "0.91&times;friction" on the ground,
     * <a href="https://minecraft.wiki/w/Entity#Motion">Entity &sect; Motion</a>.
     */
    private double groundFriction = 0.91d;

    // ---- effects ------------------------------------------------------------

    /** Fraction each level of Speed adds. Source: +20% &times; level, <a href="https://minecraft.wiki/w/Speed">Speed</a>. */
    private double speedPerLevel = 0.2d;

    /** Fraction each level of Slowness removes. Source: 15% &times; level, <a href="https://minecraft.wiki/w/Slowness">Slowness</a>. */
    private double slownessPerLevel = 0.15d;

    /**
     * Blocks per tick each level of Jump Boost adds to the initial jump.
     *
     * <p>Source: not stated by the wiki, but it gives Jump Boost I and II jumps of
     * 1.8361 and 2.5168 blocks (<a href="https://minecraft.wiki/w/Jumping">Jumping</a>), which this
     * reproduces exactly.
     */
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
     * <p>The two are consistent by construction: with friction at 0.546 and the
     * keys scaled by {@link #inputScale}, the steady state this produces is the
     * measured 4.317 blocks a second, and multiplying by {@link #sprintMultiplier}
     * gives the measured 5.612.
     *
     * <p>Source: "the default base value is 0.1", <a href="https://minecraft.wiki/w/Walking">Walking</a>.
     * The {@code movement_speed} attribute's generic default is 0.7; a player's base is 0.1.
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
     *
     * <p>Source: the game's code (newer versions write it as 0.21600002 over the
     * cube of slipperiness alone, the same number). The wiki gives the result, a
     * walking acceleration of 0.098 on ordinary ground
     * (<a href="https://minecraft.wiki/w/Player#Movement_speed">Player &sect; Movement speed</a>), and its
     * ice, blue ice and slime speeds (<a href="https://minecraft.wiki/w/Walking">Walking</a>) pin it.
     */
    private double groundAccelerationBase = 0.16277136d;

    /**
     * Horizontal acceleration available in mid-air, where friction does not apply.
     *
     * <p>Source: the game's code; the wiki's 7.127 blocks a second sprint-jumping
     * (<a href="https://minecraft.wiki/w/Sprinting">Sprinting</a>) depends on it and is reproduced.
     */
    private double airAcceleration = 0.02d;

    /** Extra air acceleration while sprinting. Source: as {@link #airAcceleration}. */
    private double sprintAirBonus = 0.006d;

    /**
     * Multiplier on walking speed while sprinting.
     *
     * <p>Source: the {@code sprinting} modifier, 0.3 {@code add_multiplied_total},
     * <a href="https://minecraft.wiki/w/Attribute">Attribute</a>; "30 percent faster",
     * <a href="https://minecraft.wiki/w/Sprinting">Sprinting</a>.
     */
    private double sprintMultiplier = 1.3d;

    /**
     * Multiplier the game applies to the input keys while sneaking.
     *
     * <p>Source: the {@code sneaking_speed} attribute's default (1.21 and later),
     * <a href="https://minecraft.wiki/w/Attribute">Attribute</a>; sneaking at 30% of walking speed,
     * <a href="https://minecraft.wiki/w/Player#Movement_speed">Player &sect; Movement speed</a>.
     */
    private double sneakMultiplier = 0.3d;

    /**
     * What the game scales the input keys by every tick, before they become
     * acceleration, on the ground and in the air.
     *
     * <p>Easy to miss, because a diagonal hides it: the keys are scaled first and
     * the diagonal is normalised after, so holding forward and left ends at the
     * same speed either way, and only straight-line movement is 2% slower for it.
     * Leaving it out is exactly the 2.05% the simulation once ran fast by.
     *
     * <p>Source: implied by the wiki, which gives a base attribute of 0.1
     * (<a href="https://minecraft.wiki/w/Walking">Walking</a>) and a walking acceleration of 0.098
     * (<a href="https://minecraft.wiki/w/Player#Movement_speed">Player &sect; Movement speed</a>).
     */
    private double inputScale = 0.98d;

    /**
     * Horizontal kick along the facing yaw when a jump starts while sprinting.
     *
     * <p>Source: the game's code; pinned, with {@link #airAcceleration}, by the
     * wiki's 7.127 blocks a second sprint-jumping (<a href="https://minecraft.wiki/w/Sprinting">Sprinting</a>).
     */
    private double sprintJumpBoost = 0.2d;

    /**
     * How high a ledge can be and still be walked up rather than into.
     *
     * <p>Source: the {@code step_height} attribute's default, <a href="https://minecraft.wiki/w/Attribute">Attribute</a>.
     */
    private double stepHeight = 0.6d;

    /**
     * Slipperiness of an ordinary block, used when nothing says otherwise.
     *
     * <p>Source: "Friction is 0.6 by default, 0.8 for Slime Blocks, 0.98 for Ice,
     * Packed Ice and Frosted Ice, and 0.989 for Blue Ice",
     * <a href="https://minecraft.wiki/w/Entity#Motion">Entity &sect; Motion</a>. The others are your
     * {@code CollisionSpace}'s to report.
     */
    private double defaultSlipperiness = 0.6d;

    /** Width of the simulated hitbox, in blocks. Source: 0.6, <a href="https://minecraft.wiki/w/Player">Player</a>. */
    private double hitboxWidth = 0.6d;

    /**
     * Height of the simulated hitbox, in blocks. Source: 1.8 standing,
     * <a href="https://minecraft.wiki/w/Player">Player</a>, which also gives 1.5 sneaking and 0.6
     * swimming, crawling or gliding.
     */
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
        this.inputScale = source.inputScale;
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

    /** The per-tick scaling of the keys. See {@link #inputScale}. */
    public PhysicsProfile withInputScale(double inputScale) {
        PhysicsProfile copy = new PhysicsProfile(this);
        copy.inputScale = fraction(inputScale, "input scale");
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

    /**
     * @return every value by its field name, in a fixed order: for saving a
     *         profile, or recording one. {@link #with(Map)} reads it back
     */
    public Map<String, Double> toMap() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("walkSpeed", walkSpeed);
        values.put("jumpVelocity", jumpVelocity);
        values.put("gravity", gravity);
        values.put("drag", drag);
        values.put("groundFriction", groundFriction);
        values.put("speedPerLevel", speedPerLevel);
        values.put("slownessPerLevel", slownessPerLevel);
        values.put("jumpBoostPerLevel", jumpBoostPerLevel);
        values.put("moveSpeedAttribute", moveSpeedAttribute);
        values.put("groundAccelerationBase", groundAccelerationBase);
        values.put("airAcceleration", airAcceleration);
        values.put("sprintAirBonus", sprintAirBonus);
        values.put("sprintMultiplier", sprintMultiplier);
        values.put("sneakMultiplier", sneakMultiplier);
        values.put("inputScale", inputScale);
        values.put("sprintJumpBoost", sprintJumpBoost);
        values.put("stepHeight", stepHeight);
        values.put("defaultSlipperiness", defaultSlipperiness);
        values.put("hitboxWidth", hitboxWidth);
        values.put("hitboxHeight", hitboxHeight);
        return values;
    }

    /**
     * @return this profile with every value {@code values} names replaced, as
     *         {@link #toMap()} names them; names it does not know are ignored
     */
    public PhysicsProfile with(Map<String, Double> values) {
        Validate.notNull(values, "values");
        PhysicsProfile profile = this;
        if (values.containsKey("walkSpeed")) {
            profile = profile.withWalkSpeed(values.get("walkSpeed"));
        }
        if (values.containsKey("jumpVelocity")) {
            profile = profile.withJumpVelocity(values.get("jumpVelocity"));
        }
        if (values.containsKey("gravity")) {
            profile = profile.withGravity(values.get("gravity"));
        }
        if (values.containsKey("drag")) {
            profile = profile.withDrag(values.get("drag"));
        }
        if (values.containsKey("groundFriction")) {
            profile = profile.withGroundFriction(values.get("groundFriction"));
        }
        if (values.containsKey("speedPerLevel")) {
            profile = profile.withSpeedPerLevel(values.get("speedPerLevel"));
        }
        if (values.containsKey("slownessPerLevel")) {
            profile = profile.withSlownessPerLevel(values.get("slownessPerLevel"));
        }
        if (values.containsKey("jumpBoostPerLevel")) {
            profile = profile.withJumpBoostPerLevel(values.get("jumpBoostPerLevel"));
        }
        if (values.containsKey("moveSpeedAttribute")) {
            profile = profile.withMoveSpeedAttribute(values.get("moveSpeedAttribute"));
        }
        if (values.containsKey("groundAccelerationBase")) {
            profile = profile.withGroundAccelerationBase(values.get("groundAccelerationBase"));
        }
        if (values.containsKey("airAcceleration")) {
            profile = profile.withAirAcceleration(values.get("airAcceleration"));
        }
        if (values.containsKey("sprintAirBonus")) {
            profile = profile.withSprintAirBonus(values.get("sprintAirBonus"));
        }
        if (values.containsKey("sprintMultiplier")) {
            profile = profile.withSprintMultiplier(values.get("sprintMultiplier"));
        }
        if (values.containsKey("sneakMultiplier")) {
            profile = profile.withSneakMultiplier(values.get("sneakMultiplier"));
        }
        if (values.containsKey("inputScale")) {
            profile = profile.withInputScale(values.get("inputScale"));
        }
        if (values.containsKey("sprintJumpBoost")) {
            profile = profile.withSprintJumpBoost(values.get("sprintJumpBoost"));
        }
        if (values.containsKey("stepHeight")) {
            profile = profile.withStepHeight(values.get("stepHeight"));
        }
        if (values.containsKey("defaultSlipperiness")) {
            profile = profile.withDefaultSlipperiness(values.get("defaultSlipperiness"));
        }
        if (values.containsKey("hitboxWidth") || values.containsKey("hitboxHeight")) {
            profile = profile.withHitbox(
                    values.containsKey("hitboxWidth") ? values.get("hitboxWidth") : hitboxWidth,
                    values.containsKey("hitboxHeight") ? values.get("hitboxHeight") : hitboxHeight);
        }
        return profile;
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
