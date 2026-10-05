package dev.px.combat.explosion.state;

/**
 * One mitigation step written against a {@link TargetState} instead of the
 * game's object, so it runs the same on a live target and on a recorded one.
 *
 * <pre>{@code
 * StateMitigation armour = (damage, s) -> myArmourFormula(damage, s.get("armor"), s.get("toughness"));
 *
 * Mitigation<LivingEntity> live   = Mitigation.fromState(capture, difficulty, armour, protection);
 * Mitigation<TargetState>  replay = Mitigation.ofState(difficulty, armour, protection);
 * }</pre>
 *
 * <p>Optional: a mitigation that reads the game's object directly works live,
 * but cannot be checked against recorded explosions.
 */
@FunctionalInterface
public interface StateMitigation {

    /** @return what is left of {@code damage} after this step, for a target in {@code state} */
    double apply(double damage, TargetState state);
}
