package dev.px.core.movement.prediction;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * One scenario, played out: where the entity would be each tick if it did that,
 * how well that scenario explained its last few ticks, and the weight that
 * earns it among the others.
 *
 * <p>Immutable.
 */
public final class Future {

    private final Scenario scenario;
    private final MotionState start;
    private final List<MotionState> trace;
    private final double error;
    private final double weight;

    Future(Scenario scenario, MotionState start, List<MotionState> trace, double error, double weight) {
        this.scenario = scenario;
        this.start = start;
        this.trace = Collections.unmodifiableList(trace);
        this.error = error;
        this.weight = weight;
    }

    Future withWeight(double weight) {
        return new Future(scenario, start, trace, error, weight);
    }

    public Scenario getScenario() {
        return scenario;
    }

    /** @return the scenario's name */
    public String getName() {
        return scenario.getName();
    }

    /** @return how many ticks ahead it runs */
    public int getTicks() {
        return trace.size();
    }

    /** @return every tick of it, the first one tick from now */
    public List<MotionState> getTrace() {
        return trace;
    }

    /** @param tick 0 for now, up to {@link #getTicks()} */
    public MotionState at(int tick) {
        Validate.check(tick >= 0 && tick <= trace.size(), "tick must be 0 to " + trace.size() + ", got " + tick);
        return tick == 0 ? start : trace.get(tick - 1);
    }

    /** @param tick 0 for now, up to {@link #getTicks()} */
    public Vec3 positionAt(int tick) {
        return at(tick).getPosition();
    }

    /**
     * @return the first tick, from now (0) on, at which {@code event} holds; -1 if
     *         it never does. Its feet, not its whole box: test against the box
     *         you care about yourself, with {@code state.hitbox(width, height)}
     */
    public int firstTick(Predicate<MotionState> event) {
        Validate.notNull(event, "event");
        if (event.test(start)) {
            return 0;
        }
        for (int i = 0; i < trace.size(); i++) {
            if (event.test(trace.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * @return how far off, in blocks, this scenario was on average when started
     *         from a few ticks back and run as far as this one runs; NaN when the
     *         entity has not been seen long enough to tell
     */
    public double getError() {
        return error;
    }

    /** @return its share of belief among the prediction's futures, 0 to 1; they sum to 1 */
    public double getWeight() {
        return weight;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Future(%s, weight %.3f, error %.3f, ends at %s)",
                scenario.getName(), weight, error, positionAt(trace.size()));
    }
}
