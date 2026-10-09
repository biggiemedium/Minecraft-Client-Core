package dev.px.navigation.test;

import dev.px.core.control.Click;
import dev.px.core.control.ControlService;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.flow.Control;
import dev.px.core.flow.Flow;
import dev.px.core.flow.FlowHandle;
import dev.px.core.flow.FlowService;
import dev.px.core.flow.Key;
import dev.px.core.flow.Span;
import dev.px.core.flow.Status;
import dev.px.core.math.Vec3;
import dev.px.core.memory.Memory;
import dev.px.core.movement.rotation.RotationService;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.PathProvider;
import dev.px.core.navigation.Route;
import dev.px.core.test.harness.Checks;
import dev.px.navigation.Travel;
import dev.px.navigation.plan.LocalPlanner;
import dev.px.testkit.FakeProviders;
import dev.px.testkit.Sandbox;
import dev.px.testkit.SandboxLogger;
import dev.px.testkit.SimPlayer;

/**
 * Travel steps: a navigator run as a step of Core's flows, in the test kit's
 * sandbox.
 *
 * <p>The sandbox's player is moved by Core's own movement rules with whatever
 * keys the controls resolve, so a trip that arrives arrived by the keys the step's
 * navigator claimed for the flow. Everything a flow can do to a step &mdash;
 * pause it for a reflex, rewind to it, fall back from it, decide it is stuck
 * &mdash; is done on purpose and what the trip did about it checked.
 */
public final class TravelTests {

    private static final Key<Vec3> SPOT = Key.of("spot");

    private TravelTests() {
    }

    public static void run() {
        Checks.section("Travel");

        arrives();
        goalsFromKeys();
        failing();
        stuck();
        pausing();
        rewinding();
        driving();
        priority();
        headless();
        noSimulation();
        builder();
    }

    private static void arrives() {
        Sandbox sandbox = field();
        SimPlayer me = sandbox.getPlayer();
        Counting planner = new Counting(planner(sandbox));
        Travel travel = Travel.builder().provider(planner).player(me::getState).build();
        Travel.Trip trip = travel.to(Goal.block(10, 64, 6));
        FlowHandle flow = sandbox.flows().start("trip", trip, 50);

        sandbox.run(3);
        double early = trip.getProgress().getRemaining();
        Checks.check("on the way, it claims the keys and the head for its navigator",
                sandbox.controls().getMovementHolder() == trip.getNavigator()
                        && sandbox.rotations().getHolder() == trip.getNavigator());
        Checks.check("and reports the blocks left as its progress (" + String.format("%.2f", early) + ")",
                early > 0d && early < 12d);
        int ticks = sandbox.runUntilDone(flow, 200);
        Checks.check("a travel step finishes when the player arrives (" + flow.getState() + " after " + ticks
                + " ticks)", flow.getState() == FlowHandle.State.DONE && Goal.block(10, 64, 6).isMet(me.getPosition()));
        Checks.checkEquals("having planned once, since the sandbox moves by the rules it planned with", 1, planner.plans);
        Checks.check("then lets go of the keys and the head",
                sandbox.controls().getMovementHolder() == null && sandbox.rotations().getHolder() == null);
        Checks.checkEquals("named for its goal in the live view", "travel to Goal.block(10, 64, 6)", trip.getName());
        Checks.check("declaring the movement keys and the head",
                trip.getUses().contains(Control.MOVEMENT) && trip.getUses().contains(Control.ROTATION));

        Sandbox there = field();
        Travel already = Travel.builder().provider(planner(there)).player(there.getPlayer()::getState).build();
        FlowHandle none = there.flows().start("none", already.to(Goal.near(Vec3.of(0.5, 64, 0.5), 1)), 50);
        Checks.checkEquals("a goal already met finishes in the tick it starts", 1, there.runUntilDone(none, 5));

        Sandbox twice = field();
        Travel both = Travel.builder().provider(planner(twice)).player(twice.getPlayer()::getState).build();
        FlowHandle there2 = twice.flows().start("there and back", Flow.sequence(
                both.to(Goal.block(8, 64, 0)), both.to(Goal.block(0, 64, 4))), 50);
        twice.runUntilDone(there2, 300);
        Checks.check("one Travel makes a step for each place it is used (" + there2.getState() + ")",
                there2.getState() == FlowHandle.State.DONE && Goal.block(0, 64, 4).isMet(twice.getPlayer().getPosition()));
    }

