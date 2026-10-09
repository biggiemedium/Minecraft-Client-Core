package dev.px.core.flow;

import dev.px.core.util.Validate;

import java.util.function.BooleanSupplier;

/**
 * A yes-or-no question a flow asks: whether to interrupt, whether a step's work
 * still holds, whether to stop.
 *
 * <pre>{@code
 * Condition lowHealth = c -> player.getHealth() < 8;
 * Condition dead = c -> !c.get(TARGET).isTracked();
 * step.until(dead.or(lowHealth));
 * }</pre>
 *
 * <p>Given the context so it can read the flow's {@link Key}s and shared memory.
 * Asked on the game thread, often every tick, so keep it cheap.
 */
@FunctionalInterface
public interface Condition {

    boolean test(FlowContext c);

    default Condition and(Condition other) {
        Validate.notNull(other, "other");
        return c -> test(c) && other.test(c);
    }

    default Condition or(Condition other) {
        Validate.notNull(other, "other");
        return c -> test(c) || other.test(c);
    }

    default Condition negate() {
        return c -> !test(c);
    }

    /** @return a condition that ignores the context, for questions about the game alone */
    static Condition of(BooleanSupplier question) {
        Validate.notNull(question, "question");
        return c -> question.getAsBoolean();
    }

    static Condition not(Condition condition) {
        Validate.notNull(condition, "condition");
        return condition.negate();
    }
}
