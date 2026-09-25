package dev.px.core.entity;

import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;

import java.util.Locale;

/**
 * One entity as of the last tick: where it is, how big, which way it faces, and
 * whatever your {@link EntitySource} said about it in your own categories, tags
 * and attributes.
 *
 * <p>Core knows only what is true of an object in any 3D world &mdash; a
 * position, a box, a facing, how it moved since last tick, how long it has been
 * seen. Everything that belongs to a particular game is yours: its category, its
 * tags, its numbers.
 *
 * <p><b>One object per entity, for as long as it exists.</b> {@link EntityService}
 * updates it in place every tick rather than building a new one, so a module
 * that holds a reference &mdash; a current target, something it is tracking
 * &mdash; keeps seeing that same entity move. When the entity leaves the world
 * the object is never reused; {@link #isTracked()} turns false and its fields
 * keep their last values.
 *
 * <p><b>Primitives first.</b> {@link #getX()}, {@link #squaredDistanceToBox} and
 * the rest read fields and allocate nothing. {@link #getPosition()},
 * {@link #getBox()} and {@link #getEyePosition()} build their value once per
 * tick on first ask.
 *
 * <p>Read on the game thread. The snapshot changes at the start of each tick.
 */
public final class TrackedEntity {

    private static final long[] NO_TAGS = new long[0];
    private static final double[] NO_ATTRIBUTES = new double[0];

    // Written by EntityService only; all package-private.
    final Object handle;
    int id = -1;
    EntityCategory category = EntityCategory.UNCATEGORIZED;
    int categorySlot = Slots.of(EntityCategory.UNCATEGORIZED);
    String name;
    double x;
    double y;
    double z;
    double velocityX;
    double velocityY;
    double velocityZ;
    double minX;
    double minY;
    double minZ;
    double maxX;
    double maxY;
    double maxZ;
    double eyeHeight;
    float yaw;
    float pitch;
    long[] tagWords = NO_TAGS;
    double[] attributes = NO_ATTRIBUTES;
    int ticksTracked;
    boolean self;
    long lastSeen;
    boolean removed;

    private Vec3 position;
    private Vec3 eye;
    private Box box;

    TrackedEntity(Object handle) {
        this.handle = handle;
    }

    /** Drops the per-tick caches; called by the service after each read. */
    void invalidate() {
        position = null;
        eye = null;
        box = null;
    }

    // ------------------------------------------------------------ identity

    /**
     * @return the game's own entity object, cast to whatever the caller expects
     *
     * <p>How a module gets from a target back to something it can act on.
     */
    @SuppressWarnings("unchecked")
    public <E> E getHandle() {
        return (E) handle;
    }

    public int getId() {
        return id;
    }

    public EntityCategory getCategory() {
        return category;
    }

    public boolean is(EntityCategory category) {
        return this.category.equals(category);
    }

    /** @return the name, or null when the source gave none */
    public String getName() {
        return name;
    }

    /** @return whether this is the local player */
    public boolean isSelf() {
        return self;
    }

    /** @return whether the entity was in the world on the last tick */
    public boolean isTracked() {
        return !removed;
    }

    /** @return ticks in a row this entity has been seen; 0 on the tick it appeared */
    public int getTicksTracked() {
        return ticksTracked;
    }

    // --------------------------------------------------- tags and numbers

    /** @return whether the source gave it this tag this tick. For many tags at once, use a {@link TagSet} */
    public boolean has(EntityTag tag) {
        int slot = Slots.of(tag);
        int word = slot >>> 6;
        return word < tagWords.length && (tagWords[word] & (1L << slot)) != 0L;
    }

    /** @return the number the source set this tick, or {@code NaN} when it set none */
    public double get(EntityAttribute attribute) {
        int slot = Slots.of(attribute);
        return slot < attributes.length ? attributes[slot] : Double.NaN;
    }

    /** @return the number, or {@code fallback} when the source set none */
    public double get(EntityAttribute attribute, double fallback) {
        double value = get(attribute);
        return Double.isNaN(value) ? fallback : value;
    }

    public boolean has(EntityAttribute attribute) {
        return !Double.isNaN(get(attribute));
    }

