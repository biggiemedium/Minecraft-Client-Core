package dev.px.navigation;

import dev.px.core.flow.Condition;
import dev.px.core.flow.Control;
import dev.px.core.flow.FlowContext;
import dev.px.core.flow.FlowHandle;
import dev.px.core.flow.Status;
import dev.px.core.flow.Step;
import dev.px.core.flow.StopReason;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.SimulationService;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.PathProvider;
import dev.px.core.navigation.Progress;
import dev.px.core.util.Validate;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Travel steps for Core's flows: each gets the player to a {@link Goal} with a
 * {@link Navigator} of its own, and finishes when the goal is met.
 *
 * <pre>{@code
 * static final Key<Tracked<Player>> TARGET = Key.of("target");
 *
 * Travel travel = Travel.builder()
 *         .provider(planner)                                 // the LocalPlanner, any planner, or one that drives
 *         .player(() -> MotionState.of(
 *                 Vec3.of(p.posX, p.posY, p.posZ), Vec3.of(p.motionX, p.motionY, p.motionZ), p.onGround))
 *         .navigator(b -> b.local(planner).stuckTicks(60))   // any Navigator setting
 *         .build();
 *
 * Step bot = Flow.loop(Flow.sequence(
 *         new FindEnemy(enemies, TARGET),
 *         travel.to(c -> Goal.near(c.get(TARGET), 5)),       // a goal from the flow's keys
 *         new Fight(crystals)))
 *     .stuckAfter(Span.seconds(30), new Recover());
 *
 * Goal home = Goal.near(base, 2);
 * Step goHome = travel.to(home).ensures(travel.at(home));    // walked back to after an interrupt
 * }</pre>
 *
 * <p>Built once, it makes as many steps as you need: {@link #to} returns a new
 * one each time, since a step runs in one place at a time.
 *
 * <h2>A trip as a step</h2>
 *
 * <p>The step {@linkplain Step#uses declares} {@link Control#MOVEMENT} and
 * {@link Control#ROTATION}, and its navigator claims both at the flow's priority,
 * read each tick, through the controls and rotations the flow's host provides.
 * With {@link Builder#turnHead turnHead(false)} it leaves the head alone and
 * declares only the keys.
 * The navigator checks routes with the host's {@link SimulationService}, so the
 * step runs unchanged in the game and in the test kit's sandbox.
 *
 * <ul>
 *   <li><b>Arrived:</b> the step is done, and lets go of the controls.
 *   <li><b>Failed:</b> no route, as far as the provider can tell. The step fails
 *       with the navigator's reason, so a {@code firstOf}, {@code retry} or
 *       {@code orElse} can take over.
 *   <li><b>Stuck:</b> the step keeps running and the navigator keeps trying. Its
 *       {@linkplain Step#progress progress} is the blocks left, so the flow's
 *       {@code stuckAfter} notices when it stops falling. Set
 *       {@link Builder#failWhenStuck} to fail instead.
 *   <li><b>Paused:</b> the navigator stops, letting go of the controls and
 *       cancelling a provider that drives. On resuming, the goal is asked for
 *       again and the trip begins afresh from wherever the player is.
 * </ul>
 *
 * <p>Immutable; each step it makes keeps its own trip. Game thread only.
 */
public final class Travel {

    private final PathProvider provider;
    private final Supplier<MotionState> player;
    private final Consumer<Navigator.Builder> settings;
    private final BooleanSupplier failWhenStuck;
    private final boolean turnHead;

    private Travel(Builder builder) {
        this.provider = builder.provider;
        this.player = builder.player;
        this.settings = builder.settings;
        this.failWhenStuck = builder.failWhenStuck;
        this.turnHead = builder.turnHead;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return a new step that travels to {@code goal} */
    public Trip to(Goal goal) {
        Validate.notNull(goal, "goal");
        return new Trip(this, c -> goal, "travel to " + goal);
    }

    /**
     * @return a new step that travels to the goal {@code goal} gives as it
     *         starts, and again as it resumes: for goals made from the flow's keys.
     *         A null goal fails the step
     */
    public Trip to(Function<FlowContext, Goal> goal) {
        Validate.notNull(goal, "goal");
        return new Trip(this, goal, "travel");
    }

    /** @return whether the player's feet are in {@code goal}: for {@link Step#ensures} */
    public Condition at(Goal goal) {
        Validate.notNull(goal, "goal");
        return at(c -> goal);
    }

    /** @return whether the player's feet are in the goal {@code goal} gives; false for none */
    public Condition at(Function<FlowContext, Goal> goal) {
        Validate.notNull(goal, "goal");
        return c -> {
            Goal wanted = goal.apply(c);
            MotionState state = player.get();
            return wanted != null && state != null && wanted.isMet(state.getPosition());
        };
    }

    /**
     * A step that travels to one goal. Made by {@link Travel#to}.
     *
     * <p>Its navigator is built the first time it starts, from the services of
     * the flow it runs in, and kept for every start after: {@link #getNavigator()}
     * is there for drawing the route, and its stats say why it planned.
     */
    public static final class Trip extends Step {

        private final Travel travel;
        private final Function<FlowContext, Goal> goalOf;
        private Navigator navigator;
        private Goal goal;
        private String problem;
        private Progress progress;

        private Trip(Travel travel, Function<FlowContext, Goal> goalOf, String name) {
            super(name);
            this.travel = travel;
            this.goalOf = goalOf;
            uses(Control.MOVEMENT);
            if (travel.turnHead) {
                uses(Control.ROTATION);
            }
        }

        @Override
        protected void start(FlowContext c) {
            problem = null;
            progress = null;
            goal = goalOf.apply(c);
            if (goal == null) {
                problem = "there is no goal to travel to";
                return;
            }
            if (navigator == null) {
                navigator = build(c);
                if (navigator == null) {
                    return;
                }
            }
            navigator.travel(goal);
        }

        @Override
        protected Status tick(FlowContext c) {
            if (problem != null) {
                return c.fail(problem);
            }
            MotionState state = travel.player.get();
            if (state == null) {
                return c.fail("the player's state is unknown: the player supplier gave null");
            }
            progress = navigator.tick(state);
            switch (progress.getState()) {
                case ARRIVED:
                    return Status.DONE;
                case FAILED:
                    return c.fail(progress.getReason());
                case STUCK:
                    return travel.failWhenStuck.getAsBoolean() ? c.fail("stuck: " + progress.getReason()) : Status.RUNNING;
                default:
                    return Status.RUNNING;
            }
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            if (navigator != null) {
                navigator.stop();
            }
        }

        /** @return the blocks left to the goal, at least; NaN before the first plan */
        @Override
        protected double progress(FlowContext c) {
            return progress == null ? Double.NaN : progress.getRemaining();
        }

        /** @return the navigator taking this trip, or null before the step first starts */
        public Navigator getNavigator() {
            return navigator;
        }

        /** @return the goal this trip is for, as it last started; null before */
        public Goal getGoal() {
            return goal;
        }

        /** @return the navigator's progress last tick; null before the first */
        public Progress getProgress() {
            return progress;
        }

        /** @return a navigator claiming through the flow's services, or null with the problem kept */
        private Navigator build(FlowContext c) {
            SimulationService simulation = c.service(SimulationService.class);
            if (simulation == null) {
                problem = "the flow's host provides no SimulationService to check routes with";
                return null;
            }
            Navigator.Builder builder = Navigator.builder();
            travel.settings.accept(builder);
            if (!travel.turnHead) {
                builder.turnHead(false);
            }
            FlowHandle flow = c.flow();
            return builder.provider(travel.provider)
                    .controls(c.controls())
                    .rotations(c.rotations())
                    .simulation(simulation)
                    .priority(flow::getPriority)
                    .build();
        }
    }

    /**
     * Builds a {@link Travel}. Needs the provider routes come from and the
     * player's state.
     */
    public static final class Builder {

        private PathProvider provider;
        private Supplier<MotionState> player;
        private Consumer<Navigator.Builder> settings = builder -> { };
        private BooleanSupplier failWhenStuck = () -> false;
        private boolean turnHead = true;

        private Builder() {
        }

        /** Where routes come from: a planner, a long-range pathfinder, or one that drives. */
        public Builder provider(PathProvider provider) {
            this.provider = Validate.notNull(provider, "provider");
            return this;
        }

        /**
         * The player's position, velocity and footing, read each tick as the flow
         * runs, before the player moves. Yours: Core has no player of its own.
         */
        public Builder player(Supplier<MotionState> player) {
            this.player = Validate.notNull(player, "player");
            return this;
        }

        /**
         * Settings for each step's navigator, applied as it is built: {@code local},
         * {@code drift}, {@code stuckTicks}, {@code turn} and the rest. The step
         * then sets the provider, controls, rotations, simulation and priority
         * itself, so the flow's always win.
         */
        public Builder navigator(Consumer<Navigator.Builder> settings) {
            this.settings = Validate.notNull(settings, "settings");
            return this;
        }

        /**
         * Whether a step fails when its navigator reports itself stuck, read each
         * tick. Off by default: the step keeps running and the flow's
         * {@code stuckAfter} decides.
         */
        public Builder failWhenStuck(BooleanSupplier fail) {
            this.failWhenStuck = Validate.notNull(fail, "fail");
            return this;
        }

        public Builder failWhenStuck(boolean fail) {
            return failWhenStuck(() -> fail);
        }

        /**
         * Whether the steps turn the head to face their keys. On by default. Off,
         * they claim only the movement keys and declare only
         * {@link Control#MOVEMENT}, leaving the head to something running beside
         * them &mdash; a fight aiming at its target:
         * {@code Flow.race(fight.against(TARGET), Flow.loop(chase.to(...)))}. Your
         * {@link dev.px.core.control.MovementSink} then corrects the keys to the yaw
         * the player faces; see {@link Navigator.Builder#turnHead}.
         */
        public Builder turnHead(boolean turn) {
            this.turnHead = turn;
            return this;
        }

        public Travel build() {
            StringBuilder missing = new StringBuilder();
            if (provider == null) {
                missing.append(", provider");
            }
            if (player == null) {
                missing.append(", player");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("a Travel needs: " + missing.substring(2));
            }
            return new Travel(this);
        }
    }
}
