package dev.px.core.flow;

import dev.px.core.flow.view.StepState;
import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One thing a flow does: walk somewhere, mine a block, wait for a message.
 *
 * <pre>{@code
 * final class Mine extends Step {
 *
 *     private final Vec3i pos;
 *
 *     Mine(Vec3i pos) {
 *         this.pos = pos;
 *         uses(Control.ROTATION, Control.ATTACK);
 *     }
 *
 *     protected void start(FlowContext c)  { }
 *     protected Status tick(FlowContext c) {
 *         c.look(eye.rotationTo(pos.center()));
 *         c.hold(Click.ATTACK);
 *         return broken(pos) ? Status.DONE : Status.RUNNING;
 *     }
 *     protected void stop(FlowContext c, StopReason why) { }
 *     protected double progress(FlowContext c) { return breakProgress(pos); }   // for stuckAfter
 * }
 *
 * Step mine = new Mine(pos).ensures(c -> broken(pos)).retry(2);
 * }</pre>
 *
 * <h2>Its life</h2>
 *
 * <p>{@link #start} once, then {@link #tick} every tick until it returns
 * {@link Status#DONE} or {@link Status#FAILED}, then {@link #stop} with why. A step
 * paused for something more urgent is stopped with {@link StopReason#PAUSED} and
 * later started again, with {@link FlowContext#isResuming()} true; keep anything
 * it needs to carry on in fields.
 *
 * <p>A step that throws fails, with the exception as its reason, and the flow
 * carries on as it would for any failure.
 *
 * <h2>Controls</h2>
 *
 * <p>Declare the controls a step claims with {@link #uses}. A reflex pauses only
 * the flows whose running steps use what it uses, and it can only know before
 * they fight if they said so. A claim on a control the step did not declare
 * still goes through, and is warned about once.
 *
 * <h2>Shaping it</h2>
 *
 * <p>Every method below returns a new step around this one, so they chain:
 * {@link #ensures}, {@link #until}, {@link #when}, {@link #retry}, {@link #orElse},
 * {@link #timeout}, {@link #stuckAfter}, {@link #interrupt} and {@link #then}.
 *
 * <p>A step instance runs in one place at a time: it keeps its own state in
 * fields, so build a new one for each place it is used.
 *
 * <p>Game thread only.
 */
public abstract class Step {

    private String name;
    private final Set<Control> uses = EnumSet.noneOf(Control.class);

    // ---- what the live view shows, kept by the engine -------------------------

    StepState state = StepState.IDLE;
    String reason;
    boolean resuming;
    /** Set when start threw, so the first tick fails where a parent hears it. */
    boolean startFailed;
    long startedTick = -1L;
    long finishedTick = -1L;
    int starts;
    int ticks;
    long nanos;
    final Set<String> keysRead = new LinkedHashSet<>();
    final Set<String> keysWritten = new LinkedHashSet<>();
    final Set<String> facts = new LinkedHashSet<>();

    protected Step() {
        String simple = getClass().getSimpleName();
        this.name = simple.isEmpty() ? "step" : simple;
    }

    protected Step(String name) {
        this.name = Validate.notBlank(name, "name");
    }

    // ------------------------------------------------------------ yours to write

    /** Called when the step begins, and again when it resumes after a pause. */
    protected void start(FlowContext c) {
    }

    /** Called every tick until it returns {@link Status#DONE} or {@link Status#FAILED}. */
    protected abstract Status tick(FlowContext c);

    /** Called once it is over, or paused: see {@link StopReason}. */
    protected void stop(FlowContext c, StopReason why) {
    }

    /**
     * @return a number that changes while the step gets somewhere &mdash; blocks
     *         left, a block's break progress &mdash; for {@link #stuckAfter};
     *         NaN, the default, for a step that does not report one
     */
    protected double progress(FlowContext c) {
        return Double.NaN;
    }

    /** Declares controls this step claims. Call from the constructor. @return this step */
    protected Step uses(Control... controls) {
        Validate.notNull(controls, "controls");
        Collections.addAll(uses, controls);
        return this;
    }

    // ---------------------------------------------------------------- reading

    public String getName() {
        return name;
    }

    /** @return this step, renamed for the live view and failure reasons */
    public Step named(String name) {
        this.name = Validate.notBlank(name, "name");
        return this;
    }

    /** @return the controls this step declared, not counting any steps inside it */
    public Set<Control> getUses() {
        return Collections.unmodifiableSet(uses);
    }

    /** @return why it last failed or was paused; null otherwise */
    public String getReason() {
        return reason;
    }

    public StepState getState() {
        return state;
    }

    // ---------------------------------------------------------------- shaping

    /** @return this step, then {@code next} */
    public Step then(Step next) {
        return Flow.sequence(this, next);
    }

    /**
     * @return this step, finishing early and successfully as soon as
     *         {@code done} holds; it is stopped as {@link StopReason#CANCELLED}
     */
    public Step until(Condition done) {
        return new Steps.Until(this, done);
    }

    /** @return this step, if {@code condition} holds when it would start; failed otherwise, so a {@code firstOf} moves on */
    public Step when(Condition condition) {
        return new Steps.When(this, condition);
    }

    /**
     * @return this step, declaring what it achieves: when it finishes,
     *         {@code achieved} must hold or the step fails; and when its flow
     *         resumes after a pause, it runs again if {@code achieved} no longer holds
     */
    public Step ensures(Condition achieved) {
        return new Steps.Ensured(this, achieved);
    }

    /** @return this step, started again up to {@code times} more times when it fails */
    public Step retry(int times) {
        return new Steps.Retry(this, times);
    }

    /** @return this step, or {@code fallback} if it fails */
    public Step orElse(Step fallback) {
        return new Steps.OrElse(this, fallback);
    }

    /** @return this step, failed if it has not finished within {@code limit}, pauses included */
    public Step timeout(Span limit) {
        return new Steps.Timeout(this, limit);
    }

    /**
     * @return this step, paused for {@code recovery} whenever the step running
     *         inside it reports the same {@link #progress} for {@code limit}; it
     *         resumes once recovery is done. Steps that report no progress are
     *         never called stuck: give those a {@link #timeout}
     */
    public Step stuckAfter(Span limit, Step recovery) {
        return new Steps.StuckAfter(this, limit, recovery);
    }

    /**
     * @return this step, paused for {@code handler} whenever {@code when} holds,
     *         and resumed after it &mdash; rewinding to any step whose
     *         {@link #ensures} no longer holds
     */
    public Step interrupt(Condition when, Step handler) {
        return new Steps.Interrupt(this, when, handler);
    }

    // ---------------------------------------------------------------- engine

    /** Starts it, keeping the record the live view reads. */
    final void begin(FlowContext c) {
        resuming = state == StepState.PAUSED;
        state = StepState.RUNNING;
        reason = null;
        if (!resuming) {
            startedTick = c.tick();
            finishedTick = -1L;
            ticks = 0;
            nanos = 0L;
            // Begun afresh, so nothing inside it is carrying on from a pause.
            for (Step child : children()) {
                child.forgetPause();
            }
        }
        starts++;
        startFailed = false;
        try {
            start(c);
        } catch (RuntimeException thrown) {
            reason = threw(thrown);
            startFailed = true;
            c.warnOnce(this, "start", thrown);
        }
    }

    /** Ticks it, failing it rather than the flow if it throws. */
    final Status run(FlowContext c) {
        if (startFailed) {
            startFailed = false;
            return Status.FAILED;
        }
        long began = System.nanoTime();
        Status status;
        try {
            status = tick(c);
            if (status == null) {
                reason = "returned no status";
                status = Status.FAILED;
            }
        } catch (RuntimeException thrown) {
            reason = threw(thrown);
            c.warnOnce(this, "tick", thrown);
            status = Status.FAILED;
        }
        ticks++;
        nanos += System.nanoTime() - began;
        if (status == Status.FAILED && reason == null) {
            reason = "failed";
        }
        return status;
    }

    /** Stops it, keeping the record. */
    final void end(FlowContext c, StopReason why) {
        switch (why) {
            case FINISHED:
                state = StepState.DONE;
                break;
            case FAILED:
                state = StepState.FAILED;
                break;
            case PAUSED:
                state = StepState.PAUSED;
                break;
            default:
                state = StepState.CANCELLED;
                break;
        }
        if (why != StopReason.PAUSED) {
            finishedTick = c.tick();
        }
        try {
            stop(c, why);
        } catch (RuntimeException thrown) {
            c.warnOnce(this, "stop", thrown);
        }
    }

    boolean isRunning() {
        return state == StepState.RUNNING;
    }

    /** Clears a pause here and below, so the next start is a fresh one. */
    void forgetPause() {
        if (state == StepState.PAUSED) {
            state = StepState.IDLE;
        }
        for (Step child : children()) {
            child.forgetPause();
        }
    }

    /** @return the steps inside this one, for the live view */
    List<Step> children() {
        return Collections.emptyList();
    }

    /** @return what this step ensures; wrappers pass on the step they wrap */
    Condition ensuresCondition() {
        return null;
    }

    /** @return the controls this step and every step inside it declare */
    Set<Control> declaredControls() {
        Set<Control> all = EnumSet.noneOf(Control.class);
        all.addAll(uses);
        for (Step child : children()) {
            all.addAll(child.declaredControls());
        }
        return all;
    }

    /** @return the controls the steps running right now declare */
    Set<Control> activeControls() {
        Set<Control> all = EnumSet.noneOf(Control.class);
        if (!isRunning()) {
            return all;
        }
        all.addAll(uses);
        for (Step child : children()) {
            all.addAll(child.activeControls());
        }
        return all;
    }

    /** @return the name of the step itself, without what it is wrapped in */
    String baseName() {
        return name;
    }

    /** @return the innermost step running, for telling progress apart between steps */
    Step activeLeaf() {
        return this;
    }

    /** @return the progress of whatever is running inside this step */
    double currentProgress(FlowContext c) {
        try {
            return progress(c);
        } catch (RuntimeException thrown) {
            return Double.NaN;
        }
    }

    private static String threw(RuntimeException thrown) {
        return "threw " + thrown.getClass().getSimpleName()
                + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
    }

    @Override
    public String toString() {
        return name + " (" + state.name().toLowerCase() + (reason == null ? "" : ": " + reason) + ")";
    }
}
