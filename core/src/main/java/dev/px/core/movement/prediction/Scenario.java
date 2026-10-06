package dev.px.core.movement.prediction;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.util.Validate;

import java.util.Locale;

/**
 * One way the future could go, as the keys held through it: carrying on as
 * now, stopping, jumping, walking to somewhere in particular.
 *
 * <p>{@link PredictionService} runs each scenario through the real movement
 * rules from where the entity is, and weighs it by how well it would have
 * predicted the entity's last few ticks. A scenario is only keys; the physics,
 * the walls and the weighing are the service's.
 *
 * <pre>{@code
 * Scenario.HOLDS                                  // keeps doing what it is doing
 * Scenario.STOPS                                  // lets go of every key
 * Scenario.toward("into the hole", hole)          // heads for a point, at the pace it moves now
 * Scenario.of("strafes left", (estimate, state, tick) ->
 *         estimate.getInput().withKeys(0, 1))     // anything else
 * }</pre>
 *
 * <p>Most scenarios are keys run through the rules. One that is not &mdash; a
 * cheat that sets velocity directly, a snap into a hole &mdash; overrides
 * {@link #adjust}, which can change the state before each tick's step.
 */
public interface Scenario {

    /** Keeps doing what it is doing: the estimated keys, held. */
    Scenario HOLDS = of("holds", (estimate, state, tick) -> estimate.getInput());

    /** Lets go of every key. */
    Scenario STOPS = of("stops", (estimate, state, tick) -> MovementInput.none(estimate.getInput().getYaw()));

    /** Keeps the estimated keys and holds jump: jumps whenever it lands. */
    Scenario JUMPS = of("jumps", (estimate, state, tick) -> estimate.getInput().withJump(true));

    /**
     * Keeps moving sideways exactly as it last moved, every tick, with no friction
     * and no keys: falling, and stopped by walls, but otherwise a straight line.
     * That is how a speed or strafe cheat that sets velocity directly moves, and
     * nothing by the rules does; weighed against {@link #HOLDS}, it wins for those
     * players on its own.
     */
    Scenario CARRIES = new Scenario() {
        @Override
        public String getName() {
            return "carries";
        }

        @Override
        public MovementInput input(MotionEstimate estimate, MotionState state, int tick) {
            return MovementInput.none(estimate.getInput().getYaw());
        }

        @Override
        public MotionState adjust(MotionEstimate estimate, MotionState state, int tick) {
            Vec3 move = estimate.getMove();
            return state.withVelocity(Vec3.of(move.getX(), state.getVelocity().getY(), move.getZ()));
        }

        @Override
        public String toString() {
            return "Scenario(carries)";
        }
    };

    /** @return what this future is called, for display and for telling futures apart */
    String getName();

    /**
     * @param estimate what the entity was doing when this future starts
     * @param state    where it is in this future now
     * @param tick     0 for the first tick of the future
     * @return the keys it holds for that tick
     */
    MovementInput input(MotionEstimate estimate, MotionState state, int tick);

    /**
     * @return how much this future is believed before any evidence, against the
     *         others' 1: a future's weight is this times how well it explained the
     *         entity's last few ticks. 1 unless overridden, so the evidence alone
     *         decides. Raise it for a future you expect when the evidence cannot
     *         tell it apart from another: someone sprinting straight at a hole moves
     *         exactly like someone about to sprint over it, until they stop
     */
    default double getPrior() {
        return 1d;
    }

    /** @return this scenario, believed {@code prior} times as much as one with no prior */
    default Scenario withPrior(double prior) {
        Validate.check(prior > 0d && !Double.isInfinite(prior), "a prior must be positive and finite");
        Scenario self = this;
        return new Scenario() {
            @Override
            public String getName() {
                return self.getName();
            }

            @Override
            public MovementInput input(MotionEstimate estimate, MotionState state, int tick) {
                return self.input(estimate, state, tick);
            }

            @Override
            public MotionState adjust(MotionEstimate estimate, MotionState state, int tick) {
                return self.adjust(estimate, state, tick);
            }

            @Override
            public double getPrior() {
                return prior;
            }

            @Override
            public String toString() {
                return self + " x" + prior;
            }
        };
    }

    /**
     * Changes the state before a tick is stepped: for a future the rules alone do
     * not produce. Unchanged unless overridden.
     *
     * @param tick 0 for the first tick of the future
     */
    default MotionState adjust(MotionEstimate estimate, MotionState state, int tick) {
        return state;
    }

    /** The keys for each tick of a future: a scenario without its name. */
    @FunctionalInterface
    interface Steering {

        /** @see Scenario#input */
        MovementInput input(MotionEstimate estimate, MotionState state, int tick);
    }

    /** @return a scenario called {@code name} that holds whatever {@code steering} says */
    static Scenario of(String name, Steering steering) {
        Validate.notNull(name, "name");
        Validate.notNull(steering, "steering");
        return new Scenario() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public MovementInput input(MotionEstimate estimate, MotionState state, int tick) {
                return steering.input(estimate, state, tick);
            }

            @Override
            public String toString() {
                return "Scenario(" + name + ")";
            }
        };
    }

    /**
     * Heads straight for {@code point} at the pace it moves now &mdash; sprinting
     * if it sprints, jumping if it jumps, walking if it is standing still &mdash;
     * and lets go once its feet are over it. Walls are the simulation's business,
     * so a point down in a hole is walked to and dropped into.
     *
     * <p>Weighed like any other: it carries weight while the entity's last few
     * ticks look like heading there.
     */
    static Scenario toward(String name, Vec3 point) {
        Validate.notNull(point, "point");
        return of(name, (estimate, state, tick) -> {
            MovementInput pace = estimate.getInput();
            double dx = point.getX() - state.getPosition().getX();
            double dz = point.getZ() - state.getPosition().getZ();
            if (dx * dx + dz * dz < ARRIVED * ARRIVED) {
                return MovementInput.none(pace.getYaw()).withSneak(pace.isSneak());
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            return MovementInput.forward(yaw).withSprint(pace.isSprint()).withSneak(pace.isSneak())
                    .withJump(pace.isJump());
        });
    }

    /** @return {@link #toward(String, Vec3)}, named after the point */
    static Scenario toward(Vec3 point) {
        Validate.notNull(point, "point");
        return toward(String.format(Locale.ROOT, "toward %.1f, %.1f, %.1f",
                point.getX(), point.getY(), point.getZ()), point);
    }

    /** How close, horizontally, counts as there: well inside any block, and under a walking step. */
    double ARRIVED = 0.05d;
}
