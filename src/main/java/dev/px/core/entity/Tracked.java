package dev.px.core.entity;

import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;

import java.util.Locale;

/**
 * One entity as one {@link EntityTracker} last saw it: the game's own object,
 * plus what Core can measure about it &mdash; where it is, how big, which way it
 * faces, how it moved since last tick, and how long it has been tracked.
 *
 * <pre>{@code
 * Tracked<EntityPlayer> target = Core.targets().best(enemies);
 * target.get().getHealth();                         // the game's object, already typed
 * target.distanceToBox(self.getEyePosition());      // Core's geometry
 * }</pre>
 *
 * <p><b>One object per entity per tracker, for as long as the tracker keeps
 * it.</b> It is updated in place every tick rather than rebuilt, so a module
 * holding a reference &mdash; a current target, something it is following
 * &mdash; keeps seeing that same entity move. Once the tracker lets go, because
 * the entity left the world or stopped being {@linkplain EntityTracker#accepts
 * accepted}, the object is never reused: {@link #isTracked()} turns false and
 * its fields keep their last values.
 *
 * <p><b>The position is the tick's, not the frame's.</b> It is read at the start
 * of the tick, so it is what modules should decide with. To draw an entity
 * smoothly between ticks, use the game's own interpolated position from
 * {@link #get()}.
 *
 * <p><b>Primitives first.</b> {@link #getX()}, {@link #squaredDistanceToBox} and
 * the rest read fields and allocate nothing. {@link #getPosition()},
 * {@link #getBox()} and {@link #getEyePosition()} build their value once per
 * tick on first ask.
 *
 * <p>Read on the game thread.
 *
 * @param <E> the game's type for this entity
 */
public final class Tracked<E> {

    // Written by the entity package only.
    final E handle;
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
    int ticksTracked;
    long lastSeen = Long.MIN_VALUE;
    boolean removed;

    private Vec3 position;
    private Vec3 eye;
    private Box box;

    Tracked(E handle) {
        this.handle = handle;
    }

    /**
     * Takes this tick's reading.
     *
     * @param tick the refresh it belongs to; one after {@link #lastSeen} means the
     *        entity was seen last tick too, so the difference is a velocity
     */
    void update(Reading reading, long tick) {
        if (lastSeen == tick - 1) {
            velocityX = reading.x - x;
            velocityY = reading.y - y;
            velocityZ = reading.z - z;
            ticksTracked++;
        } else {
            velocityX = 0d;
            velocityY = 0d;
            velocityZ = 0d;
            ticksTracked = 0;
        }
        x = reading.x;
        y = reading.y;
        z = reading.z;
        double half = reading.width / 2d;
        minX = x - half;
        minY = y;
        minZ = z - half;
        maxX = x + half;
        maxY = y + reading.height;
        maxZ = z + half;
        eyeHeight = reading.eyeHeight;
        yaw = reading.yaw;
        pitch = reading.pitch;
        lastSeen = tick;
        removed = false;
        position = null;
        eye = null;
        box = null;
    }

    // ------------------------------------------------------------ identity

    /** @return the game's own object, as the tracker's type */
    public E get() {
        return handle;
    }

    /** @return whether the tracker still holds this entity */
    public boolean isTracked() {
        return !removed;
    }

    /** @return ticks in a row this entity has been tracked; 0 on the tick it appeared */
    public int getTicksTracked() {
        return ticksTracked;
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

    /** @return movement since the previous tick, per axis; zero on the first tick tracked */
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

    public double distanceTo(Tracked<?> other) {
        return Math.sqrt(squaredDistanceTo(other.x, other.y, other.z));
    }

    @Override
    public String toString() {
        return "Tracked(" + handle + String.format(Locale.ROOT, " at %.1f %.1f %.1f)", x, y, z);
    }
}
