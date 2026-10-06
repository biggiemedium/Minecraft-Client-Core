package dev.px.core.movement.prediction;

import dev.px.core.entity.Tracked;
import dev.px.core.util.math.PhysicsProfile;

/**
 * The rules one entity moves by right now, as your client knows them: its
 * effects, its attributes, its pose. Yours to write, because only the game knows
 * that an enemy drank a Speed II potion, that a server raised their step height,
 * or that they are riding a horse.
 *
 * <pre>{@code
 * Core.prediction().setPhysics((entity, base) -> {
 *     LivingEntity living = (LivingEntity) entity.get();
 *     if (living.hasVehicle() || living.isFallFlying() || living.isTouchingWater()) {
 *         return null;                                        // not walking: the rules do not apply
 *     }
 *     double pace = 1 + base.getSpeedPerLevel() * Game.speedLevel(living)
 *                     - base.getSlownessPerLevel() * Game.slownessLevel(living);
 *     return base
 *             .withMoveSpeedAttribute(base.getMoveSpeedAttribute() * pace)
 *             .withJumpVelocity(MovementMath.jumpVelocity(base, Game.jumpBoostLevel(living)));
 * });
 * }</pre>
 *
 * <p>Since 1.20.5 a server can change most of these per player as attributes:
 * read {@code gravity}, {@code step_height} and {@code jump_strength} from the
 * entity. For movement speed, use the value <em>without</em> the sprinting
 * modifier: the simulation applies sprinting itself, from the keys.
 *
 * <p>What it tells the prediction is <em>known</em>, so it is never mistaken for
 * cheating: someone with Speed II who moves 40% faster is moving by the rules.
 * Whatever is left over &mdash; moving faster than anything known explains &mdash;
 * is what {@link Behaviour} learns.
 *
 * <p>Asked once per prediction, on the game thread.
 */
@FunctionalInterface
public interface EntityPhysics {

    /** Everyone moves by the base rules: the default, which knows no effects. */
    EntityPhysics RULES = (entity, base) -> base;

    /**
     * @param entity the entity being predicted
     * @param base   the simulation's profile, with the hitbox already resized to the entity's
     * @return the rules it moves by now; null when it is moving in a way the rules
     *         do not model &mdash; riding, gliding, swimming &mdash; and only what it
     *         has been seen to do can say where it goes
     */
    PhysicsProfile profile(Tracked<?> entity, PhysicsProfile base);
}
