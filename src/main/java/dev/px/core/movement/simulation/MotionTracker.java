package dev.px.core.movement.simulation;

import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where things have been, keyed by whatever the client wants to call them.
 *
 * <p>The key is an opaque {@link Object} &mdash; an entity id, a UUID, a name,
 * the entity itself. Core never looks inside it and never learns what an entity
 * is, which is how a tracker for players lives in a library that has no player
 * type. It is the same trick {@link dev.px.core.util.spatial.PathSpace} uses for
 * the world.
 *
 * <pre>{@code
 * // on every tick, for everything worth knowing about
 * for (EntityPlayer other : world.playerEntities) {
 *     Core.simulation().record(other.getEntityId(), toVec3(other), other.onGround);
 * }
 *
 * // later, from anywhere
 * MotionTrack track = Core.simulation().track(id);
 * Vec3 soon = track.extrapolate(2);
 * }</pre>
 *
 * <p>Every track holds the same bounded number of ticks, so memory is
 * {@code tracked * historyTicks} and does not grow over a session. Tracks that
 * stop being updated are dropped by {@link #evictBefore}, which
 * {@link SimulationService} calls once a tick &mdash; without it, every entity
 * ever seen would be remembered until the client closed.
 *
 * <p>Not thread safe. Record on the tick, from the game thread.
 */
public final class MotionTracker {

    /** Ticks of history each track keeps. */
    @Getter
    private final int historyTicks;

    private final Map<Object, MotionTrack> tracks = new LinkedHashMap<>();

    public MotionTracker(int historyTicks) {
        Validate.check(historyTicks >= 2,
                "history must be at least 2 ticks for a velocity to exist, got " + historyTicks);
        this.historyTicks = historyTicks;
    }

    /**
     * Adds a sample, creating the track if this key is new.
     *
     * @param key whatever identifies this thing. Compared by equality, so an id or
     *        a UUID works and a freshly built wrapper object does not
     */
    public void record(Object key, Vec3 position, boolean onGround, long tick) {
        Validate.notNull(key, "key");
        Validate.notNull(position, "position");
        MotionTrack track = tracks.get(key);
        if (track == null) {
            track = new MotionTrack(key, historyTicks);
            tracks.put(key, track);
        }
        track.record(position, onGround, tick);
    }

    /** @return this key's history, or null if nothing has been recorded for it. */
    public MotionTrack get(Object key) {
        return key == null ? null : tracks.get(key);
    }

    public boolean isTracked(Object key) {
        return key != null && tracks.containsKey(key);
    }

    public boolean forget(Object key) {
        return key != null && tracks.remove(key) != null;
    }

    public void clear() {
        tracks.clear();
    }

    public int size() {
        return tracks.size();
    }

    public Set<Object> keys() {
        return Collections.unmodifiableSet(tracks.keySet());
    }

    /** @return a live view of every track, in the order they were first seen. */
    public Collection<MotionTrack> all() {
        return Collections.unmodifiableCollection(tracks.values());
    }

    /**
     * Drops tracks whose newest sample is older than {@code tick}.
     *
     * @return how many were dropped
     */
    public int evictBefore(long tick) {
        int dropped = 0;
        Iterator<MotionTrack> iterator = tracks.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getLastTick() < tick) {
                iterator.remove();
                dropped++;
            }
        }
        return dropped;
    }
}
