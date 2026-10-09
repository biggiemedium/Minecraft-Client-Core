package dev.px.core.test.suite;

import dev.px.core.event.Event;
import dev.px.core.event.Stage;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.flow.Control;
import dev.px.core.flow.Flow;
import dev.px.core.flow.FlowContext;
import dev.px.core.flow.FlowHandle;
import dev.px.core.flow.Key;
import dev.px.core.flow.Span;
import dev.px.core.flow.Status;
import dev.px.core.flow.Step;
import dev.px.core.flow.StopReason;
import dev.px.core.flow.view.FlowView;
import dev.px.core.flow.view.StepState;
import dev.px.core.flow.view.StepView;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.memory.Fact;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;
import dev.px.testkit.Sandbox;
import dev.px.testkit.SimPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The flow engine: steps fitted together, interrupted, rewound and paused, run
 * in the test kit's sandbox with no game.
 *
 * <p>Most checks read a log the test's steps write as they are started, ticked
 * and stopped, because the order of those calls is the engine's whole contract:
 * which step runs, when it is stopped and why, and whether it begins again or
 * carries on.
 */
public final class FlowTests {

    private static final Key<Integer> COUNT = Key.of("count");
    private static final Key<Ping> HEARD = Key.of("heard");
    private static final Fact<String> NOTE = Fact.of("note");

    private FlowTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Flows");

