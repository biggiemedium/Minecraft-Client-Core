package dev.px.core.network.server;

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
import dev.px.core.service.Service;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which server the player is on, and what it runs.
 *
 * <pre>{@code
 * Core.server().registerSoftware(ServerSoftware.of("Paper"), ServerSoftware.proxy("Velocity"));  // yours
 *
 * Core.server().isOn("hypixel.net");            // true on mc.hypixel.net
 * Core.server().getSoftware();                  // Paper
 * Core.server().getBrand().getProxy();          // Velocity (proxy)
 * Core.server().isSingleplayer();
 * }</pre>
 *
 * <h2>What the adapter supplies</h2>
 *
 * <ul>
 *   <li><b>{@code WorldEvent}</b>, which it posts anyway: the address on joining,
 *       the unload on leaving.
 *   <li><b>The brand</b>, either way round: the describer giving the packet that
 *       carries it {@link PacketDescription#withBrand}, or the adapter calling
 *       {@link #reportBrand} with what its version already parsed.
 *   <li>Optionally, <b>registered channels</b>, with
 *       {@link PacketDescription#withChannels} or {@link #reportChannels}.
 *   <li>Optionally, <b>the {@link ServerSoftware} to recognise</b> in a brand,
 *       with {@link #registerSoftware}. Core recognises none by itself; without
 *       any, the raw brand is still there and the software reads
 *       {@link ServerSoftware#UNKNOWN}.
 * </ul>
 *
 * <p>The brand may arrive before the world does, if the game sends it while
 * still connecting. It is held and applied when the world loads. What was learned
 * is forgotten on leaving, not on joining, for the same reason.
 *
 * <p>Changes are announced with a {@link ServerChangeEvent} on the game thread.
 * The getters here are current immediately, from any thread.
 */
public final class ServerService implements Service {

    private final EventBus bus;
    private final NetworkService network;
    private final Listener listener = new Listener();
    private final Traffic traffic = new Traffic();
    private final Object lock = new Object();
    private final List<ServerSoftware> software = new CopyOnWriteArrayList<>();

    private volatile ServerInfo current = ServerInfo.DISCONNECTED;

    // Guarded by lock. What arrived while not connected, for the next join.
    private ServerBrand pendingBrand;
    private final List<String> pendingChannels = new ArrayList<>();

    /** Game thread only: the state the last event announced. */
    private ServerInfo announced = ServerInfo.DISCONNECTED;

    private Subscription packets;

    public ServerService(EventBus bus, NetworkService network) {
        this.bus = Validate.notNull(bus, "bus");
        this.network = Validate.notNull(network, "network");
    }

    @Override
    public String getName() {
        return "Server";
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
        synchronized (lock) {
            current = ServerInfo.DISCONNECTED;
            pendingBrand = null;
            pendingChannels.clear();
        }
        announced = ServerInfo.DISCONNECTED;
    }

    // ------------------------------------------------------------- reading

    /** @return everything known about the current server; {@link ServerInfo#DISCONNECTED} when there is none */
    public ServerInfo getInfo() {
        return current;
    }

    public boolean isConnected() {
        return current.isConnected();
    }

    public boolean isSingleplayer() {
        return current.isSingleplayer();
    }

    public boolean isMultiplayer() {
        return current.isMultiplayer();
    }

    /** @return the lower-case host, empty in singleplayer or when disconnected */
    public String getHost() {
        return current.getHost();
    }

    /** @see ServerInfo#isOn(String) */
    public boolean isOn(String domain) {
        return current.isOn(domain);
    }

    /** @return the brand, or null until the server sends one */
    public ServerBrand getBrand() {
        return current.getBrand();
    }

    public ServerSoftware getSoftware() {
        return current.getSoftware();
    }

    public boolean hasChannel(String channel) {
        return current.hasChannel(channel);
    }

    // -------------------------------------------------------------- software

    /**
     * Adds server software to recognise in brands, in priority order: where a
     * brand names two, the one registered first wins. A brand already received is
     * read again.
     */
    public void registerSoftware(ServerSoftware... known) {
        for (ServerSoftware each : known) {
            software.add(Validate.notNull(each, "software"));
        }
        reidentify();
    }

    public boolean unregisterSoftware(ServerSoftware known) {
        boolean removed = software.remove(known);
        if (removed) {
            reidentify();
        }
        return removed;
    }

    /** @return the software recognised in brands, in priority order */
    public List<ServerSoftware> getKnownSoftware() {
        return Collections.unmodifiableList(new ArrayList<>(software));
    }

    // ------------------------------------------------------------ reporting

    /**
     * Sets the brand from the adapter, for versions that parse it already.
     *
     * <p>Safe to call every tick: an unchanged brand changes nothing.
     */
    public void reportBrand(String brand) {
        if (brand == null || brand.trim().isEmpty()) {
            return;
        }
        ServerBrand parsed = ServerBrand.parse(brand, software);
        synchronized (lock) {
            if (current.isConnected()) {
                if (!parsed.equals(current.getBrand())) {
                    current = current.withBrand(parsed);
                }
            } else {
                pendingBrand = parsed;
            }
        }
    }

    /** Adds channels the server registered. */
    public void reportChannels(Collection<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (current.isConnected()) {
                if (!current.getChannels().containsAll(channels)) {
                    current = current.withChannels(channels);
                }
            } else {
                pendingChannels.addAll(channels);
            }
        }
    }

    // ------------------------------------------------------------ internals

    private void world(WorldEvent event) {
        synchronized (lock) {
            if (event.isUnloaded()) {
                current = ServerInfo.DISCONNECTED;
                pendingBrand = null;
                pendingChannels.clear();
                return;
            }
            String address = event.getServerAddress() == null ? "" : event.getServerAddress().trim();
            if (current.isConnected() && current.getAddress().equals(address)) {
                // A dimension change reloads the world on the same server.
                return;
            }
            ServerInfo joined = ServerInfo.connected(address);
            if (pendingBrand != null) {
                joined = joined.withBrand(pendingBrand);
            }
            if (!pendingChannels.isEmpty()) {
                joined = joined.withChannels(pendingChannels);
            }
            pendingBrand = null;
            pendingChannels.clear();
            current = joined;
        }
    }

    private void reidentify() {
        synchronized (lock) {
            if (current.getBrand() != null) {
                current = current.withBrand(ServerBrand.parse(current.getBrand().getRaw(), software));
            }
            if (pendingBrand != null) {
                pendingBrand = ServerBrand.parse(pendingBrand.getRaw(), software);
            }
        }
    }

    private void announce() {
        ServerInfo now = current;
        if (now == announced) {
            return;
        }
        ServerInfo before = announced;
        announced = now;
        bus.post(new ServerChangeEvent(before, now));
    }

    private final class Traffic implements PacketListener {

        @Override
        public void onInbound(PacketDescription packet, long nanos) {
            String brand = packet.getString(PacketFields.BRAND);
            if (brand != null) {
                reportBrand(brand);
            }
            String joined = packet.getString(PacketFields.CHANNELS);
            if (joined != null && !joined.isEmpty()) {
                reportChannels(Arrays.asList(joined.split(PacketFields.CHANNEL_SEPARATOR)));
            }
        }
    }

    private final class Listener {

        // Ahead of ordinary handlers, so the world handlers of modules already see the new server.
        @Subscribe(priority = Integer.MAX_VALUE - 1)
        private void onWorld(WorldEvent event) {
            world(event);
        }

        @Subscribe(stage = Stage.PRE, priority = Integer.MAX_VALUE - 1)
        private void onTick(TickEvent event) {
            announce();
        }
    }
}
