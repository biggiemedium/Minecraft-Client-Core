package dev.px.combat.search;

import dev.px.combat.bed.Bed;
import dev.px.combat.bed.BedPart;
import dev.px.combat.bed.BedRules;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.search.engine.ExplosiveSearch;
import dev.px.combat.search.engine.Found;
import dev.px.combat.search.engine.PlaceDevice;
import dev.px.combat.search.engine.SearchStats;
import dev.px.combat.search.engine.UseDevice;
import dev.px.combat.search.option.BedPlaceOption;
import dev.px.combat.search.option.BedUseOption;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.timing.AttackLog;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Rays;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Finds where to place a bed and set it off, and which bed already in the world
 * to use: the searching a bed aura would otherwise do by hand.
 *
 * <pre>{@code
 * BedSearch<LivingEntity> search = BedSearch.<LivingEntity>builder()
 *         .rules(beds)                                            // BedRules for your version
 *         .entities(Core.entities())
 *         .targets(Core.targets(), enemies)
 *         .vitals(myVitals)
 *         .placeReach(Reach.of(placeRange::getDouble, placeWall::getDouble))
 *         .useReach(Reach.of(useRange::getDouble, useWall::getDouble))
 *         .thresholds(myThresholds)
 *         .log(sharedLog)                                         // the same log as your crystal aura's
 *         .build();
 *
 * // each tick, in your aura:
 * BedUseOption<LivingEntity> bed = search.findUse();
 * if (bed != null) { use(bed.getCell()); search.used(bed.getBed()); }
 * BedPlaceOption<LivingEntity> spot = search.findPlace();
 * if (spot != null) {
 *     place(spot.getFoot(), spot.getFacing().toYaw());          // foot here, head the way you face
 *     use(spot.getFoot());
 *     search.used(spot.getBed());
 * }
 * }</pre>
 *
 * <p>This is {@link ExplosiveSearch} with beds plugged in: the scan, the no-ray
 * bound, branch and bound, every threshold and the timing are described there,
 * and shared with {@code CrystalSearch}. What is a bed's own:
 *
 * <ul>
 *   <li><b>Only where beds explode.</b> While {@code BedRules.explodes()} is
 *       false &mdash; in the Overworld, say &mdash; nothing is found.
 *   <li><b>Placing.</b> Up to four spots per cell, one per way the bed can face:
 *       every compass direction unless {@link Builder#facings} narrows it. Reach is
 *       measured to the foot's cell, the one you place into.
 *   <li><b>At once.</b> A bed is placed and used in the same tick, so what has
 *       already gone off this tick counts against a placement, as it does
 *       against a bed already standing.
 *   <li><b>Beds in the world</b> are blocks, found by a scan of the cells around
 *       you with your {@code BedLookup}, and remembered by position for inhibit.
 *       Either usable half in reach will do; the option says which to click.
 *   <li><b>No minimum age.</b> The game says nothing of how long a bed has stood,
 *       so {@code Thresholds.breakMinAge} never stops one.
 * </ul>
 *
 * <p>Every prediction is made with the bed's own cells empty: it is gone before
 * it explodes. See {@code BedRules}.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class BedSearch<E> {

    private final BedRules<E> rules;
    private final Supplier<Direction[]> facings;
    private final ExplosiveSearch<E> engine;
    private final Placing placing = new Placing();
    private final Using using = new Using();
    /** The half each bed in reach was reached by, during one {@link #findUse}. */
    private final Map<Bed, BedPart> reached = new HashMap<>();

    private BedSearch(Builder<E> builder) {
        this.rules = builder.rules;
        this.facings = builder.facings;
        this.engine = builder.newEngine();
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** @return the best place to put a bed and set it off now, or null when nowhere is worth it */
    public BedPlaceOption<E> findPlace() {
        Found<E, Bed> found = engine.findPlace(placing);
        return found == null ? null : new BedPlaceOption<>(found.getSubject(), found);
    }

    /** @return the best bed already in the world to use now, or null when none is worth it */
    public BedUseOption<E> findUse() {
        reached.clear();
        Found<E, Bed> found = engine.findUse(using);
        return found == null ? null : new BedUseOption<>(found.getSubject(), reached.get(found.getSubject()), found);
    }

    /**
     * You set {@code bed} off, placed this tick or already standing: it is
     * inhibited, and what it does is recorded against everyone it reaches for the
     * rest of this tick.
     */
    public void used(Bed bed) {
        Validate.notNull(bed, "bed");
        engine.used(using, bed);
    }

    /** Starts a new tick; see {@link ExplosiveSearch#tick}. {@link Builder#bus} does this for you. */
    public void tick() {
        engine.tick();
    }

    public AttackLog getLog() {
        return engine.getLog();
    }

    /** @return what the last {@link #findPlace} did */
    public SearchStats getLastPlaceStats() {
        return engine.getLastPlaceStats();
    }

    /** @return what the last {@link #findUse} did */
    public SearchStats getLastUseStats() {
        return engine.getLastUseStats();
    }

    /** @return the engine underneath, for searching with a device of your own on the same settings */
    public ExplosiveSearch<E> getEngine() {
        return engine;
    }

    /** Stops listening to the bus given at build time. */
    public void close() {
        engine.close();
    }

    // ------------------------------------------------------------ internals

    /** @return the usable half of {@code bed} in reach from {@code eye}, the nearer if both are; null if neither */
    private BedPart part(Bed bed, Vec3 eye, Reach reach) {
        BlockView world = rules.blocksWhenFired(bed);
        BedPart best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (BedPart part : BedPart.values()) {
            if (!rules.isUsable(part)) {
                continue;
            }
            Vec3i cell = bed.get(part);
            double distance = reach.distance(eye, cell.toBox());
            if (distance < nearest && reach.reaches(eye, cell.toBox(), cell.center(), world)) {
                best = part;
                nearest = distance;
            }
        }
        return best;
    }

    /** A bed placed with its foot in a cell, one spot per way it may face. */
    private final class Placing implements PlaceDevice<E, Bed> {

        @Override
        public ExplosionModel<E> model() {
            return rules.getModel();
        }

        @Override
        public Explosive explosive() {
            return rules.getExplosive();
        }

        @Override
        public boolean active() {
            return rules.explodes();
        }

        @Override
        public void spotsAt(int x, int y, int z, Consumer<? super Bed> sink) {
            Vec3i foot = Vec3i.of(x, y, z);
            for (Direction facing : facings.get()) {
                Bed bed = Bed.of(foot, facing);
                if (rules.canPlace(bed)) {
                    sink.accept(bed);
                }
            }
        }

        @Override
        public boolean visible(Vec3 eye, int x, int y, int z) {
            // The foot's cell may hold something replaceable, which the bed replaces.
            Vec3i foot = Vec3i.of(x, y, z);
            return Rays.clear(eye, foot.center(), rules.getBlocks().without(foot));
        }

        @Override
        public Vec3 origin(Bed bed) {
            return rules.origin(bed);
        }

        @Override
        public BlockView blocksWhenFired(Bed bed) {
            return rules.blocksWhenFired(bed);
        }

        @Override
        public boolean firesAtOnce() {
            return true;
        }
    }

    /** Beds already in the world, found by their heads. */
    private final class Using implements UseDevice<E, Bed> {

        @Override
        public ExplosionModel<E> model() {
            return rules.getModel();
        }

        @Override
        public Explosive explosive() {
            return rules.getExplosive();
        }

        @Override
        public boolean active() {
            return rules.explodes();
        }

        @Override
        public void forEachWithin(Vec3 eye, double range, Consumer<? super Bed> sink) {
            // A foot is one cell from its head, so a head up to a block further out can still be in reach.
            double far = range + 1d;
            int reach = (int) Math.ceil(far);
            int ex = (int) Math.floor(eye.getX());
            int ey = (int) Math.floor(eye.getY());
            int ez = (int) Math.floor(eye.getZ());
            for (int x = ex - reach; x <= ex + reach; x++) {
                for (int y = ey - reach; y <= ey + reach; y++) {
                    for (int z = ez - reach; z <= ez + reach; z++) {
                        if (nearest(eye.getX(), x) + nearest(eye.getY(), y) + nearest(eye.getZ(), z) > far * far) {
                            continue;
                        }
                        Bed bed = rules.bedAt(x, y, z);
                        if (bed != null) {
                            sink.accept(bed);
                        }
                    }
                }
            }
        }

        @Override
        public boolean inReach(Bed bed, Vec3 eye, Reach reach) {
            BedPart part = part(bed, eye, reach);
            if (part != null) {
                reached.put(bed, part);
            }
            return part != null;
        }

        @Override
        public Vec3 origin(Bed bed) {
            return rules.origin(bed);
        }

        @Override
        public BlockView blocksWhenFired(Bed bed) {
            return rules.blocksWhenFired(bed);
        }

        @Override
        public Object key(Bed bed) {
            return bed;
        }

        @Override
        public int age(Bed bed) {
            return Integer.MAX_VALUE;
        }
    }

    /** @return the squared distance along one axis from {@code eye} to the nearest point of cell {@code cell} */
    private static double nearest(double eye, int cell) {
        double gap = eye < cell ? cell - eye : eye > cell + 1 ? eye - (cell + 1) : 0d;
        return gap * gap;
    }

    public static final class Builder<E> extends ExplosiveSearch.Settings<E, Builder<E>> {

        private BedRules<E> rules;
        private Supplier<Direction[]> facings = Direction::horizontals;

        private Builder() {
        }

        @Override
        protected Builder<E> self() {
            return this;
        }

        /** Required: your version's bed rules. */
        public Builder<E> rules(BedRules<E> rules) {
            this.rules = Validate.notNull(rules, "rules");
            return this;
        }

        /**
         * Which ways a bed may face when placed, read live: all four unless set.
         * Narrow it if you will not turn to place, say to
         * {@code () -> new Direction[] { Direction.fromYaw(player.yaw) }}.
         */
        public Builder<E> facings(Supplier<Direction[]> facings) {
            this.facings = Validate.notNull(facings, "facings");
            return this;
        }

        /** Required: how far away you use a bed. */
        public Builder<E> useReach(Reach reach) {
            return reachToUse(reach);
        }

        /** @throws IllegalStateException naming each required part not given */
        public BedSearch<E> build() {
            StringBuilder missing = new StringBuilder();
            if (rules == null) {
                missing.append(" rules");
            }
            missing(missing, "useReach");
            if (missing.length() > 0) {
                throw new IllegalStateException("a BedSearch needs:" + missing);
            }
            return new BedSearch<>(this);
        }

        /** The engine, built where the settings' protected parts can be reached. */
        private ExplosiveSearch<E> newEngine() {
            return engine();
        }
    }
}
