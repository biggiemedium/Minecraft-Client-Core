package dev.px.combat.explosion.rule;

import dev.px.combat.explosion.state.StateMitigation;
import dev.px.combat.explosion.state.TargetState;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.List;

/** Runs {@link StateMitigation} steps in order, for {@link Mitigation#fromState} and {@link Mitigation#ofState}. */
final class StateSteps {

    private StateSteps() {
    }

    static List<StateMitigation> of(StateMitigation[] steps) {
        List<StateMitigation> ordered = Arrays.asList(steps.clone());
        for (StateMitigation step : ordered) {
            Validate.notNull(step, "step");
        }
        return ordered;
    }

    static double apply(double damage, TargetState state, List<StateMitigation> steps) {
        double left = damage;
        for (StateMitigation step : steps) {
            left = step.apply(left, state);
        }
        return left;
    }
}
