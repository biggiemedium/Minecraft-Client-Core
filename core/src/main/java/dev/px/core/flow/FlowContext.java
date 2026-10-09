package dev.px.core.flow;

import dev.px.core.control.Click;
import dev.px.core.control.ControlService;
import dev.px.core.event.EventBus;
import dev.px.core.math.Vec2;
import dev.px.core.memory.Fact;
import dev.px.core.memory.Memory;
import dev.px.core.movement.rotation.RotationRequest;
import dev.px.core.movement.rotation.RotationService;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

/**
 * Everything a {@link Step} reaches while it runs: its flow's data, the
 * controls, shared memory, time, and the services its host provides.
 *
 * <pre>{@code
 * protected Status tick(FlowContext c) {
 *     Tracked<Player> target = c.get(TARGET);
 *     if (target == null) {
 *         return c.fail("no target");
 *     }
 *     c.look(eye.rotationTo(target.getEyePosition()));
 *     c.move(MovementInput.forward(yaw).withSprint(true));
 *     SimulationService simulation = c.service(SimulationService.class);
 *     ...
 * }
 * }</pre>
 *
 * <p><b>Reach services through the context, not through {@code Core}'s
 * statics.</b> The same step then runs unchanged in the game and in the test
 * kit's sandbox, which builds services of its own.
 *
 * <p>Claims made here are filed for the flow, at the flow's priority, and last
 * one tick, so a step keeps a control by claiming it every tick and loses it by
 * stopping. A claim on a control the step did not {@linkplain Step#uses declare}
 * still goes through, and is warned about once.
 *
 * <p>Game thread only.
 */
public final class FlowContext {

    private final FlowHandle flow;
    private final Step step;

    FlowContext(FlowHandle flow, Step step) {
        this.flow = flow;
        this.step = step;
    }

    /** @return a context for a step running inside this one */
    FlowContext of(Step child) {
        return new FlowContext(flow, child);
    }

    // ------------------------------------------------------------------ data

    /** @return the flow's value for {@code key}, or null */
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        Validate.notNull(key, "key");
        step.keysRead.add(key.getName());
        return (T) flow.data.get(key);
    }

    public <T> T getOr(Key<T> key, T fallback) {
        T value = get(key);
        return value != null ? value : fallback;
    }

    public <T> void put(Key<T> key, T value) {
        Validate.notNull(key, "key");
        step.keysWritten.add(key.getName());
        if (value == null) {
            flow.data.remove(key);
        } else {
            flow.data.put(key, value);
        }
    }

    public boolean has(Key<?> key) {
        Validate.notNull(key, "key");
        step.keysRead.add(key.getName());
        return flow.data.containsKey(key);
    }

    // ---------------------------------------------------------------- memory

    /** @return the memory every flow shares */
    public Memory memory() {
        return flow.service.memory();
    }

    /** {@link Memory#recall(Fact, Object)}, noted in the live view. */
    public <T> T recall(Fact<T> fact, Object subject) {
        step.facts.add(fact.getName());
        return memory().recall(fact, subject);
    }

    public <T> T recall(Fact<T> fact) {
        step.facts.add(fact.getName());
        return memory().recall(fact);
    }

    /** {@link Memory#remember(Fact, Object, Object, Span)}, noted in the live view. */
    public <T> void remember(Fact<T> fact, Object subject, T value, Span expiry) {
        step.facts.add(fact.getName());
        memory().remember(fact, subject, value, expiry);
    }

    public <T> void remember(Fact<T> fact, T value) {
        step.facts.add(fact.getName());
        memory().remember(fact, value);
    }

    // -------------------------------------------------------------- controls

    /** Look here this tick, at the flow's priority. */
    public void look(Vec2 rotation) {
        Validate.notNull(rotation, "rotation");
        claimed(Control.ROTATION);
        rotations().request(flow, RotationRequest.at(rotation).priority(flow.getPriority()));
    }

    /** Look as {@code request} says this tick &mdash; its step, hold and mode &mdash; at the flow's priority. */
    public void look(RotationRequest request) {
        Validate.notNull(request, "request");
        claimed(Control.ROTATION);
        rotations().request(flow, request.priority(flow.getPriority()));
    }

    /** Hold these keys this tick, at the flow's priority. */
    public void move(MovementInput input) {
        Validate.notNull(input, "input");
        claimed(Control.MOVEMENT);
        controls().move(flow, input, flow.getPriority());
    }

    /** Click {@code button} once this tick, at the flow's priority. */
    public void click(Click button) {
        Validate.notNull(button, "button");
        claimed(button == Click.ATTACK ? Control.ATTACK : Control.USE);
        controls().press(flow, button, flow.getPriority());
    }

    /** Hold {@code button} down this tick, at the flow's priority. */
    public void hold(Click button) {
        Validate.notNull(button, "button");
        claimed(button == Click.ATTACK ? Control.ATTACK : Control.USE);
        controls().hold(flow, button, flow.getPriority());
    }

    public ControlService controls() {
        return flow.service.controls();
    }

    public RotationService rotations() {
        return flow.service.rotations();
    }

    // -------------------------------------------------------------- services

    /**
     * @return a service the host provided, such as the simulation or the
     *         entity service; null if it provides none of that type
     */
    public <S> S service(Class<S> type) {
        return flow.service.service(type);
    }

    public EventBus bus() {
        return flow.service.bus();
    }

    public CoreLogger logger() {
        return flow.service.logger();
    }

    // ------------------------------------------------------------------ time

    /** @return ticks the flow service has counted */
    public long tick() {
        return flow.service.getTick();
    }

    /** @return the service's clock, in nanoseconds, for {@link Span#hasPassed} */
    public long nanos() {
        return flow.service.nanos();
    }

    // ----------------------------------------------------------------- state

    /** @return whether this start follows a pause: carry on, rather than begin again */
    public boolean isResuming() {
        return step.resuming;
    }

    /** @return {@link Status#FAILED}, with {@code reason} kept for the live view and the parent */
    public Status fail(String reason) {
        step.reason = Validate.notNull(reason, "reason");
        return Status.FAILED;
    }

    /** @return the priority the flow's claims are filed at */
    public int priority() {
        return flow.getPriority();
    }

    /** @return the flow this step runs in */
    public FlowHandle flow() {
        return flow;
    }

    // -------------------------------------------------------------- internals

    /** Spends one of the flow's step changes for this tick. @return false once they are used up */
    boolean advance() {
        return flow.advance();
    }

    int maxRewinds() {
        return flow.service.getMaxRewinds();
    }

    void warnOnce(Step where, String what, RuntimeException thrown) {
        flow.service.warnOnce(where.getClass().getName() + ":" + where.getName() + "#" + what,
                "Step '" + where.getName() + "' in flow '" + flow.getName() + "' threw in " + what + "(): " + thrown);
    }

    private void claimed(Control control) {
        if (!step.getUses().contains(control)) {
            flow.service.warnOnce(step.getClass().getName() + ":" + step.getName() + "#uses" + control,
                    "Step '" + step.getName() + "' claims " + control + " without declaring it in uses(...);"
                            + " a reflex cannot pause its flow before they fight over it");
        }
    }
}
