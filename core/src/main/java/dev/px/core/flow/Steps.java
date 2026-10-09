package dev.px.core.flow;

import dev.px.core.flow.view.StepState;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The steps behind {@link Flow}'s factories and {@link Step}'s shaping methods. */
final class Steps {

    private Steps() {
    }

    static Step[] copy(Step[] steps) {
        Validate.notNull(steps, "steps");
        Validate.check(steps.length > 0, "at least one step is needed");
        for (Step step : steps) {
            Validate.notNull(step, "step");
        }
        return Arrays.copyOf(steps, steps.length);
    }

    // ================================================================ wrappers

    /** One step around another, passing on what it does not change. */
    abstract static class Wrapper extends Step {

        final Step inner;
        boolean innerActive;

        Wrapper(Step inner, String decoration) {
            super(Validate.notNull(inner, "step").getName() + " (" + decoration + ")");
            this.inner = inner;
        }

        @Override
        protected void start(FlowContext c) {
            startInner(c);
        }

        @Override
        protected Status tick(FlowContext c) {
            return runInner(c);
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            stopInner(c, why);
        }

        void startInner(FlowContext c) {
            inner.begin(c.of(inner));
            innerActive = true;
        }

        /** Ticks the inner step, ending it if it finished, and passing on why it failed. */
        Status runInner(FlowContext c) {
            FlowContext ic = c.of(inner);
            Status status = inner.run(ic);
            if (status == Status.RUNNING) {
                return status;
            }
            inner.end(ic, status == Status.DONE ? StopReason.FINISHED : StopReason.FAILED);
            innerActive = false;
            return status == Status.DONE ? status : c.fail(inner.getReason());
        }

        void stopInner(FlowContext c, StopReason why) {
            if (innerActive) {
                inner.end(c.of(inner), why);
                innerActive = false;
            }
        }

        @Override
        List<Step> children() {
            return Collections.singletonList(inner);
        }

        @Override
        Condition ensuresCondition() {
            return inner.ensuresCondition();
        }

        @Override
        String baseName() {
            return inner.baseName();
        }

        @Override
        Step activeLeaf() {
            return innerActive ? inner.activeLeaf() : this;
        }

        @Override
        double currentProgress(FlowContext c) {
            return innerActive ? inner.currentProgress(c.of(inner)) : Double.NaN;
        }
    }

    static final class Ensured extends Wrapper {

        private final Condition achieved;

        Ensured(Step inner, Condition achieved) {
            super(inner, "ensures");
            this.achieved = Validate.notNull(achieved, "achieved");
        }

        @Override
        protected Status tick(FlowContext c) {
            Status status = runInner(c);
            if (status == Status.DONE && !achieved.test(c)) {
                return c.fail("finished, but what it ensures does not hold");
            }
            return status;
        }

        @Override
        Condition ensuresCondition() {
            return achieved;
        }
    }

    static final class Until extends Wrapper {

        private final Condition done;
        private boolean already;

        Until(Step inner, Condition done) {
            super(inner, "until");
            this.done = Validate.notNull(done, "done");
        }

        @Override
        protected void start(FlowContext c) {
            already = done.test(c);
            if (!already) {
                startInner(c);
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            if (already || done.test(c)) {
                stopInner(c, StopReason.CANCELLED);
                return Status.DONE;
            }
            return runInner(c);
        }
    }

    static final class When extends Wrapper {

        private final Condition condition;
        private boolean skipped;

        When(Step inner, Condition condition) {
            super(inner, "when");
            this.condition = Validate.notNull(condition, "condition");
        }

        @Override
        protected void start(FlowContext c) {
            skipped = !c.isResuming() && !condition.test(c);
            if (!skipped) {
                startInner(c);
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            return skipped ? c.fail("its condition did not hold") : runInner(c);
        }
    }

    static final class Retry extends Wrapper {

        private final int times;
        private int attempts;

        Retry(Step inner, int times) {
            super(inner, "retry " + times);
            Validate.check(times >= 1, "retry at least once, got " + times);
            this.times = times;
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                attempts = 0;
            }
            startInner(c);
        }

        @Override
        protected Status tick(FlowContext c) {
            Status status = runInner(c);
            if (status == Status.FAILED && attempts < times) {
                attempts++;
                startInner(c);
                return Status.RUNNING;
            }
            if (status == Status.FAILED) {
                return c.fail(inner.getReason() + " (after " + (times + 1) + " tries)");
            }
            return status;
        }
    }

