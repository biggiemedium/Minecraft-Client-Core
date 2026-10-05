package dev.px.core.test.suite;

import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.DriftMonitor;
import dev.px.core.movement.simulation.PhysicsCalibration;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MotionTrack;
import dev.px.core.movement.simulation.MotionTracker;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;
import dev.px.core.util.math.PhysicsProfile;

import java.util.List;

/**
 * Movement prediction, against worlds made of blocks put there by hand.
 *
 * <p>Two things are being pinned down and they need different kinds of check.
 *
 * <p>The <b>simulation</b> cannot be tested for exactness, because there is no
 * game here to compare against and asserting the constants back at themselves
 * proves nothing. What it can be tested for is the behaviour that makes it worth
 * having: that a falling body lands on the floor instead of through it, that a
 * fast one does not tunnel, that a wall stops it, that a half-height step is
 * walked up and a full block is not, that jumping reaches roughly the height
 * Minecraft jumps, and that ice makes it slide. Those are the properties a caller
 * actually relies on, and each of them would break under the plausible mistakes
 * &mdash; sweeping the axes in the wrong order, clipping before moving, forgetting
 * the step retry.
 *
 * <p>The <b>tracker</b> is ordinary bookkeeping and is tested exactly: the ring
 * wraps, a velocity measured across a missed tick is halved rather than doubled,
 * and a track nobody updates is forgotten.
 */
public final class SimulationTests {

    private SimulationTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Simulation");

