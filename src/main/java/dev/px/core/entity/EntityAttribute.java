package dev.px.core.entity;

/**
 * A number about an entity, in your client's own words: health, armor, a
 * cooldown, a damage estimate, whatever your version has and your modules need.
 *
 * <pre>{@code
 * public enum Stats implements EntityAttribute {
 *     HEALTH, ABSORPTION, ARMOR, HURT_TIME
 * }
 *
 * // in your EntitySource
 * out.set(Stats.HEALTH, living.getHealth()).set(Stats.ARMOR, living.getTotalArmorValue());
 *
 * // reading, sorting, filtering
 * entity.get(Stats.HEALTH);                                  // NaN when the source did not set it
 * TargetSelector.builder().sort(TargetSort.by(Stats.HEALTH)).where(Stats.HEALTH, h -> h > 0)
 * }</pre>
 *
 * <p>Core attaches no meaning to any attribute and has no default for one: an
 * attribute the source did not set this tick reads as {@code NaN}, so "no health"
 * is never confused with "zero health".
 *
 * <p>Any object works, compared by {@code equals}; an enum is simplest.
 */
public interface EntityAttribute {

    /** @return a name for logs and debug overlays; an enum's constant name by default */
    default String getName() {
        return toString();
    }
}
