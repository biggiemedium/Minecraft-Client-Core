package dev.px.core.test.suite;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Behaviour;
import dev.px.core.movement.prediction.Future;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.prediction.Scenario;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.test.harness.MovementRig.Body;
import dev.px.core.test.harness.MovementRig.Mover;
import dev.px.core.test.harness.MovementRig.Script;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Predicting people who do not move by the rules you know: servers that send
 * positions every other tick, effects only your client can see, and cheats.
 *
 * <p>Every cheat here is one way of breaking the rules, not any client's: a
 * speed hack is the rules run faster, a strafe hack sets velocity directly, a
 * snap moves straight into a hole. The prediction is never told which; it learns
 * from what it sees, as it would from a server.
 *
 * <p>The possible bound is checked the strict way: across every tick of every
 * run, the entity's real box a few ticks later must never be somewhere the
 * bound said it could not yet be.
 */
public final class BehaviourTests {

    private static final Script SPRINT = tick -> MovementInput.forward(30f).withSprint(true);
    private static final Script HOPS = tick -> MovementInput.forward(10f).withSprint(true).withJump(true);

    private BehaviourTests() {
    }

    public static void run() {
        Checks.section("Prediction: behaviour");

        freshness();
        updateGaps();
        effects();
        speedHacks();
        strafing();
        teleports();
        snapping();
        unmodelled();
        possible();
    }

    // ------------------------------------------------------------- freshness

    private static void freshness() {
        MovementRig rig = new MovementRig(flat());
        Body body = rig.add(Vec3.of(0.5d, 0d, 0.5d), SPRINT).every(2);
        for (int i = 0; i < 20; i++) {
            rig.tick();
        }
        Tracked<Body> tracked = rig.tracked(body);
        boolean alternates = true;
        for (int i = 0; i < 8; i++) {
            alternates &= tracked.isFreshAgo(i) == (i % 2 == 0);
        }
        Checks.check("a position the server sends every other tick is news every other tick", alternates);
        Checks.checkEquals("so the tick between is no news", 0, tracked.getTicksSinceFresh());
        rig.tick();
        Checks.checkEquals("(and a tick later, it is a tick old)", 1, tracked.getTicksSinceFresh());

        MovementRig still = new MovementRig(flat());
        Body stopper = still.add(Vec3.of(0.5d, 0d, 0.5d),
                tick -> tick < 10 ? MovementInput.forward(0f) : MovementInput.none(0f)).every(2);
        for (int i = 0; i < 30; i++) {
            still.tick();
        }
        Checks.check("holding still longer than the server usually leaves it is news: it has stopped",
                still.tracked(stopper).isFreshAgo(0) && still.tracked(stopper).getTicksSinceFresh() == 0);

        MovementRig stamped = new MovementRig(flat());
        Body sent = stamped.add(Vec3.of(0.5d, 0d, 0.5d), SPRINT).every(3).stamped();
        for (int i = 0; i < 12; i++) {
            stamped.tick();
        }
        Tracked<Body> withStamps = stamped.tracked(sent);
        Checks.check("with stamps, only a new stamp is news",
                withStamps.isFreshAgo(0) && !withStamps.isFreshAgo(1) && !withStamps.isFreshAgo(2)
                        && withStamps.isFreshAgo(3));
    }

    private static void updateGaps() {
        Run everyTick = run(SPRINT, body -> { }, rig -> { });
        Run everyOther = run(SPRINT, body -> body.every(2), rig -> { });
        Run stamped = run(HOPS, body -> body.every(2).stamped(), rig -> { });
        Checks.check(String.format(Locale.ROOT, "positions every other tick predict as well as every tick, with nothing "
                        + "set (%.6f vs %.6f)", everyOther.error, everyTick.error),
                everyOther.error < 1e-6 && everyOther.reliable == everyOther.count);
        Checks.check(String.format(Locale.ROOT, "and sprint-jumping, with stamps (%.6f)", stamped.error),
                stamped.error < 1e-6 && stamped.reliable == stamped.count);
        Checks.checkEquals("the gap the server leaves is learned", 2, everyOther.behaviour.getTypicalGap());

        // Between positions, the prediction is already as far along as the entity is.
        MovementRig rig = new MovementRig(flat());
        Body body = rig.add(Vec3.of(0.5d, 0d, 0.5d), SPRINT).every(2);
        for (int i = 0; i < 31; i++) {
            rig.tick();
        }
        Prediction stale = rig.prediction.predict(rig.tracked(body), 5);
        Checks.check("a tick after the last position, the prediction is a tick old and starts where they are now ("
                        + stale.positionAt(0) + " vs " + body.state.getPosition() + ")",
                stale.getAge() == 1 && stale.positionAt(0).distanceTo(body.state.getPosition()) < 1e-9);
    }

