package dev.px.core.entity;

/**
 * What your {@link EntitySource} writes about one entity.
 *
 * <p>Core has no defaults taken from any game. Anything you do not write is
 * simply absent: no category means {@link EntityCategory#UNCATEGORIZED}, no size
 * means a box with no volume at the position, no eye height means the eyes are
 * at the position, no tags means none, and an attribute you did not set reads as
 * {@code NaN}. Write what your modules need and nothing else.
 *
 * <pre>{@code
 * out.category(Kinds.PLAYER)
 *    .name(e.getName())
 *    .position(e.posX, e.posY, e.posZ)
 *    .size(e.width, e.height)
 *    .eyeHeight(e.getEyeHeight())
 *    .rotation(e.rotationYaw, e.rotationPitch)
 *    .tag(Tags.INVISIBLE, e.isInvisible())
 *    .set(Stats.HEALTH, e.getHealth());
 * }</pre>
 *
 * <p>Every call is a field store and allocates nothing. Valid only during the
 * {@code read} call it was passed to; do not keep it.
 */
public interface EntityData {

    /** Your id for the entity, for display and debugging. Core keys entities by instance, not by this. */
    EntityData id(int id);

    /** What the entity is. One per entity; selectors and the spatial index work per category. */
    EntityData category(EntityCategory category);

    /** A name, if it has one. {@code excludeFriends()} matches friends against it. */
    EntityData name(String name);

    /** Where the entity is: the bottom centre of its box when {@link #size} builds the box. */
    EntityData position(double x, double y, double z);

    /** A box {@code width} wide and deep and {@code height} tall, standing centred on {@link #position}. */
    EntityData size(double width, double height);

    /** An explicit box, for entities whose box is not centred on their position. Call after {@link #position}. */
    EntityData box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    /** How far above {@link #position} the entity sees from. Targeting measures from the local player's. */
    EntityData eyeHeight(double eyeHeight);

    /** Which way it faces, in the same yaw and pitch convention as the rest of Core. */
    EntityData rotation(float yaw, float pitch);

    /** Adds a tag. */
    EntityData tag(EntityTag tag);

    /** Adds the tag when {@code present} is true; saves an {@code if} per tag. */
    EntityData tag(EntityTag tag, boolean present);

    /** Sets a number. */
    EntityData set(EntityAttribute attribute, double value);
}
