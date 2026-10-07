package dev.px.projectile.flight;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;
import dev.px.core.world.RayHit;
import dev.px.core.world.Rays;
import dev.px.projectile.Launch;
import dev.px.projectile.ProjectileRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Where a projectile goes in your world, tick by tick, and what it hits.
 *
 * <pre>{@code
 * Flight flight = Flight.builder(pearl)
 *         .blocks(Game.blocks())                                   // your BlockView
 *         .entities(Core.entities(), players, mobs)               // what it can hit, you included
 *         .build();
 *
 * Trajectory yours = flight.launch(pearlLaunch.launch(you, rotation));   // where yours would land
 * Trajectory theirs = flight.from(trackedPearl, Game.velocity(trackedPearl.get()));   // where theirs will land
 * }</pre>
 *
 * <p>Each tick it accelerates, drags and moves in the order its
 * {@link ProjectileRules} say, and every move is checked as the
 * <a href="https://minecraft.wiki/w/Projectile#Collision">wiki</a> describes:
 * a line from where it is to where it is going, against block shapes first,
 * then against the box of every entity it can hit, widened by its entity
 * margin, up to the block. The nearest entity wins over the block. The flight
 * ends at whatever it hits, or when it runs out of ticks.
 *
 * <p>Entities are where their trackers saw them this tick; a flight does not
 * move them. Which it can hit is the trackers you give, plus you when an
 * {@link EntityService} is given, less anything your {@link Builder#filter}
 * refuses. A projectile you launch never hits you: where the game lets one
 * clear its owner is not on the wiki.
 *
 * <p>Holds no state between flights. Game thread only.
 */
public final class Flight {

    /** Ticks a flight runs for unless {@link Builder#maxTicks} says otherwise: a tuning knob, not a game fact. */
    public static final int DEFAULT_MAX_TICKS = 300;

    private final ProjectileRules rules;
    private final BlockView blocks;
    private final EntityService entities;
    private final List<EntityTracker<?>> trackers;
    private final Predicate<? super Tracked<?>> filter;
    private final IntSupplier maxTicks;

    private Flight(Builder builder) {
        this.rules = builder.rules;
        this.blocks = builder.blocks;
        this.entities = builder.entities;
        this.trackers = builder.trackers;
        this.filter = builder.filter;
        this.maxTicks = builder.maxTicks;
    }

    public static Builder builder(ProjectileRules rules) {
        return new Builder(rules);
    }

    public ProjectileRules getRules() {
        return rules;
    }

    /** @return how many ticks a flight runs for, read now */
    public int currentMaxTicks() {
        return Math.max(0, maxTicks.getAsInt());
    }

    /** @return where a projectile you launch goes; it never hits you */
    public Trajectory launch(Launch launch) {
        return launch(launch, entity -> false);
    }

    /**
     * @param ignoring entities it passes through as well as you, such as the one
     *                 an aim is being checked against
     * @return where a projectile you launch goes
     */
    public Trajectory launch(Launch launch, Predicate<? super Tracked<?>> ignoring) {
        Validate.notNull(launch, "launch");
        Validate.notNull(ignoring, "ignoring");
        Tracked<?> self = entities != null ? entities.getSelf() : null;
        Object you = self != null ? self.get() : null;
        return fly(Motion.of(rules, launch.getPosition(), launch.getVelocity()), launch.getSpread(),
                entity -> entity.get() == you || ignoring.test(entity));
    }

    /**
     * @param velocity in blocks per tick: your game's own velocity for it, which
     *                 is exact
     * @return where a projectile at {@code position} goes; it can hit you
     */
    public Trajectory from(Vec3 position, Vec3 velocity) {
        return fly(Motion.of(rules, position, velocity), 0d, entity -> false);
    }

    /**
     * Where a projectile already flying goes, from where its tracker saw it and
     * how far it moved last tick.
     *
     * <p>It needs two ticks of history to have a move to go on: until then its
     * velocity is nothing, and it is shown falling from where it is. Where your
     * client can read the projectile's own velocity, {@link #from(Tracked, Vec3)}
     * with it is exact.
     *
     * @return where it goes; it can hit you, and never hits itself
     */
    public Trajectory from(Tracked<?> projectile) {
        Validate.notNull(projectile, "projectile");
        Object itself = projectile.get();
        return fly(Motion.following(rules, projectile.getPosition(), projectile.getVelocity()), 0d,
                entity -> entity.get() == itself);
    }

    /**
     * @param velocity in blocks per tick: your game's own velocity for it
     * @return where a projectile already flying goes, from where its tracker saw
     *         it; it can hit you, and never hits itself
     */
    public Trajectory from(Tracked<?> projectile, Vec3 velocity) {
        Validate.notNull(projectile, "projectile");
        Validate.notNull(velocity, "velocity");
        Object itself = projectile.get();
        return fly(Motion.of(rules, projectile.getPosition(), velocity), 0d, entity -> entity.get() == itself);
    }

    private Trajectory fly(Motion motion, double spread, Predicate<? super Tracked<?>> ignore) {
        int limit = currentMaxTicks();
        List<Vec3> points = new ArrayList<>();
        List<Vec3> velocities = new ArrayList<>();
        double[] reach = new double[limit + 1];
        points.add(motion.getPosition());
        velocities.add(motion.getVelocity());
        Scan scan = new Scan(ignore);
        for (int tick = 1; tick <= limit; tick++) {
            double stopped = motion.tick(scan);
            Vec3 at = motion.getPosition();
            points.add(at);
            velocities.add(motion.getVelocity());
            reach[tick] = motion.getReach();
            if (!Double.isNaN(stopped)) {
                Hit hit = scan.entity != null ? Hit.entity(at, tick, stopped, scan.entity)
                        : Hit.block(at, tick, stopped, scan.block.getCell(), scan.block.getFace());
                return new Trajectory(points, velocities, Arrays.copyOf(reach, tick + 1), spread, hit);
            }
        }
        return new Trajectory(points, velocities, reach, spread, Hit.none(motion.getPosition(), limit));
    }

    /** One flight's look at the world, a move at a time. */
    private final class Scan implements Motion.Collider {

        private final Predicate<? super Tracked<?>> ignore;
        private RayHit block;
        private Tracked<?> entity;
        private double best;
        private Vec3 from;
        private Vec3 to;

        Scan(Predicate<? super Tracked<?>> ignore) {
            this.ignore = ignore;
        }

        @Override
        public double clip(double x1, double y1, double z1, double x2, double y2, double z2) {
            from = Vec3.of(x1, y1, z1);
            to = Vec3.of(x2, y2, z2);
            block = Rays.first(from, to, blocks);
            entity = null;
            best = block != null ? block.getFraction() : Double.NaN;
            if (entities != null) {
                Tracked<?> self = entities.getSelf();
                if (self != null) {
                    consider(self);
                }
            }
            if (!trackers.isEmpty()) {
                // Any box the widened move meets comes within half the move and the margin of its middle.
                Vec3 middle = from.lerp(to, 0.5d);
                double radius = from.distanceTo(to) / 2d + rules.getEntityMargin();
                for (EntityTracker<?> tracker : trackers) {
                    tracker.forEachWithin(middle, radius, this::consider);
                }
            }
            return best;
        }

        private void consider(Tracked<?> candidate) {
            if (ignore.test(candidate) || !filter.test(candidate)) {
                return;
            }
            double at = candidate.getBox().expand(rules.getEntityMargin()).clip(from, to);
            if (Double.isNaN(at)) {
                return;
            }
            // An entity as far along as the block still wins: the game checks entities up to the block's point.
            boolean nearer = Double.isNaN(best) || at < best || (entity == null && at <= best);
            if (nearer) {
                best = at;
                entity = candidate;
            }
        }
    }

    public static final class Builder {

        private final ProjectileRules rules;
        private BlockView blocks;
        private EntityService entities;
        private List<EntityTracker<?>> trackers = new ArrayList<>();
        private Predicate<? super Tracked<?>> filter = entity -> true;
        private IntSupplier maxTicks = () -> DEFAULT_MAX_TICKS;

        private Builder(ProjectileRules rules) {
            this.rules = Validate.notNull(rules, "rules");
        }

        /** Required: the blocks it can hit. {@link BlockView#EMPTY} for none. */
        public Builder blocks(BlockView blocks) {
            this.blocks = Validate.notNull(blocks, "blocks");
            return this;
        }

        /** The entities it can hit: these trackers' and yours. None unless set. */
        public Builder entities(EntityService entities, EntityTracker<?>... trackers) {
            this.entities = Validate.notNull(entities, "entities");
            this.trackers = new ArrayList<>(Arrays.asList(trackers.clone()));
            return this;
        }

        /** The entities it can hit: only these trackers', never you. None unless set. */
        public Builder entities(EntityTracker<?>... trackers) {
            this.entities = null;
            this.trackers = new ArrayList<>(Arrays.asList(trackers.clone()));
            return this;
        }

        /** Which of those entities it can hit; all of them unless set. Asked as a flight reaches each. */
        public Builder filter(Predicate<? super Tracked<?>> filter) {
            this.filter = Validate.notNull(filter, "filter");
            return this;
        }

        /** How many ticks a flight runs before it stops looking; {@link #DEFAULT_MAX_TICKS} unless set. */
        public Builder maxTicks(int ticks) {
            Validate.check(ticks >= 0, "maxTicks can not be negative");
            this.maxTicks = () -> ticks;
            return this;
        }

        /** A limit read at each flight, such as from a setting. */
        public Builder maxTicks(IntSupplier ticks) {
            this.maxTicks = Validate.notNull(ticks, "maxTicks");
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public Flight build() {
            if (blocks == null) {
                throw new IllegalStateException("a Flight (" + rules.getName() + ") needs: blocks");
            }
            return new Flight(this);
        }
    }
}