    static final class OrElse extends Wrapper {

        private final Step fallback;
        private boolean falling;
        private boolean fallbackActive;

        OrElse(Step inner, Step fallback) {
            super(inner, "or else " + Validate.notNull(fallback, "fallback").getName());
            this.fallback = fallback;
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                falling = false;
            }
            if (falling) {
                fallback.begin(c.of(fallback));
                fallbackActive = true;
            } else {
                startInner(c);
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            if (!falling) {
                Status status = runInner(c);
                if (status != Status.FAILED) {
                    return status;
                }
                falling = true;
                fallback.begin(c.of(fallback));
                fallbackActive = true;
                if (!c.advance()) {
                    return Status.RUNNING;
                }
            }
            FlowContext fc = c.of(fallback);
            Status status = fallback.run(fc);
            if (status == Status.RUNNING) {
                return status;
            }
            fallback.end(fc, status == Status.DONE ? StopReason.FINISHED : StopReason.FAILED);
            fallbackActive = false;
            return status == Status.DONE ? status
                    : c.fail(inner.getReason() + ", and then " + fallback.getName() + ": " + fallback.getReason());
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            stopInner(c, why);
            if (fallbackActive) {
                fallback.end(c.of(fallback), why);
                fallbackActive = false;
            }
        }

        @Override
        List<Step> children() {
            return Arrays.asList(inner, fallback);
        }

        @Override
        Step activeLeaf() {
            return fallbackActive ? fallback.activeLeaf() : super.activeLeaf();
        }

        @Override
        double currentProgress(FlowContext c) {
            return fallbackActive ? fallback.currentProgress(c.of(fallback)) : super.currentProgress(c);
        }
    }

    static final class Timeout extends Wrapper {

        private final Span limit;
        private long fromTick;
        private long fromNanos;

        Timeout(Step inner, Span limit) {
            super(inner, "timeout " + Validate.notNull(limit, "limit"));
            this.limit = limit;
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                fromTick = c.tick();
                fromNanos = c.nanos();
            }
            startInner(c);
        }

        @Override
        protected Status tick(FlowContext c) {
            if (limit.hasPassed(fromTick, fromNanos, c.tick(), c.nanos())) {
                stopInner(c, StopReason.CANCELLED);
                return c.fail("timed out after " + limit);
            }
            return runInner(c);
        }
    }

    static final class StuckAfter extends Wrapper {

        private final Span limit;
        private final Step recovery;
        private boolean recovering;
        private Step leaf;
        private double last = Double.NaN;
        private long fromTick;
        private long fromNanos;

        StuckAfter(Step inner, Span limit, Step recovery) {
            super(inner, "stuck after " + Validate.notNull(limit, "limit"));
            this.limit = limit;
            this.recovery = Validate.notNull(recovery, "recovery");
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                recovering = false;
            }
            mark(c, null, Double.NaN);
            if (recovering) {
                recovery.begin(c.of(recovery));
            } else {
                startInner(c);
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            if (recovering) {
                FlowContext rc = c.of(recovery);
                Status status = recovery.run(rc);
                if (status == Status.RUNNING) {
                    return status;
                }
                recovery.end(rc, status == Status.DONE ? StopReason.FINISHED : StopReason.FAILED);
                if (status == Status.FAILED) {
                    return c.fail("stuck, and " + recovery.getName() + " failed: " + recovery.getReason());
                }
                recovering = false;
                startInner(c);
                mark(c, null, Double.NaN);
                return Status.RUNNING;
            }
            Status status = runInner(c);
            if (status != Status.RUNNING) {
                return status;
            }
            Step now = inner.activeLeaf();
            double progress = inner.currentProgress(c.of(inner));
            if (now != leaf || changed(progress)) {
                mark(c, now, progress);
            } else if (!Double.isNaN(progress) && limit.hasPassed(fromTick, fromNanos, c.tick(), c.nanos())) {
                stopInner(c, StopReason.PAUSED);
                recovering = true;
                recovery.begin(c.of(recovery));
            }
            return Status.RUNNING;
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            stopInner(c, why);
            if (recovering && recovery.isRunning()) {
                recovery.end(c.of(recovery), why);
            }
        }

        private boolean changed(double progress) {
            if (Double.isNaN(progress) || Double.isNaN(last)) {
                return Double.isNaN(progress) != Double.isNaN(last);
            }
            return Math.abs(progress - last) > 1.0E-9d;
        }

