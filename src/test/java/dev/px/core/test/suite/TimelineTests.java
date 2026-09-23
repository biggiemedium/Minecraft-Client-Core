package dev.px.core.test.suite;

import dev.px.core.Core;
import dev.px.core.event.Event;
import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscription;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.MotionUpdateEvent;
import dev.px.core.event.impl.PacketEvent;
import dev.px.core.event.impl.PacketEvent.Direction;
import dev.px.core.event.impl.PacketEvent.Phase;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.timeline.EntryType;
import dev.px.core.movement.timeline.PacketDescription;
import dev.px.core.movement.timeline.PacketKind;
import dev.px.core.movement.timeline.Timeline;
import dev.px.core.movement.timeline.TimelineEntry;
import dev.px.core.movement.timeline.TimelineJson;
import dev.px.core.movement.timeline.TimelineRecorder;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The timeline recorder: that it records the right things, in exactly the
 * order they happened, with the relationships between them intact.
 *
 * <p>Order is the claim worth testing hardest. Every check here reads seq
 * numbers off entries and asserts them against the order the test posted in,
 * including the awkward cases: a packet a module sends from inside a tick
 * handler, a packet cancelled by a handler that ran first, and thousands of
 * packets arriving from other threads while ticks are posted on this one.
 *
 * <p>Almost every check runs against a bare bus with nothing else subscribed,
 * which is the proof that the recorder needs neither {@code RotationService} nor
 * {@code SimulationService}: neither exists on that bus.
 */
public final class TimelineTests {

    private TimelineTests() {
    }

    public static void run(TestClient client) throws Exception {
        Checks.section("Timeline");

        idle();
        tickOrdering();
        cancellation();
        receivedAndApplied();
        crossThreadOrdering();
        correlation();
        marksFiltersAndCapacity();
        describerFailure();
        jsonRoundTrip();
        bootedClient(client);
    }

    // ---------------------------------------------------------------- idle

