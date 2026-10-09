package dev.px.testkit.test;

import dev.px.core.control.Click;
import dev.px.core.entity.Tracked;
import dev.px.core.flow.Control;
import dev.px.core.flow.Flow;
import dev.px.core.flow.FlowHandle;
import dev.px.core.flow.Span;
import dev.px.core.flow.Status;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.rotation.RotationPriority;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.Progress;
import dev.px.core.navigation.Route;
import dev.px.core.test.harness.Checks;
import dev.px.core.world.BlockShape;
import dev.px.testkit.FakeProviders;
import dev.px.testkit.Sandbox;
import dev.px.testkit.SimEntity;
import dev.px.testkit.SimPlayer;
import dev.px.testkit.SimWorld;

import java.util.Arrays;
import java.util.List;

/**
 * The sandbox itself: that its world, player and entities behave as the game
 * would show them to Core, so a test written against it is testing the flow and
 * not the kit.
 */
public final class SandboxTests {

    private SandboxTests() {
    }

    public static void run() {
        Checks.section("Sandbox");

        world();
        player();
        clicks();
        entities();
        clock();
        isolation();
        providers();
    }

    private static void world() {
        SimWorld world = Sandbox.create().world();
        world.floor(64, -2, 2).fill(5, 64, 0, 5, 65, 0).slab(3, 64, 0, 0.5).remove(0, 63, 0);
        Checks.check("a floor fills the layer under its top", world.isSolid(2, 63, -2) && !world.isSolid(2, 64, -2));
        Checks.check("fill fills between its corners", world.isSolid(5, 64, 0) && world.isSolid(5, 65, 0));
        Checks.check("and remove empties a cell", !world.isSolid(0, 63, 0));
        Checks.checkEquals("a block view sees the same shapes", BlockShape.FULL, world.shapeAt(5, 64, 0));
        Checks.check("a slab is half a block, to collisions as well",
                world.boxesIn(dev.px.core.math.Box.of(3, 64, 0, 4, 66, 1)).get(0).getMaxY() == 64.5);
        world.slipperiness(1, 63, 1, 0.98);
        Checks.check("slipperiness is per block, the rest keeping the profile's default",
                world.slipperinessAt(Vec3.of(1.5, 63, 1.5)) == 0.98 && world.slipperinessAt(Vec3.of(2.5, 63, 2.5)) < 0.98);
    }

    private static void player() {
        Sandbox sandbox = Sandbox.create();
        sandbox.world().floor(64, -20, 20);
        SimPlayer me = sandbox.player(Vec3.of(0.5, 64, 0.5));
        sandbox.run(5);
        Checks.check("with no keys claimed the player stands still", me.getPosition().equals(Vec3.of(0.5, 64, 0.5)));

        Object mover = new Object();
        for (int i = 0; i < 10; i++) {
            sandbox.controls().move(mover, MovementInput.forward(0f), RotationPriority.NORMAL, 20);
            sandbox.rotations().request(mover, Vec2.rotation(0f, 0f));
            sandbox.tick();
        }
        Checks.check("claimed keys move it by the real rules (z " + String.format("%.3f", me.getPosition().getZ()) + ")",
                me.getPosition().getZ() > 2 && Math.abs(me.getPosition().getX() - 0.5) < 1e-9);
        Checks.check("standing on the floor", me.getState().isOnGround() && me.getPosition().getY() == 64d);

        Sandbox turned = Sandbox.create();
        turned.world().floor(64, -20, 20);
        SimPlayer other = turned.player(Vec3.of(0.5, 64, 0.5));
        other.face(Vec2.rotation(90f, 0f));
        for (int i = 0; i < 10; i++) {
            turned.controls().move(mover, MovementInput.forward(0f), RotationPriority.NORMAL, 2);
            turned.tick();
        }
        Checks.check("keys meant for another yaw than it faces still go where they were meant (x "
                        + String.format("%.4f", other.getPosition().getX()) + ")",
                other.getPosition().getZ() > 2 && Math.abs(other.getPosition().getX() - 0.5) < 1e-6);

        Sandbox shoved = Sandbox.create();
        shoved.world().floor(64, -20, 20);
        SimPlayer pushed = shoved.player(Vec3.of(0.5, 64, 0.5));
        pushed.push(Vec3.of(0.5, 0.4, 0));
        shoved.tick();
        Checks.check("a push moves it as a hit would", pushed.getPosition().getX() > 0.9 && pushed.getPosition().getY() > 64);
        Checks.checkThrows("one player per sandbox", IllegalArgumentException.class, () -> shoved.player(Vec3.ZERO));
    }