    private static void goalsFromKeys() {
        Sandbox sandbox = field();
        SimPlayer me = sandbox.getPlayer();
        Travel travel = Travel.builder().provider(planner(sandbox)).player(me::getState).build();
        FlowHandle flow = sandbox.flows().start("to the spot", Flow.sequence(
                Flow.action("pick a spot", c -> c.put(SPOT, Vec3.of(8.5, 64, 4.5))),
                travel.to(c -> Goal.near(c.get(SPOT), 1))), 50);
        sandbox.runUntilDone(flow, 200);
        Checks.check("a goal made from the flow's keys is asked for as the step starts (" + flow.getState() + ")",
                flow.getState() == FlowHandle.State.DONE && me.getPosition().distanceTo(Vec3.of(8.5, 64, 4.5)) <= 1d);

        Sandbox follow = field();
        SimPlayer follower = follow.getPlayer();
        final Vec3[] point = {Vec3.of(6.5, 64, 0.5)};
        final boolean[] enough = {false};
        Travel keeping = Travel.builder().provider(planner(follow)).player(follower::getState).build();
        FlowHandle kept = follow.flows().start("keep near", Flow.loop(keeping.to(Goal.near(() -> point[0], 1)))
                .until(c -> enough[0]), 50);
        follow.runUntil(() -> follower.getPosition().distanceTo(point[0]) <= 1d, 200);
        point[0] = Vec3.of(6.5, 64, 9.5);
        int caught = follow.runUntil(() -> follower.getPosition().distanceTo(point[0]) <= 1d, 200);
        enough[0] = true;
        follow.runUntilDone(kept, 5);
        Checks.check("looped, a trip sets off again when its goal moves away after arriving (" + caught + " ticks), "
                + "until told otherwise", caught > 0 && kept.getState() == FlowHandle.State.DONE);

        Sandbox empty = field();
        Travel nowhere = Travel.builder().provider(planner(empty)).player(empty.getPlayer()::getState).build();
        FlowHandle lost = empty.flows().start("lost", nowhere.to(c -> null), 50);
        empty.runUntilDone(lost, 5);
        Checks.check("a goal that is null fails the step, saying so (" + lost.getReason() + ")",
                lost.getState() == FlowHandle.State.FAILED && lost.getReason().contains("no goal"));
    }

    private static void failing() {
        Sandbox sandbox = field();
        Travel travel = Travel.builder().provider(FakeProviders.unreachable()).player(sandbox.getPlayer()::getState).build();
        FlowHandle flow = sandbox.flows().start("doomed", travel.to(Goal.block(10, 64, 0)), 50);
        sandbox.runUntilDone(flow, 5);
        Checks.check("no route fails the step with the navigator's reason (" + flow.getReason() + ")",
                flow.getState() == FlowHandle.State.FAILED && flow.getReason().contains("found no way to Goal.block(10, 64, 0)"));
        Checks.check("claiming nothing", sandbox.controls().getMovementHolder() == null
                && sandbox.rotations().getHolder() == null);

        Sandbox fallback = field();
        SimPlayer me = fallback.getPlayer();
        Travel never = Travel.builder().provider(FakeProviders.unreachable()).player(me::getState).build();
        Travel walking = Travel.builder().provider(planner(fallback)).player(me::getState).build();
        FlowHandle either = fallback.flows().start("either", Flow.firstOf(
                never.to(Goal.block(10, 64, 0)), walking.to(Goal.block(10, 64, 0))), 50);
        fallback.runUntilDone(either, 200);
        Checks.check("so a firstOf moves on to the next way there (" + either.getState() + ")",
                either.getState() == FlowHandle.State.DONE && Goal.block(10, 64, 0).isMet(me.getPosition()));

        Sandbox blind = field();
        Travel unknown = Travel.builder().provider(planner(blind)).player(() -> null).build();
        FlowHandle nobody = blind.flows().start("nobody", unknown.to(Goal.block(10, 64, 0)), 50);
        blind.runUntilDone(nobody, 5);
        Checks.check("a player supplier giving null fails the step, saying so (" + nobody.getReason() + ")",
                nobody.getState() == FlowHandle.State.FAILED && nobody.getReason().contains("supplier gave null"));
    }

