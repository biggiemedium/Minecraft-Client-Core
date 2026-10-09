package dev.px.core.flow;

import dev.px.core.flow.view.FlowView;
import dev.px.core.registry.Named;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A flow that has been started, or a reflex that is waiting to fire.
 *
 * <pre>{@code
 * FlowHandle bot = Core.flows().start("bot", flow, RotationPriority.NORMAL);
 * bot.pause();
 * bot.resume();          // rewinds to any step whose ensures no longer holds
 * bot.cancel();
 * if (bot.getState() == FlowHandle.State.FAILED) log(bot.getReason());
 * }</pre>
 *
 * <p>It is also the owner of every claim its steps file, so a rotation or
 * control service names the flow as the holder.
 *
 * <p>Game thread only.
 */
public final class FlowHandle implements Named {

    /** Where a flow stands. */
    public enum State {
        /** Being ticked. */
        RUNNING,
        /** Paused: by you, by a reflex, or because the player left the world. */
        PAUSED,
        /** Its root step finished, and worked. */
        DONE,
        /** Its root step failed; {@link #getReason()} says why. */
        FAILED,
        /** Cancelled. */
        CANCELLED,
        /** A reflex that is not firing right now. */
        IDLE
    }

    /** Who paused a flow: you, or the player leaving the world; reflexes pause as themselves. */
    static final Object BY_HAND = "by hand";
    static final Object BY_WORLD = "the player left the world";

    final FlowService service;
    private final String name;
    private final Step root;
    private final boolean reflex;
    private final Condition trigger;
    private int priority;

    final Map<Key<?>, Object> data = new HashMap<>();
    private final Set<Object> pausers = new LinkedHashSet<>();
    /** For a reflex: the flows it paused this time. */
    final List<FlowHandle> paused = new ArrayList<>();

    private State state;
    private String reason;
    private long startedTick;
    private int changesLeft;
    /** Whether its root step has begun: flows begin on their first flow pass. */
    private boolean begun;

    FlowHandle(FlowService service, String name, Step root, int priority, Condition trigger) {
        this.service = service;
        this.name = name;
        this.root = root;
        this.priority = priority;
        this.trigger = trigger;
        this.reflex = trigger != null;
        this.state = reflex ? State.IDLE : State.RUNNING;
    }

    @Override
    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    /** Changes the priority its claims are filed at, from the next claim. */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    public State getState() {
        return state;
    }

    /** @return why it failed, or what paused it; null otherwise */
    public String getReason() {
        if (state == State.PAUSED && !pausers.isEmpty()) {
            List<String> who = new ArrayList<>();
            for (Object pauser : pausers) {
                who.add(pauser instanceof FlowHandle ? "reflex '" + ((FlowHandle) pauser).getName() + "'" : String.valueOf(pauser));
            }
            return "paused " + String.join(", ", who);
        }
        return reason;
    }

    public Step getRoot() {
        return root;
    }

    public boolean isReflex() {
        return reflex;
    }

    /** @return whether it is over: done, failed or cancelled */
    public boolean isDone() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    /** @return the flow's value for {@code key}, read from outside it; null if none */
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        return (T) data.get(Validate.notNull(key, "key"));
    }

    /** @return what paused it, empty while it runs */
    public Set<Object> getPausers() {
        return Collections.unmodifiableSet(pausers);
    }

    /** Pauses it until {@link #resume()}. A reflex or the world leaving may also be holding it. */
    public void pause() {
        pause(BY_HAND);
    }

    /** Lifts your pause; it runs again once nothing else holds it, rewinding as it resumes. */
    public void resume() {
        resume(BY_HAND);
    }

    /** Stops it for good. */
    public void cancel() {
        if (isDone()) {
            return;
        }
        if (state == State.RUNNING && root.isRunning()) {
            root.end(context(), StopReason.CANCELLED);
        }
        finish(State.CANCELLED, null);
        service.removed(this);
    }

    /** @return a snapshot for the live view */
    public FlowView view() {
        return service.view(this);
    }

    // ----------------------------------------------------------------- engine

    FlowContext context() {
        return new FlowContext(this, root);
    }

    Condition getTrigger() {
        return trigger;
    }

    long getStartedTick() {
        return startedTick;
    }

    void begin(long tick, int changes) {
        begun = true;
        startedTick = tick;
        changesLeft = changes;
        root.begin(context());
    }

    void pause(Object by) {
        if (isDone() || reflex) {
            return;
        }
        boolean wasRunning = pausers.isEmpty();
        pausers.add(by);
        if (wasRunning && state == State.RUNNING) {
            if (begun && root.isRunning()) {
                root.end(context(), StopReason.PAUSED);
            }
            state = State.PAUSED;
            service.letGo(this);
        }
    }

    void resume(Object by) {
        if (!pausers.remove(by) || !pausers.isEmpty() || state != State.PAUSED) {
            return;
        }
        state = State.RUNNING;
        changesLeft = service.getChangesPerTick();
        if (begun) {
            root.begin(context());
        }
    }

    /** Ticks the root step once. */
    void tick(long tick, int changes) {
        if (!begun) {
            begin(tick, changes);
        }
        changesLeft = changes;
        FlowContext c = context();
        Status status = root.run(c);
        if (status == Status.RUNNING) {
            return;
        }
        root.end(c, status == Status.DONE ? StopReason.FINISHED : StopReason.FAILED);
        finish(status == Status.DONE ? State.DONE : State.FAILED, status == Status.FAILED ? root.getReason() : null);
    }

    /** A reflex firing: its step begins. */
    void fire(long tick, int changes) {
        state = State.RUNNING;
        reason = null;
        begin(tick, changes);
    }

    /** A reflex cut short, by the player leaving the world: back to waiting. */
    void abandon() {
        root.end(context(), StopReason.CANCELLED);
        reason = "cut short: the player left the world";
        state = State.IDLE;
        service.letGo(this);
    }

    /** A reflex's step ending: back to waiting. */
    void settle(Status status) {
        root.end(context(), status == Status.DONE ? StopReason.FINISHED : StopReason.FAILED);
        reason = status == Status.FAILED ? root.getReason() : null;
        state = State.IDLE;
        service.letGo(this);
    }

    boolean advance() {
        return changesLeft-- > 0;
    }

    private void finish(State end, String why) {
        state = end;
        reason = why;
        pausers.clear();
        service.letGo(this);
    }

    @Override
    public String toString() {
        return "FlowHandle(" + name + ", " + state + (getReason() == null ? "" : ": " + getReason()) + ")";
    }
}
