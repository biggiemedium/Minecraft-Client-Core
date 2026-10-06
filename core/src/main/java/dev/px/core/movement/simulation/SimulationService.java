package dev.px.core.movement.simulation;

import dev.px.core.event.EventBus;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.core.util.math.MovementMath;
import dev.px.core.util.math.PhysicsProfile;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * The movement rules, run forwards: the profile, the world to collide against,
 * and the self-check that says when to stop trusting them.
 *
 * <p>This is where <em>your</em> future comes from. Your input is known, so
 * {@link Simulation} runs the real movement rules forward and the answer is as
 * good as the collision data: {@link #simulate}, {@link #trace}, {@link #landing}.
 *
 * <p>Somebody else's input is not known, which makes theirs a different question
 * &mdash; and {@code Core.prediction()} answers it, on top of this: it works out
 * what they are pressing from how they have moved, then runs those inputs
 * through the same rules.
 *
 * <pre>{@code
 * // once, at startup
 * Core.simulation().setCollisionSpace(new WorldCollisionSpace());
 *
 * // wherever the answer is wanted
 * MotionState landing = Core.simulation().landing(myState, held, 40);
 * }</pre>
 *
 * <p>Useful with no {@link CollisionSpace} installed: simulation falls back to
 * {@link CollisionSpace#empty()}, which gives a ballistic trajectory rather than
 * throwing. Nothing warns, because a client that only wants trajectories is not
 * misconfigured.
 *
 * <p>Game thread only, like every other per-tick path in the library.
 */
public final class SimulationService implements Service {

    private final CoreLogger logger;
    private final EventBus bus;

    /**
     * The world to collide against. Installed by the adapter.
     *
     * <p>Never null: until one is installed this is {@link CollisionSpace#empty()},
     * so a simulation still runs and answers the question "where would this go if
     * nothing were in the way".
     */
    @Getter
    @Setter
    private CollisionSpace collisionSpace = CollisionSpace.empty();

    /**
     * The movement constants simulations use.
     *
     * <p>Null follows {@link MovementMath#getProfile()}, which is what a client
     * that configured its physics in one place wants. Set it to pin this service
     * to a profile of its own.
     */
    @Setter
    private PhysicsProfile profile;

    /**
     * Scores the simulation against what the game actually did, so a caller can
     * tell when it has stopped describing reality.
     *
     * <p>Fed by {@link #observe}. Silent and empty until something does.
     */
    @Getter
    private final DriftMonitor drift = new DriftMonitor();

    public SimulationService(CoreLogger logger, EventBus bus) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
    }

    @Override
    public String getName() {
        return "Simulation";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        drift.reset();
    }

    /** @return the profile simulations use: this service's, or the global default. */
    public PhysicsProfile getProfile() {
        return profile != null ? profile : MovementMath.getProfile();
    }

    /**
     * Records where the player actually is and scores the last prediction.
     *
     * <p>Once a tick, with the input that was held over the tick that just
     * elapsed. Costs one simulated step and tells you, through
     * {@link #isReliable()}, whether predicting is currently worth anything.
     *
     * @return whether a comparison was made; see {@link DriftMonitor#observe}
     */
    public boolean observe(MotionState actual, MovementInput input) {
        return drift.observe(actual, input, getProfile(), collisionSpace);
    }

    /**
     * @return whether the simulation is currently tracking the game closely
     *         enough to predict from
     *
     * <p>False until {@link #observe} has been called enough times to judge, so a
     * caller that gates on this degrades to not predicting rather than to
     * predicting badly.
     */
    public boolean isReliable() {
        return drift.isReliable();
    }

    // ---------------------------------------------------------- simulating

    /** One tick of real movement, against the installed world. */
    public MotionState step(MotionState state, MovementInput input) {
        return Simulation.step(getProfile(), state, input, collisionSpace);
    }

    /** {@code ticks} ticks with the same input held. */
    public MotionState simulate(MotionState state, MovementInput input, int ticks) {
        return Simulation.simulate(getProfile(), state, input, ticks, collisionSpace);
    }

    /** Every intermediate state, for drawing a predicted path. */
    public List<MotionState> trace(MotionState state, MovementInput input, int ticks) {
        return Simulation.trace(getProfile(), state, input, ticks, collisionSpace);
    }

    /**
     * @return the first state within {@code ticks} that is on the ground, or null
     *         if it is still falling
     */
    public MotionState landing(MotionState state, MovementInput input, int ticks) {
        MotionState current = state;
        for (int step = 0; step < ticks; step++) {
            current = step(current, input);
            if (current.isOnGround()) {
                return current;
            }
        }
        return null;
    }
}
