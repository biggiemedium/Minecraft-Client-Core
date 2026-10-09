package dev.px.core.control;

import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.registry.Named;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import lombok.Getter;
import lombok.Setter;

import java.util.EnumMap;
import java.util.Map;

/**
 * Decides whose movement keys are held and who presses the buttons, when more
 * than one thing wants them.
 *
 * <p>{@link dev.px.core.movement.rotation.RotationService} does this for the
 * head; this does it for the rest of the player's controls, the same way. A
 * module or a navigator <em>claims</em> the keys or a button with a priority, the
 * highest claim wins, and a claim that is not renewed lapses &mdash; so something
 * switched off, returning early or throwing lets go on the next tick without
 * having to remember to.
 *
 * <pre>{@code
 * // in a module's tick handler -- claim every tick for as long as you want it
 * Core.controls().move(this, MovementInput.forward(yaw).withSprint(true), RotationPriority.HIGH);
 * Core.controls().press(this, Click.ATTACK, RotationPriority.HIGH);    // one click, this tick
 * Core.controls().hold(this, Click.USE, RotationPriority.NORMAL);      // held while you keep asking
 *
 * // in the adapter, where the game reads its keys and its clicks
 * Core.controls().applyMovement();
 * Core.controls().applyClicks();
 * }</pre>
 *
 * <h2>Arbitration</h2>
 *
 * <p>Each control is arbitrated on its own: the movement keys, the attack button
 * and the use button. Priorities are on the same scale as
 * {@link dev.px.core.movement.rotation.RotationPriority}, higher wins, and an
 * equal priority goes to whoever acquired the control first and stays there for
 * as long as they keep asking, so two claims on one priority never flicker.
 *
 * <p>A movement claim is all the keys at once, with the yaw they are meant for:
 * the winner's input is the input. Nobody's claim is merged with anybody
 * else's, because half of one plan and half of another goes neither place.
 *
 * <p>Claims expire like rotation claims: after one tick unless filed with a
 * longer hold. Read and claim in any order within a tick; nothing is decided
 * until the adapter applies.
 *
 * <h2>The player's own keys</h2>
 *
 * <p>While no claim wins, nothing is written and the player's own keys stand. A
 * button held for a claim is always let go when the claim ends, so a lapsed hold
 * never leaves the use button stuck down.
 *
 * <p>Inert without sinks: claims are still accepted and arbitrated, and nothing
 * is written. A client that does not want Core pressing keys installs no sinks
 * and pays nothing.
 *
 * <p>Game thread only, like every other per-tick path in the library.
 */
public final class ControlService implements Service {

    /**
     * Applies between ticks before the service says nobody is opening them.
     *
     * <p>An adapter that posts no {@link TickEvent} and never calls
     * {@link #beginTick()} leaves claims that never expire. Nothing throws, so it
     * has to be reported.
     */
    private static final int APPLIES_WITHOUT_TICK_WARN = 600;

    private final CoreLogger logger;
    private final EventBus bus;

    private final Claims<MovementInput> movement = new Claims<>();
    private final Map<Click, Claims<Press>> clicks = new EnumMap<>(Click.class);

    /** Buttons this service is holding down, so a hold is always let go. */
    private final Map<Click, Boolean> held = new EnumMap<>(Click.class);

    /** Writes the movement keys. Installed by the adapter; null leaves movement inert. */
    @Getter
    @Setter
    private MovementSink movementSink;

    /** Presses the buttons. Installed by the adapter; null leaves the buttons inert. */
    @Getter
    @Setter
    private ClickSink clickSink;

    private Subscription tickSubscription;

    private long tick;

    /** The tick clicks were last applied on, so applying twice never clicks twice. */
    private long clickedTick = -1L;

    private int appliesSinceTick;

    private boolean warnedAboutTicks;

