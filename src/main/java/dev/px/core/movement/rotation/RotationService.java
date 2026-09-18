package dev.px.core.movement.rotation;

import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.math.Vec2;
import dev.px.core.registry.Named;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.core.util.math.RotationMath;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides where the player looks when more than one thing wants a say.
 *
 * <p>The problem this replaces is a module keeping its own {@code aim} field and
 * writing the head itself. One module doing that is fine. Two is a silent bug:
 * whichever ran last that tick wins, the outcome depends on subscription order,
 * and there is nowhere to look to find out who took the head. Modules now
 * <em>ask</em>, and one place answers.
 *
 * <pre>{@code
 * // in a module's tick handler -- ask every tick for as long as you want it
 * Core.rotations().request(this, eye.rotationTo(target), RotationPriority.HIGH, 30f);
 *
 * // in the adapter, once, wherever rotations are written
 * Core.rotations().apply();
 * }</pre>
 *
 * <h2>Arbitration</h2>
 *
 * <p>Highest {@link RotationPriority} wins. Equal priorities go to whoever
 * acquired the rotation first and keep going there for as long as that module
 * keeps asking &mdash; so two modules on the same priority do not flicker between
 * each other tick by tick, which is the failure the obvious "most recent request
 * wins" rule produces.
 *
 * <p>A claim expires if it is not renewed (see {@link RotationRequest}), so a
 * module that is switched off, returns early or throws loses the head on the next
 * tick without having to remember to release it.
 *
 * <h2>There is no call ordering to get wrong</h2>
 *
 * <p>The obvious design resolves the rotation on the tick event, which means it
 * has to run after every module has filed &mdash; and "after every module" is not
 * something a priority can express, because a module is free to use any priority
 * too. Get it wrong and every rotation is one tick stale, which is subtle to
 * diagnose and very visible in game.
 *
 * <p>So resolution is lazy instead. {@link #beginTick()} only opens a window: it
 * expires stale claims and marks the resolution invalid. The rotation is computed
 * on the first <em>read</em> after that, which is by definition after whoever
 * wrote. Filing a claim invalidates the result again, so a late request is still
 * picked up, and the turn always steps from the rotation held at the start of the
 * tick &mdash; so reading twice, or reading before a module has asked, cannot
 * advance the turn twice or land on a different answer.
 *
 * <p>The practical consequence: <b>read and request in any order you like.</b>
 * The only thing an adapter has to place deliberately is {@link #apply()}, and
 * placing it badly costs nothing but a tick of latency on that one call.
 *
 * <h2>Releasing</h2>
 *
 * <p>When the last claim goes, the rotation eases back to where the player is
 * actually looking at {@link #setReleaseStep(float)} degrees a tick rather than
 * snapping. In {@link RotationMode#CLIENT} that is instant and costs nothing,
 * because the camera is already where the service left it; in
 * {@link RotationMode#SILENT} it is what stops the server-side head teleporting.
 *
 * <p>Inert with no {@link RotationSink} installed: claims are still accepted and
 * arbitrated, nothing is ever applied, and {@link #isActive()} stays false. A
 * client that does not want Core touching rotations installs no sink and pays
 * nothing.
 *
 * <p>Game thread only, like every other per-tick path in the library. File claims
 * from background work through {@link dev.px.core.concurrent.ThreadService#sync}.
 */
public final class RotationService implements Service {

    /** How close counts as arrived when easing back, in degrees. */
    private static final float RELEASE_EPSILON = 0.05f;

    /**
     * Resolutions between ticks before the service says nobody is opening them.
     *
     * <p>The one way to misconfigure this: an adapter that posts no
     * {@link TickEvent} and never calls {@link #beginTick()} leaves claims that
     * never expire and a turn that never advances. Nothing throws, so it has to be
     * reported.
     */
    private static final int RESOLVES_WITHOUT_TICK_WARN = 600;

    private final CoreLogger logger;
    private final EventBus bus;

    /** Live claims. A handful at most, so insertion order and a linear scan are right. */
    private final List<Lease> leases = new ArrayList<>();

    /**
     * Reads and writes the player. Installed by the adapter; null leaves the
     * service inert.
     */
    @Getter
    @Setter
    private RotationSink sink;

    /**
     * In-game mouse sensitivity, 0..1, or negative to leave rotations unsnapped.
     *
     * <p>When set, resolved rotations are rounded to a multiple of the smallest
     * turn that sensitivity can produce &mdash; see
     * {@link RotationMath#snapToSensitivity}. Core cannot read the game's options,
     * so the adapter pushes it; leaving it negative simply skips the step.
     */
    @Getter
    private float sensitivity = -1f;

    /** Degrees a tick the rotation eases back over once the last claim expires. */
    @Getter
    private float releaseStep = 15f;

    private Subscription tickSubscription;

    // ---- per-tick state -----------------------------------------------------

    private long tick;

    /** The resolved rotation. Also the value {@link #apply()} writes. */
    private Vec2 current;

    private RotationMode mode = RotationMode.CLIENT;

    private boolean active;

    private Lease holder;

    /**
     * State as of {@link #beginTick()}, so a resolution repeated inside one tick
     * starts from the same place and lands on the same answer.
     */
    private Vec2 atTickStart;

    private boolean activeAtTickStart;

    private boolean resolved = true;

    private int resolvesSinceTick;

    private boolean warnedAboutTicks;

    public RotationService(CoreLogger logger, EventBus bus) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
    }

    @Override
    public String getName() {
        return "Rotations";
    }

    @Override
    public void start() {
        // HIGHEST, and only to open the window: this must run before any module
        // files a claim, and resolution deliberately does not happen here.
        tickSubscription = bus.on(TickEvent.class, Priority.HIGHEST, event -> {
            if (event.getStage() == Stage.PRE) {
                beginTick();
            }
        });
        if (sink == null) {
            logger.debug("No RotationSink installed; rotation requests will be arbitrated but never applied");
        }
    }

    @Override
    public void stop() {
        if (tickSubscription != null) {
            tickSubscription.close();
            tickSubscription = null;
        }
        leases.clear();
        current = null;
        holder = null;
        active = false;
        activeAtTickStart = false;
        resolved = true;
    }

    // ------------------------------------------------------------- settings

    /** @param sensitivity the game's mouse sensitivity slider, 0..1, or negative to disable snapping. */
    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
        resolved = false;
    }

    /** @param degreesPerTick how fast the rotation returns to the player's own after the last claim. */
    public void setReleaseStep(float degreesPerTick) {
        Validate.check(degreesPerTick > 0f, "release step must be greater than zero, got " + degreesPerTick);
        this.releaseStep = degreesPerTick;
        resolved = false;
    }

    // ------------------------------------------------------------- requests

    /** Look here, at {@link RotationPriority#NORMAL}, this tick, camera following. */
    public void request(Object owner, Vec2 target) {
        request(owner, target, RotationPriority.NORMAL, RotationRequest.SNAP);
    }

    public void request(Object owner, Vec2 target, int priority) {
        request(owner, target, priority, RotationRequest.SNAP);
    }

    /**
     * The shorthand most modules want: a target, a priority and a turn rate,
     * renewed every tick, with the camera following.
     *
     * <p>Allocates nothing beyond the {@link Vec2} the caller already built. Use
     * {@link #request(Object, RotationRequest)} for a silent rotation or a longer
     * hold.
     *
     * @param owner whatever is asking, usually {@code this}. Identity, not equality
     * @param maxStep degrees a tick, or {@link RotationRequest#SNAP} to arrive at once
     */
    public void request(Object owner, Vec2 target, int priority, float maxStep) {
        Validate.notNull(target, "target");
        Validate.check(maxStep > 0f, "step must be greater than zero, got " + maxStep);
        fill(owner, RotationMath.normalize(target), priority, maxStep, 1, RotationMode.CLIENT);
    }

    /** Files or renews {@code owner}'s claim. */
    public void request(Object owner, RotationRequest request) {
        Validate.notNull(request, "request");
        fill(owner, request.getTarget(), request.getPriority(), request.getMaxStep(),
                request.getHoldTicks(), request.getMode());
    }

    /**
     * Drops {@code owner}'s claim now rather than letting it expire.
     *
     * <p>Optional. Nothing depends on it being called, which is the point of
     * claims expiring; use it when a module knows it is finished mid-tick and the
     * next holder should take over immediately.
     *
     * @return whether there was one to drop
     */
    public boolean release(Object owner) {
        for (int i = 0; i < leases.size(); i++) {
            if (leases.get(i).owner == owner) {
                leases.remove(i);
                resolved = false;
                return true;
            }
        }
        return false;
    }

    /** Drops every claim. The rotation then eases back as it would normally. */
    public void releaseAll() {
        if (!leases.isEmpty()) {
            leases.clear();
            resolved = false;
        }
    }

    public boolean isHeldBy(Object owner) {
        resolve();
        return holder != null && holder.owner == owner;
    }

    /** @return whether {@code owner} has a live claim, whether or not it is winning. */
    public boolean hasRequest(Object owner) {
        Lease lease = leaseOf(owner);
        return lease != null && isLive(lease);
    }

    /** @return how many claims are live this tick. */
    public int getRequestCount() {
        int count = 0;
        for (int i = 0; i < leases.size(); i++) {
            if (isLive(leases.get(i))) {
                count++;
            }
        }
        return count;
    }

    // -------------------------------------------------------------- reading

    /**
     * @return the rotation for this tick
     *
     * <p>The resolved claim while one is winning, the easing rotation while it is
     * handing back, and the player's own rotation the rest of the time &mdash; so
     * this is always safe to read and to draw from.
     */
    public Vec2 getRotation() {
        resolve();
        if (current != null) {
            return current;
        }
        return sink != null ? sink.getRotation() : Vec2.ZERO;
    }

    /** @return whether the service is driving the rotation, including the easing back. */
    public boolean isActive() {
        resolve();
        return active;
    }

    /** @return the mode of the winning claim, or of the one being eased out of. */
    public RotationMode getMode() {
        resolve();
        return mode;
    }

    /** @return whoever's claim won this tick, or null. */
    public Object getHolder() {
        resolve();
        return holder == null ? null : holder.owner;
    }

    /**
     * @return a name for the winning claim, for a debug HUD
     *
     * <p>A {@link Named} owner &mdash; every {@link dev.px.core.module.Module} is
     * one &mdash; names itself; anything else falls back to its class.
     */
    public String getHolderName() {
        Object owner = getHolder();
        if (owner == null) {
            return null;
        }
        return owner instanceof Named ? ((Named) owner).getName() : owner.getClass().getSimpleName();
    }

    // -------------------------------------------------------------- driving

    /**
     * Resolves and writes the rotation to the sink.
     *
     * <p>The adapter's one integration point, called wherever rotations are
     * written &mdash; for most clients, just before the movement packet goes out.
     * Does nothing when no claim is live or no sink is installed.
     *
     * @return whether anything was written
     */
    public boolean apply() {
        resolve();
        if (!active || sink == null || current == null) {
            return false;
        }
        sink.apply(current, mode);
        return true;
    }

    /**
     * Opens a new tick: expires claims that were not renewed and invalidates the
     * last resolution.
     *
     * <p>Wired to {@link TickEvent} at {@link Priority#HIGHEST} and {@link Stage#PRE}
     * on startup, so an adapter that posts ticks needs nothing. One that does not
     * calls this from its game loop, the same way it would
     * {@link dev.px.core.concurrent.ThreadService#runPendingSync()}.
     *
     * <p>Deliberately does not resolve. See the class notes on ordering.
     */
    public void beginTick() {
        tick++;
        // Purged a tick later than they stop counting, so a claim renewed every
        // tick is recognised as the same continuous claim rather than a new one.
        // Deleting on expiry instead would reset acquiredTick every tick and turn
        // the equal-priority tiebreak back into "whoever renewed last", which is
        // the flicker it exists to prevent.
        for (int i = leases.size() - 1; i >= 0; i--) {
            if (leases.get(i).expiresAtTick < tick - 1) {
                leases.remove(i);
            }
        }
        atTickStart = current;
        activeAtTickStart = active;
        resolved = false;
        resolvesSinceTick = 0;
    }

    // ------------------------------------------------------------ internals

    private void fill(Object owner, Vec2 target, int priority, float maxStep,
                      int holdTicks, RotationMode mode) {
        Validate.notNull(owner, "owner");
        Lease lease = leaseOf(owner);
        if (lease == null) {
            // acquiredTick is set once and survives renewal, which is what makes
            // the equal-priority tiebreak stable instead of flapping.
            lease = new Lease(owner, tick);
            leases.add(lease);
        }
        lease.target = target;
        lease.priority = priority;
        lease.maxStep = maxStep;
        lease.mode = mode;
        lease.expiresAtTick = tick + holdTicks - 1;
        resolved = false;
    }

    private Lease leaseOf(Object owner) {
        for (int i = 0; i < leases.size(); i++) {
            if (leases.get(i).owner == owner) {
                return leases.get(i);
            }
        }
        return null;
    }

    /** Highest priority, then earliest acquirer, then registration order. */
    private Lease pick() {
        Lease best = null;
        for (int i = 0; i < leases.size(); i++) {
            Lease candidate = leases.get(i);
            if (!isLive(candidate)) {
                continue;
            }
            if (best == null
                    || candidate.priority > best.priority
                    || (candidate.priority == best.priority
                        && candidate.acquiredTick < best.acquiredTick)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * @return whether this claim counts for the current tick.
     *
     * <p>A lapsed lease lingers for one tick so its identity survives a renewal;
     * it takes no part in arbitration and is not counted as a request.
     */
    private boolean isLive(Lease lease) {
        return lease.expiresAtTick >= tick;
    }

    /**
     * Computes this tick's rotation, once.
     *
     * <p>Idempotent within a tick and re-runnable after any change, because it
     * always starts from the rotation held when the tick opened rather than from
     * whatever it last produced. That is what makes reads and requests
     * order-independent.
     */
    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        countResolve();

        if (sink == null) {
            active = false;
            holder = null;
            return;
        }

        Vec2 player = sink.getRotation();
        if (player == null) {
            player = Vec2.ZERO;
        }
        Vec2 from = activeAtTickStart && atTickStart != null ? atTickStart : player;

        Lease best = pick();
        if (best != null) {
            Vec2 stepped = best.maxStep >= RotationRequest.SNAP
                    ? best.target
                    : RotationMath.step(from, best.target, best.maxStep);
            if (sensitivity >= 0f) {
                stepped = RotationMath.snapToSensitivity(from, stepped, sensitivity);
            }
            current = RotationMath.normalize(stepped);
            mode = best.mode;
            holder = best;
            active = true;
            return;
        }

        holder = null;
        if (!activeAtTickStart) {
            // Nothing held it when the tick opened and nothing holds it now, so
            // there is nothing to hand back.
            current = player;
            active = false;
            return;
        }

        // Easing back. Tested before stepping rather than after, so the step that
        // lands on the player's rotation is still applied -- checking afterwards
        // would go inactive on that tick and leave the last few degrees unsent.
        // In CLIENT mode the sink already moved the camera to where the service
        // left it, so this is true immediately and the easing costs nothing.
        if (RotationMath.difference(from, player) <= RELEASE_EPSILON) {
            current = player;
            active = false;
            return;
        }
        Vec2 stepped = releaseStep >= RotationRequest.SNAP
                ? player
                : RotationMath.step(from, player, releaseStep);
        current = RotationMath.normalize(stepped);
        active = true;
    }

    private void countResolve() {
        if (warnedAboutTicks || ++resolvesSinceTick <= RESOLVES_WITHOUT_TICK_WARN) {
            return;
        }
        warnedAboutTicks = true;
        logger.warn("RotationService has resolved " + resolvesSinceTick + " times without a tick;"
                + " nothing is posting TickEvent or calling beginTick(), so claims will never expire");
    }

    /** One owner's live claim. Mutable and reused, because it is renewed every tick. */
    private static final class Lease {

        private final Object owner;

        /** Set once, kept across renewals, so the equal-priority tiebreak is stable. */
        private final long acquiredTick;

        private Vec2 target;
        private int priority;
        private float maxStep;
        private RotationMode mode;
        private long expiresAtTick;

        private Lease(Object owner, long acquiredTick) {
            this.owner = owner;
            this.acquiredTick = acquiredTick;
        }
    }
}
