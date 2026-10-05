package dev.px.core.network.tps;

import dev.px.core.event.EventBus;
import dev.px.core.event.Subscribe;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.network.NetworkService;
import dev.px.core.network.PacketListener;
import dev.px.core.network.packet.PacketDescription;
import dev.px.core.network.packet.PacketFields;
import dev.px.core.service.Service;
import dev.px.core.util.Validate;

import java.util.Objects;

/**
 * The server's tick rate, as {@link TpsTracker} estimates it from the server's
 * clock.
 *
 * <pre>{@code
 * Core.tps().setTargetTps(20);                          // optional: your game's rate, to cap the estimate
 * double tps = Core.tps().getTps();                     // 19.8
 * if (Core.tps().getTps() < 15) slowDown();             // pace to the server, not the wall clock
 * }</pre>
 *
 * <p><b>What the adapter supplies:</b> {@code PacketEvent} posts, and a describer
 * that gives the server's clock packet {@link PacketDescription#withWorldAge}.
 * Nothing else is required. Core assumes no tick rate: until two clock updates
 * have arrived, {@link #getTps()} reports the target you set, or NaN with none,
 * and {@link #hasEstimate()} is false.
 *
 * <p>Forgets its measurements on leaving a server, and on joining a different
 * one, so one server's lag never shows on the next. The target is kept.
 */
public final class TpsService implements Service {

    private final EventBus bus;
    private final NetworkService network;
    private final TpsTracker tracker;
    private final Listener listener = new Listener();
    private final Traffic traffic = new Traffic();

    private Subscription packets;
    private String address;

    public TpsService(EventBus bus, NetworkService network) {
        this(bus, network, new TpsTracker());
    }

    public TpsService(EventBus bus, NetworkService network, TpsTracker tracker) {
        this.bus = Validate.notNull(bus, "bus");
        this.network = Validate.notNull(network, "network");
        this.tracker = Validate.notNull(tracker, "tracker");
    }

    @Override
    public String getName() {
        return "TPS";
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
        tracker.reset();
    }

    /**
     * @return ticks per second, smoothed over the last few clock updates and
     *         pulled down at once if the server stops sending them. Never above
     *         {@link #getTargetTps()} when one is set; before a measurement, the
     *         target, or NaN with none
     */
    public double getTps() {
        return tracker.getTps(network.nanoTime());
    }

    /** @return the rate the most recent update implied, unsmoothed; NaN before there is one */
    public double getLastSample() {
        return tracker.getLastSample();
    }

    /** @return whether {@link #getTps()} is a measurement rather than the assumed default */
    public boolean hasEstimate() {
        return tracker.hasEstimate();
    }

    /** @return milliseconds since the server last sent its time, or -1 if it has not */
    public long getMillisSinceUpdate() {
        return tracker.getMillisSinceUpdate(network.nanoTime());
    }

    /** @return how many milliseconds a server tick is taking, from {@link #getTps()}; NaN when that is */
    public double getTickMillis() {
        return 1000d / getTps();
    }

    /** @return the rate you said the server runs at, or NaN when you have not */
    public double getTargetTps() {
        return tracker.getTargetTps();
    }

    /**
     * The rate your game's server is meant to run at. Optional: it caps the
     * estimate and stands in for it before the first measurement. Call it again
     * from the adapter whenever the server announces a different rate.
     */
    public void setTargetTps(double targetTps) {
        tracker.setTargetTps(targetTps);
    }

    public void clearTargetTps() {
        tracker.clearTargetTps();
    }

    /** The estimator itself, for reading it on a clock of your own. */
    public TpsTracker getTracker() {
        return tracker;
    }

    private final class Traffic implements PacketListener {

        @Override
        public void onInbound(PacketDescription packet, long nanos) {
            Long age = packet.getLong(PacketFields.WORLD_AGE);
            if (age != null) {
                tracker.update(nanos, age);
            }
        }
    }

    private final class Listener {

        @Subscribe
        private void onWorld(WorldEvent event) {
            if (event.isUnloaded()) {
                tracker.reset();
                address = null;
                return;
            }
            // A dimension change reloads the world on the same server; the clock
            // carries on across it, so only a different server starts over.
            if (!Objects.equals(address, event.getServerAddress())) {
                tracker.reset();
                address = event.getServerAddress();
            }
        }
    }
}
