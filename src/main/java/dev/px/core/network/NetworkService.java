package dev.px.core.network;

import dev.px.core.event.EventBus;
import dev.px.core.event.Subscribe;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.PacketEvent;
import dev.px.core.network.packet.PacketDescriber;
import dev.px.core.network.packet.PacketDescription;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Where the adapter's packets come into Core: the describer is installed here,
 * and every network service reads traffic through here.
 *
 * <p>Wiring is two things, both of which a timeline recording needs anyway:
 *
 * <pre>{@code
 * // 1. post the traffic (see PacketEvent for where)
 * Core.bus().post(PacketEvent.sent(packet));
 * Core.bus().post(PacketEvent.received(packet));
 *
 * // 2. say what the packets are, once
 * Core.network().setDescriber(new MyPacketDescriber());
 * }</pre>
 *
 * <p>Then {@code Core.tps()}, {@code Core.lag()}, {@code Core.server()} and
 * {@code Core.anticheat()} all work, and so does {@code Core.timeline()}, which
 * reads through the same describer.
 *
 * <h2>What it does with a packet</h2>
 *
 * <ol>
 *   <li><b>Picks the arrival.</b> An inbound packet may be posted twice, RECEIVED
 *       and then APPLIED. Listeners hear about it once, on RECEIVED, which is when
 *       it actually arrived. An adapter that only ever posts APPLIED still works:
 *       until the first RECEIVED is seen, APPLIED counts as arrival.
 *   <li><b>Describes it once</b>, with failures contained: a describer that
 *       throws or returns null costs that packet its description, is logged once,
 *       and the packet is passed on as {@link PacketDescriber#DEFAULT} names it.
 *   <li><b>Passes it to every {@link PacketListener}</b>, in registration order.
 * </ol>
 *
 * <p><b>With no describer installed</b>, packets still flow, each classified
 * {@code OTHER}. That is enough for {@code Core.lag()}, which needs arrivals and
 * not meanings; TPS, brand and anticheat detection wait for a describer.
 */
public final class NetworkService implements Service {

    private final CoreLogger logger;
    private final EventBus bus;
    private final LongSupplier clock;
    private final Listener listener = new Listener();
    private final List<PacketListener> listeners = new CopyOnWriteArrayList<>();

    /** Descriptions for undescribed packets, one per class, so a missing describer allocates nothing per packet. */
    private final Map<Class<?>, PacketDescription> undescribed = new ConcurrentHashMap<>();

    /** Listeners that have thrown, so each is logged once rather than every packet. */
    private final Set<PacketListener> failed = ConcurrentHashMap.newKeySet();

    /** Null when the adapter installed none. */
    private volatile PacketDescriber describer;
    private volatile boolean warnedAboutDescriber;
    private volatile boolean sawReceived;

    public NetworkService(CoreLogger logger, EventBus bus) {
        this(logger, bus, System::nanoTime);
    }

    /** @param nanoClock monotonic nanoseconds; injectable so a test can run it by hand */
    public NetworkService(CoreLogger logger, EventBus bus, LongSupplier nanoClock) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        this.clock = Validate.notNull(nanoClock, "nanoClock");
    }

    @Override
    public String getName() {
        return "Network";
    }

    @Override
    public void start() {
        bus.subscribe(listener);
    }

    @Override
    public void stop() {
        bus.unsubscribe(listener);
    }

    // ----------------------------------------------------------- describer

    /** @param describer how to read the adapter's packets, or null to remove it */
    public void setDescriber(PacketDescriber describer) {
        this.describer = describer;
        this.warnedAboutDescriber = false;
    }

    /** @return the installed describer, or {@link PacketDescriber#DEFAULT} when there is none */
    public PacketDescriber getDescriber() {
        PacketDescriber current = describer;
        return current != null ? current : PacketDescriber.DEFAULT;
    }

    public boolean hasDescriber() {
        return describer != null;
    }

    /**
     * Describes a packet with the installed describer, never failing.
     *
     * <p>Itself a {@link PacketDescriber} that never throws and never returns null,
     * which is how the timeline recorder shares this one.
     */
    public PacketDescription describe(Object packet) {
        PacketDescriber current = describer;
        if (current != null) {
            try {
                PacketDescription description = current.describe(packet);
                if (description != null) {
                    return description;
                }
            } catch (Throwable thrown) {
                if (!warnedAboutDescriber) {
                    warnedAboutDescriber = true;
                    logger.error("PacketDescriber threw on " + packet.getClass().getName()
                            + "; treating it as undescribed (further failures are not logged)", thrown);
                }
            }
        }
        return undescribed.computeIfAbsent(packet.getClass(), type -> PacketDescriber.DEFAULT.describe(packet));
    }

    // ----------------------------------------------------------- listeners

    /** @return a handle that removes the listener when closed */
    public Subscription addListener(PacketListener packetListener) {
        Validate.notNull(packetListener, "listener");
        listeners.add(packetListener);
        return new Subscription() {
            @Override
            public void close() {
                listeners.remove(packetListener);
                failed.remove(packetListener);
            }

            @Override
            public boolean isActive() {
                return listeners.contains(packetListener);
            }
        };
    }

    /** The clock every network service timestamps with, so their readings agree. */
    public long nanoTime() {
        return clock.getAsLong();
    }

    private void packet(PacketEvent event) {
        switch (event.getPhase()) {
            case SENT:
                if (!event.isCancelled()) {
                    dispatch(event.getPacket(), false);
                }
                break;
            case RECEIVED:
                sawReceived = true;
                dispatch(event.getPacket(), true);
                break;
            case APPLIED:
                if (!sawReceived) {
                    dispatch(event.getPacket(), true);
                }
                break;
            default:
                break;
        }
    }

    private void dispatch(Object packet, boolean inbound) {
        if (listeners.isEmpty()) {
            return;
        }
        long nanos = clock.getAsLong();
        PacketDescription description = describe(packet);
        for (PacketListener each : listeners) {
            try {
                if (inbound) {
                    each.onInbound(description, nanos);
                } else {
                    each.onOutbound(description, nanos);
                }
            } catch (RuntimeException e) {
                if (failed.add(each)) {
                    logger.error("PacketListener " + each.getClass().getName() + " threw on "
                            + description.getType() + " (further failures are not logged)", e);
                }
            }
        }
    }

    /**
     * Listens last and sees cancelled packets, so an outbound packet is judged by
     * whether it was finally sent, and an inbound one counts however it was handled.
     */
    private final class Listener {

        @Subscribe(priority = Integer.MIN_VALUE, receiveCancelled = true)
        private void onPacket(PacketEvent event) {
            packet(event);
        }
    }
}
