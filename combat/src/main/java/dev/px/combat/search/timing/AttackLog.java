package dev.px.combat.search.timing;

import dev.px.core.event.EventBus;
import dev.px.core.math.Box;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What you have set off, and what it did this tick: the memory behind inhibit
 * and the one-explosion-per-tick rule.
 *
 * <ul>
 *   <li><b>Inhibit.</b> An explosive you attacked or used is left alone for a few
 *       ticks: the server has not answered yet, and setting it off again only
 *       spends a packet.
 *   <li><b>One explosion a tick.</b> The wiki gives current Java as applying only
 *       the highest explosion damage a target takes in a tick. So once one
 *       explosive is set off this tick, another only helps against a target if it
 *       hits that target harder &mdash; whatever kind it is.
 * </ul>
 *
 * <ul>
 *   <li><b>Pending placements.</b> Something you placed that the server has not
 *       shown yet still takes up room: a search sharing this log will not place
 *       where it would collide, until it shows up or its wait runs out. When it
 *       does show up, it is remembered as {@linkplain #isOwn yours}.
 * </ul>
 *
 * <p>That second rule is about explosions, not crystals: a crystal aura and a bed
 * aura running together should share one log, so each knows what the other did.
 *
 * <pre>{@code
 * AttackLog log = AttackLog.ticking(Core.bus());          // one, ticked once, for every search
 * CrystalSearch<LivingEntity> crystals = CrystalSearch.<LivingEntity>builder().log(log) ... .build();
 * BedSearch<LivingEntity> beds = BedSearch.<LivingEntity>builder().log(log) ... .build();
 * }</pre>
 *
 * <p>Keys are compared with {@code equals}: a game entity is itself, and a
 * {@code Bed}, being a position, is equal to the same bed found again. Every
 * method is O(1) expected but {@link #newTick}, which is O(n) in the explosives
 * remembered.
 *
 * <p>Game thread only.
 */
public final class AttackLog {

    /** The tick each inhibited explosive becomes free again. */
    private final Map<Object, Long> freeAt = new HashMap<>();
    private final Map<Object, Double> dealt = new HashMap<>();
    /** Placed, not yet shown: the room each takes up, and the tick its wait runs out. */
    private final List<Pending> pending = new ArrayList<>();
    /** Explosives that showed up where you placed them, and the tick they are forgotten. */
    private final Map<Object, Long> own = new HashMap<>();
    private final Listener listener = new Listener();
    private EventBus bus;
    private long tick;

    /** A log you tick yourself, with {@link #newTick}, or through the search that made it. */
    public AttackLog() {
    }

    /**
     * A log that ticks itself on {@code bus}'s {@code TickEvent}, ahead of ordinary
     * handlers, and forgets everything on leaving a world. The one to share.
     */
    public static AttackLog ticking(EventBus bus) {
        AttackLog log = new AttackLog();
        log.bus = Validate.notNull(bus, "bus");
        bus.subscribe(log.listener);
        return log;
    }

    /** Starts the next tick: forgets this tick's damage, and inhibits that have run out. */
    public void newTick() {
        tick++;
        dealt.clear();
        Iterator<Long> times = freeAt.values().iterator();
        while (times.hasNext()) {
            if (times.next() <= tick) {
                times.remove();
            }
        }
        pending.removeIf(placement -> placement.until <= tick);
        own.values().removeIf(until -> until <= tick);
    }

    /**
     * Records that something taking up {@code room} was placed, and waits
     * {@code ticks} for it to show up. Nothing is recorded for a wait of 0.
     */
    public void placed(Box room, int ticks) {
        Validate.notNull(room, "room");
        if (ticks > 0) {
            pending.add(new Pending(room, tick + ticks));
        }
    }

    /** @return whether something placed and not yet shown takes up any of {@code region} */
    public boolean isPendingIn(Box region) {
        for (Pending placement : pending) {
            if (placement.room.intersects(region)) {
                return true;
            }
        }
        return false;
    }

    /** @return how many placements are waiting to show up */
    public int getPendingCount() {
        return pending.size();
    }

    /**
     * An explosive taking up {@code room} has shown up: if it is where one of
     * yours was placed, that placement is done waiting and {@code explosive} is
     * remembered as yours for {@code ticks}.
     *
     * @return whether it is yours
     */
    public boolean arrived(Object explosive, Box room, int ticks) {
        Validate.notNull(explosive, "explosive");
        Validate.notNull(room, "room");
        if (own.containsKey(explosive)) {
            return true;
        }
        Iterator<Pending> waiting = pending.iterator();
        while (waiting.hasNext()) {
            Pending placement = waiting.next();
            // Where it was meant to be: the same room, give or take where the server rounded it to.
            if (placement.room.getCenter().distanceTo(room.getCenter()) <= ARRIVAL_SLACK) {
                waiting.remove();
                own.put(explosive, tick + Math.max(1, ticks));
                return true;
            }
        }
        return false;
    }

    /** @return whether {@code explosive} showed up where you placed one */
    public boolean isOwn(Object explosive) {
        return own.containsKey(explosive);
    }

    /** Records that {@code explosive} was set off this tick, and leaves it alone for {@code inhibitTicks}. */
    public void attacked(Object explosive, int inhibitTicks) {
        Validate.notNull(explosive, "explosive");
        if (inhibitTicks > 0) {
            freeAt.put(explosive, tick + inhibitTicks);
        }
    }

    /** @return whether {@code explosive} is still inside the inhibit window it was given */
    public boolean isInhibited(Object explosive) {
        Long free = freeAt.get(explosive);
        return free != null && tick < free;
    }

    /** Records that {@code target} will take {@code damage} this tick, keeping the highest. */
    public void dealt(Object target, double damage) {
        Double already = dealt.get(target);
        if (already == null || damage > already) {
            dealt.put(target, damage);
        }
    }

    /** @return the highest damage {@code target} takes this tick so far; 0 if none */
    public double dealtTo(Object target) {
        Double already = dealt.get(target);
        return already == null ? 0d : already;
    }

    public long getTick() {
        return tick;
    }

    /** Forgets everything: leaving a world, say. */
    public void clear() {
        freeAt.clear();
        dealt.clear();
        pending.clear();
        own.clear();
    }

    /** Stops listening to the bus given to {@link #ticking}; nothing otherwise. */
    public void close() {
        if (bus != null) {
            bus.unsubscribe(listener);
            bus = null;
        }
    }

    /** How far, in blocks, an explosive may show up from where it was placed and still be the one placed. */
    private static final double ARRIVAL_SLACK = 0.5d;

    private static final class Pending {
        final Box room;
        final long until;

        Pending(Box room, long until) {
            this.room = room;
            this.until = until;
        }
    }

    private final class Listener {

        @Subscribe(stage = Stage.PRE, priority = Integer.MAX_VALUE - 32)
        private void onTick(TickEvent event) {
            newTick();
        }

        @Subscribe
        private void onWorld(WorldEvent event) {
            if (event.isUnloaded()) {
                clear();
            }
        }
    }
}
