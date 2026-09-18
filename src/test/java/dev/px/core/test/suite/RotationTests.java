package dev.px.core.test.suite;

import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.MathUtil;
import dev.px.core.math.Vec2;
import dev.px.core.movement.rotation.RotationMode;
import dev.px.core.movement.rotation.RotationPriority;
import dev.px.core.movement.rotation.RotationRequest;
import dev.px.core.movement.rotation.RotationService;
import dev.px.core.registry.Named;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.RecordingRotationSink;
import dev.px.core.test.harness.TestClient;
import dev.px.core.util.math.RotationMath;

/**
 * Rotation arbitration: who gets the head, how fast it turns, and what happens
 * when they let go.
 *
 * <p>The behaviour being pinned down is the one a private {@code aim} field in
 * each module cannot have. Two modules both wanting the rotation must produce a
 * defined winner rather than whichever handler ran last, the winner must not
 * flicker between them tick to tick, and a module that stops asking &mdash;
 * because it was switched off, or threw &mdash; must lose the head without
 * anyone having remembered to release it.
 *
 * <p>The ordering checks are the other half. Resolution is lazy precisely so that
 * reads and requests commute, and that is only worth claiming if it is tested:
 * reading before a module has asked, reading twice, and a late high-priority
 * claim all have to land on the same answer as the tidy ordering would.
 *
 * <p>Runs against its own service and a fake sink, so the booted client keeps
 * running with no sink installed &mdash; which is the state a client that does
 * not want Core touching rotations is in.
 */
public final class RotationTests {

    private RotationTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Rotations");

