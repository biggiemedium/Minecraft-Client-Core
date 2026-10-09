package dev.px.testkit;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;

/**
 * Someone else in the sandbox: a mob, another player. Moved by Core's movement
 * rules with keys from a script, and seen by the sandbox's entity service only
 * as positions, the way the game shows other entities.
 *
 * <pre>{@code
 * SimEntity zombie = sandbox.entity("zombie", Vec3.of(8, 64, 0), tick -> MovementInput.forward(90f));
 * zombie.script(tick -> MovementInput.none(0f));     // stop
 * sandbox.targets().best(sandbox.selector());         // found like any tracked entity
 * }</pre>
 *
 * <p>What it is &mdash; hostile, a friend, a zombie &mdash; is its name and
 * whatever {@linkplain #tag tag} the test gives it; the kit knows nothing about
 * mobs.
 */
public final class SimEntity {

    /** Keys for each tick, counted from when it was added. */
    @FunctionalInterface
    public interface Script {
        MovementInput at(int tick);
    }

    private final String name;
    private MotionState state;
    private Script script;
    private PhysicsProfile rules;
    private Object tag;
    private double eyeHeight;
    private float yaw;
    private int tick;
    private boolean removed;

    SimEntity(String name, Vec3 at, Script script, PhysicsProfile rules) {
        this.name = name;
        this.state = MotionState.at(at);
        this.script = script;
        this.rules = rules;
    }

    void step(CollisionSpace world) {
        MovementInput input = script.at(tick++);
        if (input == null) {
            input = MovementInput.none(yaw);
        }
        yaw = input.getYaw();
        state = Simulation.step(rules, state, input, world);
    }

    public SimEntity script(Script script) {
        this.script = Validate.notNull(script, "script");
        return this;
    }

    public SimEntity teleport(Vec3 at) {
        state = MotionState.at(Validate.notNull(at, "at"));
        return this;
    }

    public SimEntity push(Vec3 velocity) {
        state = state.withVelocity(state.getVelocity().add(Validate.notNull(velocity, "velocity")));
        return this;
    }

    /** The rules it moves by. Its hitbox comes from them too. */
    public SimEntity rules(PhysicsProfile rules) {
        this.rules = Validate.notNull(rules, "rules");
        return this;
    }

    /** Anything the test wants to know it by: a kind, a team. */
    public SimEntity tag(Object tag) {
        this.tag = tag;
        return this;
    }

    public SimEntity eyeHeight(double height) {
        this.eyeHeight = height;
        return this;
    }

    /** Takes it out of the world: the entity service stops seeing it. */
    public void remove() {
        removed = true;
    }

    public String getName() {
        return name;
    }

    public MotionState getState() {
        return state;
    }

    public Vec3 getPosition() {
        return state.getPosition();
    }

    public PhysicsProfile getRules() {
        return rules;
    }

    public Object getTag() {
        return tag;
    }

    public double getEyeHeight() {
        return eyeHeight;
    }

    public float getYaw() {
        return yaw;
    }

    public boolean isRemoved() {
        return removed;
    }

    @Override
    public String toString() {
        return name + " at " + state.getPosition();
    }
}