        private void mark(FlowContext c, Step at, double progress) {
            leaf = at;
            last = progress;
            fromTick = c.tick();
            fromNanos = c.nanos();
        }

        @Override
        List<Step> children() {
            return Arrays.asList(inner, recovery);
        }

        @Override
        Set<Control> activeControls() {
            return recovering ? recovery.activeControls() : super.activeControls();
        }

        @Override
        Step activeLeaf() {
            return recovering ? recovery.activeLeaf() : super.activeLeaf();
        }
    }

    static final class Interrupt extends Wrapper {

        private final Condition when;
        private final Step handler;
        private boolean handling;

        Interrupt(Step inner, Condition when, Step handler) {
            super(inner, "interrupted by " + Validate.notNull(handler, "handler").getName());
            this.when = Validate.notNull(when, "when");
            this.handler = handler;
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                handling = false;
            }
            if (handling) {
                handler.begin(c.of(handler));
            } else {
                startInner(c);
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            if (!handling && when.test(c)) {
                stopInner(c, StopReason.PAUSED);
                handling = true;
                handler.begin(c.of(handler));
            }
            if (handling) {
                FlowContext hc = c.of(handler);
                Status status = handler.run(hc);
                if (status == Status.RUNNING) {
                    return status;
                }
                handler.end(hc, status == Status.DONE ? StopReason.FINISHED : StopReason.FAILED);
                if (status == Status.FAILED) {
                    return c.fail("interrupted by " + handler.getName() + ", which failed: " + handler.getReason());
                }
                handling = false;
                startInner(c);
                return Status.RUNNING;
            }
            return runInner(c);
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            stopInner(c, why);
            if (handling && handler.isRunning()) {
                handler.end(c.of(handler), why);
            }
        }

        @Override
        List<Step> children() {
            return Arrays.asList(inner, handler);
        }

        @Override
        Set<Control> activeControls() {
            return handling ? handler.activeControls() : super.activeControls();
        }

        @Override
        Step activeLeaf() {
            return handling ? handler.activeLeaf() : super.activeLeaf();
        }
    }

    // ============================================================== composites

    static final class Sequence extends Step {

        private final Step[] steps;
        private int index;
        private boolean active;
        private int rewinds;
        private String broken;

        Sequence(Step[] steps) {
            super("sequence");
            this.steps = steps;
        }

        @Override
        protected void start(FlowContext c) {
            broken = null;
            active = false;
            if (!c.isResuming()) {
                index = 0;
                rewinds = 0;
                return;
            }
            // Back to the earliest finished step whose work no longer holds.
            for (int i = 0; i < index && i < steps.length; i++) {
                Condition ensures = steps[i].ensuresCondition();
                if (ensures != null && !ensures.test(c.of(steps[i]))) {
                    // Everything from there on starts afresh, the paused step included.
                    for (int j = i; j <= index && j < steps.length; j++) {
                        steps[j].forgetPause();
                    }
                    index = i;
                    if (++rewinds > c.maxRewinds()) {
                        broken = "rewound " + rewinds + " times; what " + steps[i].baseName()
                                + " ensures keeps being lost";
                    }
                    return;
                }
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            if (broken != null) {
                return c.fail(broken);
            }
            while (true) {
                Step step = steps[index];
                FlowContext sc = c.of(step);
                if (!active) {
                    step.begin(sc);
                    active = true;
                }
                Status status = step.run(sc);
                if (status == Status.RUNNING) {
                    return status;
                }
                active = false;
                if (status == Status.FAILED) {
                    step.end(sc, StopReason.FAILED);
                    return c.fail(step.getName() + ": " + step.getReason());
                }
                step.end(sc, StopReason.FINISHED);
                if (++index >= steps.length) {
                    return Status.DONE;
                }
                if (!c.advance()) {
                    return Status.RUNNING;
                }
            }
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            if (active) {
                steps[index].end(c.of(steps[index]), why);
                active = false;
            }
        }

        /** @return the step running now, or next to run */
        int getIndex() {
            return index;
        }

        @Override
        List<Step> children() {
            return Arrays.asList(steps);
        }

        @Override
        Step activeLeaf() {
            return active ? steps[index].activeLeaf() : this;
        }

        @Override
        double currentProgress(FlowContext c) {
            return active ? steps[index].currentProgress(c.of(steps[index])) : Double.NaN;
        }
    }

    static final class Loop extends Wrapper {

        private int rounds;

