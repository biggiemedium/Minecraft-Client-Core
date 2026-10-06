package dev.px.combat.hole;

import dev.px.combat.place.Click;
import dev.px.combat.place.Clicks;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.timing.AttackLog;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.Tracked;
import dev.px.core.event.EventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.ToDoubleFunction;

/**
 * Finds the holes worth filling before an enemy gets into them: the searching an
 * auto-fill would otherwise do by hand. Your module decides what to fill with,
 * how to place it, and what to draw; this decides which holes, in what order,
 * and whether there is still time.
 *
 * <pre>{@code
 * HoleFill<LivingEntity> search = HoleFill.<LivingEntity>builder()
 *         .finder(new HoleFinder(holeRules))
 *         .prediction(Core.prediction())
 *         .entities(Core.entities())
 *         .targets(Core.targets(), enemies)
 *         .protect(friends)                                       // never fill a hole a friend is heading for
 *         .placeReach(Reach.of(placeRange::getDouble, wallRange::getDouble))
 *         .blocks(myBlocks)
 *         .horizon(() -> 15)
 *         .fillDelay(() -> pingTicks() + placeDelay.getInt())     // until your fill lands
 *         .minChance(minChance::getDouble)
 *         .protectOwn(selfProtect::isOn)                          // optional: never the hole you are heading for
 *         .escapeRadius(() -> escape.isOn() ? escapeRange.getDouble() : 0)   // optional: keep holes near you
 *         .clicks(strictClicks)                                   // optional: only holes your server lets you fill
 *         .bus(Core.bus())
 *         .build();
 *
 * for (FillOption<LivingEntity> fill : search.findFills(fillsPerTick.getInt())) {
 *     Plan plan = planner.plan(eye, fill.getCells(), looking);   // with obsidian, webs, whatever you hold
 *     place(plan);
 *     search.filled(fill);
 * }
 * }</pre>
 *
 * <h2>How</h2>
 *
 * <ol>
 *   <li><b>Holes in reach.</b> Every hole with all its cells within your place
 *       range, and past the wall range in sight; with {@code Clicks}, only holes
 *       whose every cell has a click your server accepts.
 *   <li><b>Kept.</b> Holes within your {@link Builder#escapeRadius escape radius},
 *       if you set one; the hole you are in or likely heading for, if you
 *       {@link Builder#protectOwn protect your own}; any a protected player is in or
 *       likely heading for; any you filled and the server has not shown yet.
 *   <li><b>Threatened.</b> Each enemy is predicted against the holes left, with a
 *       "heads for it" future per hole: see {@link HoleWatch}. A hole is offered
 *       for the enemy who would get there soonest with at least the minimum chance.
 *       One an enemy is already in, or someone else is in, is not.
 *   <li><b>Timed.</b> Against your fill delay: {@link FillOption.Timing#SAFE SAFE}
 *       if your fill lands before they could possibly get there, {@code RACE} if
 *       before they likely get there, {@code LATE} otherwise.
 *   <li><b>Yours.</b> Your filters have the last word; then options are ranked,
 *       the most urgent in-time fill first unless you {@link Builder#rank rank}
 *       them yourself.
 * </ol>
 *
 * <p>Each hole costs nothing until an enemy could reach it in time; each enemy
 * costs two predictions. Game thread only.
 *
 * @param <E> the game's type for who is denied
 */
public final class HoleFill<E> {

    /** Holes considered per enemy unless {@link Builder#maxHolesPerEnemy} says otherwise. */
    public static final int DEFAULT_MAX_HOLES = HoleWatch.DEFAULT_MAX_HOLES;

    /** Ticks a fill is waited for unless {@link Builder#pendingTicks} says otherwise: half a second. A tuning knob. */
    public static final int DEFAULT_PENDING_TICKS = 10;

    /** The chance at which a hole counts as yours or a friend's unless {@link Builder#protectChance} says otherwise. */
    public static final double DEFAULT_PROTECT_CHANCE = 0.5d;