    // --------------------------------------------------------------- effects

    private static void effects() {
        PhysicsProfile speedTwo = MovementRig.VANILLA.withMoveSpeedAttribute(
                MovementRig.VANILLA.getMoveSpeedAttribute() * (1 + 2 * MovementRig.VANILLA.getSpeedPerLevel()));
        Run told = run(SPRINT, body -> body.rules(speedTwo),
                rig -> rig.prediction.setPhysics((entity, base) -> base.withMoveSpeedAttribute(speedTwo.getMoveSpeedAttribute())));
        Checks.check(String.format(Locale.ROOT, "someone with Speed II your client knows about is predicted exactly "
                        + "(%.6f), and moves by the rules (%s)", told.error, told.behaviour),
                told.error < 1e-6 && told.behaviour.getExplainedShare() == 1d
                        && Math.abs(told.behaviour.getSpeedFactor() - 1d) < 1e-6);

        Run untold = run(SPRINT, body -> body.rules(speedTwo), rig -> { });
        Checks.check(String.format(Locale.ROOT, "unknown to your client, the extra speed is learned (x%.3f) and still "
                        + "predicted (%.6f)", untold.behaviour.getSpeedFactor(), untold.error),
                untold.behaviour.getSpeedFactor() > 1.3d && untold.error < 1e-6);

        Rig rig = new Rig(SPRINT, body -> { }, r -> r.prediction.setPhysics((entity, base) -> null));
        Checks.check("an entity your physics says is not moving by the rules is not judged by them",
                Double.isNaN(rig.behaviour().getExplainedShare()) && rig.behaviour().getSpeedSamples() == 0);
    }

    // ----------------------------------------------------------------- cheats

    private static void speedHacks() {
        PhysicsProfile doubled = MovementRig.VANILLA.withMoveSpeedAttribute(MovementRig.VANILLA.getMoveSpeedAttribute() * 2d);
        Run hack = run(SPRINT, body -> body.rules(doubled), rig -> { });
        Checks.check(String.format(Locale.ROOT, "a speed hack running the rules twice as fast is learned (x%.3f), "
                        + "and the rules explain none of it (%.2f)", hack.behaviour.getSpeedFactor(),
                        hack.behaviour.getExplainedShare()),
                Math.abs(hack.behaviour.getSpeedFactor() - 2d) < 0.1d && hack.behaviour.getExplainedShare() == 0d);
        Checks.check(String.format(Locale.ROOT, "and they are predicted where they go, not where the rules would let them "
                + "(%.6f)", hack.error), hack.error < 1e-6 && hack.reliable == hack.count);

        // Toward a hole: someone faster gets there sooner, and the prediction knows it.
        Vec3 hole = Vec3.of(12.5d, -1d, 0.5d);
        MovementRig fair = holeRig(12);
        MovementRig fast = holeRig(12);
        Body legit = fair.add(Vec3.of(-30.5d, 0d, 0.5d), tick -> MovementInput.forward(-90f).withSprint(true));
        Body cheat = fast.add(Vec3.of(-30.5d, 0d, 0.5d), tick -> MovementInput.forward(-90f).withSprint(true))
                .rules(doubled);
        // Walk them both to five blocks from the hole, the legit one by the rules, the cheat twice as fast.
        while (legit.state.getPosition().getX() < 7.5d) {
            fair.tick();
        }
        while (cheat.state.getPosition().getX() < 7.5d) {
            fast.tick();
        }
        // Sprinting carries anyone straight over a one-block hole, so only heading for it and stopping goes in.
        Scenario toHole = Scenario.toward("hole", hole);
        int legitTick = named(fair.prediction.predict(fair.tracked(legit), 24, toHole), "hole").firstTick(BehaviourTests::inHole);
        int cheatTick = named(fast.prediction.predict(fast.tracked(cheat), 24, toHole), "hole").firstTick(BehaviourTests::inHole);
        Checks.check("from the same spot, a speed hacker heading for a hole is predicted into it sooner (" + cheatTick
                + " ticks vs " + legitTick + ")", cheatTick > 0 && legitTick > cheatTick);
        Future heading = named(fast.prediction.predict(fast.tracked(cheat), 24, toHole), "hole");
        double covered = heading.positionAt(4).getX() - heading.positionAt(0).getX();
        Checks.check(String.format(Locale.ROOT, "heading there at their own pace, not the rules' (%.3f blocks in four "
                + "ticks; the rules allow about 1.1)", covered), covered > 2d);
        Prediction over = fair.prediction.predict(fair.tracked(legit), 24, toHole);
        Checks.check("while keeping on sprinting carries them over it, as it does in the game",
                named(over, Scenario.HOLDS.getName()).firstTick(BehaviourTests::inHole) < 0);
    }

