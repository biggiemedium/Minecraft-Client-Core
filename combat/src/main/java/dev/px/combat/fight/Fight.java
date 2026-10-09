package dev.px.combat.fight;

import dev.px.combat.monitor.Vitals;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.Tracked;
import dev.px.core.flow.Control;
import dev.px.core.flow.FlowContext;
import dev.px.core.flow.Key;
import dev.px.core.flow.Status;
import dev.px.core.flow.Step;
import dev.px.core.flow.StopReason;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.rotation.RotationRequest;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.util.Validate;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fight steps for Core's flows: each fights one target with your
 * {@link Attack}, turning the head with an {@link Aim} and moving with your
 * {@link Footwork}, until the target is lost.
 *
 * <pre>{@code
 * static final Key<Tracked<EntityPlayer>> TARGET = Key.of("target");
 *
 * Fight<EntityPlayer> fight = Fight.<EntityPlayer>builder()
 *         .attack(myAura)                 // required: your killaura's brain, or a CrystalAttack
 *         .aim(mySmoothing)               // optional: how the head turns; snaps by default
 *         .footwork(myStrafe)             // optional: the keys while fighting; none by default
 *         .keep(enemies)                  // optional: lost once it no longer passes your selector
 *         .vitals(myVitals)               // optional: its health is the step's progress
 *         .build();
 *
 * Step bot = Flow.loop(Flow.sequence(
 *         new FindEnemy(enemies, TARGET),
 *         travel.to(c -> Goal.near(c.get(TARGET), 4)),
 *         fight.against(TARGET).stuckAfter(Span.seconds(10), new Reposition())));
 * }</pre>
 *
 * <h2>A tick of a fight</h2>
 *
 * <ol>
 *   <li>The target is read from the key, or your function: once it is gone, no
 *       longer tracked, or no longer passes the {@linkplain Builder#keep selector},
 *       the step is done.
 *   <li>The attack is asked for a {@link Strike}. Its aim point is turned to
 *       through the aim, and its button clicked or held &mdash; if it named a box,
 *       only when the head, as turned this tick, looks at it. The attack then hears
 *       {@linkplain Attack#struck it went out}.
 *   <li>The footwork is asked for keys.
 * </ol>
 *
 * <p>Everything is claimed through the flow, at its priority: a reflex or a pause
 * takes it away at once, and nothing is left held. A part that
 * {@linkplain Part#drives drives} is asked each tick too, but nothing is claimed
 * for it; it must stop acting when {@linkplain Part#stop stopped}.
 *
 * <h2>Ending</h2>
 *
 * <p>Done when the target is lost, or when an {@code until} you put on the step
 * holds. Failed when there is no target as it starts, or when the attack returns
 * a {@linkplain Strike#failed failed strike}. Given {@linkplain Builder#vitals
 * vitals}, its {@linkplain Step#progress progress} is the target's health, so
 * {@code stuckAfter} notices a fight that is not landing.
 *
 * <p>The step declares {@link Control#ROTATION}, {@link Control#ATTACK} and
 * {@link Control#USE}, and {@link Control#MOVEMENT} when there is footwork, so a
 * reflex that needs any of them pauses it. To chase while fighting, race it with
 * a travel step that leaves the head alone:
 * {@code Flow.race(fight.against(TARGET), Flow.loop(chase.to(...)))}.
 *
 * <p>Immutable. Its parts are your objects and are shared by every step it
 * makes, so run one of those steps at a time. Game thread only.
 *
 * @param <E> the game's type for what is fought
 */
public final class Fight<E> {

    private final Attack<E> attack;
    private final Aim aim;
    private final Footwork<E> footwork;
    private final TargetSelector<E> keep;
    private final Vitals<? super E> vitals;
    private final Supplier<Vec3> eyes;

    private Fight(Builder<E> builder) {
        this.attack = builder.attack;
        this.aim = builder.aim;
        this.footwork = builder.footwork;
        this.keep = builder.keep;
        this.vitals = builder.vitals;
        this.eyes = builder.eyes;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** @return a new step that fights whoever is in {@code target}, read each tick */
    public Round<E> against(Key<Tracked<E>> target) {
        Validate.notNull(target, "target");
        return new Round<>(this, c -> c.get(target), "fight " + target.getName());
    }

    /** @return a new step that fights whoever {@code target} gives, asked each tick */
    public Round<E> against(Function<FlowContext, Tracked<E>> target) {
        Validate.notNull(target, "target");
        return new Round<>(this, target, "fight");
    }

    /**
     * A step that fights one target. Made by {@link Fight#against}.
     *
     * @param <E> the game's type for what is fought
     */
    public static final class Round<E> extends Step {

        private final Fight<E> fight;
        private final Function<FlowContext, Tracked<E>> targetOf;
        private Tracked<E> target;
        private Bout<E> bout;
        private int ticks;
        private String problem;
        private Strike lastStrike;
        private int strikes;
        private boolean warnedNoTargets;

        private Round(Fight<E> fight, Function<FlowContext, Tracked<E>> targetOf, String name) {
            super(name);
            this.fight = fight;
            this.targetOf = targetOf;
            uses(Control.ROTATION, Control.ATTACK, Control.USE);
            if (fight.footwork != null) {
                uses(Control.MOVEMENT);
            }
        }

        @Override
        protected void start(FlowContext c) {
            problem = null;
            if (!c.isResuming()) {
                ticks = 0;
                strikes = 0;
                lastStrike = null;
            }
            target = targetOf.apply(c);
            if (target == null || !target.isTracked()) {
                problem = "there is no target to fight";
                return;
            }
            Vec3 eye = eyes(c);
            if (eye == null) {
                return;
            }
            bout = new Bout<>(c, target, eye, ticks);
            fight.attack.start(bout);
            if (fight.aim != null) {
                fight.aim.start(bout);
            }
            if (fight.footwork != null) {
                fight.footwork.start(bout);
            }
        }

        @Override
        protected Status tick(FlowContext c) {
            if (problem != null) {
                return c.fail(problem);
            }
            Tracked<E> now = targetOf.apply(c);
            if (now == null || !now.isTracked() || !kept(c, now)) {
                return Status.DONE;
            }
            target = now;
            Vec3 eye = eyes(c);
            if (eye == null) {
                return c.fail(problem);
            }
            bout = new Bout<>(c, target, eye, ticks++);

            Strike strike = fight.attack.tick(bout);
            if (strike != null && strike.isFailed()) {
                return c.fail(strike.getFailure());
            }
            lastStrike = strike;
            if (strike != null && !fight.attack.drives()) {
                act(c, strike);
            }

            if (fight.footwork != null) {
                MovementInput keys = fight.footwork.keys(bout);
                if (keys != null && !fight.footwork.drives()) {
                    c.move(keys);
                }
            }
            return Status.RUNNING;
        }

        @Override
        protected void stop(FlowContext c, StopReason why) {
            if (bout == null) {
                return;
            }
            fight.attack.stop(bout, why);
            if (fight.aim != null) {
                fight.aim.stop(bout, why);
            }
            if (fight.footwork != null) {
                fight.footwork.stop(bout, why);
            }
            bout = null;
        }

        /** @return the target's health, given vitals; NaN otherwise */
        @Override
        protected double progress(FlowContext c) {
            if (fight.vitals == null || target == null || !target.isTracked()) {
                return Double.NaN;
            }
            return fight.vitals.pool(target.get());
        }

        /** @return who is being fought, or null before the step starts */
        public Tracked<E> getTarget() {
            return target;
        }

        /** @return what the attack asked for last tick; null before */
        public Strike getLastStrike() {
            return lastStrike;
        }

        /** @return how many strikes went out: clicked or held, facing their box */
        public int getStrikes() {
            return strikes;
        }

        /** @return ticks fought, pauses not counted */
        public int getTicks() {
            return ticks;
        }

        /** Turns to the strike's aim and clicks if the head is on its box. */
        private void act(FlowContext c, Strike strike) {
            Vec3 point = strike.getAim();
            if (point != null) {
                Vec2 wanted = bout.rotationTo(point);
                Aim aim = fight.aim != null ? fight.aim : Aim.snap();
                RotationRequest request = aim.turn(bout, point, wanted);
                if (request != null && !aim.drives()) {
                    c.look(request);
                }
            }
            if (strike.getButton() == null) {
                return;
            }
            if (strike.getFacing() != null && !bout.isFacing(strike.getFacing())) {
                return;
            }
            if (strike.isHeld()) {
                c.hold(strike.getButton());
            } else {
                c.click(strike.getButton());
            }
            strikes++;
            fight.attack.struck(bout, strike);
        }

        private boolean kept(FlowContext c, Tracked<E> candidate) {
            if (fight.keep == null) {
                return true;
            }
            TargetService targets = c.service(TargetService.class);
            if (targets == null) {
                // Rule 6: a missing piece costs the feature, never the fight.
                if (!warnedNoTargets) {
                    warnedNoTargets = true;
                    c.logger().warn("Fight '" + getName() + "' has a selector to keep but the flow's host provides"
                            + " no TargetService; the target is kept while it is tracked");
                }
                return true;
            }
            return targets.accepts(fight.keep, candidate);
        }

        /** @return the eyes, or null with the problem kept */
        private Vec3 eyes(FlowContext c) {
            if (fight.eyes != null) {
                Vec3 eye = fight.eyes.get();
                if (eye == null) {
                    problem = "the eyes supplier gave null";
                }
                return eye;
            }
            EntityService entities = c.service(EntityService.class);
            Tracked<Object> self = entities == null ? null : entities.getSelf();
            if (self == null) {
                problem = "the player's eyes are unknown: give the Fight eyes(...), or let the flow's host"
                        + " provide an EntityService that knows the player";
                return null;
            }
            return self.getEyePosition();
        }
    }

    /**
     * Builds a {@link Fight}. Needs an attack.
     *
     * @param <E> the game's type for what is fought
     */
    public static final class Builder<E> {

        private Attack<E> attack;
        private Aim aim;
        private Footwork<E> footwork;
        private TargetSelector<E> keep;
        private Vitals<? super E> vitals;
        private Supplier<Vec3> eyes;

        private Builder() {
        }

        /** When to swing and at what: your killaura's brain, or a ready-made one such as {@link CrystalAttack}. */
        public Builder<E> attack(Attack<E> attack) {
            this.attack = Validate.notNull(attack, "attack");
            return this;
        }

        /** How the head turns to the attack's aim. {@link Aim#snap()} if not given. */
        public Builder<E> aim(Aim aim) {
            this.aim = Validate.notNull(aim, "aim");
            return this;
        }

        /** The keys while fighting. Without it the step leaves the keys alone, and does not declare them. */
        public Builder<E> footwork(Footwork<E> footwork) {
            this.footwork = Validate.notNull(footwork, "footwork");
            return this;
        }

        /** The target is lost, and the fight done, once it no longer passes {@code selector}: out of range, a friend now. */
        public Builder<E> keep(TargetSelector<E> selector) {
            this.keep = Validate.notNull(selector, "selector");
            return this;
        }

        /** How much the target can still take: the step's progress, for {@code stuckAfter}. */
        public Builder<E> vitals(Vitals<? super E> vitals) {
            this.vitals = Validate.notNull(vitals, "vitals");
            return this;
        }

        /**
         * Where the player's eyes are, read each tick. Without it, the eyes of the
         * player the host's {@link EntityService} tracks.
         */
        public Builder<E> eyes(Supplier<Vec3> eyes) {
            this.eyes = Validate.notNull(eyes, "eyes");
            return this;
        }

        public Fight<E> build() {
            if (attack == null) {
                throw new IllegalStateException("a Fight needs: attack");
            }
            return new Fight<>(this);
        }
    }
}
