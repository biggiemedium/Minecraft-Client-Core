package dev.px.core.test.suite;

import dev.px.core.control.Click;
import dev.px.core.control.ControlService;
import dev.px.core.event.Stage;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.movement.rotation.RotationPriority;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.registry.Named;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingControls;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;

import java.util.Arrays;

/**
 * Controls: who holds the movement keys and who presses the buttons.
 *
 * <p>The same promises {@link RotationTests} pins down for the head &mdash; a
 * defined winner, no flicker between equals, claims that lapse when nobody
 * renews them &mdash; plus the one buttons add: a button held for a claim is
 * always let go, including when the claim simply lapsed, and a click is never
 * sent twice in a tick however often the adapter applies.
 */
public final class ControlTests {

    private ControlTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Controls");

        movement();
        expiry();
        clicks();
        holds();
        inertWithoutSinks(client);
    }

    private static void movement() {
        RecordingControls sink = new RecordingControls();
        ControlService controls = fresh(sink);
        Object walker = new NamedOwner("Navigator");
        Object dodger = new Object();

        controls.beginTick();
        Checks.check("with no claim there are no keys to hold", controls.getMovement() == null);
        Checks.check("and nothing is written, so the player's own keys stand", !controls.applyMovement());

        MovementInput walk = MovementInput.forward(90f);
        MovementInput dodge = MovementInput.of(0f, 0d, 1d).withJump(true);
        controls.move(walker, walk, RotationPriority.NORMAL);
        controls.move(dodger, dodge, RotationPriority.HIGH);
        Checks.checkEquals("the higher movement claim wins", dodge, controls.getMovement());
        Checks.check("whole: none of the loser's keys are merged in",
                !controls.getMovement().isSprint() && controls.getMovement().getYaw() == 0f);
        Checks.check("applying writes the winner's keys", controls.applyMovement());
        Checks.checkEquals("exactly as claimed, yaw included", dodge, sink.lastMove());
        Checks.checkEquals("both claims are counted", 2, controls.getClaimCount());

        controls.beginTick();
        controls.move(walker, walk, RotationPriority.NORMAL);
        Checks.checkEquals("once the higher one stops asking, the lower one has the keys", walk,
                controls.getMovement());
        Checks.checkEquals("and its holder names itself", "Navigator", controls.getMovementHolderName());

        ControlService tied = fresh(new RecordingControls());
        Object first = new Object();
        Object second = new Object();
        tied.beginTick();
        tied.move(first, MovementInput.forward(10f), RotationPriority.NORMAL);
        tied.move(second, MovementInput.forward(20f), RotationPriority.NORMAL);
        boolean stable = tied.getMovementHolder() == first;
        for (int tick = 0; tick < 5; tick++) {
            tied.beginTick();
            tied.move(second, MovementInput.forward(20f), RotationPriority.NORMAL);
            tied.move(first, MovementInput.forward(10f), RotationPriority.NORMAL);
            stable &= tied.getMovementHolder() == first;
        }
        Checks.check("an equal-priority tie stays with the first to claim, renewed in either order", stable);
    }

    private static void expiry() {
        ControlService controls = fresh(new RecordingControls());
        Object owner = new Object();

        controls.beginTick();
        controls.move(owner, MovementInput.forward(0f), RotationPriority.NORMAL);
        Checks.check("a movement claim holds the tick it was filed", controls.hasMovementClaim(owner));
        controls.beginTick();
        Checks.check("and lapses the next one if nobody renews it", controls.getMovement() == null);

        controls.beginTick();
        controls.move(owner, MovementInput.forward(0f), RotationPriority.NORMAL, 3);
        controls.beginTick();
        controls.beginTick();
        Checks.check("a three-tick hold survives two skipped ticks", controls.getMovementHolder() == owner);
        controls.beginTick();
        Checks.check("but not a third", controls.getMovementHolder() == null);

        controls.beginTick();
        controls.move(owner, MovementInput.forward(0f), RotationPriority.NORMAL);
        controls.press(owner, Click.ATTACK, RotationPriority.NORMAL);
        Checks.check("release drops every claim an owner has", controls.release(owner));
        Checks.check("its keys", controls.getMovement() == null);
        Checks.check("and its buttons", controls.getHolder(Click.ATTACK) == null);
        Checks.check("releasing an owner with nothing claimed reports it", !controls.release(new Object()));
        Checks.checkThrows("a hold shorter than a tick is refused", IllegalArgumentException.class,
                () -> controls.move(owner, MovementInput.forward(0f), RotationPriority.NORMAL, 0));
    }

    private static void clicks() {
        RecordingControls sink = new RecordingControls();
        ControlService controls = fresh(sink);
        Object aura = new Object();
        Object placer = new Object();

        controls.beginTick();
        controls.press(aura, Click.ATTACK, RotationPriority.HIGH);
        controls.press(placer, Click.USE, RotationPriority.NORMAL);
        Checks.check("each button is arbitrated on its own", controls.isClicking(Click.ATTACK)
                && controls.isClicking(Click.USE));
        controls.applyClicks();
        Checks.checkEquals("a click is pressed once", Arrays.asList("click ATTACK", "click USE"), sink.clicks);
        controls.applyClicks();
        Checks.checkEquals("and applying again in the same tick does not press it twice", 2, sink.clicks.size());

        controls.beginTick();
        controls.applyClicks();
        Checks.checkEquals("a click lasts one tick", 2, sink.clicks.size());

        controls.beginTick();
        controls.hold(placer, Click.USE, RotationPriority.NORMAL);
        controls.press(aura, Click.USE, RotationPriority.HIGH);
        Checks.check("a higher click wins a button over a lower hold", controls.isClicking(Click.USE)
                && !controls.isHolding(Click.USE));
    }

    private static void holds() {
        RecordingControls sink = new RecordingControls();
        ControlService controls = fresh(sink);
        Object eater = new Object();

        controls.beginTick();
        controls.hold(eater, Click.USE, RotationPriority.NORMAL);
        controls.applyClicks();
        Checks.checkEquals("a hold presses the button down", Arrays.asList("hold USE"), sink.clicks);
        for (int tick = 0; tick < 3; tick++) {
            controls.beginTick();
            controls.hold(eater, Click.USE, RotationPriority.NORMAL);
            controls.applyClicks();
        }
        Checks.checkEquals("and renewing it does not press it again", 1, sink.clicks.size());

        controls.beginTick();
        controls.applyClicks();
        Checks.checkEquals("once nobody renews it, it is let go", Arrays.asList("hold USE", "release USE"),
                sink.clicks);

        sink.clear();
        controls.beginTick();
        controls.hold(eater, Click.USE, RotationPriority.NORMAL);
        controls.applyClicks();
        Object aura = new Object();
        controls.beginTick();
        controls.hold(eater, Click.USE, RotationPriority.NORMAL);
        controls.press(aura, Click.USE, RotationPriority.HIGH);
        controls.applyClicks();
        Checks.checkEquals("a click taking over a held button lets it go first, then clicks",
                Arrays.asList("hold USE", "release USE", "click USE"), sink.clicks);

        sink.clear();
        controls.beginTick();
        controls.hold(eater, Click.ATTACK, RotationPriority.NORMAL, 20);
        controls.applyClicks();
        controls.stop();
        Checks.checkEquals("stopping the service lets go of a held button",
                Arrays.asList("hold ATTACK", "release ATTACK"), sink.clicks);
    }

    private static void inertWithoutSinks(TestClient client) {
        ControlService wired = client.getCore().getControlService();
        Checks.check("Core wires a control service in", wired != null);
        Checks.check("with no sinks until the client installs them",
                wired.getMovementSink() == null && wired.getClickSink() == null);

        Object owner = new Object();
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        wired.move(owner, MovementInput.forward(0f), RotationPriority.NORMAL);
        wired.hold(owner, Click.USE, RotationPriority.NORMAL);
        Checks.checkEquals("claims are still filed", 2, wired.getClaimCount());
        Checks.check("but nothing is applied", !wired.apply());
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.checkEquals("and a posted tick lapses them, so the service is subscribed",
                0, wired.getClaimCount());

        RecordingLogger logger = new RecordingLogger();
        ControlService untended = new ControlService(logger, new CoreEventBus(logger));
        for (int i = 0; i < 700; i++) {
            untended.applyMovement();
        }
        Checks.check("applying for ever without a tick warns that claims will never lapse",
                logger.loggedWarning("without a tick"));
        Checks.checkEquals("once", 1, logger.warningCount());
    }

    private static ControlService fresh(RecordingControls sink) {
        RecordingLogger logger = new RecordingLogger();
        ControlService controls = new ControlService(logger, new CoreEventBus(logger));
        controls.setMovementSink(sink);
        controls.setClickSink(sink);
        return controls;
    }

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