    // ------------------------------------------------------------ position

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    /** @return the position the source gave; built once per tick */
    public Vec3 getPosition() {
        if (position == null) {
            position = Vec3.of(x, y, z);
        }
        return position;
    }

    public double getEyeY() {
        return y + eyeHeight;
    }

    public double getEyeHeight() {
        return eyeHeight;
    }

    /** @return the position raised by the eye height; built once per tick */
    public Vec3 getEyePosition() {
        if (eye == null) {
            eye = Vec3.of(x, y + eyeHeight, z);
        }
        return eye;
    }

    /** @return movement since the previous tick, per axis; zero on the first tick seen */
    public double getVelocityX() {
        return velocityX;
    }

    public double getVelocityY() {
        return velocityY;
    }

    public double getVelocityZ() {
        return velocityZ;
    }

    public Vec3 getVelocity() {
        return Vec3.of(velocityX, velocityY, velocityZ);
    }

    /** @return distance moved on X and Z since the previous tick */
    public double getHorizontalSpeed() {
        return Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
    }

    /**
     * @return where it will be in {@code ticks} ticks if it keeps its last tick's
     *         velocity. A straight line; for anything that obeys gravity and
     *         walls, use {@code Core.simulation()}
     */
    public Vec3 extrapolate(int ticks) {
        return Vec3.of(x + velocityX * ticks, y + velocityY * ticks, z + velocityZ * ticks);
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public Vec2 getRotation() {
        return Vec2.rotation(yaw, pitch);
    }

    // ----------------------------------------------------------------- box

    public double getMinX() {
        return minX;
    }

    public double getMinY() {
        return minY;
    }

    public double getMinZ() {
        return minZ;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMaxY() {
        return maxY;
    }

    public double getMaxZ() {
        return maxZ;
    }

    public double getWidth() {
        return maxX - minX;
    }

    public double getHeight() {
        return maxY - minY;
    }

    /** @return the box; built once per tick */
    public Box getBox() {
        if (box == null) {
            box = Box.of(minX, minY, minZ, maxX, maxY, maxZ);
        }
        return box;
    }

    /** @return the centre of the box */
    public Vec3 getCenter() {
        return Vec3.of((minX + maxX) / 2d, (minY + maxY) / 2d, (minZ + maxZ) / 2d);
    }

    /**
     * @return the squared distance from a point to the nearest point of the box,
     *         0 inside it
     *
     * <p>The distance range checks should use. An entity whose position is four
     * blocks away but whose box is two blocks wide is three blocks away as far as
     * reaching it goes.
     */
    public double squaredDistanceToBox(double px, double py, double pz) {
        double dx = px < minX ? minX - px : px > maxX ? px - maxX : 0d;
        double dy = py < minY ? minY - py : py > maxY ? py - maxY : 0d;
        double dz = pz < minZ ? minZ - pz : pz > maxZ ? pz - maxZ : 0d;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceToBox(Vec3 point) {
        return Math.sqrt(squaredDistanceToBox(point.getX(), point.getY(), point.getZ()));
    }

    /** @return the point of the box nearest {@code point}; the point itself when inside */
    public Vec3 closestPoint(Vec3 point) {
        return getBox().closestPoint(point);
    }

    /**
     * @return the point of the box, shrunk by {@code inset} on every side, nearest
     *         {@code from}
     *
     * <p>Where to aim to land inside the box as close as possible, rather than on
     * its edge where rounding decides whether a ray hits. How much inset is
     * enough depends on your game; Core does not guess.
     */
    public Vec3 aimPoint(Vec3 from, double inset) {
        return getBox().inset(inset).closestPoint(from);
    }

    /** @return the squared distance from a point to the position */
    public double squaredDistanceTo(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceTo(TrackedEntity other) {
        return Math.sqrt(squaredDistanceTo(other.x, other.y, other.z));
    }

    @Override
    public String toString() {
        return "TrackedEntity(" + category.getName()
                + (name != null ? " \"" + name + "\"" : "") + " #" + id
                + String.format(Locale.ROOT, " at %.1f %.1f %.1f)", x, y, z);
    }
}