    private static void idle() {
        RecordingLogger logger = new RecordingLogger();
        CountingBus bus = new CountingBus(new CoreEventBus(logger));
        TimelineRecorder recorder = new TimelineRecorder(logger, bus);

        Checks.checkSurvives("starting the service", () -> {
            try {
                recorder.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Checks.checkEquals("starting subscribes nothing", 0, bus.subscriptions);
        Checks.check("and is not recording", !recorder.isRecording());

        bus.post(new TickEvent(Stage.PRE));
        bus.post(PacketEvent.sent(new Move()));
        Checks.checkEquals("a mark while idle records nothing", null, recorder.mark("nothing"));
        Checks.checkThrows("ending without beginning is an error", IllegalStateException.class, recorder::end);
        Checks.checkThrows("so is a snapshot", IllegalStateException.class, recorder::snapshot);

        recorder.begin("one");
        Checks.checkEquals("beginning subscribes one listener", 1, bus.subscriptions);
        Checks.checkThrows("a second begin is refused", IllegalStateException.class, () -> recorder.begin("two"));
        recorder.end();
        Checks.checkEquals("ending unsubscribes it again", 0, bus.subscriptions);

        recorder.begin("three");
        recorder.stop();
        Checks.check("stopping the service ends a recording in progress", !recorder.isRecording());
        Checks.checkEquals("and leaves nothing subscribed", 0, bus.subscriptions);
    }

    // -------------------------------------------------------------- ticks

    private static void tickOrdering() {
        Rig rig = new Rig();
        // A module sending from its own tick handlers, at ordinary priorities.
        rig.bus.on(TickEvent.class, Priority.HIGHEST, event -> {
            if (event.getStage() == Stage.PRE) {
                rig.bus.post(PacketEvent.sent(new Action()));
            }
        });
        rig.bus.on(TickEvent.class, Priority.LOWEST, event -> {
            if (event.getStage() == Stage.POST) {
                rig.bus.post(PacketEvent.sent(new Action()));
            }
        });

        rig.recorder.begin("ticks");
        rig.clock.set(1_000L);
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.clock.set(1_500L);
        rig.motion(Stage.PRE, Vec3.of(0.5d, 64d, 0.5d));
        rig.bus.post(PacketEvent.sent(new Move()));
        rig.motion(Stage.POST, Vec3.of(0.5d, 64d, 0.5d));
        rig.clock.set(2_000L);
        rig.bus.post(new TickEvent(Stage.POST));
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.bus.post(new WorldEvent(false, "play.example.net"));
        Timeline timeline = rig.recorder.end();

        List<EntryType> types = new ArrayList<>();
        for (TimelineEntry entry : timeline.getEntries()) {
            types.add(entry.getType());
        }
        Checks.checkEquals("entries come out in the order they happened",
                java.util.Arrays.asList(EntryType.TICK_START, EntryType.PACKET, EntryType.MOTION_PRE,
                        EntryType.PACKET, EntryType.MOTION_POST, EntryType.PACKET, EntryType.TICK_END,
                        EntryType.TICK_START, EntryType.PACKET, EntryType.WORLD),
                types);
        Checks.check("a packet sent from a HIGHEST tick handler lands after the tick starts",
                timeline.getEntries().get(1).getPacketType().equals("Action"));
        Checks.check("a packet sent from a LOWEST POST handler lands before the tick ends",
                timeline.getEntries().get(5).getPacketType().equals("Action")
                        && timeline.getEntries().get(6).getType() == EntryType.TICK_END);
        Checks.check("seq is gapless from zero", gapless(timeline, 0L));
        Checks.checkEquals("the first tick is tick 1", 1L, timeline.getEntries().get(0).getTick());
        Checks.checkEquals("and everything before the next boundary shares it", 1L,
                timeline.getEntries().get(6).getTick());
        Checks.checkEquals("the second tick is tick 2", 2L, timeline.getEntries().get(7).getTick());
        Checks.checkEquals("ticks counted", 2L, timeline.getTicks());
        Checks.checkEquals("time is measured from begin", 1_000L, timeline.getEntries().get(0).getNanos());
        Checks.checkEquals("and read at the moment of capture", 1_500L, timeline.getEntries().get(2).getNanos());

        TimelineEntry pre = timeline.getEntries().get(2);
        Checks.checkEquals("motion records position", Vec3.of(0.5d, 64d, 0.5d), pre.getPosition());
        Checks.checkEquals("and velocity", Vec3.of(0.1d, -0.0784d, 0d), pre.getVelocity());
        Checks.checkEquals("and ground", Boolean.TRUE, pre.getOnGround());
        Checks.checkEquals("and the keys", MovementInput.forward(90f).withSprint(true), pre.getInput());
        Checks.checkEquals("and sprint state", Boolean.TRUE, pre.getSprinting());
        Checks.checkEquals("and sneak state", Boolean.FALSE, pre.getSneaking());
        Checks.checkEquals("and rotation", Vec2.rotation(90f, 12f), pre.getRotation());
        Checks.checkEquals("and last tick's rotation", Vec2.rotation(85f, 10f), pre.getPreviousRotation());
        Checks.checkEquals("motion comes back out as one state",
                MotionState.of(Vec3.of(0.5d, 64d, 0.5d), Vec3.of(0.1d, -0.0784d, 0d), true),
                pre.getMotionState());

        TimelineEntry move = timeline.getEntries().get(3);
        Checks.checkEquals("a sent packet is outbound", Direction.OUTBOUND, move.getDirection());
        Checks.checkEquals("with the describer's kind", PacketKind.MOVEMENT, move.getKind());
        Checks.checkEquals("and its position", Vec3.of(0.5d, 64d, 0.5d), move.getPosition());

        TimelineEntry world = timeline.getEntries().get(9);
        Checks.checkEquals("a world change carries its address", "play.example.net", world.getLabel());
        Checks.checkEquals("and whether it loaded", Boolean.FALSE, world.getFields().get("loaded"));
    }

    private static void cancellation() {
        Rig rig = new Rig();
        rig.bus.on(PacketEvent.class, Priority.HIGH, event -> {
            if (event.getPacket() instanceof Action) {
                event.setCancelled(true);
            }
        });
        rig.recorder.begin("cancel");
        rig.bus.post(PacketEvent.sent(new Action()));
        rig.bus.post(PacketEvent.sent(new Move()));
        Timeline timeline = rig.recorder.end();

        Checks.checkEquals("a cancelled packet is still recorded", 2, timeline.size());
        Checks.check("and says it was cancelled", timeline.getEntries().get(0).isCancelled());
        Checks.check("an uncancelled one does not", !timeline.getEntries().get(1).isCancelled());
    }

    // --------------------------------------------------------------- links

    private static void receivedAndApplied() throws InterruptedException {
        Rig rig = new Rig();
        rig.recorder.begin("links");
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.motion(Stage.POST, Vec3.of(10d, 70d, 10d));
        TimelineEntry clientMotion = rig.recorder.snapshot().getEntries().get(1);

        final Teleport teleport = new Teleport(Vec3.of(10d, 64d, 10d));
        final Velocity velocity = new Velocity();
        Thread network = new Thread(() -> {
            rig.bus.post(PacketEvent.received(teleport));
            rig.bus.post(PacketEvent.received(velocity));
            rig.bus.post(PacketEvent.received(new Transaction(3)));
        }, "network");
        network.start();
        network.join();

        rig.bus.post(new TickEvent(Stage.POST));
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.bus.post(PacketEvent.applied(teleport));
        rig.bus.post(PacketEvent.applied(velocity));
        rig.bus.post(PacketEvent.applied(new Transaction(3)));   // a different instance
        Timeline timeline = rig.recorder.end();

        TimelineEntry received = timeline.filter(e -> e.getPhase() == Phase.RECEIVED
                && e.getKind() == PacketKind.TELEPORT).get(0);
        TimelineEntry applied = timeline.filter(e -> e.getPhase() == Phase.APPLIED
                && e.getKind() == PacketKind.TELEPORT).get(0);
        Checks.checkEquals("received on the network thread during tick 1", 1L, received.getTick());
        Checks.checkEquals("applied on the game thread during tick 2", 2L, applied.getTick());
        Checks.checkEquals("the applied half points at the received half", received.getSeq(), applied.getLinkedSeq());
        Checks.checkEquals("linked() finds it from the applied side", received, timeline.linked(applied));
        Checks.checkEquals("and from the received side", applied, timeline.linked(received));
        Checks.checkEquals("both phases are inbound", Direction.INBOUND, received.getDirection());

        TimelineEntry correction = timeline.find(applied.getSeq() + 1);
        Checks.checkEquals("an applied teleport is followed by a correction",
                EntryType.CORRECTION, correction.getType());
        Checks.checkEquals("pointing at the packet that caused it", applied.getSeq(), correction.getLinkedSeq());
        Checks.checkEquals("carrying the server's position", Vec3.of(10d, 64d, 10d), correction.getPosition());
        Checks.checkEquals("and the client motion it overruled", clientMotion.getSeq(),
                correction.getFields().get("clientSeq"));
        Checks.checkEquals("and the packet's own fields", 1L, correction.getFields().get("teleportId"));

        Checks.checkEquals("velocity is a correction too", 2, timeline.ofType(EntryType.CORRECTION).size());
        TimelineEntry otherTransaction = timeline.filter(e -> e.getPhase() == Phase.APPLIED
                && e.getKind() == PacketKind.TRANSACTION).get(0);
        Checks.check("linking is by instance, not by equality", !otherTransaction.hasLink());
        Checks.checkEquals("a received half left waiting is simply unlinked", null,
                timeline.linked(timeline.filter(e -> e.getPhase() == Phase.RECEIVED
                        && e.getKind() == PacketKind.TRANSACTION).get(0)));
    }

    private static void crossThreadOrdering() throws InterruptedException {
        RecordingLogger logger = new RecordingLogger();
        CoreEventBus bus = new CoreEventBus(logger);
        TimelineRecorder recorder = new TimelineRecorder(logger, bus);   // the real clock
        recorder.setDescriber(TimelineTests::describe);
        recorder.begin("threads");

        final int threads = 4;
        final int perThread = 2_000;
        final CountDownLatch go = new CountDownLatch(1);
        List<Thread> network = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    bus.post(PacketEvent.received(new Velocity()));
                }
            }, "network-" + t);
            network.add(thread);
            thread.start();
        }
        go.countDown();
        for (int tick = 0; tick < 200; tick++) {
            bus.post(new TickEvent(Stage.PRE));
            bus.post(new TickEvent(Stage.POST));
        }
        for (Thread thread : network) {
            thread.join();
        }
        Timeline timeline = recorder.end();