    private static void stuck() {
        // Steered straight at the goal with no local planner, into a wall it cannot climb.
        Sandbox sandbox = walled();
        Travel travel = Travel.builder().provider(FakeProviders.straightLine()).player(sandbox.getPlayer()::getState)
                .navigator(b -> b.stuckTicks(20)).build();
        Travel.Trip trip = travel.to(Goal.block(10, 64, 0));
        FlowHandle flow = sandbox.flows().start("into the wall", trip, 50);
        sandbox.run(60);
        Checks.check("stuck, the step keeps running by default (" + trip.getProgress() + ")",
                flow.getState() == FlowHandle.State.RUNNING && trip.getProgress().isStuck());

        Sandbox recovering = walled();
        Travel patient = Travel.builder().provider(FakeProviders.straightLine())
                .player(recovering.getPlayer()::getState).build();
        FlowHandle helped = recovering.flows().start("helped", patient.to(Goal.block(10, 64, 0))
                .stuckAfter(Span.ticks(15), Flow.action("knock it down", c -> recovering.world().clear(5, 64, -3, 5, 66, 3))), 50);
        recovering.runUntilDone(helped, 300);
        Checks.check("its progress is the blocks left, so stuckAfter recovers and the trip resumes and arrives ("
                + helped.getState() + ")", helped.getState() == FlowHandle.State.DONE
                && Goal.block(10, 64, 0).isMet(recovering.getPlayer().getPosition()));

        Sandbox giving = walled();
        Travel quitter = Travel.builder().provider(FakeProviders.straightLine()).player(giving.getPlayer()::getState)
                .navigator(b -> b.stuckTicks(20)).failWhenStuck(true).build();
        FlowHandle gave = giving.flows().start("gives up", quitter.to(Goal.block(10, 64, 0)), 50);
        int ticks = giving.runUntilDone(gave, 100);
        Checks.check("with failWhenStuck, it fails instead, with why (" + gave.getReason() + " after " + ticks + " ticks)",
                gave.getState() == FlowHandle.State.FAILED && gave.getReason().contains("stuck: no closer"));
    }

    private static void pausing() {
        Sandbox sandbox = field();
        SimPlayer me = sandbox.getPlayer();
        Counting planner = new Counting(planner(sandbox));
        Travel travel = Travel.builder().provider(planner).player(me::getState).build();
        Travel.Trip trip = travel.to(Goal.block(14, 64, 0));
        FlowHandle flow = sandbox.flows().start("trip", trip, 50);
        final boolean[] danger = {false};
        final boolean[] swing = {false};
        sandbox.flows().reflex("swing", c -> swing[0], 90, Flow.step("swing").uses(Control.ATTACK)
                .tick(c -> {
                    c.click(Click.ATTACK);
                    swing[0] = false;
                    return Status.DONE;
                }));
        FlowHandle dodge = sandbox.flows().reflex("dodge", c -> danger[0], 90, Flow.step("dodge").uses(Control.MOVEMENT)
                .tick(c -> {
                    c.move(MovementInput.none(0f));
                    return c.tick() % 5 == 0 ? Status.DONE : Status.RUNNING;
                }));
        sandbox.run(4);
        swing[0] = true;
        sandbox.tick();
        Checks.check("a reflex using other controls leaves the trip running", flow.getState() == FlowHandle.State.RUNNING);

        danger[0] = true;
        sandbox.tick();
        danger[0] = false;
        Checks.check("one using the movement keys pauses it (" + flow.getReason() + ")",
                flow.getState() == FlowHandle.State.PAUSED);
        Checks.check("and the trip lets go of the keys and the head at once",
                sandbox.controls().getMovementHolder() == dodge
                        && sandbox.rotations().getHolder() != trip.getNavigator());
        int plansBefore = planner.plans;
        sandbox.runUntilDone(flow, 300);
        Checks.check("resumed, it plans afresh from where the player is and arrives (" + planner.plans + " plans)",
                flow.getState() == FlowHandle.State.DONE && planner.plans > plansBefore
                        && Goal.block(14, 64, 0).isMet(me.getPosition()));
    }

