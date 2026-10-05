package dev.px.combat.explosion.state;

/**
 * Reads a {@link TargetState} off your game's object: the one place that knows
 * where armour, enchantments and effects live in your version.
 *
 * <pre>{@code
 * StateCapture<LivingEntity> capture = e -> TargetState.builder()
 *         .put("armor", Math.floor(e.getAttributeValue(EntityAttributes.GENERIC_ARMOR)))
 *         .put("toughness", e.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS))
 *         .put("protection", myProtectionOf(e))
 *         .build();
 * }</pre>
 *
 * @param <E> the game's type for what is being hurt
 */
@FunctionalInterface
public interface StateCapture<E> {

    TargetState capture(E entity);
}