    private final HoleFinder finder;
    private final EntityService entities;
    private final TargetService targetService;
    private final TargetSelector<? extends E> enemies;
    private final TargetSelector<? extends E> friends;
    private final int maxTargets;
    private final Reach placeReach;
    private final BlockView blocks;
    private final IntSupplier horizon;
    private final IntSupplier fillDelay;
    private final DoubleSupplier minChance;
    private final DoubleSupplier protectChance;
    private final BooleanSupplier protectOwn;
    private final DoubleSupplier escapeRadius;
    private final Clicks clicks;
    private final List<FillFilter<E>> filters;
    private final Comparator<FillOption<E>> rank;
    private final IntSupplier pendingTicks;
    private final AttackLog log;
    private final boolean ownsLog;
    private final HoleWatch enemyWatch;
    private final HoleWatch plainWatch;

    private FillStats lastStats = new FillStats();

    @SuppressWarnings("unchecked")
    private HoleFill(Builder<E> builder) {
        this.finder = builder.finder;
        this.entities = builder.entities;
        this.targetService = builder.targetService;
        this.enemies = builder.enemies;
        this.friends = builder.friends;
        this.maxTargets = builder.maxTargets;
        this.placeReach = builder.placeReach;
        this.blocks = builder.blocks;
        this.horizon = builder.horizon;
        this.fillDelay = builder.fillDelay;
        this.minChance = builder.minChance;
        this.protectChance = builder.protectChance;
        this.protectOwn = builder.protectOwn;
        this.escapeRadius = builder.escapeRadius;
        this.clicks = builder.clicks;
        this.filters = Collections.unmodifiableList(new ArrayList<>(builder.filters));
        this.rank = builder.rank != null ? builder.rank : HoleFill.<E>urgency();
        this.pendingTicks = builder.pendingTicks;
        this.ownsLog = builder.log == null;
        this.log = builder.log != null ? builder.log
                : builder.bus != null ? AttackLog.ticking(builder.bus) : new AttackLog();
        ToDoubleFunction<? super E> prior = builder.prior;
        this.enemyWatch = HoleWatch.builder()
                .finder(finder).prediction(builder.prediction).obstructions(builder.obstructions)
                .horizon(horizon).radius(() -> 0d).maxHoles(builder.maxHoles)
                .priorOf(entity -> prior.applyAsDouble((E) entity.get()))
                .build();
        this.plainWatch = HoleWatch.builder()
                .finder(finder).prediction(builder.prediction).obstructions(builder.obstructions)
                .horizon(horizon).radius(() -> 0d).maxHoles(builder.maxHoles)
                .build();
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** @return the best hole to fill now, or null when none is worth it */
    public FillOption<E> findFill() {
        List<FillOption<E>> best = findFills(1);
        return best.isEmpty() ? null : best.get(0);
    }

    /**
     * @return the best {@code count} holes to fill now, best first: each a
     *         different hole, all of them to fill this tick if you can place them
     */
    public List<FillOption<E>> findFills(int count) {
        Validate.check(count > 0, "count must be positive");
        FillStats stats = new FillStats();
        lastStats = stats;
        Tracked<? extends E> self = entities.<E>getSelf();
        if (self == null) {
            return Collections.emptyList();
        }
        Vec3 eye = self.getEyePosition();
        Map<Hole, List<Click>> candidates = inReach(eye, self, stats);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        double protectAt = protectChance.getAsDouble();
        if (protectOwn.getAsBoolean()) {
            stats.own += keep(self, candidates, protectAt);
        }
        List<Tracked<? extends E>> guarded = friends == null
                ? Collections.<Tracked<? extends E>>emptyList() : collect(friends, Integer.MAX_VALUE);
        Map<Object, Boolean> isGuarded = new IdentityHashMap<>();
        for (Tracked<? extends E> friend : guarded) {
            isGuarded.put(friend.get(), Boolean.TRUE);
            stats.friends += keep(friend, candidates, protectAt);
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Every enemy against every hole left; each hole remembers who threatens it.
        Map<Hole, List<HoleEntry>> threats = new LinkedHashMap<>();
        for (Hole hole : candidates.keySet()) {
            threats.put(hole, new ArrayList<HoleEntry>());
        }
        int counted = 0;
        for (Tracked<? extends E> enemy : collect(enemies, Integer.MAX_VALUE)) {
            if (isGuarded.containsKey(enemy.get()) || enemy.get() == self.get()) {
                continue;
            }
            if (counted++ >= maxTargets) {
                break;
            }
            for (HoleEntry entry : enemyWatch.assess(enemy, candidates.keySet())) {
                threats.get(entry.getHole()).add(entry);
            }
        }

        int delay = Math.max(0, fillDelay.getAsInt());
        double least = minChance.getAsDouble();
        List<FillOption<E>> offered = new ArrayList<>();
        for (Map.Entry<Hole, List<HoleEntry>> hole : threats.entrySet()) {
            List<HoleEntry> against = hole.getValue();
            if (against.isEmpty()) {
                stats.unthreatened++;
                continue;
            }
            if (any(against, true)) {
                stats.entered++;
                continue;
            }
            if (any(against, false)) {
                stats.occupied++;
                continue;
            }
            HoleEntry worst = null;
            for (HoleEntry entry : against) {
                if (entry.getChance() >= least && (worst == null || deadline(entry) < deadline(worst)
                        || (deadline(entry) == deadline(worst) && entry.getChance() > worst.getChance()))) {
                    worst = entry;
                }
            }
            if (worst == null) {
                stats.unlikely++;
                continue;
            }
            FillOption<E> option = option(hole.getKey(), candidates.get(hole.getKey()), worst, against.size(), delay);
            if (!accepted(option)) {
                stats.filtered++;
                continue;
            }
            offered.add(option);
        }
        stats.offered = offered.size();
        offered.sort(rank);
        return Collections.unmodifiableList(offered.size() > count ? new ArrayList<>(offered.subList(0, count))
                : offered);
    }

    /**
     * You filled {@code fill}'s hole: until the server shows it, or its wait runs
     * out, it is not offered again. Through the log, so searches sharing it see it too.
     */
    public void filled(FillOption<?> fill) {
        Validate.notNull(fill, "fill");
        for (Vec3i cell : fill.getCells()) {
            log.placed(cell.toBox(), pendingTicks.getAsInt());
        }
    }

    /** Starts a new tick on this search's log. A log made with a bus, or shared, ticks itself; tick a shared one once. */
    public void tick() {
        log.newTick();
    }

    public AttackLog getLog() {
        return log;
    }

    /** @return what the last {@link #findFills} did */
    public FillStats getLastStats() {
        return lastStats;
    }

    /** Stops the search's own log listening to the bus given at build time. A shared log is yours to close. */
    public void close() {
        if (ownsLog) {
            log.close();
        }
    }

    /**
     * The default ranking: anything in time before anything late; then the soonest
     * an enemy would be in; then the likeliest.
     */
    public static <E> Comparator<FillOption<E>> urgency() {
        return (a, b) -> {
            boolean aLate = a.getTiming() == FillOption.Timing.LATE;
            boolean bLate = b.getTiming() == FillOption.Timing.LATE;
            if (aLate != bLate) {
                return aLate ? 1 : -1;
            }
            int byDeadline = Integer.compare(deadline(a.getEntry()), deadline(b.getEntry()));
            return byDeadline != 0 ? byDeadline : Double.compare(b.getChance(), a.getChance());
        };
    }

    // ----------------------------------------------------------- internals

    /** Holes in place range, every cell reachable and, with clicks, clickable; those in escape range or pending kept. */
    private Map<Hole, List<Click>> inReach(Vec3 eye, Tracked<? extends E> self, FillStats stats) {
        Map<Hole, List<Click>> found = new LinkedHashMap<>();
        double escape = escapeRadius.getAsDouble();
        for (Hole hole : finder.around(eye, placeReach.range() + 1d)) {
            stats.found++;
            boolean reachable = true;
            for (Vec3i cell : hole.getCells()) {
                Box box = cell.toBox();
                reachable &= placeReach.reaches(eye, box, cell.center(), blocks);
            }
            if (!reachable) {
                continue;
            }
            stats.inReach++;
            if (escape > 0d && hole.getCentre().distanceTo(self.getPosition()) <= escape) {
                stats.escape++;
                continue;
            }
            boolean pending = false;
            for (Vec3i cell : hole.getCells()) {
                pending |= log.isPendingIn(cell.toBox());
            }
            if (pending) {
                stats.pending++;
                continue;
            }
            List<Click> ways = null;
            if (clicks != null) {
                ways = new ArrayList<>(hole.getCells().size());
                for (Vec3i cell : hole.getCells()) {
                    Click click = clicks.best(eye, cell);
                    if (click == null) {
                        ways = null;
                        break;
                    }
                    ways.add(click);
                }
                if (ways == null) {
                    stats.unclickable++;
                    continue;
                }
            }
            found.put(hole, ways);
        }
        return found;
    }

    /** Removes the holes {@code entity} is in or likely heading for. @return how many */
    private int keep(Tracked<?> entity, Map<Hole, List<Click>> candidates, double chance) {
        int kept = 0;
        for (HoleEntry entry : plainWatch.assess(entity, candidates.keySet())) {
            if ((entry.isInside() || entry.getChance() >= chance) && candidates.containsKey(entry.getHole())) {
                candidates.remove(entry.getHole());
                kept++;
            }
        }
        return kept;
    }

    private FillOption<E> option(Hole hole, List<Click> ways, HoleEntry entry, int threats, int delay) {
        @SuppressWarnings("unchecked")
        Tracked<? extends E> enemy = (Tracked<? extends E>) entry.getEntity();
        // The cell they come to first, first: filling it first denies the hole soonest.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hole.getCells().size(); i++) {
            order.add(i);
        }
        Vec3 from = enemy.getPosition();
        order.sort((a, b) -> Double.compare(hole.getCells().get(a).center().distanceTo(from),
                hole.getCells().get(b).center().distanceTo(from)));
        List<Vec3i> cells = new ArrayList<>(order.size());
        List<Click> clicked = ways == null ? null : new ArrayList<Click>(order.size());
        for (int i : order) {
            cells.add(hole.getCells().get(i));
            if (clicked != null) {
                clicked.add(ways.get(i));
            }
        }
        int possible = entry.getEarliestPossible();
        int likely = deadline(entry);
        FillOption.Timing timing = delay < possible ? FillOption.Timing.SAFE
                : delay < likely ? FillOption.Timing.RACE : FillOption.Timing.LATE;
        return new FillOption<>(hole, cells, clicked, enemy, entry, timing, possible - delay, threats);
    }

    /** @return when the enemy is likely in: the likely tick, or when it would be heading straight there, or the soonest possible */
    private static int deadline(HoleEntry entry) {
        if (entry.getLikelyTick() >= 0) {
            return entry.getLikelyTick();
        }
        if (entry.getArrivalTick() >= 0) {
            return entry.getArrivalTick();
        }
        return entry.getEarliestPossible();
    }

    private static boolean any(List<HoleEntry> entries, boolean inside) {
        for (HoleEntry entry : entries) {
            if (inside ? entry.isInside() : entry.isOccupied()) {
                return true;
            }
        }
        return false;
    }

    private boolean accepted(FillOption<E> option) {
        for (FillFilter<E> filter : filters) {
            if (!filter.accepts(option)) {
                return false;
            }
        }
        return true;
    }

    private <T extends E> List<Tracked<? extends E>> collect(TargetSelector<T> chosen, int limit) {
        return new ArrayList<Tracked<? extends E>>(targetService.all(chosen, limit));
    }

    public static final class Builder<E> {

        private HoleFinder finder;
        private PredictionService prediction;
        private EntityService entities;
        private TargetService targetService;
        private TargetSelector<? extends E> enemies;
        private TargetSelector<? extends E> friends;
        private int maxTargets = Integer.MAX_VALUE;
        private int maxHoles = DEFAULT_MAX_HOLES;
        private Reach placeReach;
        private BlockView blocks;
        private IntSupplier horizon;
        private IntSupplier fillDelay = () -> 0;
        private DoubleSupplier minChance = () -> 0d;
        private DoubleSupplier protectChance = () -> DEFAULT_PROTECT_CHANCE;
        private BooleanSupplier protectOwn = () -> false;
        private DoubleSupplier escapeRadius = () -> 0d;
        private ToDoubleFunction<? super E> prior = entity -> 1d;
        private Clicks clicks;
        private Obstructions obstructions = Obstructions.NONE;
        private final List<FillFilter<E>> filters = new ArrayList<>();
        private Comparator<FillOption<E>> rank;
        private IntSupplier pendingTicks = () -> DEFAULT_PENDING_TICKS;
        private EventBus bus;
        private AttackLog log;

        private Builder() {
        }

        /** Required: what a hole is, and how to find them. */
        public Builder<E> finder(HoleFinder finder) {
            this.finder = Validate.notNull(finder, "finder");
            return this;
        }

        /** Required: how enemies, friends and you are predicted; {@code Core.prediction()}. */
        public Builder<E> prediction(PredictionService prediction) {
            this.prediction = Validate.notNull(prediction, "prediction");
            return this;
        }

        /** Required: where you come from. */
        public Builder<E> entities(EntityService entities) {
            this.entities = Validate.notNull(entities, "entities");
            return this;
        }

        /** Required: who to deny holes to, chosen by a selector of yours. */
        public Builder<E> targets(TargetService service, TargetSelector<? extends E> selector) {
            this.targetService = Validate.notNull(service, "service");
            this.enemies = Validate.notNull(selector, "selector");
            return this;
        }

        /**
         * Optional: who must keep their holes &mdash; your friends, say. A hole one of
         * them is in, or likely heading for, is never offered, and they are never
         * treated as enemies.
         */
        public Builder<E> protect(TargetSelector<? extends E> selector) {
            this.friends = Validate.notNull(selector, "selector");
            return this;
        }

        /** @param targets how many enemies to weigh, the selector's best first; all unless set */
        public Builder<E> maxTargets(int targets) {
            Validate.check(targets > 0, "maxTargets must be positive");
            this.maxTargets = targets;
            return this;
        }

        /** @param holes how many holes, the soonest each enemy could reach first, to weigh per enemy */
        public Builder<E> maxHolesPerEnemy(int holes) {
            Validate.check(holes > 0, "maxHolesPerEnemy must be positive");
            this.maxHoles = holes;
            return this;
        }

        /** Required: how far away you place, and past what range only what you can see. */
        public Builder<E> placeReach(Reach reach) {
            this.placeReach = Validate.notNull(reach, "reach");
            return this;
        }

        /** Required: the world, for seeing a hole past the wall range. */
        public Builder<E> blocks(BlockView blocks) {
            this.blocks = Validate.notNull(blocks, "blocks");
            return this;
        }

        /**
         * Required: how many ticks ahead to look for enemies reaching a hole, read
         * live. At least your fill delay, and at least three under your trackers'
         * history.
         */
        public Builder<E> horizon(IntSupplier ticks) {
            this.horizon = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** Ticks from deciding to fill until the fill is there on the server, read live: your ping and place delay. 0 unless set. */
        public Builder<E> fillDelay(IntSupplier ticks) {
            this.fillDelay = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** The least chance, 0 to 1, of an enemy getting into a hole for it to be worth filling, read live. 0 unless set. */
        public Builder<E> minChance(DoubleSupplier chance) {
            this.minChance = Validate.notNull(chance, "chance");
            return this;
        }

        /**
         * How much more an enemy heading into a hole is believed than any other
         * future, per enemy, before the evidence: lean further for someone low on
         * health, say. 1, no lean, unless set. See {@link HoleWatch} on why.
         */
        public Builder<E> prior(ToDoubleFunction<? super E> prior) {
            this.prior = Validate.notNull(prior, "prior");
            return this;
        }

        /** The chance, 0 to 1, at which you or a friend counts as heading for a hole. {@link #DEFAULT_PROTECT_CHANCE} unless set. */
        public Builder<E> protectChance(DoubleSupplier chance) {
            this.protectChance = Validate.notNull(chance, "chance");
            return this;
        }

        /** Optional: never offer the hole you are in, or likely heading for. Read live; off unless set. */
        public Builder<E> protectOwn(BooleanSupplier on) {
            this.protectOwn = Validate.notNull(on, "on");
            return this;
        }

        /**
         * Optional: never offer a hole within this many blocks of you, so you keep
         * somewhere to escape into. Read live; 0 or less is off, as it is unless set.
         */
        public Builder<E> escapeRadius(DoubleSupplier blocks) {
            this.escapeRadius = Validate.notNull(blocks, "blocks");
            return this;
        }

        /**
         * Optional: how placing works on your server. With them, only holes whose
         * every cell has a click your rules accept are offered, and each option
         * carries those clicks.
         */
        public Builder<E> clicks(Clicks clicks) {
            this.clicks = Validate.notNull(clicks, "clicks");
            return this;
        }

        /** What counts as someone else in a hole. Nothing unless set. */
        public Builder<E> obstructions(Obstructions obstructions) {
            this.obstructions = Validate.notNull(obstructions, "obstructions");
            return this;
        }

        /** Optional: refuses any hole {@code filter} does not accept. Several are all required. */
        public Builder<E> filter(FillFilter<E> filter) {
            this.filters.add(Validate.notNull(filter, "filter"));
            return this;
        }

        /** How options are ranked, best first: {@link HoleFill#urgency()} unless set. */
        public Builder<E> rank(Comparator<FillOption<E>> rank) {
            this.rank = Validate.notNull(rank, "rank");
            return this;
        }

        /** How many ticks to wait for a fill to show up before offering its hole again. {@link #DEFAULT_PENDING_TICKS} unless set. */
        public Builder<E> pendingTicks(IntSupplier ticks) {
            this.pendingTicks = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** Optional: the search's own log ticks itself on this bus. Not needed with a shared {@link #log}. */
        public Builder<E> bus(EventBus bus) {
            this.bus = Validate.notNull(bus, "bus");
            return this;
        }

        /** Optional: a log shared with your other searches, so fills and crystals keep out of each other's way. */
        public Builder<E> log(AttackLog log) {
            this.log = Validate.notNull(log, "log");
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public HoleFill<E> build() {
            StringBuilder missing = new StringBuilder();
            if (finder == null) {
                missing.append(" finder");
            }
            if (prediction == null) {
                missing.append(" prediction");
            }
            if (entities == null) {
                missing.append(" entities");
            }
            if (enemies == null) {
                missing.append(" targets");
            }
            if (placeReach == null) {
                missing.append(" placeReach");
            }
            if (blocks == null) {
                missing.append(" blocks");
            }
            if (horizon == null) {
                missing.append(" horizon");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("a HoleFill needs:" + missing);
            }
            return new HoleFill<>(this);
        }
    }
}
