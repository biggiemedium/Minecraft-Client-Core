package dev.px.core.test.suite;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Future;
import dev.px.core.movement.prediction.MotionEstimate;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.movement.prediction.Scenario;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.test.harness.MovementRig.Body;
import dev.px.core.test.harness.MovementRig.Script;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Where somebody else will be, worked out from where they have been.
 *
 * <p>The truth here is {@link Simulation} itself, driven by scripted keys: a
 * "player" walks, sprints, sneaks and sprint-jumps through a world of blocks, and
 * the prediction service sees only what a client sees of another player &mdash;
 * the positions an {@code EntitySource} reports, tick by tick. Against that truth
 * the checks are exact: what was pressed can be read back, and where they went
 * next can be predicted to within rounding, as long as they keep doing it.
 *
 * <p>That flatters the predictor in one way, which the checks do not hide: the
 * truth obeys the same rules the prediction runs. Real players are measured by
 * recording them, which is not this suite.
 */
public final class PredictionTests {

    private PredictionTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Prediction");

        history();
        estimating();
        predicting();
        weighing();
        scenarios();
        precision();
        service(client);
    }

    // --------------------------------------------------------------- history

    private static void history() {
        MovementRig rig = new MovementRig(new GridCollisionSpace().floor(0d, -64, 64));
        Body body = rig.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f));
        rig.tick();
        Tracked<Body> tracked = rig.tracked(body);
        Checks.checkEquals("an entity seen once has one tick of history", 1, tracked.getHistorySize());
        Checks.checkEquals("which is where it is now", tracked.getPosition(), tracked.positionAgo(0));
        Checks.check("and nothing before it", tracked.positionAgo(1) == null && tracked.positionAgo(-1) == null);

        for (int i = 0; i < EntityTracker.DEFAULT_HISTORY + 10; i++) {
            rig.tick();
        }
        Checks.checkEquals("history is capped at the tracker's setting",
                EntityTracker.DEFAULT_HISTORY, tracked.getHistorySize());
        Checks.check("newest first, a tick apart (" + tracked.positionAgo(0) + ", " + tracked.positionAgo(1) + ")",
                tracked.positionAgo(0).getZ() > tracked.positionAgo(1).getZ()
                        && tracked.positionAgo(1).getZ() > tracked.positionAgo(EntityTracker.DEFAULT_HISTORY - 1).getZ());
        Checks.checkEquals("with the way it faced", 0f, tracked.yawAgo(5));
        Checks.check("and no yaw past the end", Float.isNaN(tracked.yawAgo(EntityTracker.DEFAULT_HISTORY)));

        rig.hidden.add(body);
        rig.tick();
        rig.hidden.clear();
        rig.tick();
        Tracked<Body> back = rig.tracked(body);
        Checks.checkEquals("a tick unseen breaks the run: it starts again", 1, back.getHistorySize());

        MovementRig short_ = new MovementRig(new GridCollisionSpace().floor(0d, -64, 64));
        short_.bodies.setHistory(3);
        Body brief = short_.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f));
        for (int i = 0; i < 10; i++) {
            short_.tick();
        }
        Checks.checkEquals("a tracker can keep less", 3, short_.tracked(brief).getHistorySize());
        Checks.checkThrows("but not none", IllegalArgumentException.class, () -> short_.bodies.setHistory(0));
        Checks.check("you are kept too",
                short_.entities.getSelf() != null && short_.entities.getSelf().getHistorySize() == 10);
    }

    // ------------------------------------------------------------- estimating

    private static void estimating() {
        GridCollisionSpace flat = new GridCollisionSpace().floor(0d, -200, 200);

        MotionEstimate sprint = settled(flat, tick -> MovementInput.forward(30f).withSprint(true));
        Checks.check("a sprinter is read as sprinting forward, the way they go (" + sprint + ")",
                sprint.getInput().isSprint() && !sprint.getInput().isSneak() && !sprint.getInput().isJump()
                        && Math.abs(sprint.getInput().getYaw() - 30f) < 1e-3 && sprint.getError() < 1e-9);
        MotionEstimate walk = settled(flat, tick -> MovementInput.forward(-100f));
        Checks.check("a walker as walking", !walk.getInput().isSprint() && walk.getInput().isMoving()
                && !walk.getInput().isSneak() && walk.getError() < 1e-9);
        MotionEstimate diagonal = settled(flat, tick -> MovementInput.of(-60f, 1d, 1d));
        Checks.check("two keys as two keys (" + diagonal.getInput() + ")",
                diagonal.getInput().getForward() == 1d && diagonal.getInput().getStrafe() == 1d
                        && diagonal.getError() < 1e-9);
        MotionEstimate sneak = settled(flat, tick -> MovementInput.forward(120f).withSneak(true));
        Checks.check("a sneaker as sneaking", sneak.getInput().isSneak() && sneak.getError() < 1e-9);
        MotionEstimate still = settled(flat, tick -> MovementInput.none(0f));
        MovementRig spot = new MovementRig(flat);
        Body bouncer = spot.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.none(0f).withJump(true));
        boolean jumpsInPlace = false;
        for (int i = 0; i < 30; i++) {
            spot.tick();
            MotionEstimate now = spot.prediction.estimate(spot.tracked(bouncer));
            jumpsInPlace |= i > 10 && now.getInput().isJump() && !now.getInput().isMoving();
        }
        Checks.check("someone jumping on the spot as jumping, with no keys", jumpsInPlace);
        Checks.check("someone standing as pressing nothing", !still.getInput().isMoving() && !still.getInput().isJump());

        // Mid-air the keys barely move them, so a jump's keys come from the tick it left the ground.
        MovementRig rig = new MovementRig(flat);
        Body hopper = rig.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(10f).withSprint(true).withJump(true));
        MotionEstimate midAir = null;
        for (int i = 0; i < 40; i++) {
            rig.tick();
            MotionEstimate now = rig.prediction.estimate(rig.tracked(hopper));
            if (!now.getState().isOnGround() && hopper.state.getVelocity().getY() < 0d) {
                midAir = now;
            }
        }
        Checks.check("a sprint-jumper on the way down is still read as sprint-jumping (" + midAir + ")",
                midAir != null && midAir.getInput().isJump() && midAir.getInput().isSprint());

        // The velocity a step starts from is what carries into the next tick, not the distance last moved.
        MovementRig truth = new MovementRig(flat);
        Body runner = truth.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f).withSprint(true));
        for (int i = 0; i < 30; i++) {
            truth.tick();
        }
        MotionEstimate read = truth.prediction.estimate(truth.tracked(runner));
        Vec3 real = runner.state.getVelocity();
        Checks.check("the velocity read back is the one the rules carry, after friction and gravity ("
                        + read.getState().getVelocity() + " vs " + real + ", moved " + truth.tracked(runner).getVelocity() + ")",
                read.getState().getVelocity().distanceTo(real) < 1e-9
                        && truth.tracked(runner).getVelocity().distanceTo(real) > 0.1d);
        Checks.check("standing, as the rules have it", read.getState().isOnGround());

        MovementRig fresh = new MovementRig(flat);
        Body newcomer = fresh.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f));
        fresh.tick();
        fresh.tick();
        MotionEstimate early = fresh.prediction.estimate(fresh.tracked(newcomer));
        Checks.check("with under three ticks seen there is nothing to fit: no keys, no error",
                !early.isFitted() && Double.isNaN(early.getError()) && !early.getInput().isMoving());
    }

    /** @return the estimate of a body after a second of the script, settled */
    private static MotionEstimate settled(CollisionSpace world, Script script) {
        MovementRig rig = new MovementRig(world);
        Body body = rig.add(Vec3.of(0.5d, 0d, 0.5d), script);
        for (int i = 0; i < 30; i++) {
            rig.tick();
        }
        return rig.prediction.estimate(rig.tracked(body));
    }

    // ------------------------------------------------------------- predicting

    private static void predicting() {
        GridCollisionSpace flat = new GridCollisionSpace().floor(0d, -300, 300);
        String[] names = { "sprinting", "walking on two keys", "sneaking", "sprint-jumping" };
        Script[] scripts = {
                tick -> MovementInput.forward(30f).withSprint(true),
                tick -> MovementInput.of(-60f, 1d, 1d),
                tick -> MovementInput.forward(120f).withSneak(true),
                tick -> MovementInput.forward(10f).withSprint(true).withJump(true),
        };
        for (int i = 0; i < scripts.length; i++) {
            Accuracy accuracy = measure(flat, scripts[i], 10, 0d);
            Checks.check(String.format(Locale.ROOT, "someone %s is predicted ten ticks ahead to within rounding "
                            + "(%.6f blocks; a straight line is off by %.4f)", names[i], accuracy.predicted, accuracy.line),
                    accuracy.predicted < 1e-6 && accuracy.reliable);
        }
        Accuracy hops = measure(flat, scripts[3], 10, 0d);
        Checks.check("where a straight line through their last move misses sprint-jumps by blocks", hops.line > 1d);

        // Off a ledge: the prediction falls with them; a straight line walks on air.
        GridCollisionSpace ledge = new GridCollisionSpace();
        for (int x = -60; x <= 60; x++) {
            for (int z = -20; z <= 20; z++) {
                ledge.solid(x, x <= 6 ? -1 : -9, z);
            }
        }
        Accuracy falling = measure(ledge, tick -> MovementInput.forward(-90f).withSprint(true), 10, 0d);
        Checks.check(String.format(Locale.ROOT, "someone running off a ledge is predicted falling "
                        + "(%.6f blocks off; a straight line, %.3f)", falling.predicted, falling.line),
                falling.predicted < 1e-6 && falling.line > 1d);

        // Into a wall: the prediction stops at it.
        GridCollisionSpace walled = new GridCollisionSpace().floor(0d, -60, 60).wallAtX(14, 0, -60, 60);
        Accuracy blocked = measure(walled, tick -> MovementInput.forward(-90f).withSprint(true), 10, 0d);
        Checks.check(String.format(Locale.ROOT, "and someone running at a wall, stopped by it (%.6f; a straight line, %.3f)",
                blocked.predicted, blocked.line), blocked.predicted < 1e-6 && blocked.line > 0.1d);

        // Changing what they do is beyond any prediction, but it beats the straight line and says it is unsure.
        Accuracy circling = measure(flat, tick -> MovementInput.forward(tick * 6f).withSprint(true), 10, 0d);
        Checks.check(String.format(Locale.ROOT, "someone running in circles is predicted better than a straight line "
                        + "(%.3f vs %.3f)", circling.predicted, circling.line),
                circling.predicted < circling.line);
        Checks.check("and marked unreliable", !circling.reliable);
    }

    // ---------------------------------------------------------------- weighing

    private static void weighing() {
        GridCollisionSpace flat = new GridCollisionSpace().floor(0d, -200, 200);
        MovementRig rig = new MovementRig(flat);
        Body runner = rig.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f).withSprint(true));
        for (int i = 0; i < 30; i++) {
            rig.tick();
        }
        Prediction steady = rig.prediction.predict(rig.tracked(runner), 6);
        double total = 0d;
        for (Future future : steady.getFutures()) {
            total += future.getWeight();
        }
        Checks.check("weights share out to one (" + steady + ")", Math.abs(total - 1d) < 1e-9);
        double stopping = 0d;
        for (Future future : steady.getFutures()) {
            if (future.getScenario() == Scenario.STOPS) {
                stopping = future.getWeight();
            }
        }
        Checks.check("someone who keeps running is believed to keep running, not to stop",
                steady.likeliest().getScenario() != Scenario.STOPS && stopping < 0.01d);
        Checks.check("heaviest first", steady.getFutures().get(0).getWeight() >= steady.getFutures().get(1).getWeight());
        Checks.check("each future runs as far as asked, now included",
                steady.likeliest().getTicks() == 6 && steady.likeliest().at(0).equals(steady.getEstimate().getState()));

        // Someone who keeps stopping and starting: stopping explains them best.
        MovementRig stutter = new MovementRig(flat);
        Body stop = stutter.add(Vec3.of(0.5d, 0d, 0.5d),
                tick -> (tick / 4) % 2 == 0 ? MovementInput.forward(0f).withSprint(true) : MovementInput.none(0f));
        for (int i = 0; i < 30; i++) {
            stutter.tick();
        }
        Prediction jittery = stutter.prediction.predict(stutter.tracked(stop), 4);
        Checks.check("a scenario that has described them badly weighs less (" + jittery + ")",
                jittery.getFutures().get(0).getError() <= jittery.getFutures().get(1).getError());

        // With a short history, a test from mid-air may have lost sight of the take-off: skipped, not held against it.
        MovementRig brief = new MovementRig(flat);
        brief.bodies.setHistory(20);
        Body hopper = brief.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(10f).withSprint(true).withJump(true));
        double worst = 0d;
        int unknown = 0;
        for (int i = 0; i < 60; i++) {
            brief.tick();
            if (i >= 25) {
                double error = brief.prediction.predict(brief.tracked(hopper), 10).likeliest().getError();
                if (Double.isNaN(error)) {
                    unknown++;
                } else {
                    worst = Math.max(worst, error);
                }
            }
        }
        Checks.check(String.format(Locale.ROOT, "a test start whose take-off is out of sight is skipped, not counted "
                + "as a miss (worst %.6f; %d of 35 untestable)", worst, unknown), worst < 1e-6 && unknown < 35);

        MovementRig fresh = new MovementRig(flat);
        Body newcomer = fresh.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f));
        for (int i = 0; i < 5; i++) {
            fresh.tick();
        }
        Prediction unsure = fresh.prediction.predict(fresh.tracked(newcomer), 6);
        Checks.check("seen too briefly to test any scenario: equal weights, and unreliable",
                !unsure.isReliable()
                        && Math.abs(unsure.getFutures().get(0).getWeight() - 1d / unsure.getFutures().size()) < 1e-9
                        && Double.isNaN(unsure.likeliest().getError()));
    }

    // --------------------------------------------------------------- scenarios

    private static void scenarios() {
        // A floor with a one-block hole in it at x 3, z 0, a block deep.
        GridCollisionSpace holed = new GridCollisionSpace();
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                if (x != 3 || z != 0) {
                    holed.solid(x, -1, z);
                }
                holed.solid(x, -2, z);
            }
        }
        Vec3 hole = Vec3.of(3.5d, -1d, 0.5d);
        Predicate<MotionState> inHole = state -> state.getPosition().getY() < -0.1d
                && Math.floor(state.getPosition().getX()) == 3 && Math.floor(state.getPosition().getZ()) == 0;

        // Walking straight at it: every way it could go ends in the hole.
        MovementRig rig = new MovementRig(holed);
        Body straight = rig.add(Vec3.of(-2.5d, 0d, 0.5d), tick -> MovementInput.forward(-90f));
        for (int i = 0; i < 20; i++) {
            rig.tick();
        }
        Prediction heading = rig.prediction.predict(rig.tracked(straight), 12, Scenario.toward("hole", hole));
        Checks.check("someone walking straight at a hole is predicted into it (" + heading + ")",
                heading.chance(inHole) > 0.9d && heading.likeliest().firstTick(inHole) > 0);

        // Walking past it: heading there has not described them, so it carries next to no weight.
        MovementRig past = new MovementRig(holed);
        Body passing = past.add(Vec3.of(1.5d, 0d, -12.5d), tick -> MovementInput.forward(0f).withSprint(true));
        for (int i = 0; i < 30; i++) {
            past.tick();
        }
        Prediction by = past.prediction.predict(past.tracked(passing), 18, Scenario.toward("hole", hole));
        Future toward = null;
        for (Future future : by.getFutures()) {
            if (future.getName().equals("hole")) {
                toward = future;
            }
        }
        Checks.check("someone walking past it is not, though a future heading there is weighed (" + by + ")",
                by.chance(inHole) < 0.1d && toward != null && toward.getWeight() < 0.01d);

        // Your own scenario, weighed with the rest.
        Scenario backwards = Scenario.of("backs off", (estimate, state, tick) ->
                MovementInput.forward(estimate.getInput().getYaw() + 180f));
        Prediction mine = rig.prediction.predict(rig.tracked(straight), 4, backwards);
        Checks.check("a scenario of your own is played out and weighed alongside",
                mine.getFutures().size() == rig.prediction.getScenarios().size() + 1
                        && mine.likeliest().getScenario() != backwards);
        Checks.check("toward is named after its point unless named",
                Scenario.toward(Vec3.of(1, 2, 3)).getName().equals("toward 1.0, 2.0, 3.0"));

        MovementRig hopper = new MovementRig(new GridCollisionSpace().floor(0d, -60, 60));
        Body still = hopper.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.none(0f));
        for (int i = 0; i < 10; i++) {
            hopper.tick();
        }
        hopper.prediction.setScenarios(Scenario.JUMPS);
        Prediction jumps = hopper.prediction.predict(hopper.tracked(still), 3);
        Checks.check("the scenarios a service weighs are yours to set: one that jumps, jumps",
                jumps.getFutures().size() == 1 && jumps.positionAt(3).getY() > 0.5d);
    }

    // --------------------------------------------------------------- precision

    private static void precision() {
        GridCollisionSpace flat = new GridCollisionSpace().floor(0d, -300, 300);
        Accuracy sprint = measure(flat, tick -> MovementInput.forward(30f).withSprint(true), 10, 1d / 4096d);
        Accuracy hops = measure(flat, tick -> MovementInput.forward(10f).withSprint(true).withJump(true), 10, 1d / 4096d);
        Checks.check(String.format(Locale.ROOT, "positions as precise as the server sends since 1.9 still predict to "
                        + "within a few hundredths (%.4f sprinting, %.4f sprint-jumping)", sprint.predicted, hops.predicted),
                sprint.predicted < 0.01d && hops.predicted < 0.05d);
    }

    // ----------------------------------------------------------------- service

    private static void service(TestClient client) {
        Checks.check("Core wires a prediction service in", client.getCore().getPredictionService() != null);

        RecordingLogger logger = new RecordingLogger();
        PredictionService prediction = new PredictionService(new SimulationService(logger, new CoreEventBus(logger)));
        Checks.checkThrows("it needs at least one scenario", IllegalArgumentException.class,
                () -> prediction.setScenarios());
        Checks.checkThrows("a backtest from nowhere is refused", IllegalArgumentException.class,
                () -> prediction.setBacktest(0));
        Checks.checkThrows("as is a temperature of zero", IllegalArgumentException.class,
                () -> prediction.setTemperature(0d));

        // The world is fetched once a prediction, not once a step.
        GridCollisionSpace counted = new GridCollisionSpace().floor(0d, -60, 60);
        MovementRig rig = new MovementRig(counted);
        Body body = rig.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f).withSprint(true));
        for (int i = 0; i < 25; i++) {
            rig.tick();
        }
        counted.resetQueryCount();
        rig.prediction.predict(rig.tracked(body), 10);
        Checks.check("a prediction asks your world for its blocks a handful of times, not once a step ("
                + counted.getQueryCount() + ")", counted.getQueryCount() <= 3);
    }

    // ------------------------------------------------------------ the harness

    /** @return how far {@code ahead} predictions missed, on average, over two seconds of {@code script} */
    private static Accuracy measure(CollisionSpace world, Script script, int ahead, double precision) {
        MovementRig rig = new MovementRig(world);
        Body body = rig.add(Vec3.of(0.5d, 0d, 0.5d), script);
        body.precision = precision;
        int warm = 25;
        int evaluated = 40;
        List<Vec3> truth = new ArrayList<>();
        Vec3[] predicted = new Vec3[evaluated];
        Vec3[] line = new Vec3[evaluated];
        boolean reliable = true;
        for (int tick = 0; tick < warm + evaluated + ahead; tick++) {
            rig.tick();
            truth.add(body.state.getPosition());
            if (tick >= warm && tick < warm + evaluated) {
                Tracked<Body> tracked = rig.tracked(body);
                Prediction prediction = rig.prediction.predict(tracked, ahead);
                predicted[tick - warm] = prediction.positionAt(ahead);
                line[tick - warm] = tracked.extrapolate(ahead);
                reliable &= prediction.isReliable();
            }
        }
        double missed = 0d;
        double lineMissed = 0d;
        for (int i = 0; i < evaluated; i++) {
            Vec3 real = truth.get(warm + i + ahead);
            missed += predicted[i].distanceTo(real);
            lineMissed += line[i].distanceTo(real);
        }
        return new Accuracy(missed / evaluated, lineMissed / evaluated, reliable);
    }

    private static final class Accuracy {
        final double predicted;
        final double line;
        final boolean reliable;

        Accuracy(double predicted, double line, boolean reliable) {
            this.predicted = predicted;
            this.line = line;
            this.reliable = reliable;
        }
    }
}
