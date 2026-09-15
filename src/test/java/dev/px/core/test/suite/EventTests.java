package dev.px.core.test.suite;

import dev.px.core.event.CancellableEvent;
import dev.px.core.event.Event;
import dev.px.core.event.EventBus;
import dev.px.core.event.Listenable;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.StagedEvent;
import dev.px.core.event.Subscribe;
import dev.px.core.event.Subscription;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;
import dev.px.core.util.ConsoleLogger;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The event bus: dispatch order, filtering, cancellation and the listening gate.
 *
 * <p>Declaring an event is four lines. {@link Ping} below is the whole thing,
 * where the old client's equivalent ran to seventy.
 */
public final class EventTests {

    private EventTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Events");

        EventBus bus = new CoreEventBus(new ConsoleLogger("test"));
        Listener listener = new Listener();
        bus.subscribe(listener);

        // ---- declaring and posting ------------------------------------------
        Ping ping = bus.post(new Ping("hello"));
        Checks.checkEquals("post returns the same event, so it can be inspected inline",
                "hello", ping.getMessage());
        Checks.checkEquals("a handler received it", "[high, normal, low]", listener.order.toString());

        // ---- priority --------------------------------------------------------
        listener.order.clear();
        bus.post(new Ping("again"));
        Checks.checkEquals("handlers run highest priority first",
                "[high, normal, low]", listener.order.toString());

        // ---- cancellation ----------------------------------------------------
        listener.cancelAtHigh = true;
        listener.order.clear();
        Ping cancelled = bus.post(new Ping("stop"));
        Checks.check("a cancelled event reports it", cancelled.isCancelled());
        Checks.checkEquals("cancelling stops later handlers, except opt-ins",
                "[high, low]", listener.order.toString());
        listener.cancelAtHigh = false;

        // ---- subscribing twice ------------------------------------------------
        bus.subscribe(listener);
        listener.order.clear();
        bus.post(new Ping("once"));
        Checks.checkEquals("subscribing the same instance twice is a no-op",
                3f, listener.order.size());

        // ---- supertype dispatch ------------------------------------------------
        listener.baseHits = 0;
        bus.post(new Ping("base"));
        bus.post(new Pong());
        Checks.checkEquals("a handler on a base event receives every subclass", 2f, listener.baseHits);

        // ---- stage filtering ----------------------------------------------------
        listener.preHits = 0;
        listener.postHits = 0;
        listener.anyStageHits = 0;
        bus.post(new Phased(Stage.PRE));
        bus.post(new Phased(Stage.POST));
        Checks.checkEquals("a PRE handler sees only the pre stage", 1f, listener.preHits);
        Checks.checkEquals("a POST handler sees only the post stage", 1f, listener.postHits);
        Checks.checkEquals("an unfiltered handler sees both", 2f, listener.anyStageHits);

        // ---- the listening gate --------------------------------------------------
        GatedListener gated = new GatedListener();
        bus.subscribe(gated);
        gated.listening = false;
        bus.post(new Ping("gated"));
        Checks.checkEquals("handlers go quiet while the owner is not listening", 0f, gated.normalHits);
        Checks.checkEquals("ignoreListening handlers still run", 1f, gated.alwaysHits);

        gated.listening = true;
        bus.post(new Ping("gated"));
        Checks.checkEquals("handlers resume without re-subscribing", 1f, gated.normalHits);

        // ---- lambda subscriptions ------------------------------------------------
        AtomicInteger lambdaHits = new AtomicInteger();
        Subscription subscription = bus.on(Ping.class, event -> lambdaHits.incrementAndGet());
        bus.post(new Ping("lambda"));
        Checks.checkEquals("a lambda handler receives events", 1f, lambdaHits.get());
        subscription.close();
        bus.post(new Ping("lambda"));
        Checks.checkEquals("closing the subscription removes it", 1f, lambdaHits.get());
        Checks.check("a closed subscription reports itself inactive", !subscription.isActive());
        Checks.checkSurvives("closing twice is harmless", subscription::close);

