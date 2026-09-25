package dev.px.core.entity;

/**
 * What an entity is, in your client's own words.
 *
 * <p>Core has no list of entity types and never will: a game that adds a new
 * kind of entity, or a new way of fighting with an old one, must not need a
 * change here. You declare the categories your modules care about, usually as
 * an enum, the same way module categories work:
 *
 * <pre>{@code
 * public enum Kinds implements EntityCategory {
 *     PLAYER, MONSTER, ANIMAL, CRYSTAL, ITEM, OTHER
 * }
 * }</pre>
 *
 * <p>Your {@link EntitySource} gives every entity exactly one, and selectors,
 * range queries and the spatial index all work per category. How fine to cut
 * them is yours to decide: "every hostile mob" and "one category per mob type"
 * are both fine. Anything an entity can be <em>as well as</em> its category
 * &mdash; invisible, on your team, a boss &mdash; is an {@link EntityTag}.
 *
 * <p>Any object works, compared by {@code equals}; an enum is simplest.
 */
public interface EntityCategory {

    /** What an entity is filed under when the source gave it no category. */
    EntityCategory UNCATEGORIZED = Uncategorized.INSTANCE;

    /** @return a name for logs and debug overlays; an enum's constant name by default */
    default String getName() {
        return toString();
    }
}

/** The one category Core defines, so an entity the source forgot to categorise is still indexed. */
enum Uncategorized implements EntityCategory {
    INSTANCE;

    @Override
    public String getName() {
        return "uncategorized";
    }
}