    private static void strafing() {
        // A strafe hack sets velocity directly: the same speed every tick, no friction, no acceleration.
        Mover strafe = (body, state, tick, world) -> Simulation.step(MovementRig.VANILLA,
                state.withVelocity(Vec3.of(0.2d, state.getVelocity().getY(), 0.4d)), MovementInput.none(0f), world);
        Run hack = run(SPRINT, body -> body.moving(strafe), rig -> { });
        Checks.check(String.format(Locale.ROOT, "a strafe hack setting velocity directly is predicted (%.6f)", hack.error),
                hack.error < 1e-6 && hack.reliable == hack.count);

        // On the ground a set velocity looks like faster keys. In the air it does not: the rules give the keys
        // almost no grip there, so holding 0.45 a tick through every jump is something only carrying on explains.
        Mover bhop = (body, state, tick, world) -> Simulation.step(MovementRig.VANILLA,
                state.withVelocity(Vec3.of(0d, state.getVelocity().getY(), 0.45d)),
                MovementInput.none(0f).withJump(true), world);
        Rig hopper = new Rig(SPRINT, body -> body.moving(bhop), rig -> { });
        hopper.until(60);
        Prediction hops = hopper.prediction(6);
        Checks.check("a strafe hack through the air is explained by carrying on, not by any keys (" + hops + ")",
                hops.likeliest().getScenario() == Scenario.CARRIES
                        && named(hops, Scenario.CARRIES.getName()).getWeight()
                        > 10d * named(hops, Scenario.HOLDS.getName()).getWeight());
    }

    private static void teleports() {
        Mover pearl = (body, state, tick, world) -> {
            MotionState moved = Simulation.step(MovementRig.VANILLA, state, MovementInput.forward(0f), world);
            return tick == 40 ? moved.withPosition(moved.getPosition().add(30d, 0d, 0d)) : moved;
        };
        Rig rig = new Rig(SPRINT, body -> body.moving(pearl), r -> { });
        rig.until(60);
        Behaviour behaviour = rig.behaviour();
        Checks.check("a pearl is a teleport, not speed: never learned (" + behaviour + ")",
                behaviour.getTopSpeed() < 0.3d);
        Checks.check("and the prediction picks up again from the far side",
                rig.prediction(5).isReliable() && rig.accuracyNow(5) < 1e-6);
    }

