package dev.px.core.test.suite;

import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.CollisionSpace;
import dev.px.core.movement.simulation.DriftMonitor;
import dev.px.core.movement.simulation.PhysicsCalibration;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;
import dev.px.core.util.math.MovementMath;
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
 * <p>Where other entities will be is {@code PredictionTests}' business.
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
     * <p>So the numbers below come from outside, the Minecraft Wiki (Walking,
     * Sprinting, Player, Jumping, Entity): 4.317, 5.612, 1.295 and 7.127 blocks a
     * second, ice, blue ice and slime, jumps of 1.2522 blocks and more with Jump
     * Boost, and a terminal fall of 78.4 blocks a second. The tolerance is a tenth of a percent.
     * It was three, to leave room for a 2.05% the model was off by horizontally;
     * that turned out to be a missing rule, the game scaling the keys by 0.98 each
     * tick, and with it in, the figures land within a hundredth of a percent.
     */
    private static void accuracy() {
        Checks.checkEquals("walking reaches the speed Minecraft walks at (blocks/second)",
                4.317f, (float) blocksPerSecond(MovementInput.forward(0f)), 0.1f);
        Checks.checkEquals("sprinting reaches the speed it sprints at",
                5.612f, (float) blocksPerSecond(MovementInput.forward(0f).withSprint(true)), 0.1f);
        Checks.checkEquals("sneaking reaches the speed it sneaks at",
                1.295f, (float) blocksPerSecond(MovementInput.forward(0f).withSneak(true)), 0.1f);
        // Averaged over a minute of jumps, so where in its arc the last one ends does not matter. It ran
        // at 6.09 while the keys accelerated at the air rate on the tick of each jump; the game uses the
        // ground rate there.
        Checks.checkEquals("sprint-jumping reaches the speed it sprint-jumps at",
                7.127f, (float) averageBlocksPerSecond(MovementInput.forward(0f).withSprint(true).withJump(true)), 0.1f);

        // minecraft.wiki/w/Walking: walking on slippery blocks, whose slipperiness is the world's to report.
        Checks.checkEquals("walking on ice reaches the speed Minecraft does (slipperiness 0.98)",
                4.157f, (float) blocksPerSecond(MovementInput.forward(0f), 0.98d), 0.1f);
        Checks.checkEquals("on blue ice (0.989)",
                4.376f, (float) blocksPerSecond(MovementInput.forward(0f), 0.989d), 0.1f);
        Checks.checkEquals("on slime (0.8)",
                3.040f, (float) blocksPerSecond(MovementInput.forward(0f), 0.8d), 0.1f);

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
        // minecraft.wiki/w/Jumping
        Checks.checkEquals("with Jump Boost I", 1.8361f,
                (float) jumpPeak(PhysicsProfile.vanilla().withJumpVelocity(MovementMath.jumpVelocity(PhysicsProfile.vanilla(), 1))), 0.01f);
        Checks.checkEquals("with Jump Boost II", 2.5168f,
                (float) jumpPeak(PhysicsProfile.vanilla().withJumpVelocity(MovementMath.jumpVelocity(PhysicsProfile.vanilla(), 2))), 0.01f);
        // minecraft.wiki/w/Entity#Motion
        MotionState falling = MotionState.of(Vec3.of(0d, 10_000d, 0d), Vec3.ZERO, false);
        for (int tick = 0; tick < 2000; tick++) {
            falling = Simulation.step(PhysicsProfile.vanilla(), falling, MovementInput.none(0f), CollisionSpace.empty());
        }
        Checks.checkEquals("and a long fall ends at the terminal velocity it does (blocks/second)",
                78.4f, (float) (-falling.getVelocity().getY() * 20d), 0.01f);

        Checks.checkEquals("and the first tick of a fall is the exact vanilla figure", -0.0784f,
                (float) Simulation.step(MotionState.at(Vec3.of(0.5d, 64d, 0.5d)).withOnGround(false),
                        MovementInput.none(0f), CollisionSpace.empty()).getVelocity().getY());

        // The trap itself, guarded directly: the two fields must not be confused
        // again, and they are far enough apart that mixing them up is obvious.
        Checks.check("the top speed and the acceleration attribute are different numbers",
                Math.abs(PhysicsProfile.vanilla().getWalkSpeed()
                        - PhysicsProfile.vanilla().getMoveSpeedAttribute()) > 0.1d);
    }

    /** @return travel in blocks per second averaged over a minute, after ten seconds to settle. */
    private static double averageBlocksPerSecond(MovementInput input) {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -16, 16);
        MotionState state = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));
        for (int tick = 0; tick < 200; tick++) {
            state = Simulation.step(state, input, world);
            state = state.withPosition(Vec3.of(0.5d, state.getPosition().getY(), 0.5d));   // stay on the floor
        }
        double travelled = 0d;
        for (int tick = 0; tick < 1200; tick++) {
            MotionState next = Simulation.step(state, input, world);
            travelled += next.getPosition().getZ() - state.getPosition().getZ();
            state = next.withPosition(Vec3.of(0.5d, next.getPosition().getY(), 0.5d));
        }
        return travelled / 60d;
    }

    /** @return the highest a standing jump reaches under {@code profile} */
    private static double jumpPeak(PhysicsProfile profile) {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -4, 4);
        MotionState state = Simulation.step(profile, MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.none(0f).withJump(true), world);
        double peak = state.getPosition().getY();
        for (int tick = 0; tick < 60 && !state.isOnGround(); tick++) {
            state = Simulation.step(profile, state, MovementInput.none(0f), world);
            peak = Math.max(peak, state.getPosition().getY());
        }
        return peak;
    }

    /** @return steady-state travel in blocks per second, measured after acceleration settles. */
    private static double blocksPerSecond(MovementInput input) {
        return blocksPerSecond(input, 0.6d);
    }

    /** @return steady-state travel in blocks per second on blocks this slippery. */
    private static double blocksPerSecond(MovementInput input, double slipperiness) {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -256, 256);
        world.setSlipperiness(slipperiness);
        MotionState state = MotionState.at(Vec3.of(0.5d, 0d, 0.5d));
        for (int tick = 0; tick < 200; tick++) {
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

        // Pressed into a corner, the box touches two walls exactly; the next
        // tick's box, rebuilt from its centre, can sit a rounding error inside
        // one. That must still read as touching, or the jump below goes through.
        GridCollisionSpace shaft = new GridCollisionSpace().solid(0, -1, 0);
        for (int y = 0; y <= 3; y++) {
            shaft.solid(1, y, 0).solid(-1, y, 0).solid(0, y, 1).solid(0, y, -1);
        }
        MotionState cornered = Simulation.simulate(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)),
                MovementInput.forward(45f), 6, shaft);
        double furthest = 0d;
        MotionState jumping = cornered;
        for (int tick = 0; tick < 12; tick++) {
            jumping = Simulation.step(jumping, MovementInput.forward(180f).withJump(true).withSprint(true), shaft);
            furthest = Math.min(furthest, jumping.getPosition().getZ() - 0.3d);
        }
        Checks.check("a box pressed into a corner cannot jump out through the wall it touches"
                        + String.format(" (got %.6f into it)", -furthest),
                furthest > -1.0E-6d && jumping.getPosition().getY() < 1.3d);

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

    // -------------------------------------------------------------- service

    private static void service(TestClient client) {
        RecordingLogger logger = new RecordingLogger();
        SimulationService simulation = new SimulationService(logger, new CoreEventBus(logger));
        simulation.start();

        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -16, 16);
        simulation.setCollisionSpace(world);

        MotionState landing = simulation.landing(
                MotionState.at(Vec3.of(0.5d, 8d, 0.5d)).withOnGround(false),
                MovementInput.none(0f), 60);
        Checks.check("the service simulates against the installed world", landing != null);
        Checks.checkEquals("landing on it", 0f, (float) landing.getPosition().getY());

        simulation.stop();

        // ---- and the one Core wires in -------------------------------------
        SimulationService wired = client.getCore().getSimulationService();
        Checks.check("Core wires a simulation service in", wired != null);
        Checks.check("and an empty world rather than a null one",
                wired.getCollisionSpace() != null);
        Checks.check("so simulating before a world is installed still answers",
                wired.simulate(MotionState.at(Vec3.ZERO), MovementInput.none(0f), 5) != null);
        Checks.checkEquals("falling, because nothing is in the way", true,
                wired.simulate(MotionState.at(Vec3.ZERO), MovementInput.none(0f), 5)
                        .getPosition().getY() < 0d);
    }
}
