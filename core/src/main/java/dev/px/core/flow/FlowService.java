package dev.px.core.flow;

import dev.px.core.control.ControlService;
import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscription;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.flow.view.FlowView;
import dev.px.core.flow.view.StepView;
import dev.px.core.memory.Memory;
import dev.px.core.movement.rotation.RotationService;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Runs flows: any number at once, each with a priority, sharing the player's
 * controls; and reflexes, which take over whatever they need when their moment
 * comes.
 *
 * <pre>{@code
 * FlowHandle bot = Core.flows().start("bot", flow, 50);
 * FlowHandle fire = Core.flows().reflex("put out fire", onFire, 90, new Extinguish());
 *
 * for (FlowView view : Core.flows().view()) { ... }        // the live view
 * }</pre>
 *
 * <h2>Each tick</h2>
 *
 * <p>On {@link TickEvent} {@link Stage#PRE}, after the controls and rotations have
 * opened the tick, reflexes are checked first: one whose condition holds fires,
 * pausing every flow below its priority whose running steps
 * {@linkplain Step#uses use} a control its step uses &mdash; and only those;
 * flows needing other controls carry on. When the reflex finishes, they resume,
 * rewinding to any step whose {@linkplain Step#ensures work} no longer holds.
 * Then every running flow is ticked, highest priority first.
 *
 * <p>Claims are filed with the flow as the owner, at its priority, and last one
 * tick, so a paused or finished flow lets go of the controls on its own; it is
 * also released at once.
 *
 * <h2>Leaving the world</h2>
 *
 * <p>When the player leaves the world every flow pauses, and when they join one
 * every flow resumes, rewinding as it does. Nothing is kept past a restart.
 *
 * <h2>Services for steps</h2>
 *
 * <p>Steps reach services through {@link FlowContext#service}, from what the host
 * {@linkplain #provide provides}: Core provides its own, and the test kit's
 * sandbox provides its own instead, so a step written against the context runs in
 * both.
 *
 * <p>Game thread only.
 */
public final class FlowService implements Service {

    /** Step changes one flow may make in one tick, by default. A tuning knob. */
    public static final int DEFAULT_CHANGES_PER_TICK = 64;

    /** Rewinds one sequence may make before it fails, by default. A tuning knob. */
    public static final int DEFAULT_MAX_REWINDS = 16;

    private final CoreLogger logger;
    private final EventBus bus;
    private final ControlService controls;
    private final RotationService rotations;
    private final Memory memory;
    private final LongSupplier nanos;
    private final Map<Class<?>, Object> services = new HashMap<>();
    private final List<FlowHandle> flows = new ArrayList<>();
    private final List<FlowHandle> reflexes = new ArrayList<>();
    /** Flows that have ended, kept for the live view until {@link #clearFinished()}. */
    private final List<FlowHandle> finished = new ArrayList<>();
    private final Set<String> warned = new HashSet<>();
    private final List<Subscription> subscriptions = new ArrayList<>();

    private long tick;
    private int changesPerTick = DEFAULT_CHANGES_PER_TICK;
    private int maxRewinds = DEFAULT_MAX_REWINDS;
    private boolean inWorld = true;
    private int unnamed;

    public FlowService(CoreLogger logger, EventBus bus, ControlService controls, RotationService rotations,
                       Memory memory) {
        this(logger, bus, controls, rotations, memory, System::nanoTime);
    }

    /** @param nanos the clock wall-clock spans are measured against */
    public FlowService(CoreLogger logger, EventBus bus, ControlService controls, RotationService rotations,
                       Memory memory, LongSupplier nanos) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        this.controls = Validate.notNull(controls, "controls");
        this.rotations = Validate.notNull(rotations, "rotations");
        this.memory = Validate.notNull(memory, "memory");
        this.nanos = Validate.notNull(nanos, "nanos");
    }

    @Override
    public String getName() {
        return "Flows";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Service>[] dependsOn() {
        return new Class[] {ControlService.class, RotationService.class, Memory.class};
    }

    @Override
    public void start() {
        // HIGH, below the HIGHEST that opens the controls' and rotations' tick, so
        // claims filed here are this tick's.
        subscriptions.add(bus.on(TickEvent.class, Priority.HIGH, event -> {
            if (event.getStage() == Stage.PRE) {
                tick();
            }
        }));
        subscriptions.add(bus.on(WorldEvent.class, event -> {
            if (event.isUnloaded()) {
                leftWorld();
            } else {
                joinedWorld();
            }
        }));
    }

    @Override
    public void stop() {
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
        cancelAll();
    }

    // ------------------------------------------------------------ starting

    /** Starts {@code root} as a flow, named after it. */
    public FlowHandle start(Step root, int priority) {
        Validate.notNull(root, "root");
        return start(root.getName() + " #" + (++unnamed), root, priority);
    }

    /**
     * Starts {@code root} as a flow. Its root step begins, and is first ticked,
     * on the next flow pass.
     *
     * @param priority what its claims are filed at, on
     *        {@link dev.px.core.movement.rotation.RotationPriority}'s scale
     */
    public FlowHandle start(String name, Step root, int priority) {
        Validate.notBlank(name, "name");
        Validate.notNull(root, "root");
        Validate.check(!inUse(root), "step '" + root.getName() + "' is already running in a flow; build another");
        FlowHandle flow = new FlowHandle(this, name, root, priority, null);
        flows.add(flow);
        if (!inWorld) {
            flow.pause(FlowHandle.BY_WORLD);
        }
        return flow;
    }

    /**
     * Registers a reflex: whenever {@code when} holds and it is not already
     * firing, {@code step} runs, pausing the flows below {@code priority} that
     * need the controls it {@linkplain Step#uses uses}, until it finishes.
     *
     * @return its handle; {@link FlowHandle#cancel()} removes it
     */
    public FlowHandle reflex(String name, Condition when, int priority, Step step) {
        Validate.notBlank(name, "name");
        Validate.notNull(when, "when");
        Validate.notNull(step, "step");
        FlowHandle reflex = new FlowHandle(this, name, step, priority, when);
        reflexes.add(reflex);
        return reflex;
    }

    public FlowHandle reflex(Condition when, int priority, Step step) {
        Validate.notNull(step, "step");
        return reflex(step.getName() + " #" + (++unnamed), when, priority, step);
    }

    // ------------------------------------------------------------ ticking

    /**
     * Fires reflexes and ticks every running flow. Wired to {@link TickEvent} on
     * start; call it yourself if nothing posts ticks.
     */
    public void tick() {
        tick++;
        if (!inWorld) {
            return;
        }
        for (FlowHandle reflex : new ArrayList<>(reflexes)) {
            if (reflex.getState() == FlowHandle.State.IDLE && test(reflex)) {
                fire(reflex);
            }
            if (reflex.getState() == FlowHandle.State.RUNNING) {
                runReflex(reflex);
            }
        }
        List<FlowHandle> order = new ArrayList<>(flows);
        order.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        for (FlowHandle flow : order) {
            if (flow.getState() == FlowHandle.State.RUNNING) {
                flow.tick(tick, changesPerTick);
                if (flow.isDone()) {
                    flows.remove(flow);
                    finished.add(flow);
                }
            }
        }
    }

    private boolean test(FlowHandle reflex) {
        try {
            return reflex.getTrigger().test(reflex.context());
        } catch (RuntimeException thrown) {
            warnOnce("reflex:" + reflex.getName(), "Reflex '" + reflex.getName() + "' threw testing its condition: "
                    + thrown);
            return false;
        }
    }

    private void fire(FlowHandle reflex) {
        Set<Control> needs = reflex.getRoot().declaredControls();
        reflex.paused.clear();
        for (FlowHandle flow : flows) {
            if (flow.getPriority() < reflex.getPriority() && flow.getState() == FlowHandle.State.RUNNING
                    && overlaps(flow.getRoot().activeControls(), needs)) {
                flow.pause(reflex);
                reflex.paused.add(flow);
            }
        }
        reflex.fire(tick, changesPerTick);
    }

    private void runReflex(FlowHandle reflex) {
        FlowContext c = reflex.context();
        Status status = reflex.getRoot().run(c);
        if (status == Status.RUNNING) {
            return;
        }
        reflex.settle(status);
        for (FlowHandle flow : reflex.paused) {
            flow.resume(reflex);
        }
        reflex.paused.clear();
    }

    private static boolean overlaps(Set<Control> a, Set<Control> b) {
        Set<Control> both = EnumSet.noneOf(Control.class);
        both.addAll(a);
        both.retainAll(b);
        return !both.isEmpty();
    }

    // -------------------------------------------------------------- world

    private void leftWorld() {
        if (!inWorld) {
            return;
        }
        inWorld = false;
        for (FlowHandle flow : new ArrayList<>(flows)) {
            flow.pause(FlowHandle.BY_WORLD);
        }
        for (FlowHandle reflex : reflexes) {
            if (reflex.getState() == FlowHandle.State.RUNNING) {
                reflex.abandon();
                for (FlowHandle flow : reflex.paused) {
                    flow.resume(reflex);
                }
                reflex.paused.clear();
            }
        }
    }

    private void joinedWorld() {
        if (inWorld) {
            return;
        }
        inWorld = true;
        for (FlowHandle flow : new ArrayList<>(flows)) {
            flow.resume(FlowHandle.BY_WORLD);
        }
    }

    /** @return whether flows are running: false between leaving a world and joining one */
    public boolean isInWorld() {
        return inWorld;
    }

    // ------------------------------------------------------------ reading

    /** @return the flows started and not yet over */
    public List<FlowHandle> getFlows() {
        return Collections.unmodifiableList(flows);
    }

    public List<FlowHandle> getReflexes() {
        return Collections.unmodifiableList(reflexes);
    }

    /** @return the running flow called {@code name}, or null */
    public FlowHandle find(String name) {
        for (FlowHandle flow : flows) {
            if (flow.getName().equals(name)) {
                return flow;
            }
        }
        return null;
    }

    /** @return every flow, then every reflex, then every flow that has ended since the last {@link #clearFinished()} */
    public List<FlowView> view() {
        List<FlowView> views = new ArrayList<>();
        for (FlowHandle flow : flows) {
            views.add(view(flow));
        }
        for (FlowHandle reflex : reflexes) {
            views.add(view(reflex));
        }
        for (FlowHandle flow : finished) {
            views.add(view(flow));
        }
        return views;
    }

    /** Forgets flows that have ended, so the view shows only what is current. */
    public void clearFinished() {
        finished.clear();
    }

    public void cancelAll() {
        for (FlowHandle flow : new ArrayList<>(flows)) {
            flow.cancel();
        }
        for (FlowHandle reflex : new ArrayList<>(reflexes)) {
            reflex.cancel();
        }
    }

    public long getTick() {
        return tick;
    }

    long nanos() {
        return nanos.getAsLong();
    }

    // ----------------------------------------------------------- services

    /** Makes {@code service} reachable from steps through {@link FlowContext#service}. */
    public <S> void provide(Class<S> type, S service) {
        Validate.notNull(type, "type");
        services.put(type, Validate.notNull(service, "service"));
    }

    @SuppressWarnings("unchecked")
    <S> S service(Class<S> type) {
        return (S) services.get(type);
    }

    ControlService controls() {
        return controls;
    }

    RotationService rotations() {
        return rotations;
    }

    Memory memory() {
        return memory;
    }

    EventBus bus() {
        return bus;
    }

    CoreLogger logger() {
        return logger;
    }

    // ----------------------------------------------------------- settings

    public int getChangesPerTick() {
        return changesPerTick;
    }

    /** How many times one flow may move on to another step in one tick before waiting for the next. */
    public void setChangesPerTick(int changes) {
        Validate.check(changes >= 1, "at least one change a tick, got " + changes);
        this.changesPerTick = changes;
    }

    public int getMaxRewinds() {
        return maxRewinds;
    }

    /**
     * How many times one sequence may rewind before it fails, saying what keeps
     * being lost: the guard against a step whose {@code ensures} flickers.
     */
    public void setMaxRewinds(int rewinds) {
        Validate.check(rewinds >= 0, "maxRewinds must not be negative, got " + rewinds);
        this.maxRewinds = rewinds;
    }

    // ----------------------------------------------------------- internals

    void letGo(FlowHandle flow) {
        controls.release(flow);
        rotations.release(flow);
    }

    void removed(FlowHandle flow) {
        if (flows.remove(flow)) {
            finished.add(flow);
        }
        if (reflexes.remove(flow)) {
            for (FlowHandle paused : flow.paused) {
                paused.resume(flow);
            }
            flow.paused.clear();
        }
    }

    void warnOnce(String key, String message) {
        if (warned.add(key)) {
            logger.warn(message);
        }
    }

    private boolean inUse(Step root) {
        for (FlowHandle flow : flows) {
            if (flow.getRoot() == root) {
                return true;
            }
        }
        return false;
    }

    FlowView view(FlowHandle flow) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (Map.Entry<Key<?>, Object> entry : flow.data.entrySet()) {
            keys.put(entry.getKey().getName(), String.valueOf(entry.getValue()));
        }
        return new FlowView(flow.getName(), flow.getPriority(), flow.getState().name(), flow.getReason(),
                flow.isReflex(), flow.getStartedTick(), keys, view(flow.getRoot()));
    }

    private static StepView view(Step step) {
        List<StepView> children = new ArrayList<>();
        for (Step child : step.children()) {
            children.add(view(child));
        }
        Set<String> uses = new LinkedHashSet<>();
        for (Control control : step.getUses()) {
            uses.add(control.name());
        }
        return new StepView(step.getName(), step.state, step.reason, uses, step.starts, step.ticks,
                step.nanos / 1000L, step.startedTick, step.finishedTick,
                new LinkedHashSet<>(step.keysRead), new LinkedHashSet<>(step.keysWritten),
                new LinkedHashSet<>(step.facts), children);
    }
}