    private static void snapping() {
        // A hole snap: walking, then pulled straight into a hole a block a tick.
        Vec3 hole = Vec3.of(4.5d, -1d, 0.5d);
        Mover snap = (body, state, tick, world) -> {
            if (tick < 30) {
                return Simulation.step(MovementRig.VANILLA, state, MovementInput.forward(0f), world);
            }
            Vec3 at = state.getPosition();
            Vec3 toward = hole.subtract(at);
            double step = Math.min(1d, toward.length());
            return MotionState.of(at.add(toward.normalize().scale(step)), Vec3.ZERO, false);
        };
        MovementRig rig = holeRig(4);
        Body body = rig.add(Vec3.of(0.5d, 0d, -6.5d), tick -> MovementInput.forward(0f)).moving(snap);
        for (int i = 0; i < 30; i++) {
            rig.tick();
        }
        // Somewhere six blocks off, wherever it is: the same distance before and after.
        int before = rig.prediction.predict(rig.tracked(body), 5).earliestPossible(sixOff(body));
        for (int i = 0; i < 12; i++) {
            rig.tick();
        }
        Behaviour snapped = rig.prediction.behaviour(rig.tracked(body));
        int after = rig.prediction.predict(rig.tracked(body), 5).earliestPossible(sixOff(body));
        Checks.check("once seen snapping a block a tick, it is believed it could again (" + snapped + ")",
                snapped.getTopSpeed() > 0.9d);
        Checks.check("so the soonest it could reach another hole drops (" + before + " ticks before, " + after
                + " after)", after < before);
    }

    private static void unmodelled() {
        Rig rig = new Rig(SPRINT, body -> { }, r -> r.prediction.setPhysics((entity, base) -> null));
        rig.until(40);
        Prediction drifting = rig.prediction(5);
        Checks.check("riding, gliding or swimming: one future, carrying on as it moves, and never reliable",
                drifting.getFutures().size() == 1 && !drifting.isReliable()
                        && drifting.likeliest().getScenario() == Scenario.CARRIES);
    }

    // --------------------------------------------------------------- possible

