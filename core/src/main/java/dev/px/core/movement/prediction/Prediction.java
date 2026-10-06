package dev.px.core.movement.prediction;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where an entity may be over the next few ticks, two ways:
 *
 * <ul>
 *   <li><b>Likely</b>: one {@link Future} per scenario, each weighted by how well
 *       it explained the entity's recent movement. {@link #chance} and
 *       {@link #chanceBy} add them up.
 *   <li><b>Possible</b>: {@link #earliestPossible}, the soonest it could be
 *       somewhere at all, from the fastest it moves by its rules or has been seen
 *       to move. A floor, never a guess: if it says four ticks, it cannot be there
 *       in three.
 * </ul>
 *
 * <pre>{@code
 * Prediction next = Core.prediction().predict(target, 6);
 * Vec3 there = next.likeliest().positionAt(3);                       // the best single guess
 * double odds = next.chance(state -> hole.contains(state.getPosition()));   // across every future
 * int soonest = next.earliestPossible(holeBox);                      // however it moves
 * if (next.isReliable()) { ... }
 * }</pre>
 *
 * <p>Every tick count is from <em>now</em>. When the server has not sent the
 * entity's position for a tick or two, the futures start from the last one it
 * did send, {@link #getAge()} ticks ago, and are already that far along.
 *
 * <p>Immutable; a snapshot of the tick it was made on.
 */
public final class Prediction {

    private final Tracked<?> entity;
    private final MotionEstimate estimate;
    private final List<Future> futures;
    private final int ticks;
    private final boolean reliable;
    private final Behaviour behaviour;
    private final Envelope envelope;

    Prediction(Tracked<?> entity, MotionEstimate estimate, List<Future> futures, int ticks, boolean reliable,
               Behaviour behaviour, Envelope envelope) {
        this.entity = entity;
        this.estimate = estimate;
        this.futures = Collections.unmodifiableList(futures);
        this.ticks = ticks;
        this.reliable = reliable;
        this.behaviour = behaviour;
        this.envelope = envelope;
    }

    public Tracked<?> getEntity() {
        return entity;
    }

    /** @return what the entity is doing now, which every future starts from */
    public MotionEstimate getEstimate() {
        return estimate;
    }

    /** @return ticks since the position the futures start from: 0 when this tick's was news */
    public int getAge() {
        return estimate.getTicksAgo();
    }

    /** @return how it has been seen to move, which the futures and the possible bound lean on */
    public Behaviour getBehaviour() {
        return behaviour;
    }

    /** @return how many ticks ahead every future runs */
    public int getTicks() {
        return ticks;
    }

    /** @return every future, the heaviest first */
    public List<Future> getFutures() {
        return futures;
    }

    /** @return the heaviest future */
    public Future likeliest() {
        return futures.get(0);
    }

    /** @return the likeliest future's position {@code tick} ticks from now */
    public Vec3 positionAt(int tick) {
        return likeliest().positionAt(tick);
    }

    /**
     * @return the share of belief, 0 to 1, in futures where {@code event} happens
     *         at some tick, now included
     */
    public double chance(Predicate<MotionState> event) {
        return chanceBy(event, ticks);
    }

    /**
     * @return the share of belief, 0 to 1, in futures where {@code event} has
     *         happened by {@code tick}, now included
     */
    public double chanceBy(Predicate<MotionState> event, int tick) {
        Validate.notNull(event, "event");
        double chance = 0d;
        for (Future future : futures) {
            int first = future.firstTick(event);
            if (first >= 0 && first <= tick) {
                chance += future.getWeight();
            }
        }
        return Math.min(1d, chance);
    }

    /**
     * The soonest, in ticks from now, the entity's box could touch {@code target}
     * however it moves: at the fastest of what its rules allow and what it has
     * been seen to do, going straight there through any wall, dropping or climbing
     * as fast as it ever has.
     *
     * <p>A floor, not a forecast. Walls and the way it is facing can only make it
     * later, so the only way to beat it is to move faster than this entity has
     * yet been seen to.
     *
     * @return 0 when it already touches it; {@code Integer.MAX_VALUE} when it
     *         could never climb that high
     */
    public int earliestPossible(Box target) {
        Validate.notNull(target, "target");
        return envelope.earliest(target, estimate.getTicksAgo());
    }

    /**
     * @return whether its likeliest future would have predicted the entity's last
     *         few ticks to within the service's reliable error. False when it has
     *         not been seen long enough to tell, and when it is doing something the
     *         movement rules do not model: being knocked back, swimming, flying
     */
    public boolean isReliable() {
        return reliable;
    }

    @Override
    public String toString() {
        return "Prediction(" + ticks + " ticks, " + (reliable ? "reliable" : "unreliable") + ", " + futures + ")";
    }
}