    private static void clicks() {
        Sandbox sandbox = Sandbox.create();
        SimPlayer me = sandbox.player(Vec3.ZERO);
        FlowHandle eat = sandbox.flows().start("eat", Flow.step("eat").uses(Control.USE)
                .tick(c -> {
                    c.hold(Click.USE);
                    return c.tick() >= 3 ? Status.DONE : Status.RUNNING;
                }), 50);
        sandbox.runUntilDone(eat, 10);
        Checks.check("a held button is held while claimed", me.getClicks().contains("hold USE"));
        sandbox.tick();
        Checks.checkEquals("and let go after", Arrays.asList("hold USE", "release USE"), me.getClicks());
    }

    private static void entities() {
        Sandbox sandbox = Sandbox.create();
        sandbox.world().floor(64, -40, 40);
        sandbox.player(Vec3.of(0.5, 64, 0.5));
        SimEntity zombie = sandbox.entity("zombie", Vec3.of(0.5, 64, 10.5), tick -> MovementInput.forward(0f))
                .tag("hostile");
        SimEntity sheep = sandbox.entity("sheep", Vec3.of(5.5, 64, 0.5), tick -> MovementInput.none(0f));
        sandbox.run(29);
        Vec3 before = zombie.getPosition();
        sandbox.tick();
        Tracked<SimEntity> seen = sandbox.tracked(zombie);
        Checks.check("entities are tracked from positions alone, read as the tick opens (" + seen + ")",
                seen != null && seen.getPosition().equals(before));
        Checks.check("moving by their scripts", zombie.getPosition().getZ() > 15);
        Checks.checkEquals("and found by targeting, nearest first", sheep,
                sandbox.targets().best(sandbox.selector()).get());
        Prediction next = sandbox.prediction().predict(seen, 10);
        Checks.check("prediction reads them as it would the game's (" + next.isReliable() + ")",
                next.isReliable() && next.positionAt(10).getZ() > zombie.getPosition().getZ());
        Checks.checkEquals("tags are the test's own", "hostile", zombie.getTag());
        sheep.remove();
        sandbox.tick();
        Checks.checkEquals("a removed entity is no longer seen", 1, sandbox.targets().count(sandbox.selector()));
    }

    private static void clock() {
        Sandbox sandbox = Sandbox.create();
        FlowHandle wait = sandbox.flows().start("wait", Flow.waitFor(Span.seconds(1)), 50);
        int ticks = sandbox.runUntilDone(wait, 100);
        Checks.checkEquals("the clock moves on a fixed amount a tick, so seconds take the same ticks every run",
                21, ticks);
        Checks.checkEquals("counting ticks run", 21L, sandbox.getTick());
    }

    private static void isolation() {
        Sandbox first = Sandbox.create();
        Sandbox second = Sandbox.create();
        first.player(Vec3.ZERO);
        second.player(Vec3.ZERO);
        first.flows().start("one", Flow.waitFor(Span.ticks(100)), 50);
        first.leaveWorld();
        Checks.check("sandboxes share nothing: each has its own services and its own world events",
                second.flows().getFlows().isEmpty() && second.flows().isInWorld() && !first.flows().isInWorld());
        first.close();
        Checks.check("and closing one leaves the other running", runs(second));
    }

    private static boolean runs(Sandbox sandbox) {
        FlowHandle flow = sandbox.flows().start("x", Flow.waitFor(Span.ticks(1)), 50);
        return sandbox.runUntilDone(flow, 5) > 0;
    }

    private static void providers() {
        FakeProviders.Planner line = FakeProviders.straightLine();
        Route route = line.plan(Goal.block(4, 64, 4), null);
        Checks.check("the straight-line provider answers with the goal's anchor",
                route != null && !route.isPrecise() && route.getEnd().equals(Vec3.of(4.5, 64, 4.5)));
        Checks.check("and nothing for a goal with none", line.plan(Goal.level(3), null) == null && line.getPlans() == 2);
        Checks.check("the unreachable one never finds a way", FakeProviders.unreachable().plan(Goal.level(1), null) == null);
        FakeProviders.Planner fixed = FakeProviders.routes(route);
        Checks.check("a scripted one hands out its routes in order, then none",
                fixed.plan(Goal.level(1), null) == route && fixed.plan(Goal.level(1), null) == null);

        Sandbox sandbox = Sandbox.create();
        SimPlayer me = sandbox.player(Vec3.of(0.5, 64, 0.5));
        FakeProviders.Driver driver = FakeProviders.driver(me, 1d);
        Goal there = Goal.block(3, 64, 0);
        List<Progress> progress = Arrays.asList(driver.follow(there), driver.follow(there), driver.follow(there));
        Checks.check("the fake driver moves the player itself and reports arriving (" + progress + ")",
                driver.drives() && progress.get(2).isArrived() && there.isMet(me.getPosition()));
        driver.cancel();
        Checks.check("and counts being followed and cancelled",
                driver.getFollows() == 3 && driver.getCancels() == 1 && driver.getFollowing() == null);
    }
}