        arbitration();
        expiry();
        turning();
        ordering();
        releasing();
        modesAndSensitivity();
        inertWithoutSink(client);
    }

    // ---------------------------------------------------------- arbitration

    private static void arbitration() {
        RecordingRotationSink sink = new RecordingRotationSink();
        RotationService rotations = fresh(sink);

        Object aim = new NamedOwner("Kill Aura");
        Object scaffold = new Object();

        rotations.beginTick();
        rotations.request(scaffold, Vec2.rotation(-45f, 0f), RotationPriority.NORMAL);
        rotations.request(aim, Vec2.rotation(90f, 10f), RotationPriority.HIGH);

        Checks.checkEquals("both claims are live", 2, rotations.getRequestCount());
        Checks.check("the higher priority wins", rotations.isHeldBy(aim));
        Checks.check("and the lower one does not", !rotations.isHeldBy(scaffold));
        Checks.checkEquals("the winning target is what resolves", 90f, rotations.getRotation().getYaw());
        Checks.checkEquals("the loser still has a claim on file", true, rotations.hasRequest(scaffold));
        Checks.checkEquals("the holder names itself", "Kill Aura", rotations.getHolderName());

        // A claim arriving after the rotation has already been read still wins.
        Object crystal = new Object();
        rotations.request(crystal, Vec2.rotation(0f, -30f), RotationPriority.HIGHEST);
        Checks.check("a higher claim filed later takes over the same tick", rotations.isHeldBy(crystal));
        Checks.checkEquals("and its target is what resolves", -30f, rotations.getRotation().getPitch());
        Checks.checkEquals("an unnamed holder falls back to its class",
                "Object", rotations.getHolderName());

        // ---- ties go to whoever got there first, and stay there ---------------
        RotationService tied = fresh(new RecordingRotationSink());
        Object first = new NamedOwner("First");
        Object second = new NamedOwner("Second");

        tied.beginTick();
        tied.request(first, Vec2.rotation(10f, 0f), RotationPriority.NORMAL);
        tied.request(second, Vec2.rotation(20f, 0f), RotationPriority.NORMAL);
        Checks.checkEquals("an equal-priority tie goes to the first acquirer",
                "First", tied.getHolderName());

        // The obvious "most recent wins" rule would hand the head back and forth
        // here, once per tick, for as long as both modules are enabled.
        boolean stable = true;
        for (int tick = 0; tick < 5; tick++) {
            tied.beginTick();
            // Renewed in the other order on purpose.
            tied.request(second, Vec2.rotation(20f, 0f), RotationPriority.NORMAL);
            tied.request(first, Vec2.rotation(10f, 0f), RotationPriority.NORMAL);
            stable &= tied.isHeldBy(first);
        }
        Checks.check("and holds it across renewals rather than flickering", stable);

        tied.beginTick();
        tied.request(second, Vec2.rotation(20f, 0f), RotationPriority.NORMAL);
        Checks.check("until the first stops asking", tied.isHeldBy(second));
    }

    // --------------------------------------------------------------- expiry

    private static void expiry() {
        RecordingRotationSink sink = new RecordingRotationSink();
        RotationService rotations = fresh(sink);
        Object owner = new Object();

        rotations.beginTick();
        rotations.request(owner, Vec2.rotation(90f, 0f));
        Checks.check("a claim holds the tick it was filed", rotations.isHeldBy(owner));

        rotations.beginTick();
        Checks.checkEquals("and expires the next one if nobody renews it",
                0, rotations.getRequestCount());
        Checks.check("so a module switched off mid-hold cannot lock the head",
                rotations.getHolder() == null);

        // A longer hold survives ticks where the module did not get to run.
        rotations.beginTick();
        rotations.request(owner, RotationRequest.at(90f, 0f).hold(3));
        rotations.beginTick();
        Checks.check("a three-tick hold survives one skipped tick", rotations.isHeldBy(owner));
        rotations.beginTick();
        Checks.check("and a second", rotations.isHeldBy(owner));
        rotations.beginTick();
        Checks.check("but not a third", rotations.getHolder() == null);

        rotations.beginTick();
        rotations.request(owner, Vec2.rotation(90f, 0f));
        Checks.check("release drops a claim inside the same tick", rotations.release(owner));
        Checks.check("and the head is free immediately", rotations.getHolder() == null);
        Checks.check("releasing something with no claim reports it", !rotations.release(new Object()));
    }

    // -------------------------------------------------------------- turning

    private static void turning() {
        RecordingRotationSink sink = new RecordingRotationSink();
        RotationService rotations = fresh(sink);
        Object owner = new Object();

        // Stepped: the head may only move so far in a tick.
        rotations.beginTick();
        rotations.request(owner, Vec2.rotation(90f, 0f), RotationPriority.NORMAL, 10f);
        Checks.checkEquals("a stepped turn moves at most one step", 10f,
                rotations.getRotation().getYaw());

        rotations.beginTick();
        rotations.request(owner, Vec2.rotation(90f, 0f), RotationPriority.NORMAL, 10f);
        Checks.checkEquals("and keeps going the next tick", 20f, rotations.getRotation().getYaw());

        for (int tick = 0; tick < 20; tick++) {
            rotations.beginTick();
            rotations.request(owner, Vec2.rotation(90f, 0f), RotationPriority.NORMAL, 10f);
            rotations.getRotation();
        }
        Checks.checkEquals("and arrives", 90f, rotations.getRotation().getYaw());

        // Snapping is the default, because most callers have smoothed already.
        RotationService instant = fresh(new RecordingRotationSink());
        instant.beginTick();
        instant.request(owner, Vec2.rotation(90f, 0f));
        Checks.checkEquals("an unstepped claim arrives at once", 90f,
                instant.getRotation().getYaw());

        // Yaw wraps; the turn must take the short way round.
        RecordingRotationSink nearWrap = new RecordingRotationSink();
        nearWrap.setRotation(Vec2.rotation(170f, 0f));
        RotationService wrapping = fresh(nearWrap);
        wrapping.beginTick();
        wrapping.request(owner, Vec2.rotation(-170f, 0f), RotationPriority.NORMAL, 10f);
        Vec2 wrapped = wrapping.getRotation();
        Checks.checkEquals("a turn across 180 takes the short way", 10f,
                Math.abs(MathUtil.angleDifference(wrapped.getYaw(), -170f)));
        Checks.check("rather than spinning the long way round",
                Math.abs(MathUtil.angleDifference(170f, wrapped.getYaw())) < 15f);

        // Pitch cannot leave the range a head can reach.
        RotationService clamped = fresh(new RecordingRotationSink());
        clamped.beginTick();
        clamped.request(owner, Vec2.rotation(0f, 120f));
        Checks.checkEquals("pitch is clamped to what a head can do", 90f,
                clamped.getRotation().getPitch());
    }

    // ------------------------------------------------------------- ordering

    private static void ordering() {
        RecordingRotationSink sink = new RecordingRotationSink();
        RotationService rotations = fresh(sink);
        Object owner = new Object();

        // Reading first is the case a tick-ordered design gets wrong: the answer
        // would be a tick stale. Here the read simply resolves again.
        rotations.beginTick();
        Checks.check("reading before anything asks is idle", !rotations.isActive());
        Checks.checkEquals("and reports the player's own rotation", 0f,
                rotations.getRotation().getYaw());
        rotations.request(owner, Vec2.rotation(90f, 0f));
        Checks.check("a claim filed after that read still takes effect", rotations.isActive());
        Checks.checkEquals("in the same tick", 90f, rotations.getRotation().getYaw());

        // Reading twice must not advance the turn twice.
        RotationService stepped = fresh(new RecordingRotationSink());
        stepped.beginTick();
        stepped.request(owner, Vec2.rotation(90f, 0f), RotationPriority.NORMAL, 10f);
        float once = stepped.getRotation().getYaw();
        stepped.getRotation();
        stepped.isActive();
        stepped.getMode();
        Checks.checkEquals("resolving repeatedly in one tick does not step twice",
                once, stepped.getRotation().getYaw());

        // And a read between two claims must not change what the second one does.
        RotationService interleaved = fresh(new RecordingRotationSink());
        Object low = new Object();
        Object high = new Object();
        interleaved.beginTick();
        interleaved.request(low, Vec2.rotation(90f, 0f), RotationPriority.LOW, 10f);
        interleaved.getRotation();
        interleaved.request(high, Vec2.rotation(-90f, 0f), RotationPriority.HIGH, 10f);

        RotationService clean = fresh(new RecordingRotationSink());
        clean.beginTick();
        clean.request(low, Vec2.rotation(90f, 0f), RotationPriority.LOW, 10f);
        clean.request(high, Vec2.rotation(-90f, 0f), RotationPriority.HIGH, 10f);

        Checks.checkEquals("a read between two claims changes nothing",
                clean.getRotation().getYaw(), interleaved.getRotation().getYaw());
        Checks.checkEquals("and the turn starts from where the tick opened, not from the read",
                -10f, interleaved.getRotation().getYaw());
    }

    // ------------------------------------------------------------ releasing

    private static void releasing() {
        // SILENT: the camera never moved, so the server-side head has to walk back.
        RecordingRotationSink sink = new RecordingRotationSink();
        RotationService rotations = fresh(sink);
        rotations.setReleaseStep(15f);
        Object owner = new Object();

        rotations.beginTick();
        rotations.request(owner, RotationRequest.at(90f, 0f).mode(RotationMode.SILENT));
        Checks.check("apply writes while a claim is live", rotations.apply());
        Checks.checkEquals("what the claim asked for", 90f, sink.lastApplied().getYaw());
        Checks.checkEquals("tagged with its mode", RotationMode.SILENT, sink.getLastMode());

        rotations.beginTick();
        Checks.check("the rotation is still driven after the claim expires", rotations.apply());
        Checks.checkEquals("easing back a step at a time", 75f, sink.lastApplied().getYaw());

        int guard = 0;
        while (rotations.isActive() && guard++ < 50) {
            rotations.apply();
            rotations.beginTick();
        }
        Checks.check("and eventually stops", !rotations.isActive());
        Checks.checkEquals("having landed on the player's own rotation", 0f,
                sink.lastApplied().getYaw());
        Checks.check("apply does nothing once idle", !rotations.apply());

        // CLIENT: the sink moved the camera, so there is nothing to hand back.
        RecordingRotationSink camera = new RecordingRotationSink();
        camera.setWriteBack(true);
        RotationService following = fresh(camera);
        following.beginTick();
        following.request(owner, Vec2.rotation(90f, 0f));
        following.apply();
        Checks.checkEquals("a client-mode rotation moves the camera", 90f,
                camera.getRotation().getYaw());

        following.beginTick();
        Checks.check("so releasing it needs no easing at all", !following.isActive());
        Checks.check("and writes nothing further", !following.apply());
    }

    // -------------------------------------------------- modes, sensitivity

    private static void modesAndSensitivity() {
        Object owner = new Object();

        RecordingRotationSink sink = new RecordingRotationSink();
        RotationService rotations = fresh(sink);
        rotations.beginTick();
        rotations.request(owner, RotationRequest.at(45f, 0f).mode(RotationMode.SILENT));
        Checks.checkEquals("the mode of the winning claim is what resolves",
                RotationMode.SILENT, rotations.getMode());

        // Mouse-grid snapping, which is off until the adapter supplies the slider.
        RotationService unsnapped = fresh(new RecordingRotationSink());
        unsnapped.beginTick();
        unsnapped.request(owner, Vec2.rotation(30.07f, 0f));
        Checks.checkEquals("with no sensitivity set the target is used as given",
                30.07f, unsnapped.getRotation().getYaw());

        RotationService snapped = fresh(new RecordingRotationSink());
        snapped.setSensitivity(0.5f);
        snapped.beginTick();
        snapped.request(owner, Vec2.rotation(30.07f, 0f));
        float step = RotationMath.sensitivityStep(0.5f);
        float yaw = snapped.getRotation().getYaw();
        Checks.check("with it set the result lands on the mouse grid",
                Math.abs(yaw / step - Math.round(yaw / step)) < 0.01f);
        Checks.check("which is not where the raw target was", Math.abs(yaw - 30.07f) > 0.001f);
    }

    // ---------------------------------------------------- inert by default

    private static void inertWithoutSink(TestClient client) {
        RotationService wired = client.getCore().getRotationService();
        Checks.check("Core wires a rotation service in", wired != null);
        Checks.check("with no sink until the client installs one", wired.getSink() == null);

        Object owner = new Object();
        wired.beginTick();
        Checks.checkSurvives("requesting with no sink is harmless",
                () -> wired.request(owner, Vec2.rotation(90f, 0f)));
        Checks.checkEquals("the claim is still filed", 1, wired.getRequestCount());
        Checks.check("but nothing is driven", !wired.isActive());
        Checks.check("and nothing is applied", !wired.apply());
        Checks.checkEquals("reading is still safe", 0f, wired.getRotation().getYaw());

        wired.releaseAll();
        Checks.checkEquals("releaseAll clears the board", 0, wired.getRequestCount());
    }

    // ------------------------------------------------------------- helpers

    private static RotationService fresh(RecordingRotationSink sink) {
        RecordingLogger logger = new RecordingLogger();
        RotationService service = new RotationService(logger, new CoreEventBus(logger));
        service.setSink(sink);
        // Nothing posts to that bus, so every tick below is opened by hand -- which
        // is also the path an adapter that does not post TickEvent takes.
        service.start();
        return service;
    }

    /** An owner that names itself, the way every Module does. */
    private static final class NamedOwner implements Named {

        private final String name;

        private NamedOwner(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