    private static void rewinding() {
        Sandbox sandbox = field();
        SimPlayer me = sandbox.getPlayer();
        Travel travel = Travel.builder().provider(planner(sandbox)).player(me::getState).build();
        Goal post = Goal.block(8, 64, 0);
        final boolean[] thrown = {false};
        final boolean[] finished = {false};
        FlowHandle flow = sandbox.flows().start("guard", Flow.sequence(
                        travel.to(post).ensures(travel.at(post)),
                        Flow.waitUntil(c -> finished[0]))
                .interrupt(c -> thrown[0], Flow.action("thrown off", c -> {
                    me.teleport(Vec3.of(0.5, 64, 6.5));
                    thrown[0] = false;
                })), 50);
        sandbox.runUntil(() -> post.isMet(me.getPosition()), 200);
        sandbox.run(3);
        thrown[0] = true;
        sandbox.tick();
        Checks.check("thrown off its post, the player is away from it", !post.isMet(me.getPosition()));
        int back = sandbox.runUntil(() -> post.isMet(me.getPosition()), 200);
        Checks.check("ensures(travel.at(goal)) rewinds the flow to the trip, which walks back (" + back + " ticks)",
                back > 0 && flow.getState() == FlowHandle.State.RUNNING);
        finished[0] = true;
        sandbox.runUntilDone(flow, 5);
        Checks.checkEquals("and the flow carries on from there", FlowHandle.State.DONE, flow.getState());

        Sandbox away = field();
        Travel elsewhere = Travel.builder().provider(planner(away)).player(away.getPlayer()::getState).build();
        Checks.check("at(goal) holds only where the feet are in the goal",
                !elsewhere.at(post).test(null) && elsewhere.at(Goal.block(0, 64, 0)).test(null));
    }

    private static void driving() {
        Sandbox sandbox = field();
        SimPlayer me = sandbox.getPlayer();
        FakeProviders.Driver driver = FakeProviders.driver(me, 0.3);
        Travel travel = Travel.builder().provider(driver).player(me::getState).build();
        FlowHandle flow = sandbox.flows().start("driven", travel.to(Goal.block(12, 64, 0)), 50);
        sandbox.run(5);
        Checks.check("a provider that drives is followed", driver.getFollows() >= 5 && driver.getCancels() == 0);
        flow.pause();
        sandbox.tick();
        Checks.checkEquals("pausing the flow cancels it", 1, driver.getCancels());
        Vec3 paused = me.getPosition();
        sandbox.run(3);
        Checks.check("and it moves the player no more while paused", me.getPosition().equals(paused));
        flow.resume();
        sandbox.runUntilDone(flow, 200);
        Checks.check("resumed, it is followed again to the goal (" + flow.getState() + ")",
                flow.getState() == FlowHandle.State.DONE && Goal.block(12, 64, 0).isMet(me.getPosition()));
        Checks.checkEquals("and cancelled once the step is done", 2, driver.getCancels());
    }

    private static void priority() {
        Sandbox sandbox = field();
        SimPlayer me = sandbox.getPlayer();
        Travel travel = Travel.builder().provider(planner(sandbox)).player(me::getState).build();
        Travel.Trip trip = travel.to(Goal.block(10, 64, 0));
        FlowHandle flow = sandbox.flows().start("trip", trip, 50);
        FlowHandle still = sandbox.flows().start("stand still", Flow.step("stand").uses(Control.MOVEMENT)
                .tick(c -> {
                    c.move(MovementInput.none(0f));
                    return Status.RUNNING;
                }), 70);
        sandbox.run(5);
        Checks.check("a flow above it wins the keys", sandbox.controls().getMovementHolder() == still
                && me.getPosition().distanceTo(Vec3.of(0.5, 64, 0.5)) < 1.0E-6);
        flow.setPriority(80);
        sandbox.runUntilDone(flow, 200);
        Checks.check("its navigator claims at the flow's priority, read each tick: raised above, it goes ("
                + flow.getState() + ")", flow.getState() == FlowHandle.State.DONE
                && Goal.block(10, 64, 0).isMet(me.getPosition()));
    }

