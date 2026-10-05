package dev.px.combat.monitor;

/**
 * How much damage an entity can still take, read off your game's object: what
 * the {@link DamageMonitor} watches change after an explosion.
 *
 * <pre>{@code
 * Vitals<EntityLivingBase> vitals = new Vitals<EntityLivingBase>() {
 *     public double pool(EntityLivingBase e)          { return e.getHealth() + e.getAbsorptionAmount(); }
 *     public boolean isRecentlyHurt(EntityLivingBase e) { return e.hurtTime > 0; }
 *     public boolean isTrusted(EntityLivingBase e)    { return e == mc.thePlayer || serverShowsHealth; }
 * };
 * }</pre>
 *
 * @param <E> the game's type for what can be hurt
 */
@FunctionalInterface
public interface Vitals<E> {

    /**
     * @return everything damage is taken from before death: health, plus
     *         absorption or anything else your game spends first. NaN when it
     *         cannot be known
     */
    double pool(E entity);

    /**
     * Whether the entity's health can be believed. Some servers hide or fake other
     * players' health; a sample read from one would teach the monitor nonsense.
     * The local player's is always real.
     */
    default boolean isTrusted(E entity) {
        return true;
    }

    /**
     * Whether the entity is still recovering from a hit, so another would land
     * reduced or not at all. A sample taken then measures the recovery rule, not
     * the explosion, and is thrown away.
     */
    default boolean isRecentlyHurt(E entity) {
        return false;
    }
}
