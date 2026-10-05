package dev.px.combat.search;

import dev.px.combat.crystal.CrystalRules;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.search.engine.ExplosiveSearch;
import dev.px.combat.search.engine.Found;
import dev.px.combat.search.engine.PlaceDevice;
import dev.px.combat.search.engine.SearchStats;
import dev.px.combat.search.engine.UseDevice;
import dev.px.combat.search.option.BreakOption;
import dev.px.combat.search.option.PlaceOption;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.timing.AttackLog;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Rays;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Finds where to place a crystal and which crystal to break: the searching your
 * crystal aura would otherwise do by hand. Your aura decides when to act, how to
 * rotate and swap, and what to draw; this decides what is worth doing.
 *
 * <pre>{@code
 * CrystalSearch<LivingEntity> search = CrystalSearch.<LivingEntity>builder()
 *         .rules(crystals)                                        // CrystalRules for your version
 *         .entities(Core.entities())                              // you
 *         .targets(Core.targets(), enemies)                       // a TargetSelector: who counts
 *         .crystals(Core.entities().get(CrystalTracker.class))    // crystals already in the world
 *         .vitals(myVitals)
 *         .placeReach(Reach.of(placeRange::getDouble, placeWall::getDouble))
 *         .breakReach(Reach.of(breakRange::getDouble, breakWall::getDouble))
 *         .thresholds(myThresholds)
 *         .bus(Core.bus())                                        // ticks itself
 *         .build();
 *
 * // each tick, in your aura:
 * BreakOption<LivingEntity> hit = search.findBreak();
 * if (hit != null) { attack(hit.getCrystal().get()); search.attacked(hit.getCrystal()); }
 * PlaceOption<LivingEntity> spot = search.findPlace();
 * if (spot != null) place(spot.getX(), spot.getY(), spot.getZ());
 *
 * // or the best few, to fall back on when you cannot reach the first:
 * for (PlaceOption<LivingEntity> option : search.findPlaces(3)) {
 *     if (canRotateTo(option)) { place(option.getX(), option.getY(), option.getZ()); break; }
 * }
 *
 * // from your spawn-packet handler, for an instant break:
 * BreakOption<LivingEntity> now = search.spawned(crystalEntity);
 * }</pre>
 *
 * <p>This is {@link ExplosiveSearch} with crystals plugged in: the scan, the
 * no-ray bound, branch and bound, every threshold and the timing are described
 * there, and shared with {@code BedSearch}. What is a crystal's own:
 *
 * <ul>
 *   <li><b>Placing.</b> One spot per cell: a base {@code CrystalRules.canPlace}
 *       accepts. Its reach is measured to the base block, and past the wall
 *       range the point just above the base's top face must be in sight.
 *   <li><b>Breaking.</b> Crystals within break range come from your tracker's
 *       spatial grid, not a scan of every entity, measured to their boxes. Their
 *       age is how long the tracker has held them, so a crystal from
 *       {@link #spawned} is never too young &mdash; the point of breaking on spawn.
 *   <li><b>Timing.</b> A crystal is remembered by its entity, so inhibit
 *       follows it until it is gone.
 * </ul>
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class CrystalSearch<E> {

    /** How far above a base's top face its visibility is tested, so the ray does not graze the base itself. */
    private static final double ABOVE = 1e-4d;

    private final CrystalRules<E> rules;
    private final EntityTracker<?> crystals;
    private final ExplosiveSearch<E> engine;
    private final Placing placing = new Placing();
    private final Breaking breaking = new Breaking();

    private CrystalSearch(Builder<E> builder) {
        this.rules = builder.rules;
        this.crystals = builder.crystals;
        this.engine = builder.newEngine();
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** @return the best place to put a crystal now, or null when nowhere is worth it */
    public PlaceOption<E> findPlace() {
        Found<E, Vec3i> found = engine.findPlace(placing);
        return found == null ? null : toPlace(found);
    }

    /**
     * The best {@code count} places to put a crystal now, best first: somewhere to
     * fall back on when you cannot rotate to or reach the first. They are
     * alternatives, not a set to fill together &mdash; two may be too close to both
     * hold a crystal.
     *
     * @return at most {@code count} options, each on its own base; empty when nowhere is worth it
     */
    public List<PlaceOption<E>> findPlaces(int count) {
        List<Found<E, Vec3i>> found = engine.findPlaces(placing, count);
        List<PlaceOption<E>> places = new ArrayList<>(found.size());
        for (Found<E, Vec3i> option : found) {
            places.add(toPlace(option));
        }
        return places;
    }

    /** @return the best crystal to break now, or null when none is worth it */
    public BreakOption<E> findBreak() {
        return toBreak(engine.findUse(breaking));
    }

    /**
     * A crystal just spawned: should it be broken now? Call it from your spawn
     * packet handler, on the game thread. The crystal is tracked at once, so the
     * minimum age does not apply; everything else does.
     *
     * @param crystal the game's own new crystal entity
     * @return the option to take now, or null to leave it
     */
    @SuppressWarnings("unchecked")
    public BreakOption<E> spawned(Object crystal) {
        Tracked<?> tracked = ((EntityTracker<Object>) crystals).track(crystal);
        return tracked == null ? null : toBreak(engine.evaluate(breaking, tracked, true));
    }

    /**
     * You attacked {@code crystal}: it is inhibited, and what it does is recorded
     * against everyone it reaches for the rest of this tick.
     */
    public void attacked(Tracked<?> crystal) {
        Validate.notNull(crystal, "crystal");
        engine.used(breaking, crystal);
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

    /** @return what the last {@link #findBreak} or {@link #spawned} did */
    public SearchStats getLastBreakStats() {
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

    private PlaceOption<E> toPlace(Found<E, Vec3i> found) {
        Vec3i base = found.getSubject();
        return new PlaceOption<>(base.getX(), base.getY(), base.getZ(), found);
    }

    private BreakOption<E> toBreak(Found<E, Tracked<?>> found) {
        return found == null ? null : new BreakOption<>(found.getSubject(), found);
    }

    /** A crystal on a base: one spot per cell. */
    private final class Placing implements PlaceDevice<E, Vec3i> {

        @Override
        public ExplosionModel<E> model() {
            return rules.getModel();
        }

        @Override
        public Explosive explosive() {
            return rules.getExplosive();
        }

        @Override
        public void spotsAt(int x, int y, int z, Consumer<? super Vec3i> sink) {
            if (rules.canPlace(x, y, z)) {
                sink.accept(Vec3i.of(x, y, z));
            }
        }

        @Override
        public boolean visible(Vec3 eye, int x, int y, int z) {
            return Rays.clear(eye, Vec3.of(x + 0.5, y + 1 + ABOVE, z + 0.5), rules.getBlocks());
        }

        @Override
        public Vec3 origin(Vec3i base) {
            return rules.origin(base.getX(), base.getY(), base.getZ());
        }

        @Override
        public BlockView blocksWhenFired(Vec3i base) {
            return rules.getBlocks();
        }
    }

    /** Crystals in the world, from your tracker. */
    private final class Breaking implements UseDevice<E, Tracked<?>> {

        @Override
        public ExplosionModel<E> model() {
            return rules.getModel();
        }

        @Override
        public Explosive explosive() {
            return rules.getExplosive();
        }

        @Override
        public void forEachWithin(Vec3 eye, double range, Consumer<? super Tracked<?>> sink) {
            crystals.forEachWithin(eye, range, sink::accept);
        }

        @Override
        public boolean inReach(Tracked<?> crystal, Vec3 eye, Reach reach) {
            return reach.reaches(eye, crystal.getBox(), crystal.getCenter(), rules.getBlocks());
        }

        @Override
        public Vec3 origin(Tracked<?> crystal) {
            return rules.origin(crystal);
        }

        @Override
        public BlockView blocksWhenFired(Tracked<?> crystal) {
            return rules.getBlocks();
        }

        @Override
        public Object key(Tracked<?> crystal) {
            return crystal.get();
        }

        @Override
        public int age(Tracked<?> crystal) {
            return crystal.getTicksTracked();
        }
    }

    public static final class Builder<E> extends ExplosiveSearch.Settings<E, Builder<E>> {

        private CrystalRules<E> rules;
        private EntityTracker<?> crystals;

        private Builder() {
        }

        @Override
        protected Builder<E> self() {
            return this;
        }

        /** Required: your version's crystal rules. */
        public Builder<E> rules(CrystalRules<E> rules) {
            this.rules = Validate.notNull(rules, "rules");
            return this;
        }

        /** Required: the tracker holding crystals already in the world. */
        public Builder<E> crystals(EntityTracker<?> crystals) {
            this.crystals = Validate.notNull(crystals, "crystals");
            return this;
        }

        /** Required: how far away you break crystals. */
        public Builder<E> breakReach(Reach reach) {
            return reachToUse(reach);
        }

        /** @throws IllegalStateException naming each required part not given */
        public CrystalSearch<E> build() {
            StringBuilder missing = new StringBuilder();
            if (rules == null) {
                missing.append(" rules");
            }
            if (crystals == null) {
                missing.append(" crystals");
            }
            missing(missing, "breakReach");
            if (missing.length() > 0) {
                throw new IllegalStateException("a CrystalSearch needs:" + missing);
            }
            return new CrystalSearch<>(this);
        }

        /** The engine, built where the settings' protected parts can be reached. */
        private ExplosiveSearch<E> newEngine() {
            return engine();
        }
    }
}
