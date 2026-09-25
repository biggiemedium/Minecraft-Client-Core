package dev.px.core.network.lag;

import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.network.NetworkService;
import dev.px.core.network.PacketListener;
import dev.px.core.network.packet.PacketDescription;
import dev.px.core.network.packet.PacketFields;
import dev.px.core.platform.Platform;
import dev.px.core.service.Service;
import dev.px.core.util.Validate;
import dev.px.core.util.collect.RollingAverage;
import dev.px.core.util.time.RateMeter;

/**
 * How the connection is doing: ping, packet rates, and whether the server has
 * gone quiet.
 *
 * <pre>{@code
 * Core.lag().getPing();                 // 48, as the server measures it
 * Core.lag().getInboundRate();          // 212 packets a second
 * Core.lag().isLagging();               // true while the server is silent
 * Core.lag().getLagMillis();            // and for how long
 * }</pre>
 *
 * <h2>Two different kinds of lag</h2>
 *
 * <p>A <b>slow server</b> still talks, just less often: that is TPS, and
 * {@code Core.tps()} measures it. A <b>silent server</b> &mdash; frozen,
 * garbage-collecting, or with the connection stalled somewhere between &mdash;
 * sends nothing at all, and every packet the client sends in the meantime is
 * judged late when it lands. This service watches for the second: once nothing
 * has arrived for {@link #getSpikeThresholdMillis()}, {@link #isLagging()} turns
 * true and a {@link LagSpikeEvent} is posted, and another when traffic resumes.
 * A vanilla server sends the time every second even to an empty world, so a
 * healthy connection is never silent that long.
 *
 * <h2>What the adapter supplies</h2>
 *
 * <p>Arrival and rates need only {@code PacketEvent} posts; no describer is
 * required. Ping needs one of:
 *
 * <ul>
 *   <li>the describer tagging the local player's player-list entry with
 *       {@link PacketDescription#withLatency}, or
 *   <li>the adapter calling {@link #reportPing} with whatever its version
 *       exposes, such as the local player's list entry each second.
 * </ul>
 *
 * <p>The ping is the server's own measurement, not one taken here: a client
 * cannot time a round trip the server starts, and nothing the client starts is
 * answered promptly by a vanilla server. It is what the tab list shows.
 */
public final class LagService implements Service {

    /** Silence longer than this is a spike. A vanilla server is never quiet for over a second. */
    public static final long DEFAULT_SPIKE_THRESHOLD_MILLIS = 1500L;

    /** Ping reports to average and take the jitter of. */
    private static final int PING_WINDOW = 10;

    private static final long NONE = Long.MIN_VALUE;

    private final EventBus bus;
    private final NetworkService network;
    private final Platform platform;
    private final Listener listener = new Listener();
    private final Traffic traffic = new Traffic();
    private final Object lock = new Object();

    // Guarded by lock: written from the network thread, read from the game thread.
    private final RateMeter inbound = RateMeter.perSecond();
    private final RateMeter outbound = RateMeter.perSecond();
    private final RollingAverage pings = RollingAverage.of(PING_WINDOW);
    private long lastInboundNanos = NONE;
    private int lastPing = -1;
    private boolean spiking;
    private long spikeFromNanos;
    private long resumedNanos = NONE;

    private volatile long spikeThresholdNanos = DEFAULT_SPIKE_THRESHOLD_MILLIS * 1_000_000L;
    private Subscription packets;

    public LagService(EventBus bus, NetworkService network, Platform platform) {
        this.bus = Validate.notNull(bus, "bus");
        this.network = Validate.notNull(network, "network");
        this.platform = Validate.notNull(platform, "platform");
    }

