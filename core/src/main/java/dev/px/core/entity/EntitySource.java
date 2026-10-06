package dev.px.core.entity;

/**
 * Your game's world, as {@link EntityService} reads it. One class per game
 * version, the same seam as {@code PacketDescriber}.
 *
 * <p>It answers two things: which entities exist, and where each one is. What
 * an entity <em>is</em> &mdash; a player, a crystal, an enemy &mdash; is not the
 * source's business; that is what an {@link EntityTracker} of the right type
 * decides, with the game's own object in hand.
 *
 * <pre>{@code
 * public final class LegacyEntities implements EntitySource<Entity> {
 *
 *     private final Minecraft mc = Minecraft.getMinecraft();
 *
 *     public Iterable<Entity> entities() { return mc.theWorld == null ? null : mc.theWorld.loadedEntityList; }
 *     public Entity self()               { return mc.thePlayer; }
 *
 *     public double x(Entity e)          { return e.posX; }
 *     public double y(Entity e)          { return e.posY; }
 *     public double z(Entity e)          { return e.posZ; }
 *     public double width(Entity e)      { return e.width; }
 *     public double height(Entity e)     { return e.height; }
 *     public double eyeHeight(Entity e)  { return e.getEyeHeight(); }
 *     public float yaw(Entity e)         { return e.rotationYaw; }
 *     public float pitch(Entity e)       { return e.rotationPitch; }
 * }
 *
 * Core.entities().setSource(new LegacyEntities());       // once
 * }</pre>
 *
 * <p>Core has no defaults taken from any game. The three a source may leave out
 * read as zero: eyes at the position, and facing yaw 0, pitch 0.
 *
 * <p>Called on the game thread, from the tick. A method may throw for an entity
 * it cannot make sense of; that entity is skipped for the tick and the first
 * failure is logged.
 *
 * @param <E> the game's entity type: the common supertype of everything listed
 */
public interface EntitySource<E> {

    /** @return every loaded entity, or null when there is no world */
    Iterable<? extends E> entities();

    /** @return the local player, or null when there is none */
    E self();

    /** The position: the bottom centre of the entity's box. */
    double x(E entity);

    double y(E entity);

    double z(E entity);

    /** How wide and deep the box is, centred on the position. */
    double width(E entity);

    /** How tall the box is, standing on the position. */
    double height(E entity);

    /** How far above the position the entity sees from. Targeting measures from the local player's. */
    default double eyeHeight(E entity) {
        return 0d;
    }

    /** Which way it faces, in the same yaw and pitch convention as the rest of Core. */
    default float yaw(E entity) {
        return 0f;
    }

    default float pitch(E entity) {
        return 0f;
    }

    /**
     * Something that changes whenever the server sends this entity a new
     * position: a counter your packet handler bumps, or the tick the last
     * position packet was applied. Report the position the server sent, too, not
     * the one the game eases toward for drawing.
     *
     * <p>Servers do not send every entity's position every tick, so a position
     * that has not changed may be news that it stopped or no news at all. With a
     * stamp, {@code Core.prediction()} knows which; without one, -1, a position
     * counts as new when it changes, or once it has held still for its tracker's
     * {@linkplain EntityTracker#setUpdateGap update gap}.
     *
     * @return the stamp, or -1 when you do not know
     */
    default long positionStamp(E entity) {
        return -1L;
    }
}
