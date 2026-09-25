package dev.px.core.entity;

/**
 * A yes-or-no fact about an entity, in your client's own words.
 *
 * <pre>{@code
 * public enum Tags implements EntityTag {
 *     LIVING, DEAD, INVISIBLE, TEAMMATE, BOT, SNEAKING
 * }
 *
 * // in your EntitySource
 * out.tag(Tags.INVISIBLE, e.isInvisible()).tag(Tags.LIVING, e instanceof EntityLivingBase);
 *
 * // in a selector
 * TargetSelector.builder().withAll(Tags.LIVING).without(Tags.DEAD, Tags.TEAMMATE, Tags.BOT)
 * }</pre>
 *
 * <p>Core attaches no meaning to any tag. It stores them one bit each, so a
 * selector that excludes five of them tests all five with a single AND per
 * entity (see {@link TagSet}), and there is no limit on how many you declare.
 *
 * <p>Any object works, compared by {@code equals}; an enum is simplest.
 */
public interface EntityTag {

    /** @return a name for logs and debug overlays; an enum's constant name by default */
    default String getName() {
        return toString();
    }
}