        accuracy();
        falling();
        walking();
        jumping();
        surfaces();
        cost();
        tracking();
        drift();
        calibration();
        service(client);
    }

    // ------------------------------------------------------------- accuracy

    /**
     * Against the figures Minecraft is measured at, not against our own constants.
     *
     * <p>This section exists because the first version of it did the opposite. It
     * asserted that horizontal speed landed "between 0.15 and 0.35", a band
     * derived from {@code PhysicsProfile.walkSpeed}, and so it passed happily
     * while the simulation ran two and a half times too fast &mdash; the profile
     * field it was reading is a top speed and the formula it was feeding wants the
     * movement-speed attribute, which is a different quantity with a similar name.
     * A test written against the model cannot catch the model being wrong.
     *
     * <p>So the numbers below come from outside: 4.317, 5.612 and 1.295 blocks a
     * second, and a jump of 1.2522 blocks. The tolerance is three percent, which
     * is tight enough to catch a mixed-up constant and loose enough to leave room
     * for the two percent the model is genuinely off by.
     */
    private static void accuracy() {
        Checks.checkEquals("walking reaches the speed Minecraft walks at (blocks/second)",
                4.317f, (float) blocksPerSecond(MovementInput.forward(0f)), 3f);
        Checks.checkEquals("sprinting reaches the speed it sprints at",
                5.612f, (float) blocksPerSecond(MovementInput.forward(0f).withSprint(true)), 3f);
        Checks.checkEquals("sneaking reaches the speed it sneaks at",
                1.295f, (float) blocksPerSecond(MovementInput.forward(0f).withSneak(true)), 3f);

        // The vertical axis is exact: these fall straight out of gravity, drag and
        // the jump constant applied in the right order, and nothing was tuned.
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -64, 64);
        MotionState state = Simulation.step(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.none(0f).withJump(true), world);
        double peak = 0d;
        for (int tick = 0; tick < 30 && !state.isOnGround(); tick++) {
            state = Simulation.step(state, MovementInput.none(0f), world);
            peak = Math.max(peak, state.getPosition().getY());
        }
        Checks.checkEquals("a jump reaches the height Minecraft jumps", 1.2522f, (float) peak);

        Checks.checkEquals("and the first tick of a fall is the exact vanilla figure", -0.0784f,
                (float) Simulation.step(MotionState.at(Vec3.of(0.5d, 64d, 0.5d)).withOnGround(false),
                        MovementInput.none(0f), CollisionSpace.empty()).getVelocity().getY());

        // The trap itself, guarded directly: the two fields must not be confused
        // again, and they are far enough apart that mixing them up is obvious.
        Checks.check("the top speed and the acceleration attribute are different numbers",
                Math.abs(PhysicsProfile.vanilla().getWalkSpeed()
                        - PhysicsProfile.vanilla().getMoveSpeedAttribute()) > 0.1d);
    }

    /** @return steady-state travel in blocks per second, measured after acceleration settles. */
    private static double blocksPerSecond(MovementInput input) {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -256, 256);
        MotionState state = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));
        for (int tick = 0; tick < 60; tick++) {
            state = Simulation.step(state, input, world);
        }
        double from = state.getPosition().getZ();
        for (int tick = 0; tick < 20; tick++) {
            state = Simulation.step(state, input, world);
        }
        return state.getPosition().getZ() - from;
    }

    // ---------------------------------------------------------------- drift

    /**
     * The self-check that says when the simulation has stopped describing the game.
     *
     * <p>Tested by lying to it. Feed it states the simulation itself produced and
     * the error is zero; feed it states produced under different physics and the
     * error appears on the axis that changed, which is what makes the split
     * between horizontal and vertical worth keeping.
     */
    private static void drift() {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -256, 256);
        PhysicsProfile vanilla = PhysicsProfile.vanilla();
        MovementInput input = MovementInput.forward(0f);

        DriftMonitor honest = new DriftMonitor();
        Checks.check("nothing to compare against on the first observation",
                !honest.observe(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)), input, vanilla, world));
        Checks.check("and nothing is claimed about reliability yet", !honest.isReliable());

        MotionState truth = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));
        for (int tick = 0; tick < 20; tick++) {
            truth = Simulation.step(vanilla, truth, input, world);
            honest.observe(truth, input, vanilla, world);
        }
        Checks.check("a world that matches the model drifts by nothing",
                honest.getError() < 1.0E-9d);
        Checks.check("so it reports itself reliable", honest.isReliable());

        // A world running different horizontal physics: the error has to show up,
        // and it has to show up on the horizontal axis alone.
        DriftMonitor misled = new DriftMonitor();
        PhysicsProfile faster = vanilla.withMoveSpeedAttribute(0.2d);
        MotionState other = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));
        for (int tick = 0; tick < 20; tick++) {
            other = Simulation.step(faster, other, input, world);
            misled.observe(other, input, vanilla, world);
        }
        Checks.check("a mismatch in the game's physics shows up as drift",
                misled.getError() > 0.01d);
        Checks.check("so the simulation stops being trusted", !misled.isReliable());
        Checks.check("and the axis that moved is named",
                misled.getHorizontalError() > misled.getVerticalError() * 100d);

        // A teleport is not a prediction failure and must not be recorded as one.
        DriftMonitor teleported = new DriftMonitor();
        teleported.observe(MotionState.at(Vec3.of(0d, 0d, 0d)), input, vanilla, world);
        Checks.check("a teleport is not scored as drift",
                !teleported.observe(MotionState.at(Vec3.of(900d, 70d, 900d)), input, vanilla, world));
        Checks.checkEquals("and is counted separately", 1L, teleported.getDiscontinuities());
        Checks.checkEquals("leaving the error history clean", 0, teleported.getSampleCount());

        honest.reset();
        Checks.checkEquals("reset forgets everything", 0, honest.getSampleCount());
        Checks.check("including where it was", honest.getLastObserved() == null);
    }

    // ---------------------------------------------------------- calibration

    /**
     * The offline harness: measure the gap, then find the constant that closes it.
     *
     * <p>Proven by planting the answer. Samples are generated under a profile
     * whose attribute has been moved off the default, and the scan has to find its
     * way back to that value from a range that brackets it. If it can recover a
     * constant it was never told, it can find one on a real version.
     */
    private static void calibration() {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -256, 256);
        PhysicsProfile vanilla = PhysicsProfile.vanilla();
        PhysicsProfile truth = vanilla.withMoveSpeedAttribute(0.117d);
        MovementInput input = MovementInput.forward(0f);

        PhysicsCalibration calibration = new PhysicsCalibration();
        MotionState state = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));
        for (int tick = 0; tick < 120; tick++) {
            MotionState next = Simulation.step(truth, state, input, world);
            calibration.record(state, input, next);
            state = next;
        }
        Checks.checkEquals("every transition is recorded", 120, calibration.size());

        PhysicsCalibration.Report before = calibration.check(vanilla, world);
        Checks.checkEquals("the report covers every sample", 120, before.getSamples());
        Checks.check("and the default profile is measurably off", before.getMeanError() > 0.001d);
        Checks.check("on the horizontal axis, which is what was changed",
                before.getHorizontalError() > before.getVerticalError());

        PhysicsCalibration.Report exact = calibration.check(truth, world);
        Checks.check("while the profile that produced them is not off at all",
                exact.getMeanError() < 1.0E-9d);

        // Coarse sweep, then a narrow one around the winner.
        PhysicsCalibration.Tuning coarse =
                calibration.tune(vanilla::withMoveSpeedAttribute, 0.05d, 0.2d, 31, world);
        PhysicsCalibration.Tuning fine = calibration.tune(vanilla::withMoveSpeedAttribute,
                coarse.getValue() - 0.005d, coarse.getValue() + 0.005d, 41, world);

        Checks.checkEquals("a two-pass scan recovers the constant it was never told",
                0.117f, (float) fine.getValue(), 1f);
        Checks.check("and the fit is better than where it started",
                fine.getReport().getMeanError() < before.getMeanError());
        Checks.check("a tuning describes itself for a debug command",
                fine.describe().contains("best"));

        Checks.checkThrows("a scan needs at least two steps", IllegalArgumentException.class,
                () -> calibration.tune(vanilla::withMoveSpeedAttribute, 0d, 1d, 1, world));
        Checks.checkThrows("and a range that goes somewhere", IllegalArgumentException.class,
                () -> calibration.tune(vanilla::withMoveSpeedAttribute, 1d, 1d, 5, world));

        calibration.clear();
        Checks.checkEquals("clearing drops the samples", 0, calibration.size());
        Checks.checkEquals("and an empty run reports nothing rather than dividing by zero",
                0, calibration.check(vanilla, world).getSamples());
    }

    // -------------------------------------------------------------- falling

    private static void falling() {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -8, 8);

        MotionState dropped = MotionState.at(Vec3.of(0.5d, 6d, 0.5d)).withOnGround(false);
        MotionState landing = Simulation.landing(dropped, MovementInput.none(0f), 60, world);

        Checks.check("a falling body lands", landing != null);
        Checks.checkEquals("on top of the floor rather than inside it", 0f,
                (float) landing.getPosition().getY());
        Checks.check("and knows it is on the ground", landing.isOnGround());

        // The mistake this guards against is sweeping the axes together, or
        // clipping after moving: either lets a fast fall pass through a thin floor.
        MotionState fast = MotionState.of(Vec3.of(0.5d, 5d, 0.5d), Vec3.of(0d, -40d, 0d), false);
        MotionState afterOne = Simulation.step(fast, MovementInput.none(0f), world);
        Checks.checkEquals("a body moving forty blocks a tick still stops at the floor",
                0f, (float) afterOne.getPosition().getY());
        Checks.check("rather than tunnelling through it", afterOne.isOnGround());

        // With nothing to hit, it keeps going -- which is the honest answer, and
        // what an un-installed CollisionSpace gives you.
        MotionState ballistic = Simulation.simulate(dropped, MovementInput.none(0f),
                20, CollisionSpace.empty());
        Checks.check("with no world at all it simply falls", ballistic.getPosition().getY() < 0d);
        Checks.check("and never claims to have landed", !ballistic.isOnGround());

        // A drop accelerates: the second half of a fall covers more than the first.
        List<MotionState> path = Simulation.trace(dropped, MovementInput.none(0f), 8,
                CollisionSpace.empty());
        double early = path.get(0).getPosition().getY() - path.get(1).getPosition().getY();
        double late = path.get(6).getPosition().getY() - path.get(7).getPosition().getY();
        Checks.check("falling accelerates", late > early);
    }

    // -------------------------------------------------------------- walking

    private static void walking() {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -16, 16);
        MotionState standing = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));

        // Yaw 0 faces +Z in Minecraft, so walking forward must increase Z and
        // leave X alone.
        MotionState walked = Simulation.simulate(standing, MovementInput.forward(0f), 20, world);
        Checks.check("walking forward at yaw 0 travels along +Z",
                walked.getPosition().getZ() > 2d);
        Checks.checkEquals("and not sideways", 0.5f, (float) walked.getPosition().getX());
        Checks.check("staying on the ground throughout", walked.isOnGround());

        // Speed itself is asserted in accuracy(), against the figures Minecraft is
        // measured at rather than against our own constants.

        MotionState sprinted = Simulation.simulate(standing,
                MovementInput.forward(0f).withSprint(true), 20, world);
        Checks.check("sprinting covers more ground",
                sprinted.getPosition().getZ() > walked.getPosition().getZ());

        MotionState sneaked = Simulation.simulate(standing,
                MovementInput.forward(0f).withSneak(true), 20, world);
        Checks.check("sneaking covers less",
                sneaked.getPosition().getZ() < walked.getPosition().getZ());

        // A wall two blocks high at x = 2, walked into along +X.
        GridCollisionSpace walled = new GridCollisionSpace().floor(0d, -16, 16).wallAtX(2, 0, -4, 4);
        MotionState intoWall = Simulation.simulate(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.forward(-90f), 40, walled);
        Checks.check("a wall stops horizontal movement",
                intoWall.getPosition().getX() < 2d && intoWall.getPosition().getX() > 1.5d);
        Checks.checkEquals("and the blocked axis loses its velocity", 0f,
                (float) intoWall.getVelocity().getX());
        Checks.check("without stopping the other one", intoWall.isOnGround());
    }

    // -------------------------------------------------------------- jumping

    private static void jumping() {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -16, 16);
        MotionState standing = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));

        MotionState leapt = Simulation.step(standing, MovementInput.none(0f).withJump(true), world);
        Checks.check("jumping leaves the ground", !leapt.isOnGround());
        Checks.check("with upward velocity", leapt.getVelocity().getY() > 0d);

        // Vanilla's jump peaks a little over 1.25 blocks. Getting this wrong means
        // gravity, drag or the jump constant are being applied in the wrong order.
        double peak = 0d;
        MotionState current = leapt;
        for (int tick = 0; tick < 30; tick++) {
            current = Simulation.step(current, MovementInput.none(0f), world);
            peak = Math.max(peak, current.getPosition().getY());
            if (current.isOnGround()) {
                break;
            }
        }
        Checks.check("a jump peaks around a block and a quarter  ("
                        + String.format("%.3f", peak) + ")",
                peak > 1.15d && peak < 1.35d);
        Checks.check("and comes back down", current.isOnGround());
        Checks.checkEquals("landing exactly on the floor", 0f, (float) current.getPosition().getY());

        // A sprint jump gets a horizontal kick the moment it leaves the ground.
        MotionState sprintJump = Simulation.step(standing,
                MovementInput.forward(0f).withJump(true).withSprint(true), world);
        MotionState walkJump = Simulation.step(standing,
                MovementInput.forward(0f).withJump(true), world);
        Checks.check("a sprint jump launches faster than a walking one",
                sprintJump.getHorizontalSpeed() > walkJump.getHorizontalSpeed());
    }

    // ------------------------------------------------------------- surfaces

    private static void surfaces() {
        // A half-height block is inside the step height, so it is walked up.
        // The ledge runs from z 2 to 6, and twenty ticks of walking gets well onto
        // it without reaching the far end, where stepping back down would prove
        // nothing.
        GridCollisionSpace step = new GridCollisionSpace().floor(0d, -32, 32);
        for (int z = 2; z <= 6; z++) {
            step.solid(0, 0, z, 0.5d);
        }
        MotionState climbed = Simulation.simulate(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.forward(0f), 20, step);
        Checks.checkEquals("a half-height ledge is stepped onto", 0.5f,
                (float) climbed.getPosition().getY());
        Checks.check("and walking continues past it", climbed.getPosition().getZ() > 3d);

        // A full block is taller than the step height, so it is walked into.
        GridCollisionSpace blocked = new GridCollisionSpace().floor(0d, -32, 32);
        for (int z = 2; z <= 6; z++) {
            blocked.solid(0, 0, z, 1d);
        }
        MotionState stopped = Simulation.simulate(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.forward(0f), 40, blocked);
        Checks.checkEquals("a full block is not", 0f, (float) stopped.getPosition().getY());
        Checks.check("and stops the walk", stopped.getPosition().getZ() < 2d);

        // Turning stepping off makes the same ledge a wall, which is the knob a
        // caller reaches for when simulating something that cannot step.
        MotionState noStep = Simulation.simulate(
                PhysicsProfile.vanilla().withStepHeight(0d),
                MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.forward(0f), 20, step);
        Checks.checkEquals("step height zero turns the ledge into a wall", 0f,
                (float) noStep.getPosition().getY());

        // Ice: the same acceleration, then the keys released. Slippery ground keeps
        // the momentum for longer.
        GridCollisionSpace ice = new GridCollisionSpace().floor(0d, -64, 64);
        ice.setSlipperiness(0.98d);
        GridCollisionSpace dirt = new GridCollisionSpace().floor(0d, -64, 64);

        Checks.check("ice slides further than dirt",
                slideDistance(ice) > slideDistance(dirt));
    }

    /** Accelerates for twenty ticks, then coasts for twenty with no keys held. */
    private static double slideDistance(GridCollisionSpace world) {
        MotionState state = Simulation.simulate(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.forward(0f), 20, world);
        double from = state.getPosition().getZ();
        state = Simulation.simulate(state, MovementInput.none(0f), 20, world);
        return state.getPosition().getZ() - from;
    }

    // ----------------------------------------------------------------- cost

    private static void cost() {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -16, 16);
        world.resetQueryCount();
        Simulation.trace(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)), MovementInput.forward(0f), 10, world);
        Checks.checkEquals("a tick costs exactly one look at the world", 10, world.getQueryCount());

        MotionState start = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));
        MovementInput input = MovementInput.forward(30f);
        List<MotionState> path = Simulation.trace(start, input, 12, world);
        Checks.checkEquals("a trace holds one state per tick", 12, path.size());
        Checks.checkEquals("and its last state is what simulate returns",
                Simulation.simulate(start, input, 12, world).getPosition(),
                path.get(11).getPosition());
        Checks.check("zero ticks is an empty trace",
                Simulation.trace(start, input, 0, world).isEmpty());
        Checks.checkThrows("a negative count is rejected", IllegalArgumentException.class,
                () -> Simulation.simulate(start, input, -1, world));
    }

    // ------------------------------------------------------------- tracking

    private static void tracking() {
        MotionTracker tracker = new MotionTracker(4);
        Object target = "player-7";

        Checks.check("an unknown key has no track", tracker.get(target) == null);
        Checks.check("and is not tracked", !tracker.isTracked(target));

        tracker.record(target, Vec3.of(0d, 64d, 0d), true, 1L);
        tracker.record(target, Vec3.of(0d, 64d, 1d), true, 2L);

        MotionTrack track = tracker.get(target);
        Checks.checkEquals("the newest position is the one read back",
                Vec3.of(0d, 64d, 1d), track.getPosition());
        Checks.checkEquals("velocity is measured from the last two samples",
                Vec3.of(0d, 0d, 1d), track.getVelocity());
        Checks.checkEquals("and extends in a straight line",
                Vec3.of(0d, 64d, 4d), track.extrapolate(3));
        Checks.check("the ground flag comes along", track.isOnGround());
        Checks.checkEquals("as does the tick it was taken on", 2L, track.getLastTick());

        // A sample missed to lag must average over the gap, not report double speed.
        tracker.record(target, Vec3.of(0d, 64d, 3d), true, 4L);
        Checks.checkEquals("a missed tick averages over the gap rather than doubling",
                Vec3.of(0d, 0d, 1d), track.getVelocity());

        // The ring is bounded: the oldest sample falls off rather than growing.
        for (int i = 0; i < 10; i++) {
            tracker.record(target, Vec3.of(i, 64d, 0d), false, 10L + i);
        }
        Checks.checkEquals("history is capped at the configured size", 4, track.getSampleCount());
        Checks.check("so asking past the end gets nothing", track.positionAgo(4) == null);
        Checks.check("and asking backwards gets nothing", track.positionAgo(-1) == null);
        Checks.checkEquals("while the samples it did keep are the newest",
                Vec3.of(9d, 64d, 0d), track.positionAgo(0));
        Checks.checkEquals("in order", Vec3.of(8d, 64d, 0d), track.positionAgo(1));

        Checks.check("a track converts to a state a simulation can step",
                track.toState() != null);
        Checks.checkEquals("a single sample has no velocity yet", Vec3.ZERO,
                freshTrack().getVelocity());

        Checks.checkThrows("a history too short to hold a velocity is rejected",
                IllegalArgumentException.class, () -> new MotionTracker(1));

        // Eviction, which is what stops every entity ever seen being remembered.
        // A tracker of its own, so the count is not whatever earlier checks left.
        MotionTracker ageing = new MotionTracker(4);
        ageing.record("stale", Vec3.ZERO, true, 1L);
        ageing.record("fresh", Vec3.ZERO, true, 100L);
        Checks.checkEquals("both are tracked", 2, ageing.size());
        Checks.checkEquals("eviction drops only the stale one", 1, ageing.evictBefore(50L));
        Checks.check("the fresh one survives", ageing.isTracked("fresh"));
        Checks.check("the stale one does not", !ageing.isTracked("stale"));

        Checks.check("a track can be forgotten by hand", ageing.forget("fresh"));
        Checks.check("and forgetting an unknown key says so", !ageing.forget("nobody"));
    }

    private static MotionTrack freshTrack() {
        MotionTracker tracker = new MotionTracker(4);
        tracker.record("one", Vec3.ZERO, true, 1L);
        return tracker.get("one");
    }

    // -------------------------------------------------------------- service

    private static void service(TestClient client) {
        RecordingLogger logger = new RecordingLogger();
        SimulationService simulation = new SimulationService(logger, new CoreEventBus(logger));
        simulation.setForgetAfterTicks(3);
        simulation.start();

        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -16, 16);
        simulation.setCollisionSpace(world);

        simulation.beginTick();
        simulation.record("a", Vec3.of(0d, 64d, 0d), true);
        simulation.beginTick();
        simulation.record("a", Vec3.of(0d, 64d, 1d), true);

        Checks.checkEquals("the service stamps samples with its own clock", 2L, simulation.getTick());
        Checks.checkEquals("and predicts from them", Vec3.of(0d, 64d, 3d), simulation.predict("a", 2));
        Checks.check("an untracked key predicts nothing", simulation.predict("nobody", 2) == null);

        MotionState landing = simulation.landing(
                MotionState.at(Vec3.of(0.5d, 8d, 0.5d)).withOnGround(false),
                MovementInput.none(0f), 60);
        Checks.check("the service simulates against the installed world", landing != null);
        Checks.checkEquals("landing on it", 0f, (float) landing.getPosition().getY());

        MotionState coasted = simulation.coast("a", 5);
        Checks.check("a tracked thing can be coasted forward with physics", coasted != null);
        Checks.check("falling as it goes, since nothing holds it up at y 64",
                coasted.getPosition().getY() < 64d);
        Checks.check("coasting an untracked key gives nothing", simulation.coast("nobody", 5) == null);

        // Tracks are dropped once nobody has updated them for long enough.
        for (int tick = 0; tick < 5; tick++) {
            simulation.beginTick();
        }
        Checks.check("a track nobody updates is eventually forgotten", !simulation.isTracked("a"));

        simulation.stop();

        // ---- and the one Core wires in -------------------------------------
        SimulationService wired = client.getCore().getSimulationService();
        Checks.check("Core wires a simulation service in", wired != null);
        Checks.check("with a tracker ready to use", wired.getTracker() != null);
        Checks.check("and an empty world rather than a null one",
                wired.getCollisionSpace() != null);
        Checks.check("so simulating before a world is installed still answers",
                wired.simulate(MotionState.at(Vec3.ZERO), MovementInput.none(0f), 5) != null);
        Checks.checkEquals("falling, because nothing is in the way", true,
                wired.simulate(MotionState.at(Vec3.ZERO), MovementInput.none(0f), 5)
                        .getPosition().getY() < 0d);
    }
}