    @Override
    public String getName() {
        return "Lag";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Service>[] dependsOn() {
        return new Class[] { NetworkService.class };
    }

    @Override
    public void start() {
        packets = network.addListener(traffic);
        bus.subscribe(listener);
    }

    @Override
    public void stop() {
        if (packets != null) {
            packets.close();
            packets = null;
        }
        bus.unsubscribe(listener);
        reset();
    }

    // ---------------------------------------------------------------- ping

    /** @return the latest ping the server reported, in milliseconds, or -1 if none has been */
    public int getPing() {
        synchronized (lock) {
            return lastPing;
        }
    }

    /** @return the mean of the last few reports, or -1 if none has been */
    public double getAveragePing() {
        synchronized (lock) {
            return pings.count() == 0 ? -1d : pings.average();
        }
    }

    /**
     * @return how much the ping varies between reports, in milliseconds
     *
     * <p>The number that separates a steady 80ms from one that swings between 20
     * and 140: the same average, and very different to play on.
     */
    public double getPingJitter() {
        synchronized (lock) {
            return pings.count() < 2 ? 0d : pings.standardDeviation();
        }
    }

    /**
     * Reports the ping from the adapter, for versions where polling is easier
     * than describing the player-list packet.
     *
     * <pre>{@code
     * // 1.8.9, once a second from a tick handler
     * NetworkPlayerInfo self = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
     * if (self != null) Core.lag().reportPing(self.getResponseTime());
     * }</pre>
     *
     * <p>A report equal to the last is ignored, so polling faster than the server
     * updates does not flatten the jitter to zero.
     */
    public void reportPing(int millis) {
        if (millis < 0) {
            return;
        }
        synchronized (lock) {
            if (millis == lastPing && pings.count() > 0) {
                return;
            }
            lastPing = millis;
            pings.push(millis);
        }
    }

    // --------------------------------------------------------------- rates

    /** @return packets received per second, over the last second */
    public double getInboundRate() {
        long now = network.nanoTime();
        synchronized (lock) {
            return inbound.rate(now);
        }
    }

    /** @return packets sent per second, over the last second */
    public double getOutboundRate() {
        long now = network.nanoTime();
        synchronized (lock) {
            return outbound.rate(now);
        }
    }

    // --------------------------------------------------------------- spikes

    /** @return milliseconds since anything arrived from the server, or -1 if nothing has */
    public long getMillisSinceLastPacket() {
        long now = network.nanoTime();
        synchronized (lock) {
            return lastInboundNanos == NONE ? -1L : (now - lastInboundNanos) / 1_000_000L;
        }
    }

    /** @return whether the server is in a silence longer than the spike threshold */
    public boolean isLagging() {
        synchronized (lock) {
            return spiking && resumedNanos == NONE;
        }
    }

    /** @return how long the current silence has lasted, or 0 when not {@link #isLagging() lagging} */
    public long getLagMillis() {
        long now = network.nanoTime();
        synchronized (lock) {
            return spiking && resumedNanos == NONE ? (now - spikeFromNanos) / 1_000_000L : 0L;
        }
    }

    public long getSpikeThresholdMillis() {
        return spikeThresholdNanos / 1_000_000L;
    }

    /** @param millis how long the server must be silent before it counts as a spike */
    public void setSpikeThresholdMillis(long millis) {
        Validate.check(millis > 0L, "threshold must be positive");
        this.spikeThresholdNanos = millis * 1_000_000L;
    }

    // ------------------------------------------------------------ internals

    private void inbound(PacketDescription packet, long nanos) {
        Long latency = packet.getLong(PacketFields.LATENCY);
        synchronized (lock) {
            inbound.record(nanos);
            lastInboundNanos = nanos;
            if (spiking && resumedNanos == NONE) {
                resumedNanos = nanos;
            }
        }
        if (latency != null) {
            reportPing((int) Math.min(Integer.MAX_VALUE, latency));
        }
    }

    private void outbound(long nanos) {
        synchronized (lock) {
            outbound.record(nanos);
        }
    }

    /**
     * Runs on the game thread, so the events are posted there. A spike is
     * noticed within a tick of crossing the threshold, and its end within a
     * tick of the first packet back.
     */
    private void tick() {
        long now = network.nanoTime();
        boolean inGame = platform.isInGame();
        LagSpikeEvent event = null;
        synchronized (lock) {
            if (!spiking) {
                if (inGame && lastInboundNanos != NONE && now - lastInboundNanos > spikeThresholdNanos) {
                    spiking = true;
                    spikeFromNanos = lastInboundNanos;
                    resumedNanos = NONE;
                    event = new LagSpikeEvent(LagSpikeEvent.Phase.STARTED, (now - spikeFromNanos) / 1_000_000L);
                }
            } else if (resumedNanos != NONE) {
                spiking = false;
                event = new LagSpikeEvent(LagSpikeEvent.Phase.ENDED, (resumedNanos - spikeFromNanos) / 1_000_000L);
            } else if (!inGame) {
                // Left mid-spike with no WorldEvent to say so.
                spiking = false;
                event = new LagSpikeEvent(LagSpikeEvent.Phase.ENDED, (now - spikeFromNanos) / 1_000_000L);
            }
        }
        if (event != null) {
            bus.post(event);
        }
    }

    private void reset() {
        synchronized (lock) {
            inbound.clear();
            outbound.clear();
            pings.clear();
            lastInboundNanos = NONE;
            lastPing = -1;
            spiking = false;
            resumedNanos = NONE;
        }
    }

    private final class Traffic implements PacketListener {

        @Override
        public void onInbound(PacketDescription packet, long nanos) {
            inbound(packet, nanos);
        }

        @Override
        public void onOutbound(PacketDescription packet, long nanos) {
            outbound(nanos);
        }
    }

    private final class Listener {

        @Subscribe(stage = Stage.PRE)
        private void onTick(TickEvent event) {
            tick();
        }

        @Subscribe
        private void onWorld(WorldEvent event) {
            if (!event.isUnloaded()) {
                return;
            }
            // Leaving mid-spike ends it, so nothing waits for packets that will not come.
            long now = network.nanoTime();
            LagSpikeEvent ended = null;
            synchronized (lock) {
                if (spiking) {
                    long until = resumedNanos != NONE ? resumedNanos : now;
                    ended = new LagSpikeEvent(LagSpikeEvent.Phase.ENDED, (until - spikeFromNanos) / 1_000_000L);
                }
            }
            reset();
            if (ended != null) {
                bus.post(ended);
            }
        }
    }
}