        Loop(Step body) {
            super(body, "loop");
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                rounds = 0;
            }
            startInner(c);
        }

        @Override
        protected Status tick(FlowContext c) {
            Status status = runInner(c);
            if (status == Status.DONE) {
                rounds++;
                // Begins again next tick, so a body that finishes at once cannot spin.
                startInner(c);
                return Status.RUNNING;
            }
            return status;
        }

        int getRounds() {
            return rounds;
        }
    }

    static final class FirstOf extends Step {

        private final Step[] options;
        private int index;
        private boolean active;
        private final List<String> failures = new ArrayList<>();

        FirstOf(Step[] options) {
            super("first of");
            this.options = options;
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                index = 0;
                failures.clear();
            }
            active = false;
        }

        @Override
        protected Status tick(FlowContext c) {
            while (true) {
                Step option = options[index];
                FlowContext oc = c.of(option);
                if (!active) {
                    option.begin(oc);
                    active = true;
                }
                Status status = option.run(oc);
                if (status == Status.RUNNING) {
                    return status;
                }
                active = false;
                if (status == Status.DONE) {
                    option.end(oc, StopReason.FINISHED);
                    return status;
                }
                option.end(oc, StopReason.FAILED);
                failures.add(option.getName() + ": " + option.getReason());
                if (++index >= options.length) {
                    return c.fail("every option failed (" + String.join("; ", failures) + ")");
                }
                if (!c.advance()) {
                    return Status.RUNNING;
                }
            }
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            if (active) {
                options[index].end(c.of(options[index]), why);
                active = false;
            }
        }

        @Override
        List<Step> children() {
            return Arrays.asList(options);
        }

        @Override
        Step activeLeaf() {
            return active ? options[index].activeLeaf() : this;
        }

        @Override
        double currentProgress(FlowContext c) {
            return active ? options[index].currentProgress(c.of(options[index])) : Double.NaN;
        }
    }

    /** Branches side by side: {@code parallel} needs all of them, {@code race} the first. */
    static final class Together extends Step {

        private final Step[] branches;
        private final boolean race;
        private final boolean[] over;

        Together(Step[] branches, boolean race) {
            super(race ? "race" : "parallel");
            this.branches = branches;
            this.race = race;
            this.over = new boolean[branches.length];
        }

        @Override
        protected void start(FlowContext c) {
            for (int i = 0; i < branches.length; i++) {
                if (!c.isResuming()) {
                    over[i] = false;
                }
                if (!over[i]) {
                    branches[i].begin(c.of(branches[i]));
                }
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            List<String> failed = new ArrayList<>();
            boolean running = false;
            for (int i = 0; i < branches.length; i++) {
                if (over[i]) {
                    if (branches[i].getState() == StepState.FAILED) {
                        failed.add(branches[i].getName() + ": " + branches[i].getReason());
                    }
                    continue;
                }
                FlowContext bc = c.of(branches[i]);
                Status status = branches[i].run(bc);
                if (status == Status.RUNNING) {
                    running = true;
                    continue;
                }
                over[i] = true;
                branches[i].end(bc, status == Status.DONE ? StopReason.FINISHED : StopReason.FAILED);
                if (status == Status.DONE && race) {
                    cancelRest(c);
                    return Status.DONE;
                }
                if (status == Status.FAILED) {
                    if (!race) {
                        cancelRest(c);
                        return c.fail(branches[i].getName() + ": " + branches[i].getReason());
                    }
                    failed.add(branches[i].getName() + ": " + branches[i].getReason());
                }
            }
            if (running) {
                return Status.RUNNING;
            }
            return race ? c.fail("every branch failed (" + String.join("; ", failed) + ")") : Status.DONE;
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            for (int i = 0; i < branches.length; i++) {
                if (!over[i] && branches[i].isRunning()) {
                    branches[i].end(c.of(branches[i]), why);
                }
            }
        }

        private void cancelRest(FlowContext c) {
            for (int i = 0; i < branches.length; i++) {
                if (!over[i]) {
                    over[i] = true;
                    branches[i].end(c.of(branches[i]), StopReason.CANCELLED);
                }
            }
        }

        @Override
        List<Step> children() {
            return Arrays.asList(branches);
        }

        @Override
        Set<Control> activeControls() {
            Set<Control> all = EnumSet.noneOf(Control.class);
            for (Step branch : branches) {
                all.addAll(branch.activeControls());
            }
            return all;
        }
    }
}