        sequences();
        loops();
        fallbacks();
        sideBySide();
        shaping();
        events();
        ensuring();
        flicker();
        stuck();
        reflexes();
        world();
        controls();
        failures();
        handles();
        view();
        wiredIntoCore(client);
    }

    // ------------------------------------------------------------- sequence

    private static void sequences() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        FlowHandle flow = sandbox.flows().start("seq",
                Flow.sequence(new Probe("a", log, 2), new Probe("b", log, 1)), 50);
        sandbox.tick();
        Checks.checkEquals("a sequence starts its first step and ticks it", Arrays.asList("a start"), log);
        sandbox.tick();
        Checks.checkEquals("when it finishes, the next starts in the same tick",
                Arrays.asList("a start", "a stop FINISHED", "b start", "b stop FINISHED"), log);
        Checks.checkEquals("and the flow is done when the last is", FlowHandle.State.DONE, flow.getState());

        List<Step> instant = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            instant.add(Flow.action("instant " + i, c -> { }));
        }
        Sandbox quick = Sandbox.create();
        FlowHandle hundred = quick.flows().start("hundred", Flow.sequence(instant.toArray(new Step[0])), 50);
        Checks.checkEquals("a hundred instant steps take two ticks, at "
                        + quick.flows().getChangesPerTick() + " changes a tick",
                2, quick.runUntilDone(hundred, 10));

        List<String> failing = new ArrayList<>();
        Sandbox runner = Sandbox.create();
        FlowHandle broken = runner.flows().start("broken", Flow.sequence(
                new Probe("a", failing, 1), new Probe("b", failing, 1).failing(), new Probe("c", failing, 1)), 50);
        runner.runUntilDone(broken, 5);
        Checks.check("a step failing fails the sequence (" + broken.getReason() + ")",
                broken.getState() == FlowHandle.State.FAILED && "b: b gave up".equals(broken.getReason()));
        Checks.check("and nothing after it starts", !failing.contains("c start"));
    }

    // ----------------------------------------------------------------- loop

    private static void loops() {
        Sandbox sandbox = Sandbox.create();
        Step body = Flow.action("count", c -> c.put(COUNT, c.getOr(COUNT, 0) + 1));
        FlowHandle flow = sandbox.flows().start("loop",
                Flow.loop(body).until(c -> c.getOr(COUNT, 0) >= 5), 50);
        int ticks = sandbox.runUntilDone(flow, 20);
        Checks.checkEquals("a loop runs its body once a tick, even one that finishes at once", 5,
                (int) flow.get(COUNT));
        Checks.check("and until ends it successfully as soon as its condition holds (" + ticks + " ticks)",
                ticks == 6 && flow.getState() == FlowHandle.State.DONE);

        Sandbox again = Sandbox.create();
        List<String> log = new ArrayList<>();
        FlowHandle failing = again.flows().start("loop", Flow.loop(new Probe("x", log, 2).failing()), 50);
        again.runUntilDone(failing, 10);
        Checks.check("a loop whose body fails, fails", failing.getState() == FlowHandle.State.FAILED);
    }

    // ------------------------------------------------------------- fallbacks

    private static void fallbacks() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        FlowHandle flow = sandbox.flows().start("first", Flow.firstOf(
                new Probe("elytra", log, 1).when(c -> false),
                new Probe("nether", log, 2).failing(),
                new Probe("walk", log, 1)), 50);
        sandbox.runUntilDone(flow, 10);
        Checks.check("firstOf tries its options in the order written and takes the first that works",
                flow.getState() == FlowHandle.State.DONE && log.contains("walk stop FINISHED"));
        Checks.check("an option whose when does not hold is never started", !log.contains("elytra start"));
        Checks.check("one that fails gives way to the next",
                log.indexOf("nether stop FAILED") < log.indexOf("walk start"));

        Sandbox none = Sandbox.create();
        FlowHandle all = none.flows().start("none", Flow.firstOf(
                new Probe("a", log, 1).failing(), new Probe("b", log, 1).failing()), 50);
        none.runUntilDone(all, 10);
        Checks.check("when every option fails, it says what each did: " + all.getReason(),
                all.getState() == FlowHandle.State.FAILED
                        && all.getReason().contains("a: a gave up") && all.getReason().contains("b: b gave up"));
    }

    // -------------------------------------------------------- side by side

    private static void sideBySide() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        FlowHandle both = sandbox.flows().start("both",
                Flow.parallel(new Probe("a", log, 2), new Probe("b", log, 3)), 50);
        Checks.checkEquals("parallel finishes when every branch has", 3, sandbox.runUntilDone(both, 10));

        Sandbox failing = Sandbox.create();
        List<String> flog = new ArrayList<>();
        FlowHandle broken = failing.flows().start("broken",
                Flow.parallel(new Probe("a", flog, 5), new Probe("b", flog, 1).failing()), 50);
        failing.runUntilDone(broken, 10);
        Checks.check("and fails when any does, cancelling the rest",
                broken.getState() == FlowHandle.State.FAILED && flog.contains("a stop CANCELLED"));

        Sandbox racing = Sandbox.create();
        List<String> rlog = new ArrayList<>();
        FlowHandle race = racing.flows().start("race",
                Flow.race(new Probe("slow", rlog, 5), new Probe("fast", rlog, 2)), 50);
        Checks.checkEquals("a race finishes with its first branch", 2, racing.runUntilDone(race, 10));
        Checks.check("and cancels the others", rlog.contains("slow stop CANCELLED"));

        Sandbox lost = Sandbox.create();
        FlowHandle nobody = lost.flows().start("nobody",
                Flow.race(new Probe("a", rlog, 1).failing(), new Probe("b", rlog, 2).failing()), 50);
        lost.runUntilDone(nobody, 10);
        Checks.check("a race every branch loses fails", nobody.getState() == FlowHandle.State.FAILED);
    }

    // -------------------------------------------------------------- shaping

    private static void shaping() {
        final int[] starts = {0};
        Step flaky = Flow.step("flaky").start(c -> starts[0]++).tick(c -> starts[0] < 3 ? c.fail("not yet") : Status.DONE);
        Sandbox sandbox = Sandbox.create();
        FlowHandle retried = sandbox.flows().start("retry", flaky.retry(2), 50);
        sandbox.runUntilDone(retried, 10);
        Checks.check("retry starts a failed step again (" + starts[0] + " starts)",
                retried.getState() == FlowHandle.State.DONE && starts[0] == 3);

        starts[0] = 0;
        Step flaky2 = Flow.step("flaky").start(c -> starts[0]++).tick(c -> starts[0] < 3 ? c.fail("not yet") : Status.DONE);
        Sandbox fewer = Sandbox.create();
        FlowHandle gaveUp = fewer.flows().start("retry", flaky2.retry(1), 50);
        fewer.runUntilDone(gaveUp, 10);
        Checks.check("but only so many times: " + gaveUp.getReason(),
                gaveUp.getState() == FlowHandle.State.FAILED && gaveUp.getReason().contains("after 2 tries"));

        List<String> log = new ArrayList<>();
        Sandbox orElse = Sandbox.create();
        FlowHandle fallback = orElse.flows().start("orElse",
                new Probe("plan", log, 1).failing().orElse(new Probe("backup", log, 1)), 50);
        orElse.runUntilDone(fallback, 10);
        Checks.check("orElse runs its fallback when the step fails",
                fallback.getState() == FlowHandle.State.DONE && log.contains("backup stop FINISHED"));

        Sandbox timing = Sandbox.create();
        List<String> tlog = new ArrayList<>();
        FlowHandle slow = timing.flows().start("slow", new Probe("slow", tlog, 100).timeout(Span.ticks(3)), 50);
        int ticks = timing.runUntilDone(slow, 20);
        Checks.check("timeout fails a step that takes too long, in ticks (" + ticks + " ticks: " + slow.getReason() + ")",
                ticks == 4 && slow.getReason().contains("timed out after 3 ticks") && tlog.contains("slow stop CANCELLED"));

        Sandbox clock = Sandbox.create();
        FlowHandle wall = clock.flows().start("wall", new Probe("slow", tlog, 100).timeout(Span.seconds(0.2)), 50);
        int clockTicks = clock.runUntilDone(wall, 20);
        Checks.check("or in seconds, by the clock and not by counting ticks (" + clockTicks + " ticks at "
                + Sandbox.DEFAULT_TICK_MILLIS + "ms)", clockTicks == 5);
        Sandbox fastClock = Sandbox.create();
        fastClock.setTickMillis(100L);
        FlowHandle fastWall = fastClock.flows().start("wall", new Probe("slow", tlog, 100).timeout(Span.seconds(0.2)), 50);
        Checks.checkEquals("so a slower clock, or a lagging server, takes fewer ticks to get there", 3,
                fastClock.runUntilDone(fastWall, 20));

        Sandbox waiting = Sandbox.create();
        FlowHandle wait = waiting.flows().start("wait", Flow.waitFor(Span.ticks(4)), 50);
        Checks.checkEquals("waitFor waits as long as it is told, counted from the tick it began", 5,
                waiting.runUntilDone(wait, 20));
    }

    // --------------------------------------------------------------- events

    private static void events() {
        Sandbox sandbox = Sandbox.create();
        final int[] asked = {0};
        sandbox.post(new Ping("go"));
        FlowHandle flow = sandbox.flows().start("await",
                Flow.awaitEvent(Ping.class, ping -> {
                    asked[0]++;
                    return ping.text.equals("go");
                }).into(HEARD), 50);
        sandbox.tick();
        Checks.check("awaiting an event ignores one posted before it began", flow.getState() == FlowHandle.State.RUNNING);
        sandbox.post(new Ping("nope"));
        sandbox.tick();
        Checks.check("and one its filter refuses", flow.getState() == FlowHandle.State.RUNNING);
        sandbox.post(new Ping("go"));
        sandbox.tick();
        Checks.check("and finishes on one it accepts, keeping it in its key",
                flow.getState() == FlowHandle.State.DONE && flow.get(HEARD) != null && flow.get(HEARD).text.equals("go"));
        int before = asked[0];
        sandbox.post(new Ping("go"));
        Checks.checkEquals("having stopped listening when it stopped", before, asked[0]);

        Sandbox cancelled = Sandbox.create();
        final int[] heard = {0};
        FlowHandle waiting = cancelled.flows().start("waiting", Flow.awaitEvent(Ping.class, ping -> {
            heard[0]++;
            return false;
        }), 50);
        cancelled.tick();
        cancelled.post(new Ping("one"));
        waiting.cancel();
        cancelled.post(new Ping("two"));
        Checks.checkEquals("a cancelled wait stops listening too", 1, heard[0]);
    }

    // ------------------------------------------------------------- ensures

    private static void ensuring() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        final boolean[] world = {false, false};
        final boolean[] danger = {false};
        Step a = Flow.step("a").tick(c -> {
            log.add("a");
            world[0] = true;
            return Status.DONE;
        }).ensures(c -> world[0]);
        Step b = Flow.step("b").tick(c -> {
            log.add("b");
            world[1] = true;
            return Status.DONE;
        }).ensures(c -> world[1]);
        Probe c = new Probe("c", log, 30);
        Step handler = Flow.step("h").tick(cx -> {
            world[0] = false;
            danger[0] = false;
            log.add("h");
            return Status.DONE;
        });
        FlowHandle flow = sandbox.flows().start("ensures",
                Flow.sequence(a, b, c).interrupt(cx -> danger[0], handler), 50);
        sandbox.run(3);
        danger[0] = true;
        sandbox.run(2);
        Checks.checkEquals("an interrupt pauses the running step, and on resuming the flow rewinds to the earliest"
                        + " step whose work no longer holds, running from there",
                Arrays.asList("a", "b", "c start", "c stop PAUSED", "h", "a", "b", "c start"), log);

        log.clear();
        final boolean[] calm = {false};
        Sandbox second = Sandbox.create();
        world[0] = false;
        world[1] = false;
        Probe c2 = new Probe("c", log, 30);
        FlowHandle kept = second.flows().start("kept", Flow.sequence(
                Flow.step("a").tick(cx -> {
                    log.add("a");
                    world[0] = true;
                    return Status.DONE;
                }).ensures(cx -> world[0]), c2)
                .interrupt(cx -> calm[0], Flow.action("nothing broken", cx -> calm[0] = false)), 50);
        second.run(3);
        calm[0] = true;
        second.run(2);
        Checks.checkEquals("when nothing was undone, the paused step carries on where it was",
                Arrays.asList("a", "c start", "c stop PAUSED", "c start (resuming)"), log);
        Checks.check("and still has the ticks it had", c2.ticked >= 3);

        Sandbox lying = Sandbox.create();
        FlowHandle lie = lying.flows().start("lie", Flow.action("claims", cx -> { }).ensures(cx -> false), 50);
        lying.runUntilDone(lie, 5);
        Checks.check("a step that finishes without what it ensures holding fails: " + lie.getReason(),
                lie.getState() == FlowHandle.State.FAILED && lie.getReason().contains("does not hold"));
    }

    private static void flicker() {
        Sandbox sandbox = Sandbox.create();
        sandbox.flows().setMaxRewinds(2);
        final boolean[] held = {false};
        final boolean[] danger = {false};
        FlowHandle flow = sandbox.flows().start("flicker", Flow.sequence(
                Flow.action("a", c -> held[0] = true).ensures(c -> held[0]),
                new Probe("work", new ArrayList<String>(), 1000))
                .interrupt(c -> danger[0], Flow.action("knock", c -> {
                    held[0] = false;
                    danger[0] = false;
                })), 50);
        for (int i = 0; i < 4 && !flow.isDone(); i++) {
            sandbox.run(2);
            danger[0] = true;
            sandbox.run(2);
        }
        Checks.check("a step whose work keeps being lost fails once rewound past the limit: " + flow.getReason(),
                flow.getState() == FlowHandle.State.FAILED && flow.getReason().contains("rewound 3 times"));
    }

    // ---------------------------------------------------------------- stuck

    private static void stuck() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        Probe digging = new Probe("dig", log, 1000);
        digging.progress = 5d;
        FlowHandle flow = sandbox.flows().start("stuck",
                digging.stuckAfter(Span.ticks(10), new Probe("recover", log, 3)), 50);
        sandbox.run(12);
        Checks.check("a step whose progress stops changing is paused for recovery",
                log.contains("dig stop PAUSED") && log.contains("recover start"));
        sandbox.run(4);
        Checks.check("and resumed once recovery is done",
                log.contains("recover stop FINISHED") && log.contains("dig start (resuming)"));
        Checks.check("still running", flow.getState() == FlowHandle.State.RUNNING);

        Sandbox moving = Sandbox.create();
        List<String> mlog = new ArrayList<>();
        Probe walking = new Probe("walk", mlog, 1000);
        walking.progressCounts = true;
        moving.flows().start("moving", walking.stuckAfter(Span.ticks(5), new Probe("recover", mlog, 1)), 50);
        moving.run(40);
        Checks.check("one whose progress keeps changing is never called stuck", !mlog.contains("recover start"));

        Sandbox silent = Sandbox.create();
        List<String> slog = new ArrayList<>();
        silent.flows().start("silent", new Probe("quiet", slog, 1000).stuckAfter(Span.ticks(5),
                new Probe("recover", slog, 1)), 50);
        silent.run(40);
        Checks.check("nor one that reports no progress at all: timeout is for those", !slog.contains("recover start"));
    }

    // -------------------------------------------------------------- reflexes

    private static void reflexes() {
        Sandbox sandbox = Sandbox.create();
        sandbox.world().floor(64, -20, 20);
        sandbox.player(Vec3.of(0.5, 64, 0.5));
        final boolean[] fire = {false};
        List<String> log = new ArrayList<>();
        Probe walking = new Probe("walk", log, 1000).using(Control.MOVEMENT);
        Probe looking = new Probe("look", log, 1000).using(Control.ROTATION);
        Probe urgent = new Probe("urgent", log, 1000).using(Control.MOVEMENT);
        FlowHandle walker = sandbox.flows().start("walker", walking, 50);
        FlowHandle looker = sandbox.flows().start("looker", looking, 50);
        FlowHandle high = sandbox.flows().start("high", urgent, 95);
        FlowHandle reflex = sandbox.flows().reflex("extinguish", c -> fire[0], 90,
                new Probe("extinguish", log, 3).using(Control.MOVEMENT));
        sandbox.run(2);
        Checks.check("a reflex waits until its condition holds", reflex.getState() == FlowHandle.State.IDLE);
        fire[0] = true;
        sandbox.tick();
        fire[0] = false;
        Checks.check("then pauses the flows below it that use a control it uses: " + walker.getReason(),
                walker.getState() == FlowHandle.State.PAUSED && walker.getReason().contains("reflex 'extinguish'"));
        Checks.check("leaving those that use other controls running", looker.getState() == FlowHandle.State.RUNNING);
        Checks.check("and those above it", high.getState() == FlowHandle.State.RUNNING);
        int lookTicks = looking.ticked;
        sandbox.run(3);
        Checks.check("which keep being ticked meanwhile", looking.ticked > lookTicks);
        Checks.check("once it is done, the paused flows resume", walker.getState() == FlowHandle.State.RUNNING
                && log.contains("walk start (resuming)") && reflex.getState() == FlowHandle.State.IDLE);
    }

    // ---------------------------------------------------------------- world

    private static void world() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        Probe working = new Probe("work", log, 1000);
        FlowHandle flow = sandbox.flows().start("work", working, 50);
        sandbox.run(3);
        sandbox.leaveWorld();
        Checks.check("leaving the world pauses every flow: " + flow.getReason(),
                flow.getState() == FlowHandle.State.PAUSED && flow.getReason().contains("left the world"));
        int ticks = working.ticked;
        sandbox.run(5);
        Checks.checkEquals("and nothing runs while out of it", ticks, working.ticked);
        sandbox.joinWorld();
        sandbox.tick();
        Checks.check("joining one resumes them", flow.getState() == FlowHandle.State.RUNNING
                && log.contains("work start (resuming)") && working.ticked == ticks + 1);
        flow.pause();
        sandbox.leaveWorld();
        sandbox.joinWorld();
        Checks.check("but not one paused by hand as well", flow.getState() == FlowHandle.State.PAUSED);
    }

    // ------------------------------------------------------------- controls

    private static void controls() {
        Sandbox sandbox = Sandbox.create();
        sandbox.world().floor(64, -30, 30);
        SimPlayer me = sandbox.player(Vec3.of(0.5, 64, 0.5));
        Step north = Flow.step("north").uses(Control.MOVEMENT, Control.ROTATION).tick(c -> {
            c.look(Vec2.rotation(0f, 0f));
            c.move(MovementInput.forward(0f));
            return Status.RUNNING;
        });
        Step south = Flow.step("south").uses(Control.MOVEMENT, Control.ROTATION).tick(c -> {
            c.look(Vec2.rotation(180f, 0f));
            c.move(MovementInput.forward(180f));
            return Status.RUNNING;
        }).until(c -> c.tick() > 15);
        FlowHandle low = sandbox.flows().start("north", north, 50);
        sandbox.run(5);
        double afterNorth = me.getPosition().getZ();
        Checks.check("a flow's steps move the player through the controls (z " + String.format("%.2f", afterNorth) + ")",
                afterNorth > 0.6);
        Checks.checkEquals("the flow holds the keys, by name", "north", sandbox.controls().getMovementHolderName());
        FlowHandle high = sandbox.flows().start("south", south, 60);
        sandbox.run(8);
        Checks.check("a higher-priority flow takes them (z " + String.format("%.2f", me.getPosition().getZ()) + ")",
                me.getPosition().getZ() < afterNorth);
        sandbox.runUntilDone(high, 20);
        sandbox.run(4);
        double resumed = me.getPosition().getZ();
        sandbox.run(4);
        Checks.check("and gives them back when it finishes", me.getPosition().getZ() > resumed);
        low.pause();
        Checks.check("a paused flow lets go at once", sandbox.controls().getMovementHolder() == null
                && sandbox.rotations().getHolder() == null);

        Sandbox careless = Sandbox.create();
        careless.player(Vec3.ZERO);
        careless.flows().start("careless", Flow.step("sloppy").tick(c -> {
            c.look(Vec2.rotation(10f, 0f));
            return Status.RUNNING;
        }), 50);
        careless.run(10);
        int warnings = 0;
        for (String warning : careless.logger().getWarnings()) {
            if (warning.contains("without declaring it")) {
                warnings++;
            }
        }
        Checks.checkEquals("a claim on an undeclared control works, and is warned about once", 1, warnings);
        Checks.checkEquals("and still turns the head", 10f, careless.getPlayer().getRotation().getYaw());
    }

    // ------------------------------------------------------------- failures

    private static void failures() {
        Sandbox sandbox = Sandbox.create();
        FlowHandle flow = sandbox.flows().start("thrower", Flow.step("boom").tick(c -> {
            throw new IllegalStateException("boom");
        }), 50);
        sandbox.runUntilDone(flow, 5);
        Checks.check("a step that throws fails, the exception its reason: " + flow.getReason(),
                flow.getState() == FlowHandle.State.FAILED && flow.getReason().contains("IllegalStateException: boom"));
        Checks.check("and is logged", sandbox.logger().hasWarning("threw in tick()"));
        Checks.check("the service carries on ticking other flows", noThrow(sandbox));
    }

    private static boolean noThrow(Sandbox sandbox) {
        try {
            FlowHandle next = sandbox.flows().start("next", Flow.waitFor(Span.ticks(1)), 50);
            return sandbox.runUntilDone(next, 5) > 0;
        } catch (RuntimeException thrown) {
            return false;
        }
    }

    // --------------------------------------------------------------- handles

    private static void handles() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        Probe probe = new Probe("p", log, 1000);
        FlowHandle flow = sandbox.flows().start("p", probe, 50);
        sandbox.run(2);
        flow.pause();
        Checks.check("pausing by hand stops the step as paused",
                flow.getState() == FlowHandle.State.PAUSED && log.contains("p stop PAUSED"));
        flow.resume();
        Checks.check("resuming starts it again, carrying on", log.contains("p start (resuming)"));
        Checks.checkThrows("a step already running in a flow cannot start another", IllegalArgumentException.class,
                () -> sandbox.flows().start("again", probe, 50));
        flow.cancel();
        Checks.check("cancelling stops it for good", flow.getState() == FlowHandle.State.CANCELLED
                && log.contains("p stop CANCELLED") && !sandbox.flows().getFlows().contains(flow));
        Checks.check("an ended flow stays in the live view until cleared",
                containsView(sandbox, "p"));
        sandbox.flows().clearFinished();
        Checks.check("and then goes", !containsView(sandbox, "p"));
    }

    private static boolean containsView(Sandbox sandbox, String name) {
        for (FlowView view : sandbox.flows().view()) {
            if (view.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- view

    private static void view() {
        Sandbox sandbox = Sandbox.create();
        List<String> log = new ArrayList<>();
        FlowHandle flow = sandbox.flows().start("bot", Flow.sequence(
                Flow.action("note", c -> {
                    c.put(COUNT, 7);
                    c.remember(NOTE, "seen");
                }),
                new Probe("work", log, 1000).using(Control.MOVEMENT)), 50);
        sandbox.run(3);
        FlowView view = flow.view();
        Checks.checkEquals("the live view names the flow and its state", "RUNNING", view.getState());
        Checks.checkEquals("shows its keys", "7", view.getKeys().get("count"));
        StepView note = view.getRoot().find("note");
        Checks.check("each step's state and what it touched",
                note != null && note.getState() == StepState.DONE && note.getKeysWritten().contains("count")
                        && note.getFacts().contains("note"));
        StepView work = view.getRoot().find("work");
        Checks.check("its declared controls and how long it has run (" + work.getTicks() + " ticks)",
                work.getUses().contains("MOVEMENT") && work.getTicks() == 3 && work.getStartedTick() >= 0);
        Checks.checkEquals("and where the flow is now", "sequence > work", view.getRoot().describe());
        Checks.checkEquals("memory written through the context is shared", "seen", sandbox.memory().recall(NOTE));
    }

    // ----------------------------------------------------------------- core

    private static void wiredIntoCore(TestClient client) {
        Checks.check("Core wires in the flow service and memory",
                client.getCore().getFlowService() != null && client.getCore().getMemory() != null);
        final Object[] seen = {null};
        FlowHandle flow = client.getCore().getFlowService().start("core",
                Flow.action("reach", c -> seen[0] = c.service(SimulationService.class)), 50);
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.check("and ticks flows on the tick, handing steps Core's own services",
                flow.isDone() && seen[0] == client.getCore().getSimulationService());
        client.getCore().getFlowService().clearFinished();
    }

    // -------------------------------------------------------------- harness

    /** A step that writes to a log as it is started, ticked and stopped. */
    static final class Probe extends Step {

        private final List<String> log;
        private final int finishAfter;
        private boolean fails;
        int ticked;
        double progress = Double.NaN;
        boolean progressCounts;

        Probe(String name, List<String> log, int finishAfter) {
            super(name);
            this.log = log;
            this.finishAfter = finishAfter;
        }

        Probe failing() {
            fails = true;
            return this;
        }

        Probe using(Control... controls) {
            uses(controls);
            return this;
        }

        @Override
        protected void start(FlowContext c) {
            log.add(getName() + " start" + (c.isResuming() ? " (resuming)" : ""));
            if (!c.isResuming()) {
                ticked = 0;
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            ticked++;
            if (ticked >= finishAfter) {
                return fails ? c.fail(getName() + " gave up") : Status.DONE;
            }
            return Status.RUNNING;
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            log.add(getName() + " stop " + why);
        }

        @Override
        protected double progress(FlowContext c) {
            return progressCounts ? ticked : progress;
        }
    }

    /** An event of the test's own. */
    static final class Ping extends Event {

        final String text;

        Ping(String text) {
            this.text = text;
        }
    }
}