    private static void possible() {
        String[] names = { "sprinting", "sprint-jumping", "every other tick", "Speed II unknown", "a speed hack",
                "a strafe hack" };
        PhysicsProfile speedTwo = MovementRig.VANILLA.withMoveSpeedAttribute(0.14d);
        PhysicsProfile doubled = MovementRig.VANILLA.withMoveSpeedAttribute(0.2d);
        Mover strafe = (body, state, tick, world) -> Simulation.step(MovementRig.VANILLA,
                state.withVelocity(Vec3.of(0.2d, state.getVelocity().getY(), 0.4d)), MovementInput.none(0f), world);
        List<Consumer<Body>> bodies = new ArrayList<>();
        bodies.add(body -> { });
        bodies.add(body -> { });
        bodies.add(body -> body.every(2));
        bodies.add(body -> body.rules(speedTwo));
        bodies.add(body -> body.rules(doubled));
        bodies.add(body -> body.moving(strafe));
        Script[] scripts = { SPRINT, HOPS, HOPS, SPRINT, SPRINT, SPRINT };
        int late = 0;
        int checked = 0;
        for (int i = 0; i < names.length; i++) {
            Run run = run(scripts[i], bodies.get(i), rig -> { });
            late += run.late;
            checked += run.boundChecks;
        }
        Checks.check("the soonest possible is never later than they really got anywhere, over " + checked
                + " checks in six kinds of movement (" + late + " late)", late == 0 && checked > 0);

        // Falling into a pit: a drop is bounded by how fast an ordinary fall goes.
        GridCollisionSpace pit = new GridCollisionSpace();
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                pit.solid(x, x >= 3 && x <= 5 ? -9 : -1, z);
            }
        }
        Run off = run(pit, SPRINT90, body -> { }, rig -> { });
        Checks.check("off a ledge and down a pit too (" + off.late + " late of " + off.boundChecks + ")",
                off.late == 0 && off.boundChecks > 0);

        Rig rig = new Rig(SPRINT, body -> { }, r -> { });
        rig.until(30);
        Prediction now = rig.prediction(5);
        Box here = MotionState.at(now.positionAt(0)).hitbox(0.6d, 1.8d);
        Checks.checkEquals("somewhere it already is, it could be now", 0, now.earliestPossible(here));
        Box ahead = here.offset(10d, 0d, 0d);
        int ticks = now.earliestPossible(ahead);
        Checks.check("ten blocks off, no sooner than the fastest it could cover them (" + ticks + ")",
                ticks >= 10d / 0.7d && ticks < 10d / 0.2d);
        Checks.check("the chance by a tick never falls as the tick grows",
                now.chanceBy(state -> state.getPosition().getZ() > here.getMinZ() + 1d, 2)
                        <= now.chanceBy(state -> state.getPosition().getZ() > here.getMinZ() + 1d, 5));
    }

    // ---------------------------------------------------------------- harness

    private static final Script SPRINT90 = tick -> MovementInput.forward(-90f).withSprint(true);

    private static Future named(Prediction prediction, String name) {
        for (Future future : prediction.getFutures()) {
            if (future.getName().equals(name)) {
                return future;
            }
        }
        throw new IllegalStateException("no future called " + name);
    }

    private static boolean inHole(MotionState state) {
        return state.getPosition().getY() < -0.1d;
    }

    private static GridCollisionSpace flat() {
        return new GridCollisionSpace().floor(0d, -400, 400);
    }

    /** A floor with a one-block hole, a block deep, at {@code x}, z 0. */
    private static MovementRig holeRig(int x) {
        GridCollisionSpace world = new GridCollisionSpace();
        for (int bx = -60; bx <= 60; bx++) {
            for (int z = -30; z <= 30; z++) {
                if (bx != x || z != 0) {
                    world.solid(bx, -1, z);
                }
                world.solid(bx, -2, z);
            }
        }
        return new MovementRig(world);
    }

    /** @return a block-sized box six blocks along x from where {@code body} is */
    private static Box sixOff(Body body) {
        return MotionState.at(body.state.getPosition().add(6d, 0d, 0d)).hitbox(1d, 1d);
    }

    private static Run run(Script script, Consumer<Body> body, Consumer<MovementRig> setup) {
        return run(flat(), script, body, setup);
    }

    /** Two seconds of predictions ten ticks ahead, after two of warming up. */
    private static Run run(CollisionSpace world, Script script, Consumer<Body> setup, Consumer<MovementRig> rigSetup) {
        int ahead = 10;
        int warm = 40;
        int count = 40;
        MovementRig rig = new MovementRig(world);
        rigSetup.accept(rig);
        Body body = rig.add(Vec3.of(0.5d, 0d, 0.5d), script);
        setup.accept(body);
        List<Vec3> truth = new ArrayList<>();
        List<Prediction> predictions = new ArrayList<>();
        for (int tick = 0; tick < warm + count + ahead; tick++) {
            rig.tick();
            truth.add(body.state.getPosition());
            if (tick >= warm && tick < warm + count) {
                predictions.add(rig.prediction.predict(rig.tracked(body), ahead));
            }
        }
        Run run = new Run();
        run.count = count;
        for (int i = 0; i < count; i++) {
            Prediction prediction = predictions.get(i);
            run.error += prediction.positionAt(ahead).distanceTo(truth.get(warm + i + ahead)) / count;
            if (prediction.isReliable()) {
                run.reliable++;
            }
            for (int k = 1; k <= ahead; k++) {
                Box there = MotionState.at(truth.get(warm + i + k)).hitbox(0.6d, 1.8d);
                run.boundChecks++;
                if (prediction.earliestPossible(there) > k) {
                    run.late++;
                }
            }
        }
        run.behaviour = rig.prediction.behaviour(rig.tracked(body));
        return run;
    }

    private static final class Run {
        double error;
        int count;
        int reliable;
        int late;
        int boundChecks;
        Behaviour behaviour;
    }

    /** One body in a flat world, for asking things tick by tick. */
    private static final class Rig {
        final MovementRig world;
        final Body body;
        final dev.px.core.movement.prediction.PredictionService prediction;

        Rig(Script script, Consumer<Body> setup, Consumer<MovementRig> rigSetup) {
            world = new MovementRig(flat());
            rigSetup.accept(world);
            body = world.add(Vec3.of(0.5d, 0d, 0.5d), script);
            setup.accept(body);
            prediction = world.prediction;
            until(30);
        }

        void until(int ticks) {
            while (body.tick < ticks) {
                world.tick();
            }
        }

        Behaviour behaviour() {
            return prediction.behaviour(world.tracked(body));
        }

        Prediction prediction(int ticks) {
            return prediction.predict(world.tracked(body), ticks);
        }

        /** @return how far a prediction made now misses where it really is that many ticks on; moves the world on */
        double accuracyNow(int ticks) {
            Vec3 predicted = prediction(ticks).positionAt(ticks);
            for (int i = 0; i < ticks; i++) {
                world.tick();
            }
            return predicted.distanceTo(body.state.getPosition());
        }
    }
}