    public ControlService(CoreLogger logger, EventBus bus) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        for (Click button : Click.values()) {
            clicks.put(button, new Claims<Press>());
            held.put(button, Boolean.FALSE);
        }
    }

    @Override
    public String getName() {
        return "Controls";
    }

    @Override
    public void start() {
        // HIGHEST, and only to open the window, exactly as rotations do: it must run
        // before any module files a claim.
        tickSubscription = bus.on(TickEvent.class, Priority.HIGHEST, event -> {
            if (event.getStage() == Stage.PRE) {
                beginTick();
            }
        });
        if (movementSink == null || clickSink == null) {
            logger.debug("No " + (movementSink == null ? "MovementSink" : "ClickSink")
                    + " installed; control claims will be arbitrated but never applied");
        }
    }

    @Override
    public void stop() {
        if (tickSubscription != null) {
            tickSubscription.close();
            tickSubscription = null;
        }
        releaseAll();
        letGoOfEverything();
    }

    // ------------------------------------------------------------- movement

    /** Hold these keys this tick, at {@code priority}. */
    public void move(Object owner, MovementInput input, int priority) {
        move(owner, input, priority, 1);
    }

    /**
     * Files or renews {@code owner}'s claim on the movement keys.
     *
     * @param input the keys, and the yaw they are meant for
     * @param holdTicks how many ticks the claim survives without being renewed
     */
    public void move(Object owner, MovementInput input, int priority, int holdTicks) {
        Validate.notNull(input, "input");
        movement.file(owner, input, priority, holdTicks);
    }

    /** @return the winning movement keys this tick, or null while the player's own stand */
    public MovementInput getMovement() {
        Claims.Lease<MovementInput> winner = movement.winner();
        return winner == null ? null : winner.value;
    }

    /** @return whoever's movement claim is winning, or null */
    public Object getMovementHolder() {
        Claims.Lease<MovementInput> winner = movement.winner();
        return winner == null ? null : winner.owner;
    }

    /** @return a name for whoever holds the movement keys, for a debug HUD, or null */
    public String getMovementHolderName() {
        return nameOf(getMovementHolder());
    }

    public boolean hasMovementClaim(Object owner) {
        return movement.has(owner);
    }

    /**
     * Writes the winning keys to the {@link MovementSink}.
     *
     * <p>The adapter's integration point, called where the game reads its keys
     * each tick. Does nothing when no claim wins or no sink is installed.
     *
     * @return whether anything was written
     */
    public boolean applyMovement() {
        countApply();
        MovementInput input = getMovement();
        if (input == null || movementSink == null) {
            return false;
        }
        movementSink.apply(input);
        return true;
    }

    // --------------------------------------------------------------- clicks

    /** Click {@code button} once this tick, at {@code priority}. */
    public void press(Object owner, Click button, int priority) {
        Validate.notNull(button, "button");
        clicks.get(button).file(owner, Press.CLICK, priority, 1);
    }

    /** Hold {@code button} down this tick, at {@code priority}. */
    public void hold(Object owner, Click button, int priority) {
        hold(owner, button, priority, 1);
    }

    /**
     * Files or renews {@code owner}'s claim to hold {@code button} down.
     *
     * @param holdTicks how many ticks the claim survives without being renewed
     */
    public void hold(Object owner, Click button, int priority, int holdTicks) {
        Validate.notNull(button, "button");
        clicks.get(button).file(owner, Press.HOLD, priority, holdTicks);
    }

    /** @return whether the winning claim on {@code button} this tick is a single click */
    public boolean isClicking(Click button) {
        Claims.Lease<Press> winner = clicks.get(button).winner();
        return winner != null && winner.value == Press.CLICK;
    }

    /** @return whether the winning claim on {@code button} this tick holds it down */
    public boolean isHolding(Click button) {
        Claims.Lease<Press> winner = clicks.get(button).winner();
        return winner != null && winner.value == Press.HOLD;
    }

    /** @return whoever's claim on {@code button} is winning, or null */
    public Object getHolder(Click button) {
        Claims.Lease<Press> winner = clicks.get(button).winner();
        return winner == null ? null : winner.owner;
    }

    public boolean hasClaim(Object owner, Click button) {
        return clicks.get(button).has(owner);
    }

    /**
     * Presses, holds and lets go of the buttons through the {@link ClickSink}.
     *
     * <p>The adapter's integration point, called where the game handles its
     * clicks each tick. Calling it twice in a tick clicks once. A button this
     * service held is let go as soon as nobody claims holding it.
     *
     * @return whether anything was written
     */
    public boolean applyClicks() {
        countApply();
        if (clickSink == null || clickedTick == tick) {
            return false;
        }
        clickedTick = tick;
        boolean wrote = false;
        for (Click button : Click.values()) {
            Claims.Lease<Press> winner = clicks.get(button).winner();
            boolean wantHeld = winner != null && winner.value == Press.HOLD;
            if (held.get(button) && !wantHeld) {
                clickSink.setHeld(button, false);
                held.put(button, Boolean.FALSE);
                wrote = true;
            }
            if (winner != null && winner.value == Press.CLICK) {
                clickSink.click(button);
                wrote = true;
            }
            if (wantHeld && !held.get(button)) {
                clickSink.setHeld(button, true);
                held.put(button, Boolean.TRUE);
                wrote = true;
            }
        }
        return wrote;
    }

    /** Both {@link #applyMovement()} and {@link #applyClicks()}, for an adapter with one place for input. */
    public boolean apply() {
        boolean moved = applyMovement();
        boolean clicked = applyClicks();
        return moved || clicked;
    }

    // ------------------------------------------------------------ releasing

    /**
     * Drops every claim {@code owner} has, now, rather than letting them lapse.
     *
     * <p>Optional, like {@link dev.px.core.movement.rotation.RotationService#release}.
     *
     * @return whether there was anything to drop
     */
    public boolean release(Object owner) {
        boolean dropped = movement.release(owner);
        for (Claims<Press> claims : clicks.values()) {
            dropped |= claims.release(owner);
        }
        return dropped;
    }

    /** Drops every claim. A held button is let go the next time clicks are applied. */
    public void releaseAll() {
        movement.clear();
        for (Claims<Press> claims : clicks.values()) {
            claims.clear();
        }
    }

    /** @return how many claims are live this tick, across every control */
    public int getClaimCount() {
        int count = movement.count();
        for (Claims<Press> claims : clicks.values()) {
            count += claims.count();
        }
        return count;
    }

    // --------------------------------------------------------------- ticking

    /**
     * Opens a new tick: claims that were not renewed lapse.
     *
     * <p>Wired to {@link TickEvent} at {@link Priority#HIGHEST} and {@link Stage#PRE}
     * on startup, so an adapter that posts ticks needs nothing. One that does not
     * calls this from its game loop.
     */
    public void beginTick() {
        tick++;
        movement.beginTick();
        for (Claims<Press> claims : clicks.values()) {
            claims.beginTick();
        }
        appliesSinceTick = 0;
    }

    // ------------------------------------------------------------ internals

    private void letGoOfEverything() {
        for (Click button : Click.values()) {
            if (held.get(button)) {
                if (clickSink != null) {
                    clickSink.setHeld(button, false);
                }
                held.put(button, Boolean.FALSE);
            }
        }
    }

    private void countApply() {
        if (warnedAboutTicks || ++appliesSinceTick <= APPLIES_WITHOUT_TICK_WARN) {
            return;
        }
        warnedAboutTicks = true;
        logger.warn("ControlService has applied " + appliesSinceTick + " times without a tick;"
                + " nothing is posting TickEvent or calling beginTick(), so claims will never expire");
    }

    private static String nameOf(Object owner) {
        if (owner == null) {
            return null;
        }
        return owner instanceof Named ? ((Named) owner).getName() : owner.getClass().getSimpleName();
    }

    /** What a claim on a button asks for. */
    private enum Press {
        CLICK,
        HOLD
    }
}