        // ---- resilience -----------------------------------------------------------
        bus.subscribe(new ThrowingListener());
        AtomicInteger afterThrow = new AtomicInteger();
        bus.on(Ping.class, Priority.LOWEST, event -> afterThrow.incrementAndGet());
        Checks.checkSurvives("a handler that throws does not break the post",
                () -> bus.post(new Ping("boom")));
        Checks.checkEquals("later handlers still run after one throws", 1f, afterThrow.get());

        // ---- unsubscribing ---------------------------------------------------------
        bus.unsubscribe(listener);
        listener.order.clear();
        bus.post(new Ping("gone"));
        Checks.checkEquals("unsubscribing removes every handler", 0f, listener.order.size());
        Checks.check("isSubscribed reflects it", !bus.isSubscribed(listener));

        // ---- validation -------------------------------------------------------------
        Checks.checkThrows("a handler with the wrong parameter count is rejected at registration",
                IllegalArgumentException.class, () -> bus.subscribe(new BadListener()));

        // ---- the real client ----------------------------------------------------------
        client.reset();
        client.getKillAura().disable();
        client.getKillAura().resetCounters();
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.checkEquals("a disabled module receives nothing", 0f, client.getKillAura().getPreTicks());
        Checks.checkEquals("its ignoreListening handler still runs", 1f, client.getKillAura().getEveryTick());

        client.getKillAura().enable();
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        client.getCore().getBus().post(new TickEvent(Stage.POST));
        Checks.checkEquals("an enabled module receives its stage", 1f, client.getKillAura().getPreTicks());
        Checks.checkEquals("and the other stage separately", 1f, client.getKillAura().getPostTicks());
        Checks.check("priority ordering holds across a module",
                client.getKillAura().isHighPriorityRanFirst());
    }

    // ------------------------------------------------------------ example events

    /** A plain cancellable event. This is the whole declaration. */
    @Getter
    @Setter
    @AllArgsConstructor
    public static class Ping extends CancellableEvent {
        private String message;
    }

    /** A subclass, to prove a handler on the supertype receives it. */
    public static final class Pong extends Ping {
        public Pong() {
            super("pong");
        }
    }

    /** Fires before and after its action; handlers narrow with {@code stage}. */
    public static final class Phased extends StagedEvent {
        public Phased(Stage stage) {
            super(stage);
        }
    }

    // ---------------------------------------------------------------- listeners

    static final class Listener {

        final List<String> order = new ArrayList<>();
        boolean cancelAtHigh;
        int baseHits;
        int preHits;
        int postHits;
        int anyStageHits;

        @Subscribe(priority = Priority.HIGH)
        private void high(Ping event) {
            order.add("high");
            if (cancelAtHigh) {
                event.cancel();
            }
        }

        @Subscribe
        private void normal(Ping event) {
            order.add("normal");
        }

        /** Opts in to cancelled events, so it still runs after high cancels. */
        @Subscribe(priority = Priority.LOW, receiveCancelled = true)
        private void low(Ping event) {
            order.add("low");
        }

        /** Declared on the base type, so it receives Ping and Pong alike. */
        @Subscribe
        private void anyEvent(Event event) {
            if (event instanceof Ping) {
                baseHits++;
            }
        }

        @Subscribe(stage = Stage.PRE)
        private void pre(Phased event) {
            preHits++;
        }

        @Subscribe(stage = Stage.POST)
        private void post(Phased event) {
            postHits++;
        }

        @Subscribe
        private void anyStage(Phased event) {
            anyStageHits++;
        }
    }

    /** Gates its own handlers, the way a module gates on being enabled. */
    static final class GatedListener implements Listenable {

        boolean listening = true;
        int normalHits;
        int alwaysHits;

        @Override
        public boolean isListening() {
            return listening;
        }

        @Subscribe
        private void gated(Ping event) {
            normalHits++;
        }

        @Subscribe(ignoreListening = true)
        private void ungated(Ping event) {
            alwaysHits++;
        }
    }

    static final class ThrowingListener {
        @Subscribe(priority = Priority.HIGH)
        private void explode(Ping event) {
            throw new IllegalStateException("deliberate handler failure");
        }
    }

    static final class BadListener {
        @Subscribe
        private void twoParameters(Ping event, String extra) {
        }
    }
}