        Checks.checkEquals("every entry from every thread is kept", threads * perThread + 400, timeline.size());
        Checks.check("seq stays gapless across threads", gapless(timeline, 0L));
        boolean monotonic = true;
        boolean ticksInOrder = true;
        for (int i = 1; i < timeline.size(); i++) {
            TimelineEntry before = timeline.getEntries().get(i - 1);
            TimelineEntry after = timeline.getEntries().get(i);
            monotonic &= after.getNanos() >= before.getNanos();
            ticksInOrder &= after.getTick() >= before.getTick();
        }
        Checks.check("time never runs backwards along seq", monotonic);
        Checks.check("nor does the tick", ticksInOrder);
        Checks.checkEquals("no errors under contention", 0, logger.errorCount());
    }

    // ---------------------------------------------------------- correlation

    private static void correlation() {
        Rig rig = new Rig();
        rig.recorder.begin("correlate");
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.bus.post(PacketEvent.sent(new Move()));
        TimelineEntry sent = rig.recorder.snapshot().getEntries().get(1);
        rig.bus.post(PacketEvent.received(new Transaction(7)));
        rig.bus.post(PacketEvent.sent(new Transaction(7)));
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.bus.post(PacketEvent.received(new Teleport(Vec3.ZERO)));
        for (int i = 0; i < 3; i++) {
            rig.bus.post(new TickEvent(Stage.PRE));
        }
        rig.bus.post(PacketEvent.received(new Teleport(Vec3.ZERO)));
        Timeline timeline = rig.recorder.end();

        List<TimelineEntry> replies = timeline.responsesTo(sent, PacketKind.TELEPORT, 2);
        Checks.checkEquals("a teleport inside the window answers the move", 1, replies.size());
        Checks.checkEquals("and it is the first one", 2L, replies.get(0).getTick());
        Checks.checkEquals("with no kind, every arrival in the window counts", 2,
                timeline.responsesTo(sent, null, 2).size());
        Checks.checkEquals("a wider window reaches the later teleport", 2,
                timeline.responsesTo(sent, PacketKind.TELEPORT, 10).size());

        TimelineEntry inbound = timeline.filter(e -> e.getKind() == PacketKind.TRANSACTION && e.isInbound()).get(0);
        List<TimelineEntry> answer = timeline.correlated(inbound);
        Checks.checkEquals("a transaction pairs with its reply by key", 1, answer.size());
        Checks.check("travelling the other way", answer.get(0).isOutbound());
        Checks.check("a packet with no key correlates with nothing", timeline.correlated(sent).isEmpty());
    }

    // ------------------------------------------------ marks, filters, capacity

    private static void marksFiltersAndCapacity() {
        Rig rig = new Rig();
        rig.recorder.begin("marks");
        rig.bus.post(PacketEvent.sent(new Move()));
        rig.recorder.mark("jump");
        rig.bus.post(PacketEvent.sent(new Action()));
        rig.bus.post(PacketEvent.sent(new Move()));
        rig.recorder.mark("landed");
        rig.bus.post(PacketEvent.sent(new Move()));
        Timeline timeline = rig.recorder.end();
        List<TimelineEntry> between = timeline.between("jump", "landed");
        Checks.checkEquals("between two marks, exclusive", 2, between.size());
        Checks.checkEquals("starting just after the first", "Action", between.get(0).getPacketType());
        Checks.checkThrows("a missing mark is an error", IllegalArgumentException.class,
                () -> timeline.between("jump", "never"));

        rig.recorder.setFilter(description -> description.getKind() != PacketKind.ACTION);
        rig.recorder.begin("filtered");
        rig.bus.post(PacketEvent.sent(new Action()));
        rig.bus.post(PacketEvent.sent(new Move()));
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.bus.post(PacketEvent.sent(new Action()));
        Timeline filtered = rig.recorder.end();
        Checks.checkEquals("the filter drops packets it rejects", 2, filtered.size());
        Checks.check("without leaving holes in seq", gapless(filtered, 0L));
        rig.recorder.setFilter(null);

        rig.recorder.setCapacity(5);
        rig.recorder.begin("capped");
        Checks.checkThrows("capacity is fixed while recording", IllegalStateException.class,
                () -> rig.recorder.setCapacity(10));
        for (int i = 0; i < 8; i++) {
            rig.recorder.mark("m" + i);
        }
        Timeline capped = rig.recorder.end();
        Checks.checkEquals("a full recording keeps its capacity", 5, capped.size());
        Checks.checkEquals("and counts what it dropped", 3L, capped.getDropped());
        Checks.checkEquals("dropping the oldest first", 3L, capped.getEntries().get(0).getSeq());
        Checks.checkEquals("seq lookup still works after dropping", "m5", capped.find(5L).getLabel());
        Checks.checkEquals("and a dropped seq is simply absent", null, capped.find(1L));
    }

    private static void describerFailure() {
        Rig rig = new Rig();
        rig.recorder.setDescriber(packet -> {
            if (packet instanceof Action) {
                throw new IllegalStateException("broken branch");
            }
            return packet instanceof Move ? null : describe(packet);
        });
        rig.recorder.begin("broken");
        rig.bus.post(PacketEvent.sent(new Action()));
        rig.bus.post(PacketEvent.sent(new Action()));
        rig.bus.post(PacketEvent.sent(new Move()));
        Timeline timeline = rig.recorder.end();

        Checks.checkEquals("a throwing describer costs the description, not the entry", 3, timeline.size());
        Checks.checkEquals("falling back to the class name", "Action", timeline.getEntries().get(0).getPacketType());
        Checks.checkEquals("classified as other", PacketKind.OTHER, timeline.getEntries().get(0).getKind());
        Checks.checkEquals("logged once, not per packet", 1, rig.logger.errorCount());
        Checks.checkEquals("a null description falls back too", "Move", timeline.getEntries().get(2).getPacketType());
    }

    // ---------------------------------------------------------------- json

    private static void jsonRoundTrip() throws Exception {
        Rig rig = new Rig();
        rig.recorder.putMetadata("gameVersion", "1.8.9");
        rig.recorder.begin("round trip");
        rig.bus.post(new TickEvent(Stage.PRE));
        rig.motion(Stage.PRE, Vec3.of(0.3d, 64.0000001d, -12.75d));
        rig.bus.post(PacketEvent.sent(new Move()));
        Teleport teleport = new Teleport(Vec3.of(1d, 2d, 3d));
        rig.bus.post(PacketEvent.received(teleport));
        rig.bus.post(PacketEvent.applied(teleport));
        rig.bus.post(PacketEvent.sent(new Transaction(9)));
        rig.recorder.mark("done");
        rig.bus.post(new WorldEvent(true, null));
        rig.bus.post(new TickEvent(Stage.POST));
        Timeline written = rig.recorder.end();

        StringWriter out = new StringWriter();
        TimelineJson.write(written, out);
        String text = out.toString();
        Timeline read = TimelineJson.read(new StringReader(text));

        Checks.checkEquals("one header line plus one line per entry",
                written.size() + 1, text.split("\n").length);
        Checks.checkEquals("every entry reads back equal", written.getEntries(), read.getEntries());
        Checks.checkEquals("the label survives", "round trip", read.getLabel());
        Checks.checkEquals("and the metadata", "1.8.9", read.getMetadata().get("gameVersion"));
        Checks.checkEquals("and the tick count", written.getTicks(), read.getTicks());
        Checks.check("absent fields are left out, not written as null", !text.contains("null"));
        boolean refused = false;
        try {
            TimelineJson.read(new StringReader("{\"format\":\"other\",\"version\":1}\n"));
        } catch (java.io.IOException expected) {
            refused = true;
        }
        Checks.check("something that is not a timeline is refused", refused);
    }

    // -------------------------------------------------------------- client

    private static void bootedClient(TestClient client) {
        TimelineRecorder recorder = Core.timeline();
        Checks.check("the booted client has a recorder", recorder != null);
        Checks.check("idle after startup", !recorder.isRecording());

        recorder.begin("live");
        Core.bus().post(new TickEvent(Stage.PRE));
        Core.bus().post(PacketEvent.sent(new Move()));
        Core.bus().post(new TickEvent(Stage.POST));
        Timeline timeline = recorder.end();
        Checks.checkEquals("records through the client's own bus", 3, timeline.size());
        Checks.checkEquals("packets read as their class name with no describer installed",
                "Move", timeline.getEntries().get(1).getPacketType());
    }

    // ------------------------------------------------------------- helpers

    private static boolean gapless(Timeline timeline, long first) {
        Set<Long> seen = new HashSet<>();
        long expected = first;
        for (TimelineEntry entry : timeline.getEntries()) {
            if (entry.getSeq() != expected++ || !seen.add(entry.getSeq())) {
                return false;
            }
        }
        return true;
    }

    /** The adapter's describer, for the fake packets below. */
    private static PacketDescription describe(Object packet) {
        if (packet instanceof Move) {
            return PacketDescription.of("C04Move", PacketKind.MOVEMENT)
                    .withPosition(Vec3.of(0.5d, 64d, 0.5d))
                    .withOnGround(true);
        }
        if (packet instanceof Action) {
            return PacketDescription.of("Action", PacketKind.ACTION).with("action", "START_SPRINTING");
        }
        if (packet instanceof Teleport) {
            return PacketDescription.of("S08Teleport", PacketKind.TELEPORT)
                    .withPosition(((Teleport) packet).position)
                    .withRotation(Vec2.rotation(0f, 0f))
                    .with("teleportId", 1)
                    .with("relative", false);
        }
        if (packet instanceof Velocity) {
            return PacketDescription.of("S12Velocity", PacketKind.VELOCITY)
                    .withVelocity(Vec3.of(0d, 0.4d, 0d))
                    .with("scale", 8000.5d);
        }
        if (packet instanceof Transaction) {
            return PacketDescription.of("Transaction", PacketKind.TRANSACTION)
                    .withCorrelationKey("tx:" + ((Transaction) packet).uid);
        }
        return null;
    }

    private static final class Move {
    }

    private static final class Action {
    }

    private static final class Velocity {
    }

    private static final class Teleport {
        private final Vec3 position;

        private Teleport(Vec3 position) {
            this.position = position;
        }
    }

    private static final class Transaction {
        private final int uid;

        private Transaction(int uid) {
            this.uid = uid;
        }

        // Equal by uid, which is exactly why linking must not use equals.
        @Override
        public boolean equals(Object other) {
            return other instanceof Transaction && ((Transaction) other).uid == uid;
        }

        @Override
        public int hashCode() {
            return uid;
        }
    }

    /** A bare bus, a hand-driven clock, and a recorder with the test describer. */
    private static final class Rig {

        final RecordingLogger logger = new RecordingLogger();
        final CoreEventBus bus = new CoreEventBus(logger);
        final AtomicLong clock = new AtomicLong();
        final TimelineRecorder recorder = new TimelineRecorder(logger, bus, clock::get);

        Rig() {
            recorder.setDescriber(TimelineTests::describe);
        }

        void motion(Stage stage, Vec3 position) {
            bus.post(new MotionUpdateEvent(stage,
                    MotionState.of(position, Vec3.of(0.1d, -0.0784d, 0d), true),
                    MovementInput.forward(90f).withSprint(true),
                    true, false,
                    Vec2.rotation(90f, 12f), Vec2.rotation(85f, 10f)));
        }
    }

    /** Counts live subscriptions, to prove the recorder holds none while idle. */
    private static final class CountingBus implements EventBus {

        private final EventBus delegate;
        private int subscriptions;

        private CountingBus(EventBus delegate) {
            this.delegate = delegate;
        }

        @Override
        public void subscribe(Object listener) {
            subscriptions++;
            delegate.subscribe(listener);
        }

        @Override
        public void unsubscribe(Object listener) {
            if (delegate.isSubscribed(listener)) {
                subscriptions--;
            }
            delegate.unsubscribe(listener);
        }

        @Override
        public boolean isSubscribed(Object listener) {
            return delegate.isSubscribed(listener);
        }

        @Override
        public <T extends Event> T post(T event) {
            return delegate.post(event);
        }

        @Override
        public <T extends Event> Subscription on(Class<T> type, Consumer<? super T> handler) {
            subscriptions++;
            return delegate.on(type, handler);
        }

        @Override
        public <T extends Event> Subscription on(Class<T> type, int priority, Consumer<? super T> handler) {
            subscriptions++;
            return delegate.on(type, priority, handler);
        }

        @Override
        public void clear() {
            subscriptions = 0;
            delegate.clear();
        }
    }
}
