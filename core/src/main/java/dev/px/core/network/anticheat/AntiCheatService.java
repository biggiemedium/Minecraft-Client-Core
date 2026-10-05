package dev.px.core.network.anticheat;

import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.network.NetworkService;
import dev.px.core.network.PacketListener;
import dev.px.core.network.packet.PacketDescription;
import dev.px.core.network.packet.PacketFields;
import dev.px.core.network.server.ServerChangeEvent;
import dev.px.core.network.server.ServerInfo;
import dev.px.core.network.server.ServerService;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.core.util.time.TickTimer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which anticheat the server is probably running.
 *
 * <pre>{@code
 * // what to look for is yours: Core registers no signatures of its own
 * Core.anticheat().register(AntiCheatSignature.onServer("example.net", "MyAC"));
 * Core.anticheat().register(AntiCheatSignatures.transactionBased(2, 10));
 *
 * Core.anticheat().getPrimary().map(Detection::getName).orElse("None");   // "MyAC"
 * if (Core.anticheat().isDetected("MyAC")) useSaferMode();
 * }</pre>
 *
 * <h2>How it decides</h2>
 *
 * <p>It gathers {@link ServerEvidence} &mdash; the address, brand and channels
 * from {@code Core.server()}, and the {@link TransactionPattern} of what the
 * server has been sending &mdash; and asks every registered
 * {@link AntiCheatSignature} what it makes of it. Each one that recognises
 * something returns a {@link Detection}; they are ranked by confidence, a named
 * anticheat above the generic {@link AntiCheatSignatures#TRANSACTION_BASED} at
 * equal confidence, and registration order after that.
 *
 * <p>This is a guess, and says so: every detection carries its confidence and
 * the evidence behind it. Nothing a server sends proves which anticheat it runs.
 * Detection is for choosing sensible defaults, not for trusting blindly.
 *
 * <h2>What the adapter supplies</h2>
 *
 * <p>Signatures, with {@link #register}: Core ships none, so with none
 * registered nothing is ever detected. Everything {@code Core.server()} needs.
 * And, for signatures that read timing, the describer giving the packets the
 * server sends for the client to answer {@link PacketDescription#withTransaction}.
 *
 * <p>Re-evaluated every {@link #setEvaluateEveryTicks few ticks} on the game thread, and at once when the server
 * changes; a change in the result posts an {@link AntiCheatChangeEvent}. The
 * transaction history starts over whenever the server does, so a lobby's
 * anticheat is not attributed to the game server behind the same proxy.
 */
public final class AntiCheatService implements Service {

    /** Ticks between re-evaluations until {@link #setEvaluateEveryTicks} says otherwise. A tuning knob, not a fact about any game. */
    public static final int DEFAULT_EVALUATE_EVERY_TICKS = 20;

    private static final Comparator<Detection> RANKING = Comparator
            .comparing(Detection::getConfidence).reversed()
            .thenComparing(detection -> detection.is(AntiCheatSignatures.TRANSACTION_BASED));

    private final CoreLogger logger;
    private final EventBus bus;
    private final NetworkService network;
    private final ServerService server;
    private final TransactionTracker transactions = new TransactionTracker();
    private final List<AntiCheatSignature> signatures = new CopyOnWriteArrayList<>();
    private final Set<AntiCheatSignature> failed = ConcurrentHashMap.newKeySet();
    private final Listener listener = new Listener();
    private final Traffic traffic = new Traffic();
    private volatile TickTimer timer = TickTimer.every(DEFAULT_EVALUATE_EVERY_TICKS);

    private volatile List<Detection> detections = Collections.emptyList();
    private Subscription packets;

    public AntiCheatService(CoreLogger logger, EventBus bus, NetworkService network, ServerService server) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        this.network = Validate.notNull(network, "network");
        this.server = Validate.notNull(server, "server");
    }

    @Override
    public String getName() {
        return "AntiCheat";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Service>[] dependsOn() {
        return new Class[] { NetworkService.class, ServerService.class };
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
        transactions.reset();
        detections = Collections.emptyList();
    }

    // ------------------------------------------------------------- reading

    /** @return everything detected, most certain first; empty when nothing is */
    public List<Detection> getDetections() {
        return detections;
    }

    /** @return the most certain detection */
    public Optional<Detection> getPrimary() {
        List<Detection> current = detections;
        return current.isEmpty() ? Optional.<Detection>empty() : Optional.of(current.get(0));
    }

    /** @return whether any detection names {@code anticheat}, ignoring case */
    public boolean isDetected(String anticheat) {
        for (Detection detection : detections) {
            if (detection.is(anticheat)) {
                return true;
            }
        }
        return false;
    }

    /** @return what the server's transactions have looked like since it last changed */
    public TransactionPattern getTransactions() {
        return transactions.pattern();
    }

    // ---------------------------------------------------------- signatures

    /** Adds a signature, consulted from the next evaluation on. */
    public void register(AntiCheatSignature signature) {
        signatures.add(Validate.notNull(signature, "signature"));
    }

    public boolean unregister(AntiCheatSignature signature) {
        failed.remove(signature);
        return signatures.remove(signature);
    }

    /** Removes every signature. */
    public void clearSignatures() {
        signatures.clear();
        failed.clear();
    }

    /** @return the registered signatures, in registration order */
    public List<AntiCheatSignature> getSignatures() {
        return Collections.unmodifiableList(new ArrayList<>(signatures));
    }

    /** @param ticks how often signatures are asked again, between server changes */
    public void setEvaluateEveryTicks(int ticks) {
        Validate.check(ticks > 0, "ticks must be positive");
        timer = TickTimer.every(ticks);
    }

    /**
     * Asks every signature now, rather than waiting for the next evaluation.
     *
     * <p>Call from the game thread: a changed result is posted from here.
     *
     * @return the detections, most certain first
     */
    public List<Detection> evaluate() {
        ServerInfo info = server.getInfo();
        List<Detection> found = new ArrayList<>();
        if (info.isConnected()) {
            ServerEvidence evidence = new ServerEvidence(info, transactions.pattern());
            for (AntiCheatSignature signature : signatures) {
                Detection detection = detect(signature, evidence);
                if (detection != null) {
                    found.add(detection);
                }
            }
            found.sort(RANKING);
        }
        List<Detection> result = Collections.unmodifiableList(found);
        List<Detection> before = detections;
        detections = result;
        if (!before.equals(result)) {
            bus.post(new AntiCheatChangeEvent(before, result));
        }
        return result;
    }

    private Detection detect(AntiCheatSignature signature, ServerEvidence evidence) {
        try {
            return signature.detect(evidence);
        } catch (RuntimeException e) {
            if (failed.add(signature)) {
                logger.error("AntiCheatSignature " + signature.getClass().getName()
                        + " threw; skipping it (further failures are not logged)", e);
            }
            return null;
        }
    }

    private final class Traffic implements PacketListener {

        @Override
        public void onInbound(PacketDescription packet, long nanos) {
            Long id = packet.getLong(PacketFields.TRANSACTION);
            if (id != null) {
                transactions.record(nanos, id);
            }
        }
    }

    private final class Listener {

        @Subscribe(stage = Stage.PRE)
        private void onTick(TickEvent event) {
            if (timer.tick()) {
                evaluate();
            }
        }

        @Subscribe
        private void onServer(ServerChangeEvent event) {
            if (event.isJoin() || event.isLeave() || event.isBrandChanged()) {
                transactions.reset();
                timer.reset();
            }
            evaluate();
        }
    }
}
