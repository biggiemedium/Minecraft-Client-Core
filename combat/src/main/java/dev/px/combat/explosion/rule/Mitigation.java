package dev.px.combat.explosion.rule;

import dev.px.combat.explosion.state.StateCapture;
import dev.px.combat.explosion.state.StateMitigation;
import dev.px.combat.explosion.state.TargetState;
import dev.px.core.entity.Tracked;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.List;

/**
 * One thing that reduces damage before it lands: difficulty, armour, an
 * enchantment, an effect, a shield, absorption. Your game's rules, in your
 * game's order.
 *
 * <pre>{@code
 * Mitigation<EntityLivingBase> mine = Mitigation.chain(
 *         myDifficulty,                                    // each one yours
 *         (damage, target) -> afterArmour(damage, target.get()),
 *         myBlastProtection,
 *         myResistance);
 * }</pre>
 *
 * <p>Each step reads whatever it needs off the game's object with
 * {@link Tracked#get()}. A version that changes how armour works changes one step.
 *
 * @param <E> the game's type for what is being hurt
 */
@FunctionalInterface
public interface Mitigation<E> {

    /** @return what is left of {@code damage} after this step, for this target */
    double apply(double damage, Tracked<? extends E> target);

    /** Reduces nothing. Say so on purpose: a model without armour over-predicts against anyone wearing some. */
    static <E> Mitigation<E> none() {
        return (damage, target) -> damage;
    }

    /**
     * Steps written against captured state, for a live target: the state is read
     * off it with {@code capture} each time, then each step applies in order.
     * Written this way, the same steps can be checked against recorded explosions
     * with {@link #ofState}.
     */
    static <E> Mitigation<E> fromState(StateCapture<? super E> capture, StateMitigation... steps) {
        Validate.notNull(capture, "capture");
        List<StateMitigation> ordered = StateSteps.of(steps);
        return (damage, target) -> StateSteps.apply(damage, capture.capture(target.get()), ordered);
    }

    /** The same steps, for a target that is already a {@link TargetState}: a recorded one. */
    static Mitigation<TargetState> ofState(StateMitigation... steps) {
        List<StateMitigation> ordered = StateSteps.of(steps);
        return (damage, target) -> StateSteps.apply(damage, target.get(), ordered);
    }

    /** Applies each step to what the one before left, in order. */
    @SafeVarargs
    static <E> Mitigation<E> chain(Mitigation<? super E>... steps) {
        List<Mitigation<? super E>> ordered = Arrays.asList(steps.clone());
        for (Mitigation<? super E> step : ordered) {
            Validate.notNull(step, "step");
        }
        return (damage, target) -> {
            double left = damage;
            for (Mitigation<? super E> step : ordered) {
                left = step.apply(left, target);
            }
            return left;
        };
    }
}
