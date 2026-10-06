package dev.px.core.test.suite;

import dev.px.core.Core;
import dev.px.core.event.Listenable;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.ChatSendEvent;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.PacketEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.hook.GameHooks;
import dev.px.core.hook.Hook;
import dev.px.core.hook.HookStatus;
import dev.px.core.input.Key;
import dev.px.core.input.MouseButton;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.FakePlatform;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code Core.hooks()}: the one place an adapter tells Core about the game, and
 * the reason a hook nobody calls no longer fails silently.
 *
 * <p>Runs on a bare bus with a hand-driven clock, so the self-check's grace
 * period is checked to the nanosecond without waiting for it.
 */
public final class HookTests {

    private HookTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Game hooks");

        posting();
        counting();
        listeners();
        selfCheck();
        verification();
        bootedClient(client);
    }

    // -------------------------------------------------------------- posting

    private static void posting() {
        Rig rig = new Rig();
        List<String> seen = new ArrayList<>();
        rig.bus.on(TickEvent.class, e -> seen.add("tick:" + e.getStage()));
        rig.bus.on(WorldEvent.class, e -> seen.add("world:" + e.isLoaded() + ":" + e.getServerAddress()));
        rig.hooks.tickStart();
        rig.hooks.tickEnd();
        rig.hooks.worldLoaded(null);
        rig.hooks.worldUnloaded();
        Checks.checkEquals("each hook posts its event, in the stage it names",
                "[tick:PRE, tick:POST, world:true:, world:false:]", seen.toString());

        rig.bus.on(PacketEvent.class, Priority.HIGHEST, e -> e.setCancelled(e.getPacket() instanceof String));
        Checks.check("packet hooks say whether a handler cancelled the packet",
                rig.hooks.packetSent("cancel me") && !rig.hooks.packetSent(1) && rig.hooks.packetReceived("x"));

        rig.bus.on(KeyEvent.class, Priority.HIGHEST, e -> e.setCancelled(e.getKey() == Key.ESCAPE));
        Checks.check("input hooks too, and a null modifier set is none",
                rig.hooks.key(Key.ESCAPE, null, true) && !rig.hooks.key(Key.A, null, true));

        rig.bus.on(ChatSendEvent.class, e -> e.setMessage(e.getMessage().toUpperCase()));
        Checks.checkEquals("chat hands back the event, so a rewritten message is what gets sent", "HELLO",
                rig.hooks.chatSend("hello").getMessage());
    }

    private static void counting() {
        Rig rig = new Rig();
        Checks.check("nothing has fired before anything is called", !rig.hooks.hasFired(Hook.TICK));
        rig.hooks.tickStart();
        rig.hooks.tickEnd();
        Checks.checkEquals("each post counts", 2L, rig.hooks.timesFired(Hook.TICK));

        rig.bus.post(new TickEvent(Stage.PRE));
        Checks.checkEquals("an event the adapter posts itself counts the same", 3L, rig.hooks.timesFired(Hook.TICK));

        rig.bus.on(PacketEvent.class, Priority.HIGHEST, e -> e.setCancelled(true));
        rig.hooks.packetReceived(new Object());
        rig.hooks.packetSent(new Object());
        Checks.check("a cancelled packet still fired its hook, inbound and outbound apart",
                rig.hooks.timesFired(Hook.PACKET_IN) == 1L && rig.hooks.timesFired(Hook.PACKET_OUT) == 1L);
        rig.hooks.packetApplied(new Object());
        Checks.checkEquals("applied counts as inbound", 2L, rig.hooks.timesFired(Hook.PACKET_IN));
        rig.hooks.mouse(MouseButton.LEFT, null, true, 0f, 0f);
        rig.hooks.render2D(0f, 1f, 1f);
        Checks.check("and every other hook is counted under its own name",
                rig.hooks.hasFired(Hook.MOUSE) && rig.hooks.hasFired(Hook.RENDER_2D) && !rig.hooks.hasFired(Hook.KEY));
    }

    // ------------------------------------------------------------ listeners

    private static void listeners() {
        Rig rig = new Rig();
        Checks.check("the hooks' own counting is not a listener", rig.hooks.listenersOf(Hook.TICK).isEmpty());

        TickUser service = new TickUser();
        rig.bus.subscribe(service);
        Checks.checkEquals("a listener is named after the class that owns it", "[TickUser]",
                rig.hooks.listenersOf(Hook.TICK).toString());

        Switchable module = new Switchable();
        rig.bus.subscribe(module);
        Checks.checkEquals("one that is not listening, like a disabled module, does not count", 1,
                rig.hooks.listenersOf(Hook.TICK).size());
        module.on = true;
        Checks.checkEquals("until it is", 2, rig.hooks.listenersOf(Hook.TICK).size());

        rig.bus.subscribe(new CatchAll());
        Checks.checkEquals("a catch-all handler on Event itself does not make every hook needed", 2,
                rig.hooks.listenersOf(Hook.TICK).size());

        rig.bus.on(WorldEvent.class, e -> { });
        Checks.checkEquals("a bus.on(...) handler is named after the class that registered it", "[HookTests]",
                rig.hooks.listenersOf(Hook.WORLD).toString());

        HookStatus tick = rig.hooks.report().get(Hook.TICK.ordinal());
        Checks.check("the report says who waits on a hook that has not fired",
                tick.isMissing() && tick.getListeners().contains("TickUser") && tick.toString().contains("MISSING"));
        Checks.check("and a hook nothing uses is not missing", !rig.hooks.report().get(Hook.SCREEN.ordinal()).isMissing());
    }

    // ------------------------------------------------------------ self-check

    private static void selfCheck() {
        Rig rig = new Rig();
        rig.bus.subscribe(new TickUser());
        rig.platform.setInGame(false);
        rig.at(0L);
        rig.hooks.checkNow();
        rig.at(60_000L);
        rig.hooks.checkNow();
        Checks.checkEquals("out of a world, a missing hook is no news", 0, rig.logger.warningCount());

        rig.platform.setInGame(true);
        rig.hooks.checkNow();
        rig.at(64_999L);
        rig.hooks.checkNow();
        Checks.checkEquals("in a world, nothing is said within the grace period", 0, rig.logger.warningCount());

        rig.at(65_000L);
        rig.hooks.checkNow();
        Checks.checkEquals("after it, a needed steady hook nobody calls is warned about", 1, rig.logger.warningCount());
        Checks.check("naming what is idle and the method to call",
                rig.logger.loggedWarning("TickUser") && rig.logger.loggedWarning("Core.hooks().tickStart()"));
        rig.at(90_000L);
        rig.hooks.checkNow();
        Checks.checkEquals("once, not every second", 1, rig.logger.warningCount());

        Rig called = new Rig();
        called.bus.subscribe(new TickUser());
        called.hooks.checkNow();
        called.hooks.tickStart();
        called.at(60_000L);
        called.hooks.checkNow();
        Checks.checkEquals("a hook that is called is never warned about", 0, called.logger.warningCount());

        Rig unused = new Rig();
        unused.hooks.checkNow();
        unused.at(60_000L);
        unused.hooks.checkNow();
        Checks.checkEquals("nor one that nothing needs", 0, unused.logger.warningCount());

        Rig keys = new Rig();
        keys.bus.subscribe(new KeyUser());
        keys.hooks.checkNow();
        keys.at(60_000L);
        keys.hooks.checkNow();
        Checks.checkEquals("nor one that fires only when the player acts, since its absence proves nothing", 0,
                keys.logger.warningCount());

        Rig late = new Rig();
        late.hooks.checkNow();
        late.at(30_000L);
        late.hooks.checkNow();
        late.bus.subscribe(new TickUser());
        late.at(31_000L);
        late.hooks.checkNow();
        Checks.checkEquals("something that starts needing a hook late gets the whole grace period from then", 0,
                late.logger.warningCount());
        late.at(36_000L);
        late.hooks.checkNow();
        Checks.checkEquals("and is warned once it has passed", 1, late.logger.warningCount());

        Rig quick = new Rig();
        quick.hooks.setGracePeriodMillis(0L);
        quick.bus.subscribe(new TickUser());
        quick.hooks.checkNow();
        Checks.checkEquals("the grace period is yours to set", 1, quick.logger.warningCount());
        Checks.checkThrows("but not below zero", IllegalArgumentException.class,
                () -> quick.hooks.setGracePeriodMillis(-1L));

        Rig broken = new Rig();
        broken.platform.setThrowOnInGame(true);
        broken.hooks.checkNow();
        broken.hooks.checkNow();
        Checks.checkEquals("a platform that throws is logged once and the check skips", 1, broken.logger.errorCount());
    }

    // ---------------------------------------------------------- verification

    private static void verification() {
        Rig rig = new Rig();
        rig.hooks.verify();
        Checks.check("with nothing listening, nothing is required", true);

        rig.bus.subscribe(new TickUser());
        rig.bus.subscribe(new KeyUser());
        String message = thrown(rig.hooks::verify);
        Checks.check("verify fails while something waits on a hook that never fired",
                message != null && message.startsWith("2 game hooks have never fired"));
        Checks.check("listing each, the call to make, and what is idle",
                message.contains("TICK: call Core.hooks().tickStart()") && message.contains("Idle without it: TickUser")
                        && message.contains("KEY: call Core.hooks().key("));

        rig.hooks.tickStart();
        rig.hooks.key(Key.A, null, true);
        rig.hooks.verify();
        Checks.check("and passes once they have", true);

        String named = thrown(() -> rig.hooks.verify(Hook.TICK, Hook.CHAT_SEND));
        Checks.check("verify(hooks) requires exactly those, needed or not",
                named != null && named.startsWith("1 game hook") && named.contains("CHAT_SEND"));
    }

    // -------------------------------------------------------------- client

    private static void bootedClient(TestClient client) {
        GameHooks hooks = Core.hooks();
        Checks.check("the booted client has hooks", hooks != null);
        List<String> tick = hooks.listenersOf(Hook.TICK);
        Checks.check("and knows what Core itself idles without ticks",
                tick.contains("EntityService") && tick.contains("RotationService") && tick.contains("LagService") && tick.contains("Core"));
        Checks.check("without keys, the module binds", hooks.listenersOf(Hook.KEY).contains("InputService"));
        Checks.check("without chat, the commands", hooks.listenersOf(Hook.CHAT_SEND).contains("CommandRegistry"));
        Checks.check("and without packets, the network", hooks.listenersOf(Hook.PACKET_IN).contains("NetworkService"));
        Checks.check("the earlier suites' ticks were counted", hooks.hasFired(Hook.TICK));
    }

    // ------------------------------------------------------------- helpers

    private static String thrown(Runnable body) {
        try {
            body.run();
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    /** A bare bus, a fake platform in a world, a hand-driven clock, and hooks with no timer. */
    private static final class Rig {
        final RecordingLogger logger = new RecordingLogger();
        final CoreEventBus bus = new CoreEventBus(logger);
        final FakePlatform platform = new FakePlatform(new File("build/tmp/hook-tests"));
        final AtomicLong clock = new AtomicLong();
        final GameHooks hooks = new GameHooks(logger, bus, platform, null, clock::get);

        Rig() {
            hooks.start();
        }

        void at(long millis) {
            clock.set(millis * 1_000_000L);
        }
    }

    private static final class TickUser {
        @Subscribe
        private void onTick(TickEvent event) {
        }
    }

    private static final class KeyUser {
        @Subscribe
        private void onKey(KeyEvent event) {
        }
    }

    private static final class Switchable implements Listenable {
        boolean on;

        @Override
        public boolean isListening() {
            return on;
        }

        @Subscribe
        private void onTick(TickEvent event) {
        }
    }

    private static final class CatchAll {
        @Subscribe
        private void onAnything(dev.px.core.event.Event event) {
        }
    }
}
