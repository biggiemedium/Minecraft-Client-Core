package dev.px.core.test.suite;

import dev.px.core.event.Stage;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Behaviour;
import dev.px.core.movement.prediction.record.MovementJson;
import dev.px.core.movement.prediction.record.MovementRecorder;
import dev.px.core.movement.prediction.record.MovementRecording;
import dev.px.core.movement.prediction.record.MovementReplay;
import dev.px.core.movement.prediction.record.ReplayReport;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.test.harness.MovementRig.Body;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.util.math.PhysicsProfile;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Locale;

/**
 * Recording real movement, saving it, and replaying predictions against it.
 *
 * <p>The "real" movement is the rig's: a legit player whose positions the server
 * sends every other tick with stamps, a speed hacker, and someone with Speed II
 * your client knows about until it wears off. A block is placed mid-recording,
 * so the world has to be kept as it changes.
 */
public final class RecordingTests {

    private RecordingTests() {
    }

    public static void run() {
        Checks.section("Prediction: recording");

        Session session = record();
        recording(session);
        json(session);
        replay(session);
        lifecycle();
    }

    private static final class Session {
        MovementRecording recording;
        GridCollisionSpace world;
    }

    private static Session record() {
        Session session = new Session();
        session.world = new GridCollisionSpace().floor(0d, -200, 200);
        MovementRig rig = new MovementRig(session.world);
        PhysicsProfile speedTwo = MovementRig.VANILLA.withMoveSpeedAttribute(0.14d);
        Body legit = rig.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(30f).withSprint(true))
                .every(2).stamped();
        Body hacker = rig.add(Vec3.of(5.5d, 0d, 0.5d), tick -> MovementInput.forward(-30f).withSprint(true))
                .rules(MovementRig.VANILLA.withMoveSpeedAttribute(0.2d));
        Body potion = rig.add(Vec3.of(-5.5d, 0d, 0.5d), tick -> MovementInput.forward(0f).withSprint(true))
                .rules(speedTwo);
        // Your client knows about the potion until tick 60, when it wears off and the body slows to the rules.
        boolean[] potionOn = { true };
        rig.prediction.setPhysics((entity, base) -> entity.get() == potion && potionOn[0]
                ? base.withMoveSpeedAttribute(speedTwo.getMoveSpeedAttribute()) : base);

