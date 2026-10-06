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
 * <p><b>Its recent past.</b> It also keeps where it was over the last few ticks
 * in a row &mdash; {@link #positionAgo} &mdash; as many as its tracker's
 * {@linkplain EntityTracker#setHistory history} allows. That is what
 * {@code Core.prediction()} reads to work out how it is moving. A tick it was not
 * seen breaks the run, and the history starts again from the tick it reappears.
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

    /** The last few ticks' positions and yaws, newest at {@code historyHead - 1}; a ring. */
    private final double[] historyX;
    private final double[] historyY;
    private final double[] historyZ;
    private final float[] historyYaw;
    private final boolean[] historyFresh;
    private int historyHead;
    private int historySize;
    /** The fewest ticks without news after which holding still counts as news. */
    private final int updateGap;
    private long stamp = -1L;
    private int quiet;
    private int sinceFresh;
    /** Ticks between its last three pieces of news: how often the server sends it, as seen. */
    private final int[] intervals = new int[3];
    private int intervalCount;
    private int sinceNews;

    /**
     * @param history how many ticks of position to keep, now included; at least 1
     * @param updateGap ticks without a new position after which holding still counts as one
     */
    Tracked(E handle, int history, int updateGap) {
        this.handle = handle;
        this.historyX = new double[history];
        this.historyY = new double[history];
        this.historyZ = new double[history];
        this.historyYaw = new float[history];
        this.historyFresh = new boolean[history];
        this.updateGap = updateGap;
    }

    /**
     * Takes this tick's reading.
     *
     * @param tick the refresh it belongs to; one after {@link #lastSeen} means the
     *        entity was seen last tick too, so the difference is a velocity
     */
    void update(Reading reading, long tick) {
        boolean fresh;
        if (lastSeen != tick - 1) {
            fresh = true;
            quiet = 0;
            sinceNews = 0;
            intervalCount = 0;
        } else {
            boolean news = reading.stamp != -1L
                    ? reading.stamp != stamp
                    : reading.x != x || reading.y != y || reading.z != z;
            sinceNews++;
            if (news) {
                intervals[intervalCount % intervals.length] = sinceNews;
                intervalCount++;
                sinceNews = 0;
            }
            quiet = news ? 0 : quiet + 1;
            // Quiet for longer than the server usually leaves it means it is standing still.
            fresh = news || quiet >= Math.max(updateGap, usualInterval());
        }
        stamp = reading.stamp;
        sinceFresh = fresh ? 0 : sinceFresh + 1;
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
            historySize = 0;                     // not seen last tick: the run starts again
        }
        historyX[historyHead] = reading.x;
        historyY[historyHead] = reading.y;
        historyZ[historyHead] = reading.z;
        historyYaw[historyHead] = reading.yaw;
        historyFresh[historyHead] = fresh;
        historyHead = (historyHead + 1) % historyX.length;
        if (historySize < historyX.length) {
            historySize++;
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

    /** @return the refresh this reading is from: {@code EntityService.getTick()} when it was taken */
    public long getTick() {
        return lastSeen;
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
     *         velocity. A straight line, through walls and without gravity; for
     *         where it will really be, use {@code Core.prediction()}
     */
    public Vec3 extrapolate(int ticks) {
        return Vec3.of(x + velocityX * ticks, y + velocityY * ticks, z + velocityZ * ticks);
    }

    public float getYaw() {
        return yaw;
    }

    /**
     * A stand-in for this entity at {@code position}: the same game object, size,
     * facing and velocity, its box moved with it. For asking "what if it were
     * there" of anything that measures a {@code Tracked} &mdash; where a prediction
     * says it will be when an explosion lands, say.
     *
     * <p>No tracker holds it, so {@link #isTracked()} is false, and its history is
     * just this one position. Taken now, it does not move with the entity.
     */
    public Tracked<E> projected(Vec3 position) {
        Tracked<E> copy = new Tracked<>(handle, 1, 1);
        double dx = position.getX() - x;
        double dy = position.getY() - y;
        double dz = position.getZ() - z;
        copy.x = position.getX();
        copy.y = position.getY();
        copy.z = position.getZ();
        copy.minX = minX + dx;
        copy.minY = minY + dy;
        copy.minZ = minZ + dz;
        copy.maxX = maxX + dx;
        copy.maxY = maxY + dy;
        copy.maxZ = maxZ + dz;
        copy.velocityX = velocityX;
        copy.velocityY = velocityY;
        copy.velocityZ = velocityZ;
        copy.eyeHeight = eyeHeight;
        copy.yaw = yaw;
        copy.pitch = pitch;
        copy.ticksTracked = ticksTracked;
        copy.lastSeen = lastSeen;
        copy.removed = true;
        copy.historyX[0] = copy.x;
        copy.historyY[0] = copy.y;
        copy.historyZ[0] = copy.z;
        copy.historyYaw[0] = yaw;
        copy.historyFresh[0] = true;
        copy.historySize = 1;
        copy.historyHead = 0;
        return copy;
    }

    // ------------------------------------------------------------- history

    /**
     * @return how many ticks in a row of position this holds, this tick's
     *         included: 1 on the tick it appeared, up to its tracker's
     *         {@linkplain EntityTracker#setHistory history}
     */
    public int getHistorySize() {
        return historySize;
    }

    /**
     * @param ticksAgo 0 for this tick, 1 for the one before
     * @return where it was then, or null past the history
     */
    public Vec3 positionAgo(int ticksAgo) {
        if (ticksAgo < 0 || ticksAgo >= historySize) {
            return null;
        }
        int at = indexAgo(ticksAgo);
        return Vec3.of(historyX[at], historyY[at], historyZ[at]);
    }

    /** @return which way it faced {@code ticksAgo} ticks ago, or NaN past the history */
    public float yawAgo(int ticksAgo) {
        return ticksAgo < 0 || ticksAgo >= historySize ? Float.NaN : historyYaw[indexAgo(ticksAgo)];
    }

    /**
     * @return whether the position {@code ticksAgo} ticks ago was news: a new
     *         position from the server, or holding still long enough to be sure of
     *         it. False past the history. See {@link EntitySource#positionStamp}
     */
    public boolean isFreshAgo(int ticksAgo) {
        return ticksAgo >= 0 && ticksAgo < historySize && historyFresh[indexAgo(ticksAgo)];
    }

    /** @return the stamp your source gave with this tick's position; -1 when it gives none */
    public long getPositionStamp() {
        return stamp;
    }

    /** @return ticks since its position was last news: 0 when this tick's was */
    public int getTicksSinceFresh() {
        return sinceFresh;
    }

    /** @return the median of the last three intervals between news; 1 before any */
    private int usualInterval() {
        if (intervalCount == 0) {
            return 1;
        }
        if (intervalCount < intervals.length) {
            return intervals[intervalCount - 1];
        }
        int a = intervals[0];
        int b = intervals[1];
        int c = intervals[2];
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    private int indexAgo(int ticksAgo) {
        int length = historyX.length;
        return ((historyHead - 1 - ticksAgo) % length + length) % length;
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