    private static void headless() {
        Sandbox sandbox = field();
        SimPlayer me = sandbox.getPlayer();
        Travel chase = Travel.builder().provider(planner(sandbox)).player(me::getState).turnHead(false).build();
        Travel.Trip trip = chase.to(Goal.block(10, 64, 4));
        final boolean[] navigatorHeld = {false};
        // Something else in the flow keeps the head facing along +z, as a fight on a target would.
        FlowHandle flow = sandbox.flows().start("chase", Flow.race(trip, Flow.step("look").uses(Control.ROTATION)
                .tick(c -> {
                    c.look(dev.px.core.math.Vec2.rotation(0f, 10f));
                    navigatorHeld[0] |= c.rotations().getHolder() == trip.getNavigator() && trip.getNavigator() != null;
                    return Status.RUNNING;
                })), 50);
        sandbox.runUntilDone(flow, 300);
        Checks.check("with turnHead(false), a trip arrives on its keys alone, the head looking elsewhere ("
                        + flow.getState() + ", " + trip.getNavigator().getStats() + ", at " + me.getPosition() + ", navigator held the head: " + navigatorHeld[0] + ")",
                flow.getState() == FlowHandle.State.DONE && reached(me, Goal.block(10, 64, 4))
                        && !navigatorHeld[0]);
        Checks.check("and declares only the keys",
                trip.getUses().contains(Control.MOVEMENT) && !trip.getUses().contains(Control.ROTATION));
    }

    private static void noSimulation() {
        SandboxLogger logger = new SandboxLogger();
        CoreEventBus bus = new CoreEventBus(logger);
        ControlService controls = new ControlService(logger, bus);
        RotationService rotations = new RotationService(logger, bus);
        FlowService flows = new FlowService(logger, bus, controls, rotations, new Memory(bus));
        Travel travel = Travel.builder().provider(FakeProviders.straightLine())
                .player(() -> MotionState.at(Vec3.of(0.5, 64, 0.5))).build();
        FlowHandle flow = flows.start("unsimulated", travel.to(Goal.block(10, 64, 0)), 50);
        flows.tick();
        flows.tick();
        Checks.check("a host with no simulation fails the step, naming it, rather than throwing (" + flow.getReason() + ")",
                flow.getState() == FlowHandle.State.FAILED && flow.getReason().contains("no SimulationService"));
    }

    private static void builder() {
        Checks.checkThrows("a Travel without its parts is refused", IllegalStateException.class,
                () -> Travel.builder().build());
        try {
            Travel.builder().build();
        } catch (IllegalStateException refused) {
            Checks.checkEquals("naming every missing part", "a Travel needs: provider, player", refused.getMessage());
        }
    }

    // ------------------------------------------------------------- the world

    /** A sandbox with a floor whose top is at y = 64 and a player standing on it at the origin. */
    private static Sandbox field() {
        Sandbox sandbox = Sandbox.create();
        sandbox.world().floor(64, -20, 30);
        sandbox.player(Vec3.of(0.5, 64, 0.5));
        return sandbox;
    }

    /** {@link #field()}, with a wall three blocks high across x = 5. */
    private static Sandbox walled() {
        Sandbox sandbox = field();
        sandbox.world().fill(5, 64, -3, 5, 66, 3);
        return sandbox;
    }

    /** @return whether the player's feet were in {@code goal} on any tick: momentum may carry them on after arriving */
    private static boolean reached(SimPlayer player, Goal goal) {
        for (MotionState state : player.getTrail()) {
            if (goal.isMet(state.getPosition())) {
                return true;
            }
        }
        return false;
    }

    private static LocalPlanner planner(Sandbox sandbox) {
        return LocalPlanner.builder().simulation(sandbox.simulation()).build();
    }

    /** A provider passing every plan on, counting them. */
    private static final class Counting implements PathProvider {

        private final PathProvider inner;
        int plans;

        Counting(PathProvider inner) {
            this.inner = inner;
        }

        @Override
        public Route plan(Goal goal, MotionState from) {
            plans++;
            return inner.plan(goal, from);
        }
    }
}
