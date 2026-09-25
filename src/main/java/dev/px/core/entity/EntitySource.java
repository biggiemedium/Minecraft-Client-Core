package dev.px.core.entity;

/**
 * Your game's world, as {@link EntityService} reads it. One class per game
 * version, the same seam as {@code PacketDescriber}.
 *
 * <p>Three things to declare, once, in your own code: what entities <em>are</em>
 * ({@link EntityCategory}), what can be <em>true</em> of them
 * ({@link EntityTag}), and what <em>numbers</em> they have
 * ({@link EntityAttribute}). Core ships none of these, so nothing here changes
 * when a game adds an entity, a mechanic or a way to fight.
 *
 * <pre>{@code
 * // Your vocabulary: as coarse or as fine as your modules need.
 * public enum Kinds implements EntityCategory { PLAYER, MONSTER, CRYSTAL, OTHER }
 * public enum Tags implements EntityTag { INVISIBLE, TEAMMATE, DEAD }
 * public enum Stats implements EntityAttribute { HEALTH, ARMOR }
 *
 * public final class LegacyEntities implements EntitySource<Entity> {
 *
 *     private final Minecraft mc = Minecraft.getMinecraft();
 *
 *     public Iterable<Entity> entities() {
 *         return mc.theWorld == null ? null : mc.theWorld.loadedEntityList;
 *     }
 *
 *     public Entity self() {
 *         return mc.thePlayer;
 *     }
 *
 *     public void read(Entity e, EntityData out) {
 *         out.id(e.getEntityId())
 *            .position(e.posX, e.posY, e.posZ)
 *            .size(e.width, e.height)
 *            .eyeHeight(e.getEyeHeight())
 *            .rotation(e.rotationYaw, e.rotationPitch)
 *            .tag(Tags.INVISIBLE, e.isInvisible());
 *         if (e instanceof EntityPlayer) {
 *             out.category(Kinds.PLAYER).name(e.getName());
 *         } else if (e instanceof EntityEnderCrystal) {
 *             out.category(Kinds.CRYSTAL);
 *         } else if (e instanceof IMob) {
 *             out.category(Kinds.MONSTER);
 *         } else {
 *             out.category(Kinds.OTHER);
 *         }
 *         if (e instanceof EntityLivingBase) {
 *             EntityLivingBase living = (EntityLivingBase) e;
 *             out.set(Stats.HEALTH, living.getHealth())
 *                .set(Stats.ARMOR, living.getTotalArmorValue())
 *                .tag(Tags.DEAD, living.deathTime > 0);
 *         }
 *     }
 * }
 *
 * Core.entities().setSource(new LegacyEntities());       // once
 * }</pre>
 *
 * <p>That is the whole integration: Core reads the world at the start of every
 * tick, and every selector, lock and query works from that snapshot.
 *
 * <p>Called on the game thread, from the tick. {@link #read} may throw for an
 * entity it cannot make sense of; that entity is skipped for the tick and the
 * first failure is logged.
 *
 * @param <E> the game's entity type
 */
public interface EntitySource<E> {

    /** @return every loaded entity, or null when there is no world */
    Iterable<? extends E> entities();

    /** @return the local player, or null when there is none */
    E self();

    /** Describes one entity. Called for the local player too. */
    void read(E entity, EntityData out);
}
