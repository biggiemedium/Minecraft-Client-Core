package dev.px.combat.search;

import dev.px.combat.anchor.Anchor;
import dev.px.combat.anchor.AnchorRules;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.place.Click;
import dev.px.combat.place.Clicks;
import dev.px.combat.search.engine.ExplosiveSearch;
import dev.px.combat.search.engine.Found;
import dev.px.combat.search.engine.PlaceDevice;
import dev.px.combat.search.engine.SearchStats;
import dev.px.combat.search.engine.UseDevice;
import dev.px.combat.search.option.AnchorPlaceOption;
import dev.px.combat.search.option.AnchorUseOption;
import dev.px.combat.search.rule.AimCost;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.timing.AttackLog;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;
import dev.px.core.world.Rays;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Finds where to place a respawn anchor and set it off, and which anchor already
 * in the world to use: the searching an anchor aura would otherwise do by hand.
 * Which item is held for each click &mdash; the anchor, glowstone, anything else
 * &mdash; is your module's.
 *
 * <pre>{@code
 * AnchorSearch<LivingEntity> search = AnchorSearch.<LivingEntity>builder()
 *         .rules(anchors)                                         // AnchorRules for your version
 *         .entities(Core.entities())
 *         .targets(Core.targets(), enemies)
 *         .vitals(myVitals)
 *         .placeReach(Reach.of(placeRange::getDouble, placeWall::getDouble))
 *         .useReach(Reach.of(useRange::getDouble, useWall::getDouble))
 *         .thresholds(myThresholds)
 *         .log(sharedLog)                                         // the same log as your crystal and bed auras'
 *         .build();
 *
 * // each tick, in your aura:
 * AnchorUseOption<LivingEntity> anchor = search.findUse();
 * if (anchor != null) {
 *     if (anchor.getChargesNeeded() > 0) { holdGlowstone(); use(anchor.getCell()); }
 *     holdAnythingElse(); use(anchor.getCell());
 *     search.used(anchor.getAnchor());
 * }
 * AnchorPlaceOption<LivingEntity> spot = search.findPlace();
 * if (spot != null) {
 *     holdAnchor(); place(spot.getCell());
 *     holdGlowstone(); use(spot.getCell());
 *     holdAnythingElse(); use(spot.getCell());
 *     search.used(spot.getCell());
 * }
 * }</pre>
 *
 * <p>This is {@link ExplosiveSearch} with anchors plugged in: the scan, the
 * no-ray bound, branch and bound, every threshold and the timing are described
 * there, and shared with {@code CrystalSearch} and {@code BedSearch}. What is an
 * anchor's own:
 *
 * <ul>
 *   <li><b>Only where anchors explode.</b> While {@code AnchorRules.explodes()} is
 *       false &mdash; in the Nether, say &mdash; nothing is found.
 *   <li><b>Placing.</b> One spot per cell: the anchor goes into it, and you aim
 *       at the cell. With click rules, a spot needs a click to place it and,
 *       placing in one tick, a click on the anchor to charge it and set it off.
 *   <li><b>In one tick, or over several.</b> Placed, charged and set off in the
 *       same tick unless {@link Builder#oneTick} says otherwise, so what has
 *       already gone off this tick counts against a placement. A server that takes
 *       one click a tick needs three: say so, call {@link #placed} once it is
 *       down, and {@link #findUse} finds it standing on the ticks after, empty or
 *       charged, and yours.
 *   <li><b>Anchors in the world</b> are blocks, found by a scan of the cells
 *       around you with your {@code AnchorLookup}, and remembered by their cell
 *       for inhibit, whatever their charges. An empty one is offered too, needing
 *       a charge first, unless {@link Builder#useEmpty} says not.
 *   <li><b>No minimum age.</b> The game says nothing of how long an anchor has
 *       stood, so {@code Thresholds.breakMinAge} never stops one.
 * </ul>
 *
 * <p>Every prediction is made with the anchor's own cell empty: it is gone
 * before it explodes. See {@code AnchorRules}.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class AnchorSearch<E> {

    private final AnchorRules<E> rules;
    /** Null without click rules. */
    private final Clicks clicks;
    private final BooleanSupplier oneTick;
    private final BooleanSupplier useEmpty;
    private final ExplosiveSearch<E> engine;
    private final Placing placing = new Placing();
    private final Using using = new Using();

    private AnchorSearch(Builder<E> builder) {
        this.rules = builder.rules;
        this.clicks = builder.clicks;
        this.oneTick = builder.oneTick;
        this.useEmpty = builder.useEmpty;
        this.engine = builder.newEngine();
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** @return the best place to put an anchor and set it off, or null when nowhere is worth it */
    public AnchorPlaceOption<E> findPlace() {
        placing.clear();
        return toPlace(engine.findPlace(placing));
    }

    /**
     * The best {@code count} places to put an anchor and set it off now, best
     * first: alternatives for when you cannot act on the first, not anchors to
     * place together.
     *
     * @return at most {@code count} options, one per cell; empty when nowhere is worth it
     */
    public List<AnchorPlaceOption<E>> findPlaces(int count) {
        placing.clear();
        return toPlaces(engine.findPlaces(placing, count));
    }

    /**
     * Up to {@code count} anchors to place together, none in another's cell:
     * worth it only when they are set off on later ticks, as with
     * {@link Builder#oneTick} off.
     */
    public List<AnchorPlaceOption<E>> planPlaces(int count) {
        placing.clear();
        return toPlaces(engine.planPlaces(placing, count));
    }

    /**
     * You placed an anchor into {@code cell}, to charge and set off on later
     * ticks: until it shows up, or its wait runs out, nothing else is placed
     * there, and when it shows up it is {@linkplain dev.px.combat.search.option.Option#isOwn yours}.
     * Nothing while the search places in one tick.
     */
    public void placed(Vec3i cell) {
        Validate.notNull(cell, "cell");
        engine.placed(placing, cell);
    }

    /** @see #placed(Vec3i) */
    public void placed(AnchorPlaceOption<?> option) {
        Validate.notNull(option, "option");
        placed(option.getCell());
    }

    /** @return the best anchor already in the world to set off now, or null when none is worth it */
    public AnchorUseOption<E> findUse() {
        using.chosen.clear();
        Found<E, Anchor> found = engine.findUse(using);
        return found == null ? null : new AnchorUseOption<>(found.getSubject(), found,
                using.chosen.get(found.getSubject()));
    }

    /**
     * You set {@code anchor} off: it is inhibited, and what it does is recorded
     * against everyone it reaches for the rest of this tick.
     */
    public void used(Anchor anchor) {
        Validate.notNull(anchor, "anchor");
        engine.used(using, anchor);
    }

    /** You set off the anchor in {@code cell}, placed this tick or standing. */
    public void used(Vec3i cell) {
        Validate.notNull(cell, "cell");
        used(Anchor.of(cell, 1));
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

    private AnchorPlaceOption<E> toPlace(Found<E, Vec3i> found) {
        if (found == null) {
            return null;
        }
        Vec3i cell = found.getSubject();
        return new AnchorPlaceOption<>(cell, found, placing.chosen.get(cell), placing.chosenUse.get(cell));
    }

    private List<AnchorPlaceOption<E>> toPlaces(List<Found<E, Vec3i>> found) {
        List<AnchorPlaceOption<E>> places = new ArrayList<>(found.size());
        for (Found<E, Vec3i> option : found) {
            places.add(toPlace(option));
        }
        return places;
    }

    /** An anchor placed into a cell. */
    private final class Placing implements PlaceDevice<E, Vec3i> {

        /** The click each cell was judged placeable by, this search. */
        final Map<Vec3i, Click> chosen = new HashMap<>();
        /** The click on each anchor once placed, this search, when placing in one tick. */
        final Map<Vec3i, Click> chosenUse = new HashMap<>();

        void clear() {
            chosen.clear();
            chosenUse.clear();
        }

        /** A click into the cell and, placing in one tick, a click on the anchor once it is there. */
        @Override
        public boolean clickable(Vec3 eye, Vec3i cell) {
            if (clicks == null) {
                return true;
            }
            Click place = clicks.best(eye, cell);
            if (place == null) {
                return false;
            }
            if (oneTick.getAsBoolean()) {
                Click use = clicks.bestOn(eye, cell);
                if (use == null) {
                    return false;
                }
                chosenUse.put(cell, use);
            }
            chosen.put(cell, place);
            return true;
        }

        @Override
        public Vec3 aim(Vec3 eye, Vec3i cell) {
            Click click = chosen.get(cell);
            return click != null ? click.getHit() : aim(cell);
        }

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
        public void spotsAt(int x, int y, int z, Consumer<? super Vec3i> sink) {
            Vec3i cell = Vec3i.of(x, y, z);
            if (rules.canPlace(cell)) {
                sink.accept(cell);
            }
        }

        @Override
        public boolean visible(Vec3 eye, int x, int y, int z) {
            // The cell may hold something replaceable, which the anchor replaces.
            Vec3i cell = Vec3i.of(x, y, z);
            return Rays.clear(eye, cell.center(), rules.getBlocks().without(cell));
        }

        @Override
        public Vec3 origin(Vec3i cell) {
            return rules.origin(cell);
        }

        @Override
        public BlockView blocksWhenFired(Vec3i cell) {
            return rules.blocksWhenFired(cell);
        }

        @Override
        public Vec3 aim(Vec3i cell) {
            return cell.center();
        }

        @Override
        public Box occupies(Vec3i cell) {
            return cell.toBox();
        }

        @Override
        public boolean firesAtOnce() {
            return oneTick.getAsBoolean();
        }
    }

    /** Anchors already in the world, found by their cells. */
    private final class Using implements UseDevice<E, Anchor> {

        /** The click each anchor was judged usable by, this search. */
        final Map<Anchor, Click> chosen = new HashMap<>();

        @Override
        public boolean clickable(Vec3 eye, Anchor anchor) {
            if (clicks == null) {
                return true;
            }
            Click click = clicks.bestOn(eye, anchor.getCell());
            if (click == null) {
                return false;
            }
            chosen.put(anchor, click);
            return true;
        }

        @Override
        public Vec3 aim(Vec3 eye, Anchor anchor) {
            Click click = chosen.get(anchor);
            return click != null ? click.getHit() : aim(anchor);
        }

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
        public void forEachWithin(Vec3 eye, double range, Consumer<? super Anchor> sink) {
            int reach = (int) Math.ceil(range);
            int ex = (int) Math.floor(eye.getX());
            int ey = (int) Math.floor(eye.getY());
            int ez = (int) Math.floor(eye.getZ());
            boolean empty = useEmpty.getAsBoolean();
            for (int x = ex - reach; x <= ex + reach; x++) {
                for (int y = ey - reach; y <= ey + reach; y++) {
                    for (int z = ez - reach; z <= ez + reach; z++) {
                        if (nearest(eye.getX(), x) + nearest(eye.getY(), y) + nearest(eye.getZ(), z) > range * range) {
                            continue;
                        }
                        Anchor anchor = rules.anchorAt(x, y, z);
                        if (anchor != null && (empty || anchor.isCharged())) {
                            sink.accept(anchor);
                        }
                    }
                }
            }
        }

        @Override
        public boolean inReach(Anchor anchor, Vec3 eye, Reach reach) {
            Vec3i cell = anchor.getCell();
            return reach.reaches(eye, cell.toBox(), cell.center(), rules.blocksWhenFired(cell));
        }

        @Override
        public Vec3 origin(Anchor anchor) {
            return rules.origin(anchor.getCell());
        }

        @Override
        public BlockView blocksWhenFired(Anchor anchor) {
            return rules.blocksWhenFired(anchor.getCell());
        }

        @Override
        public Vec3 aim(Anchor anchor) {
            return anchor.getCell().center();
        }

        @Override
        public Box occupies(Anchor anchor) {
            return anchor.getCell().toBox();
        }

        /** By cell: charging an anchor does not make it a different one to inhibit. */
        @Override
        public Object key(Anchor anchor) {
            return anchor.getCell();
        }

        @Override
        public int age(Anchor anchor) {
            return Integer.MAX_VALUE;
        }
    }

    /** @return the squared distance along one axis from {@code eye} to the nearest point of cell {@code cell} */
    private static double nearest(double eye, int cell) {
        double gap = eye < cell ? cell - eye : eye > cell + 1 ? eye - (cell + 1) : 0d;
        return gap * gap;
    }

    public static final class Builder<E> extends ExplosiveSearch.Settings<E, Builder<E>> {

        private AnchorRules<E> rules;
        private Clicks clicks;
        private BooleanSupplier oneTick = () -> true;
        private BooleanSupplier useEmpty = () -> true;

        private Builder() {
        }

        @Override
        protected Builder<E> self() {
            return this;
        }

        /** Required: your version's anchor rules. */
        public Builder<E> rules(AnchorRules<E> rules) {
            this.rules = Validate.notNull(rules, "rules");
            return this;
        }

        /**
         * How placing and using work on your server. With them, an anchor is only
         * offered with clicks your rules allow, and the options say which. Without
         * them, any anchor in reach.
         */
        public Builder<E> clicks(Clicks clicks) {
            this.clicks = Validate.notNull(clicks, "clicks");
            return this;
        }

        /**
         * Whether you place, charge and set off an anchor in the same tick, read
         * live: yes unless set. Off for a server that takes one click a tick: what
         * you place is then pending until it shows up, and set off by {@code findUse}.
         */
        public Builder<E> oneTick(BooleanSupplier oneTick) {
            this.oneTick = Validate.notNull(oneTick, "oneTick");
            return this;
        }

        /** Whether an empty anchor in the world is offered, to charge and then set off, read live: yes unless set. */
        public Builder<E> useEmpty(BooleanSupplier useEmpty) {
            this.useEmpty = Validate.notNull(useEmpty, "useEmpty");
            return this;
        }

        /** Required: how far away you use an anchor. */
        public Builder<E> useReach(Reach reach) {
            return reachToUse(reach);
        }

        /** What turning to look at an anchor costs before you use it. {@link AimCost#NONE} unless set. */
        public Builder<E> useAimCost(AimCost cost) {
            return aimCostToUse(cost);
        }

        /** @throws IllegalStateException naming each required part not given */
        public AnchorSearch<E> build() {
            StringBuilder missing = new StringBuilder();
            if (rules == null) {
                missing.append(" rules");
            }
            missing(missing, "useReach");
            if (missing.length() > 0) {
                throw new IllegalStateException("an AnchorSearch needs:" + missing);
            }
            return new AnchorSearch<>(this);
        }

        /** The engine, built where the settings' protected parts can be reached. */
        private ExplosiveSearch<E> newEngine() {
            return engine();
        }
    }
}
