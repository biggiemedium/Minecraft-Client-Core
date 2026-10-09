package dev.px.core.control;

import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.List;

/**
 * Claims on one control, arbitrated by priority, that expire unless renewed.
 *
 * <p>The arbitration {@link dev.px.core.movement.rotation.RotationService} does
 * for the head, kept separate from what is being claimed so the movement keys
 * and each button share it: the highest priority wins, an equal priority goes to
 * whoever acquired the control first, and a claim lapses once it has not been
 * renewed for its hold.
 *
 * <p>Game thread only.
 *
 * @param <V> what a claim asks for
 */
final class Claims<V> {

    /** Live claims. A handful at most, so insertion order and a linear scan are right. */
    private final List<Lease<V>> leases = new ArrayList<>();

    private long tick;

    /**
     * Files or renews {@code owner}'s claim.
     *
     * <p>An owner keeps one claim per control: asking again replaces what it asked
     * for, its priority and its hold, and keeps its place in the tie-break.
     */
    void file(Object owner, V value, int priority, int holdTicks) {
        Validate.notNull(owner, "owner");
        Validate.notNull(value, "value");
        Validate.check(holdTicks >= 1, "hold must be at least one tick, got " + holdTicks);
        Lease<V> lease = leaseOf(owner);
        if (lease == null) {
            lease = new Lease<>(owner, tick);
            leases.add(lease);
        }
        lease.value = value;
        lease.priority = priority;
        lease.expiresAtTick = tick + holdTicks - 1;
    }

    /**
     * Opens a new tick, purging claims that lapsed before the last one.
     *
     * <p>A lapsed claim lingers for one tick so a claim renewed every tick is
     * recognised as the same one, and keeps the tie-break it won.
     */
    void beginTick() {
        tick++;
        for (int i = leases.size() - 1; i >= 0; i--) {
            if (leases.get(i).expiresAtTick < tick - 1) {
                leases.remove(i);
            }
        }
    }

    /** @return whether there was a claim to drop */
    boolean release(Object owner) {
        for (int i = 0; i < leases.size(); i++) {
            if (leases.get(i).owner == owner) {
                leases.remove(i);
                return true;
            }
        }
        return false;
    }

    void clear() {
        leases.clear();
    }

    /** @return the winning claim this tick, or null */
    Lease<V> winner() {
        Lease<V> best = null;
        for (int i = 0; i < leases.size(); i++) {
            Lease<V> candidate = leases.get(i);
            if (!isLive(candidate)) {
                continue;
            }
            if (best == null
                    || candidate.priority > best.priority
                    || (candidate.priority == best.priority && candidate.acquiredTick < best.acquiredTick)) {
                best = candidate;
            }
        }
        return best;
    }

    boolean has(Object owner) {
        Lease<V> lease = leaseOf(owner);
        return lease != null && isLive(lease);
    }

    int count() {
        int count = 0;
        for (int i = 0; i < leases.size(); i++) {
            if (isLive(leases.get(i))) {
                count++;
            }
        }
        return count;
    }

    private boolean isLive(Lease<V> lease) {
        return lease.expiresAtTick >= tick;
    }

    private Lease<V> leaseOf(Object owner) {
        for (int i = 0; i < leases.size(); i++) {
            if (leases.get(i).owner == owner) {
                return leases.get(i);
            }
        }
        return null;
    }

    /** One owner's claim. Mutable and reused, because it is renewed every tick. */
    static final class Lease<V> {

        final Object owner;

        /** Set once, kept across renewals, so the equal-priority tie-break is stable. */
        final long acquiredTick;

        V value;
        int priority;
        long expiresAtTick;

        private Lease(Object owner, long acquiredTick) {
            this.owner = owner;
            this.acquiredTick = acquiredTick;
        }
    }
}
