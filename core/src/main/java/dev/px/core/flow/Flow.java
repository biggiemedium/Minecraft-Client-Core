package dev.px.core.flow;

import dev.px.core.event.Event;
import dev.px.core.event.Subscription;
import dev.px.core.util.Validate;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Builds flows: automation written as steps, with fallbacks, interrupts and
 * rewinding.
 *
 * <pre>{@code
 * static final Key<Tracked<Player>> TARGET = Key.of("target");
 *
 * Step bot = Flow.loop(Flow.sequence(
 *         new FindEnemy(enemies, TARGET).ensures(tracked(TARGET)),
 *         travel.to(c -> near(c.get(TARGET), 5)).ensures(within(TARGET, 6)),   // navigation's Travel
 *         fight.against(TARGET).until(lowHealth)))                         // combat's Fight
 *     .interrupt(lowHealth, new Retreat(home).then(new Heal()))
 *     .stuckAfter(Span.seconds(30), new Recover());
 *
 * FlowHandle running = Core.flows().start("bot", bot, 50);
 * Core.flows().reflex(onFire, 90, new Extinguish());
 * }</pre>
 *
 * <p>The library ships no behaviours: the steps above are the developer's, or
 * come ready-made from the libraries built on Core. What ships here is how steps
 * fit together:
 *
 * <ul>
 *   <li>{@link #sequence}: one after another; a failure fails the lot.
 *   <li>{@link #loop}: again and again, until something stops it.
 *   <li>{@link #firstOf}: options in the order written, falling back on failure.
 *       The library never scores or chooses between them.
 *   <li>{@link #parallel}: side by side, every one must finish.
 *   <li>{@link #race}: side by side, the first to finish wins.
 *   <li>{@link #awaitEvent}: until a matching event is posted on the bus.
 * </ul>
 *
 * <p>and on every step, {@link Step#ensures}, {@link Step#until},
 * {@link Step#when}, {@link Step#retry}, {@link Step#orElse},
 * {@link Step#timeout}, {@link Step#stuckAfter} and {@link Step#interrupt}.
 *
 * <p>A step that finishes at once lets the next one start in the same tick, up
 * to a {@linkplain FlowService#setChangesPerTick limit per tick}, so a run of
 * instant steps does not cost a tick each and a loop of them cannot spin.
 */
public final class Flow {

    private Flow() {
    }

    public static Step sequence(Step... steps) {
        return new Steps.Sequence(Steps.copy(steps));
    }

    /** @return {@code body}, begun again each time it finishes; it ends only by failing, or around an {@code until} */
    public static Step loop(Step body) {
        return new Steps.Loop(body);
    }

    public static Step firstOf(Step... options) {
        return new Steps.FirstOf(Steps.copy(options));
    }

    public static Step parallel(Step... branches) {
        return new Steps.Together(Steps.copy(branches), false);
    }

    public static Step race(Step... branches) {
        return new Steps.Together(Steps.copy(branches), true);
    }

    /**
     * @return a step that finishes when an event of {@code type} matching
     *         {@code filter} is posted; it listens only while it runs
     */
    public static <E extends Event> Await<E> awaitEvent(Class<E> type, Predicate<? super E> filter) {
        return new Await<>(type, filter);
    }

    public static <E extends Event> Await<E> awaitEvent(Class<E> type) {
        return new Await<>(type, event -> true);
    }

    /** @return a step that waits {@code span}, then finishes */
    public static Step waitFor(Span span) {
        Validate.notNull(span, "span");
        final long[] from = new long[2];
        return new Inline("wait " + span)
                .start(c -> {
                    if (!c.isResuming()) {
                        from[0] = c.tick();
                        from[1] = c.nanos();
                    }
                })
                .tick(c -> span.hasPassed(from[0], from[1], c.tick(), c.nanos()) ? Status.DONE : Status.RUNNING);
    }

    /** @return a step that runs until {@code condition} holds */
    public static Step waitUntil(Condition condition) {
        Validate.notNull(condition, "condition");
        return new Inline("wait until").tick(c -> condition.test(c) ? Status.DONE : Status.RUNNING);
    }

    /** @return a step that does {@code action} once and finishes */
    public static Step action(String name, Consumer<FlowContext> action) {
        Validate.notNull(action, "action");
        return new Inline(name).tick(c -> {
            action.accept(c);
            return Status.DONE;
        });
    }

    /** @return a step that finishes if {@code condition} holds, and fails if not */
    public static Step check(String name, Condition condition) {
        Validate.notNull(condition, "condition");
        return new Inline(name).tick(c -> condition.test(c) ? Status.DONE : c.fail("it did not hold"));
    }

    /**
     * @return a step written inline, for steps too small to be a class
     *
     * <pre>{@code
     * Step aimed = Flow.step("wait until aimed").uses(Control.ROTATION)
     *         .tick(c -> { c.look(aim); return aimed() ? Status.DONE : Status.RUNNING; });
     * }</pre>
     */
    public static Inline step(String name) {
        return new Inline(name);
    }

    /** A step built from lambdas. Needs a {@link #tick}; the rest is optional. */
    public static final class Inline extends Step {

        private Consumer<FlowContext> onStart;
        private Function<FlowContext, Status> onTick;
        private BiConsumer<FlowContext, StopReason> onStop;
        private ToDoubleFunction<FlowContext> onProgress;

        private Inline(String name) {
            super(name);
        }

        public Inline uses(Control... controls) {
            super.uses(controls);
            return this;
        }

        public Inline start(Consumer<FlowContext> start) {
            this.onStart = Validate.notNull(start, "start");
            return this;
        }

        public Inline tick(Function<FlowContext, Status> tick) {
            this.onTick = Validate.notNull(tick, "tick");
            return this;
        }

        public Inline stop(BiConsumer<FlowContext, StopReason> stop) {
            this.onStop = Validate.notNull(stop, "stop");
            return this;
        }

        public Inline progress(ToDoubleFunction<FlowContext> progress) {
            this.onProgress = Validate.notNull(progress, "progress");
            return this;
        }

        @Override
        protected void start(FlowContext c) {
            if (onStart != null) {
                onStart.accept(c);
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            return onTick == null ? c.fail("it has no tick") : onTick.apply(c);
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            if (onStop != null) {
                onStop.accept(c, why);
            }
        }

        @Override
        protected double progress(FlowContext c) {
            return onProgress == null ? Double.NaN : onProgress.applyAsDouble(c);
        }
    }

    /**
     * Waits for an event on the bus.
     *
     * <p>Subscribes when it starts and closes the subscription when it stops,
     * so a step that is not running hears nothing. {@link #into} keeps the event
     * in a key for the steps after it.
     */
    public static final class Await<E extends Event> extends Step {

        private final Class<E> type;
        private final Predicate<? super E> filter;
        private Key<? super E> into;
        private Subscription subscription;
        private E heard;

        private Await(Class<E> type, Predicate<? super E> filter) {
            super("await " + Validate.notNull(type, "type").getSimpleName());
            this.type = type;
            this.filter = Validate.notNull(filter, "filter");
        }

        /** Keeps the event in {@code key} when it arrives. */
        @SuppressWarnings("unchecked")
        public Await<E> into(Key<? super E> key) {
            this.into = Validate.notNull(key, "key");
            return this;
        }

        @Override
        protected void start(FlowContext c) {
            if (!c.isResuming()) {
                heard = null;
            }
            subscription = c.bus().on(type, event -> {
                if (heard == null && filter.test(event)) {
                    heard = event;
                }
            });
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected Status tick(FlowContext c) {
            if (heard == null) {
                return Status.RUNNING;
            }
            if (into != null) {
                c.put((Key) into, heard);
            }
            return Status.DONE;
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            if (subscription != null) {
                subscription.close();
                subscription = null;
            }
        }
    }
}
