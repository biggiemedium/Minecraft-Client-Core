package dev.px.core.movement.simulation;

import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.math.Vec3;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.core.util.math.MovementMath;
import dev.px.core.util.math.PhysicsProfile;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Where things are going: the tracker, the world to collide against, and the
 * clock that ages both.
 *
 * <p>Two questions that look like one and are not, which is why this holds two
 * mechanisms rather than one:
 *
 * <ul>
 *   <li><b>Where will <em>I</em> be?</b> Deterministic. The input is known, so
 *       {@link Simulation} runs the real movement rules forward and the answer is
 *       as good as the collision data. Use {@link #simulate} and {@link #trace}.
 *   <li><b>Where will <em>they</em> be?</b> A guess. Nobody tells the client
 *       another player's intentions, so all there is to go on is where they have
 *       been. {@link #predict} extends the measured velocity in a straight line,
 *       and is worth trusting for a tick or two of lag compensation and not much
 *       further.
 * </ul>
 *
 * <p>Conflating those is the mistake this service is arranged to prevent. A
 * twenty-tick {@code simulate} of the local player is a good estimate; a
 * twenty-tick {@code predict} of someone else is a straight line drawn through
 * where they used to be.
 *
 * <pre>{@code
 * // once, at startup
 * Core.simulation().setCollisionSpace(new WorldCollisionSpace());
 *
 * // every tick, for anything worth knowing about
 * Core.simulation().record(other.getEntityId(), position, other.onGround);
 *
 * // wherever the answer is wanted
 * MotionState landing = Core.simulation().landing(myState, held, 40);
 * Vec3 soon = Core.simulation().predict(targetId, 2);
 * }</pre>
 *
 * <p>Inert but useful with no {@link CollisionSpace} installed: tracking needs no
 * world at all and works fully, and simulation falls back to
 * {@link CollisionSpace#empty()}, which gives a ballistic trajectory rather than
 * throwing. Nothing warns, because a client that only wants the tracker is not
 * misconfigured.
 *
 * <p>Game thread only, like every other per-tick path in the library.
 */
public final class SimulationService implements Service {

    /** Ticks of history each track keeps unless the client says otherwise. */
    public static final int DEFAULT_HISTORY_TICKS = 20;

    /** How long a track survives without a sample before it is dropped. */
    public static final int DEFAULT_FORGET_TICKS = 60;

    private final CoreLogger logger;
    private final EventBus bus;

    /** The recorded history. Replaced if {@link #setHistoryTicks} is called before startup. */
    @Getter
    private MotionTracker tracker = new MotionTracker(DEFAULT_HISTORY_TICKS);

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

    /** Ticks a track may go without a sample before {@link #beginTick()} drops it. */
    @Getter
    @Setter
    private int forgetAfterTicks = DEFAULT_FORGET_TICKS;

    /**
     * Scores the simulation against what the game actually did, so a caller can
     * tell when it has stopped describing reality.
     *
     * <p>Fed by {@link #observe}. Silent and empty until something does.
     */
    @Getter
    private final DriftMonitor drift = new DriftMonitor();

    @Getter
    private long tick;

    private Subscription tickSubscription;

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
        tickSubscription = bus.on(TickEvent.class, Priority.HIGHEST, event -> {
            if (event.getStage() == Stage.PRE) {
                beginTick();
            }
        });
    }

    @Override
    public void stop() {
        if (tickSubscription != null) {
            tickSubscription.close();
            tickSubscription = null;
        }
        tracker.clear();
        drift.reset();
    }

    /**
     * How many ticks of history each track keeps.
     *
     * <p>Rebuilds the tracker, discarding what it held, so call it during setup
     * rather than mid-session. Two is the minimum, because a velocity needs two
     * samples to exist.
     */
    public void setHistoryTicks(int ticks) {
        this.tracker = new MotionTracker(ticks);
    }

    /** @return the profile simulations use: this service's, or the global default. */
    public PhysicsProfile getProfile() {
        return profile != null ? profile : MovementMath.getProfile();
    }

    // -------------------------------------------------------------- clock

    /**
     * Advances the clock and drops tracks nobody has updated.
     *
     * <p>Wired to {@link TickEvent} at {@link Priority#HIGHEST} and
     * {@link Stage#PRE} on startup. An adapter that does not post ticks calls this
     * from its game loop instead &mdash; without it, samples all land on tick zero,
     * every velocity comes out as a divide by nothing, and nothing is ever
     * forgotten.
     */
    public void beginTick() {
        tick++;
        if (forgetAfterTicks > 0) {
            int dropped = tracker.evictBefore(tick - forgetAfterTicks);
            if (dropped > 0) {
                logger.debug("Forgot " + dropped + " motion track(s) with no recent samples");
            }
        }
    }

    // ------------------------------------------------------------ tracking

    /** Records where something is, stamped with the current tick. */
    public void record(Object key, Vec3 position, boolean onGround) {
        tracker.record(key, position, onGround, tick);
    }

    /** @return this key's history, or null if nothing has been recorded for it. */
    public MotionTrack track(Object key) {
        return tracker.get(key);
    }

    public boolean isTracked(Object key) {
        return tracker.isTracked(key);
    }

    public boolean forget(Object key) {
        return tracker.forget(key);
    }

    /**
     * @return where a tracked thing will be in {@code ticks}, or null if it is not
     *         tracked
     *
     * <p>A straight line along its measured velocity. See the class notes on why
     * that is the right shape of answer for something whose input is unknown, and
     * why it stops being useful quickly.
     */
    public Vec3 predict(Object key, int ticks) {
        MotionTrack track = tracker.get(key);
        return track == null ? null : track.extrapolate(ticks);
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

    /**
     * Simulates a tracked thing forward, using the velocity it was measured at and
     * assuming it holds no keys.
     *
     * <p>Between {@link #predict} and {@link #simulate}: it collides and falls, so
     * it will not walk through a wall or hover over a cliff, but it still assumes
     * the thing does nothing of its own accord. Good for "where does this
     * projectile land", poor for "where is this player running".
     *
     * @return the state after {@code ticks}, or null if the key is not tracked
     */
    public MotionState coast(Object key, int ticks) {
        MotionTrack track = tracker.get(key);
        if (track == null) {
            return null;
        }
        MotionState state = track.toState();
        return state == null ? null : simulate(state, MovementInput.none(0f), ticks);
    }
}
