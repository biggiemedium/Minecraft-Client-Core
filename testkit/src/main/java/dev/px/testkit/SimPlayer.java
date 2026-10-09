package dev.px.testkit;

import dev.px.core.control.Click;
import dev.px.core.control.ClickSink;
import dev.px.core.control.MovementSink;
import dev.px.core.math.MathUtil;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.MovementCorrection;
import dev.px.core.movement.rotation.RotationMode;
import dev.px.core.movement.rotation.RotationSink;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The sandbox's player: moved by Core's own movement rules with whatever keys
 * the control service resolves, facing wherever the rotation service turns it.
 *
 * <pre>{@code
 * SimPlayer me = sandbox.player(Vec3.of(0.5, 64, 0.5));
 * sandbox.run(20);
 * me.getPosition();             // where the flow walked it
 * me.getClicks();               // ["hold USE", "release USE", "click ATTACK"]
 * me.push(Vec3.of(0, 0.4, 0.6));   // knock it back, as a hit would
 * }</pre>
 *
 * <p>It is the sink for all three of Core's control services, as an adapter's
 * would be. Keys last the tick they were applied; with none applied, it stands
 * still. Keys meant for a yaw other than the one it faces are corrected to it
 * exactly, as an adapter using {@link MovementCorrection} would.
 */
public final class SimPlayer implements MovementSink, ClickSink, RotationSink {

    private MotionState state;
    private Vec2 rotation = Vec2.ZERO;
    private PhysicsProfile rules;
    private double eyeHeight;
    private MovementInput keys;
    private MovementInput lastKeys;
    private final List<String> clicks = new ArrayList<>();
    private final Set<Click> held = EnumSet.noneOf(Click.class);
    private final List<MotionState> trail = new ArrayList<>();

    SimPlayer(Vec3 at, PhysicsProfile rules) {
        this.state = MotionState.at(at);
        this.rules = rules;
    }

    // ------------------------------------------------------------- the sinks

    @Override
    public void apply(MovementInput input) {
        keys = input;
    }

    @Override
    public void click(Click button) {
        clicks.add("click " + button);
    }

    @Override
    public void setHeld(Click button, boolean down) {
        clicks.add((down ? "hold " : "release ") + button);
        if (down) {
            held.add(button);
        } else {
            held.remove(button);
        }
    }

    @Override
    public Vec2 getRotation() {
        return rotation;
    }

    @Override
    public void apply(Vec2 rotation, RotationMode mode) {
        this.rotation = rotation;
    }

    // ------------------------------------------------------------ moving

    /** Moves one tick with the keys applied this tick, then forgets them. */
    void step(CollisionSpace world) {
        MovementInput input = keys == null ? MovementInput.none(rotation.getYaw()) : facing(keys);
        lastKeys = keys;
        keys = null;
        state = Simulation.step(rules, state, input, world);
        trail.add(state);
    }

    /** @return the keys as the game would press them at the yaw the player faces */
    private MovementInput facing(MovementInput input) {
        float yaw = rotation.getYaw();
        if (Math.abs(MathUtil.angleDifference(input.getYaw(), yaw)) < 1.0E-4f) {
            return input.withYaw(yaw);
        }
        MovementCorrection.Input fixed = MovementCorrection.correct(input.getYaw(), input.getForward(),
                input.getStrafe(), yaw, MovementCorrection.Mode.EXACT);
        return input.withYaw(yaw).withKeys(fixed.getForward(), fixed.getStrafe());
    }

    // ------------------------------------------------------------ changing it

    /** Puts it somewhere else, standing still on the ground. */
    public SimPlayer teleport(Vec3 at) {
        state = MotionState.at(Validate.notNull(at, "at"));
        return this;
    }

    /** Adds to its velocity, as a hit or an explosion would. */
    public SimPlayer push(Vec3 velocity) {
        state = state.withVelocity(state.getVelocity().add(Validate.notNull(velocity, "velocity")));
        return this;
    }

    /** Turns its head, as the player would with the mouse. */
    public SimPlayer face(Vec2 rotation) {
        this.rotation = Validate.notNull(rotation, "rotation");
        return this;
    }

    /** The rules it really moves by, which may differ from the ones the simulation plans with. */
    public SimPlayer rules(PhysicsProfile rules) {
        this.rules = Validate.notNull(rules, "rules");
        return this;
    }

    /** How far above its feet it sees from, for targeting; 0 until you say. */
    public SimPlayer eyeHeight(double height) {
        this.eyeHeight = height;
        return this;
    }

    // ------------------------------------------------------------ reading

    public MotionState getState() {
        return state;
    }

    public Vec3 getPosition() {
        return state.getPosition();
    }

    public double getEyeHeight() {
        return eyeHeight;
    }

    public PhysicsProfile getRules() {
        return rules;
    }

    /** @return the keys it moved with last tick, as claimed; null if none were */
    public MovementInput getLastKeys() {
        return lastKeys;
    }

    /** @return every click, hold and release, in order */
    public List<String> getClicks() {
        return Collections.unmodifiableList(clicks);
    }

    public boolean isHeld(Click button) {
        return held.contains(button);
    }

    /** @return every state it has been in, one a tick */
    public List<MotionState> getTrail() {
        return Collections.unmodifiableList(trail);
    }

    @Override
    public String toString() {
        return "SimPlayer(" + state + ", facing " + rotation + ")";
    }
}