        MovementRecorder recorder = new MovementRecorder(rig.prediction)
                .names(entity -> entity == legit ? "legit" : entity == hacker ? "hacker" : "potion");
        recorder.putMetadata("server", "the rig");
        recorder.begin("rig session", rig.bodies);
        for (int tick = 0; tick < 120; tick++) {
            if (tick == 60) {
                potionOn[0] = false;
                potion.rules(MovementRig.VANILLA);
                session.world.solid(2, 3, 4);       // a block placed, near where the legit player runs
            }
            rig.tick();
            recorder.tick();
        }
        session.recording = recorder.end();
        return session;
    }

    private static void recording(Session session) {
        MovementRecording recording = session.recording;
        Checks.checkEquals("every tick is recorded", 120L, recording.getTicks());
        Checks.checkEquals("every entity, named", "legit",
                recording.getTracks().get(0).getName());
        Checks.check("each with a sample a tick (" + recording + ")",
                recording.getTracks().size() == 3 && recording.getTracks().get(1).getSamples().size() == 120);
        Checks.check("with the stamps your source gave",
                recording.getTracks().get(0).getSamples().get(40).getStamp() >= 0
                        && recording.getTracks().get(1).getSamples().get(40).getStamp() == -1L);
        MovementRecording.Track potion = recording.getTracks().get(2);
        Checks.check("the rules your client gave are kept when they change: the potion wearing off",
                potion.getRules().size() == 2
                        && potion.getRules().get(0).getProfile().getMoveSpeedAttribute() == 0.14d
                        && potion.getRules().get(1).getProfile().getMoveSpeedAttribute() == 0.1d
                        && potion.getRules().get(1).getTick() == 61L);
        int captures = 0;
        boolean changed = false;
        for (MovementRecording.Blocks blocks : recording.getBlocks()) {
            captures++;
            changed |= blocks.getTick() > 60L;
        }
        Checks.check("the world around them is captured, and captured again when a block is placed (" + captures
                + " captures)", captures > 0 && changed);
        Checks.checkEquals("with the metadata", "the rig", recording.getMetadata().get("server"));
    }

    private static void json(Session session) {
        try {
            StringWriter first = new StringWriter();
            MovementJson.write(session.recording, first);
            MovementRecording read = MovementJson.read(new StringReader(first.toString()));
            StringWriter second = new StringWriter();
            MovementJson.write(read, second);
            Checks.check("a recording written and read back writes out identically ("
                    + first.toString().split("\n").length + " lines)", first.toString().equals(second.toString()));
            Checks.check("one object a line, a header first",
                    first.toString().startsWith("{\"format\":\"px-movement\",\"version\":1"));
        } catch (IOException e) {
            Checks.check("a recording round-trips without failing (" + e + ")", false);
        }
        Checks.check("something that is not a movement recording is refused",
                refused("{\"format\":\"px-timeline\",\"version\":1}\n"));
        Checks.check("as is a newer version", refused("{\"format\":\"px-movement\",\"version\":99}\n"));
    }

    private static boolean refused(String json) {
        try {
            MovementJson.read(new StringReader(json));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static void replay(Session session) {
        ReplayReport report = MovementReplay.of(session.recording).horizons(1, 5, 10).run();
        ReplayReport.Horizon ten = report.getHorizon(10);
        // Every tick is scored, the first ticks of each entity and the potion wearing off included: those miss,
        // and say so. What the predictions that called themselves reliable missed by is what trusting them buys.
        Checks.check(String.format(Locale.ROOT, "replayed, predictions that call themselves reliable are close ten ticks "
                        + "ahead (%.4f), the rest are where the misses are (%.4f overall)%n%s",
                ten.getReliableMeanError(), ten.getMeanError(), report),
                ten.getSamples() > 100 && ten.getReliableMeanError() < 0.05d
                        && ten.getMeanError() > ten.getReliableMeanError() && ten.getReliableShare() > 0.8d);
        Checks.check("and the bound is never late", ten.getBoundLate() == 0 && ten.getBoundChecks() == ten.getSamples());

        Behaviour legit = report.getBehaviours().get("legit");
        Behaviour hacker = report.getBehaviours().get("hacker");
        Behaviour potion = report.getBehaviours().get("potion");
        Checks.check("what was learned is reported by name: the server's gap for the legit player (" + legit + ")",
                legit.getTypicalGap() == 2 && legit.getExplainedShare() > 0.95d);
        Checks.check("the hacker's speed (" + hacker + ")", hacker.getSpeedFactor() > 1.8d);
        Checks.check("and the potion, known while it lasted, never taken for cheating (" + potion + ")",
                potion.getExplainedShare() > 0.95d && Math.abs(potion.getSpeedFactor() - 1d) < 0.05d);

        ReplayReport fewer = MovementReplay.of(session.recording).horizons(5)
                .configure(prediction -> prediction.setBacktest(1)).run();
        Checks.check("a replay can try other settings", fewer.getHorizons().size() == 1
                && fewer.getHorizon(5).getSamples() > 0);
    }

    private static void lifecycle() {
        GridCollisionSpace world = new GridCollisionSpace().floor(0d, -40, 40);
        MovementRig rig = new MovementRig(world);
        rig.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.forward(0f));
        MovementRecorder recorder = new MovementRecorder(rig.prediction);
        Checks.checkThrows("ending what never began is refused", IllegalStateException.class, recorder::end);
        recorder.tick();
        Checks.check("and a tick while idle records nothing", !recorder.isRecording());

        RecordingLogger logger = new RecordingLogger();
        CoreEventBus bus = new CoreEventBus(logger);
        recorder.begin("driven", rig.bodies);
        Checks.checkThrows("beginning twice is refused", IllegalStateException.class,
                () -> recorder.begin("again", rig.bodies));
        recorder.bus(bus);
        for (int i = 0; i < 5; i++) {
            rig.tick();
            bus.post(new TickEvent(Stage.PRE));
        }
        MovementRecording driven = recorder.end();
        bus.post(new TickEvent(Stage.PRE));
        Checks.check("the bus's ticks drive it, and stop when it ends (" + driven + ")", driven.getTicks() == 5L);
    }
}
