package dev.px.combat.trap;

import dev.px.combat.place.Occupancy;
import dev.px.combat.place.Plan;
import dev.px.combat.place.PlacementPlanner;
import dev.px.combat.place.Shapes;
import dev.px.combat.search.timing.AttackLog;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.Tracked;
import dev.px.core.event.EventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.prediction.PredictionService;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Finds who to trap and what to place first: the searching an auto-trap would
 * otherwise do by hand. Your module decides what to place with, and draws what it
 * likes; this decides who, which cells, in what order, and this tick's clicks.
 *
 * <pre>{@code
 * PlacementStyle style = strictServer ? myStrictPreset : PlacementStyle.vanilla(reach, blocks);
 * PlacementPlanner planner = style.planner(style.clicks(Game::isSolid, Game::isReplaceable),
 *         Obstructions.of(Core.entities(), players)).build();      // a solid block never goes into a player
 *
 * TrapSearch<LivingEntity> search = TrapSearch.<LivingEntity>builder()
 *         .prediction(Core.prediction())
 *         .entities(Core.entities())
 *         .targets(Core.targets(), enemies)
 *         .protect(friends)
 *         .planner(planner)
 *         .pattern(() -> antiStep.isOn() ? TrapPattern.ANTI_STEP : TrapPattern.FULL)
 *         .placeDelay(() -> pingTicks() + placeDelay.getInt())
 *         .looking(rotations::getServerRotation)                 // clicks needing least turn
 *         .bus(Core.bus())
 *         .build();
 *
 * TrapOption<LivingEntity> trap = search.findTrap();
 * if (trap != null) { place(trap.getPlan()); search.placed(trap); }
 * }</pre>
 *
 * <h2>How</h2>
 *
 * <ol>
 *   <li><b>Who.</b> Each target, friends never. One who is moving is left alone
 *       unless you {@link Builder#trapMoving trap moving targets}: a trap is built
 *       around where someone stands, and a moving player is rarely there when it
 *       lands. Someone walled in at the feet, as in a hole, always counts as still.
 *   <li><b>Which cells.</b> Your {@link TrapPattern} around them &mdash; where they
 *       will be when the blocks land, for a moving target &mdash; less what is
 *       filled already, or placed and not yet shown.
 *   <li><b>In what order.</b> {@link TrapOrder#ESCAPES_FIRST} unless set: walled in,
 *       the roof first, since jumping is the only way out; in the open, the side of
 *       the feet ring they are likeliest to walk out of, by their predicted futures.
 *   <li><b>Never into them.</b> A cell they are likely in when a block lands is
 *       left for a later tick: the server refuses a solid block inside a player.
 *   <li><b>This tick.</b> The cells go to your {@link PlacementPlanner}, which
 *       adds supports &mdash; a roof needs a block beside it, on top of the head
 *       ring &mdash; and keeps to your style: per-tick limit, faces, reach.
 * </ol>
 *
 * <p>For a server that wants one placement a tick, a {@code PlacementStyle} with
 * one a tick spends each tick on the cell that matters most. Each target costs a
 * prediction and a plan. Game thread only.
 *
 * @param <E> the game's type for who is trapped
 */
public final class TrapSearch<E> {

    /** Ticks a placement is waited for unless {@link Builder#pendingTicks} says otherwise: half a second. A tuning knob. */
    public static final int DEFAULT_PENDING_TICKS = 10;

    /** Ticks ahead to read which way a target is likeliest to leave, unless {@link Builder#horizon} says otherwise. */
    public static final int DEFAULT_HORIZON = 10;

    /** How far, in blocks, a target may be predicted to move before landing and still count as still. A tuning knob. */
    public static final double DEFAULT_STILL = 0.25d;

    /** The chance a target is in a cell when a block lands, past which the cell waits. A tuning knob. */
    public static final double DEFAULT_AVOID_CHANCE = 0.1d;

    private final PredictionService prediction;
    private final EntityService entities;
    private final TargetService targetService;
    private final TargetSelector<? extends E> enemies;
    private final TargetSelector<? extends E> friends;
    private final int maxTargets;
    private final PlacementPlanner planner;
    private final Supplier<TrapPattern> pattern;
    private final Supplier<TrapOrder> order;
    private final IntSupplier placeDelay;
    private final IntSupplier horizon;
    private final BooleanSupplier trapMoving;
    private final DoubleSupplier still;
    private final DoubleSupplier avoidChance;
    private final Supplier<Vec2> looking;
    private final List<TrapFilter<E>> filters;
    private final Comparator<TrapOption<E>> rank;
    private final IntSupplier pendingTicks;
    private final AttackLog log;
    private final boolean ownsLog;

    private TrapStats lastStats = new TrapStats();

    private TrapSearch(Builder<E> builder) {
        this.prediction = builder.prediction;
        this.entities = builder.entities;
        this.targetService = builder.targetService;
        this.enemies = builder.enemies;
        this.friends = builder.friends;
        this.maxTargets = builder.maxTargets;
        this.planner = builder.planner;
        this.pattern = builder.pattern;
        this.order = builder.order;
        this.placeDelay = builder.placeDelay;
        this.horizon = builder.horizon;
        this.trapMoving = builder.trapMoving;
        this.still = builder.still;
        this.avoidChance = builder.avoidChance;
        this.looking = builder.looking;
        this.filters = Collections.unmodifiableList(new ArrayList<>(builder.filters));
        this.rank = builder.rank != null ? builder.rank : TrapSearch.<E>nearlyDone();
        this.pendingTicks = builder.pendingTicks;
        this.ownsLog = builder.log == null;
        this.log = builder.log != null ? builder.log
                : builder.bus != null ? AttackLog.ticking(builder.bus) : new AttackLog();
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** @return the best player to trap now, with this tick's placements; null when there is none */
    public TrapOption<E> findTrap() {
        List<TrapOption<E>> best = findTraps(1);
        return best.isEmpty() ? null : best.get(0);
    }

    /**
     * @return up to {@code count} players to trap, best first, each with its own
     *         plan. Each plan is made alone, so placing more than one in a tick is
     *         yours to keep within your server's limit
     */
    public List<TrapOption<E>> findTraps(int count) {
        Validate.check(count > 0, "count must be positive");
        TrapStats stats = new TrapStats();
        lastStats = stats;
        Tracked<? extends E> self = entities.<E>getSelf();
        if (self == null) {
            return Collections.emptyList();
        }
        Vec3 eye = self.getEyePosition();
        Vec2 look = looking != null ? looking.get() : null;
        Map<Object, Boolean> guarded = new IdentityHashMap<>();
        if (friends != null) {
            for (Tracked<? extends E> friend : collect(friends)) {
                guarded.put(friend.get(), Boolean.TRUE);
            }
        }
        int delay = Math.max(0, placeDelay.getAsInt());
        int ahead = Math.max(1, horizon.getAsInt());
        List<TrapOption<E>> offered = new ArrayList<>();
        int counted = 0;
        for (Tracked<? extends E> target : collect(enemies)) {
            if (guarded.containsKey(target.get()) || target.get() == self.get()) {
                continue;
            }
            if (counted++ >= maxTargets) {
                break;
            }
            stats.targets++;
            TrapOption<E> option = trap(target, eye, look, delay, ahead, stats);
            if (option == null) {
                continue;
            }
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

    /** You placed {@code trap}'s plan: its cells are not planned again while the server catches up. */
    public void placed(TrapOption<?> trap) {
        Validate.notNull(trap, "trap");
        for (Plan.Step step : trap.getPlan().getSteps()) {
            log.placed(step.getCell().toBox(), pendingTicks.getAsInt());
        }
    }

    /** Starts a new tick on this search's log. A log made with a bus, or shared, ticks itself; tick a shared one once. */
    public void tick() {
        log.newTick();
    }

    public AttackLog getLog() {
        return log;
    }

    /** @return what the last {@link #findTraps} did */
    public TrapStats getLastStats() {
        return lastStats;
    }

    /** Stops the search's own log listening to the bus given at build time. A shared log is yours to close. */
    public void close() {
        if (ownsLog) {
            log.close();
        }
    }

    /** The default ranking: the trap with fewest cells left first, then the nearest target. */
    public static <E> Comparator<TrapOption<E>> nearlyDone() {
        return (a, b) -> {
            int byMissing = Integer.compare(a.getMissing().size(), b.getMissing().size());
            return byMissing != 0 ? byMissing
                    : Double.compare(a.getTarget().distanceToBox(a.getTarget().getPosition()),
                    b.getTarget().distanceToBox(b.getTarget().getPosition()));
        };
    }

    // ----------------------------------------------------------- internals

    private TrapOption<E> trap(Tracked<? extends E> target, Vec3 eye, Vec2 look, int delay, int ahead, TrapStats stats) {
        Prediction next = prediction.predict(target, delay + ahead);
        Box now = target.getBox();
        boolean enclosed = walled(now);
        Vec3 landing = next.positionAt(delay);
        boolean settled = enclosed
                || (next.isReliable() && landing.distanceTo(target.getPosition()) <= still.getAsDouble());
        if (!settled && !trapMoving.getAsBoolean()) {
            stats.moving++;
            return null;
        }
        Box around = settled ? now : next.likeliest().at(delay).hitbox(target.getWidth(), target.getHeight());

        Occupancy atLanding = Occupancy.of(next, delay, delay);
        Occupancy leaving = Occupancy.of(next, delay, delay + ahead);
        double avoid = avoidChance.getAsDouble();
        List<TrapPattern.Cell> cells = ordered(pattern.get().cells(around), enclosed, leaving);
        List<Vec3i> missing = new ArrayList<>();
        List<Vec3i> deferred = new ArrayList<>();
        List<Vec3i> toPlace = new ArrayList<>();
        for (TrapPattern.Cell cell : cells) {
            Vec3i at = cell.getCell();
            if (!planner.getClicks().isReplaceable(at) || log.isPendingIn(at.toBox())) {
                continue;
            }
            missing.add(at);
            if (atLanding.chance(at) >= avoid || at.toBox().intersects(now)) {
                deferred.add(at);
            } else {
                toPlace.add(at);
            }
        }
        if (missing.isEmpty()) {
            stats.sealed++;
            return null;
        }
        Plan plan = planner.plan(eye, toPlace, look);
        if (plan.isEmpty()) {
            stats.unplaceable++;
            return null;
        }
        boolean roofed = true;
        for (TrapPattern.Cell cell : cells) {
            if (cell.getPart() == TrapPattern.Part.ROOF && missing.contains(cell.getCell())) {
                boolean planned = false;
                for (Plan.Step step : plan.getSteps()) {
                    planned |= step.getCell().equals(cell.getCell());
                }
                roofed &= planned;
            }
        }
        return new TrapOption<>(target, cells, missing, deferred, plan, enclosed, roofed, next);
    }

    /** @return whether every cell beside the box's feet is filled: walled in, as in a hole */
    private boolean walled(Box box) {
        for (Vec3i cell : Shapes.around(box)) {
            if (planner.getClicks().isReplaceable(cell)) {
                return false;
            }
        }
        return true;
    }

    /** @return the pattern's cells, in the order to place them */
    private List<TrapPattern.Cell> ordered(List<TrapPattern.Cell> cells, boolean enclosed, Occupancy leaving) {
        List<TrapPattern.Cell> sorted = new ArrayList<>(cells);
        if (order.get() == TrapOrder.BOTTOM_UP) {
            sorted.sort(Comparator.comparingInt(cell -> layer(cell.getPart())));
            return sorted;
        }
        // Escapes first: walled in, up is the only way out; in the open, walking out is the quickest way.
        TrapPattern.Part[] priority = enclosed
                ? new TrapPattern.Part[] { TrapPattern.Part.ROOF, TrapPattern.Part.HEAD, TrapPattern.Part.FEET,
                TrapPattern.Part.EXTRA }
                : new TrapPattern.Part[] { TrapPattern.Part.FEET, TrapPattern.Part.ROOF, TrapPattern.Part.HEAD,
                TrapPattern.Part.EXTRA };
        sorted.sort((a, b) -> {
            int byPart = Integer.compare(rankOf(priority, a.getPart()), rankOf(priority, b.getPart()));
            if (byPart != 0) {
                return byPart;
            }
            // Within a part, the side they are likeliest to leave by first.
            return Double.compare(leaving.chance(b.getCell()), leaving.chance(a.getCell()));
        });
        return sorted;
    }

    private static int rankOf(TrapPattern.Part[] priority, TrapPattern.Part part) {
        for (int i = 0; i < priority.length; i++) {
            if (priority[i] == part) {
                return i;
            }
        }
        return priority.length;
    }

    private static int layer(TrapPattern.Part part) {
        switch (part) {
            case FEET:
                return 0;
            case HEAD:
                return 1;
            case ROOF:
                return 2;
            default:
                return 3;
        }
    }

    private boolean accepted(TrapOption<E> option) {
        for (TrapFilter<E> filter : filters) {
            if (!filter.accepts(option)) {
                return false;
            }
        }
        return true;
    }

    private <T extends E> List<Tracked<? extends E>> collect(TargetSelector<T> chosen) {
        return new ArrayList<Tracked<? extends E>>(targetService.all(chosen, Integer.MAX_VALUE));
    }

    public static final class Builder<E> {

        private PredictionService prediction;
        private EntityService entities;
        private TargetService targetService;
        private TargetSelector<? extends E> enemies;
        private TargetSelector<? extends E> friends;
        private int maxTargets = Integer.MAX_VALUE;
        private PlacementPlanner planner;
        private Supplier<TrapPattern> pattern = () -> TrapPattern.FULL;
        private Supplier<TrapOrder> order = () -> TrapOrder.ESCAPES_FIRST;
        private IntSupplier placeDelay = () -> 0;
        private IntSupplier horizon = () -> DEFAULT_HORIZON;
        private BooleanSupplier trapMoving = () -> false;
        private DoubleSupplier still = () -> DEFAULT_STILL;
        private DoubleSupplier avoidChance = () -> DEFAULT_AVOID_CHANCE;
        private Supplier<Vec2> looking;
        private final List<TrapFilter<E>> filters = new ArrayList<>();
        private Comparator<TrapOption<E>> rank;
        private IntSupplier pendingTicks = () -> DEFAULT_PENDING_TICKS;
        private EventBus bus;
        private AttackLog log;

        private Builder() {
        }

        /** Required: how targets are predicted; {@code Core.prediction()}. */
        public Builder<E> prediction(PredictionService prediction) {
            this.prediction = Validate.notNull(prediction, "prediction");
            return this;
        }

        /** Required: where you come from. */
        public Builder<E> entities(EntityService entities) {
            this.entities = Validate.notNull(entities, "entities");
            return this;
        }

        /** Required: who to trap, chosen by a selector of yours. */
        public Builder<E> targets(TargetService service, TargetSelector<? extends E> selector) {
            this.targetService = Validate.notNull(service, "service");
            this.enemies = Validate.notNull(selector, "selector");
            return this;
        }

        /** Optional: who is never trapped, whatever the target selector says. */
        public Builder<E> protect(TargetSelector<? extends E> selector) {
            this.friends = Validate.notNull(selector, "selector");
            return this;
        }

        /** @param targets how many targets to weigh, the selector's best first; all unless set */
        public Builder<E> maxTargets(int targets) {
            Validate.check(targets > 0, "maxTargets must be positive");
            this.maxTargets = targets;
            return this;
        }

        /**
         * Required: how to place, in your style. Its obstructions must include the
         * players you trap: a solid block is refused inside one, supports included.
         */
        public Builder<E> planner(PlacementPlanner planner) {
            this.planner = Validate.notNull(planner, "planner");
            return this;
        }

        /** Which trap to build, read live. {@link TrapPattern#FULL} unless set. */
        public Builder<E> pattern(Supplier<TrapPattern> pattern) {
            this.pattern = Validate.notNull(pattern, "pattern");
            return this;
        }

        /** Which cells first, read live. {@link TrapOrder#ESCAPES_FIRST} unless set. */
        public Builder<E> order(Supplier<TrapOrder> order) {
            this.order = Validate.notNull(order, "order");
            return this;
        }

        /** Ticks from deciding to place until the block is there on the server, read live: your ping and place delay. 0 unless set. */
        public Builder<E> placeDelay(IntSupplier ticks) {
            this.placeDelay = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** Ticks past landing to read which way a target is likeliest to leave, read live. {@link #DEFAULT_HORIZON} unless set. */
        public Builder<E> horizon(IntSupplier ticks) {
            this.horizon = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** Whether to trap someone moving, around where they will be when the blocks land, read live. Off unless set. */
        public Builder<E> trapMoving(BooleanSupplier on) {
            this.trapMoving = Validate.notNull(on, "on");
            return this;
        }

        /** How far, in blocks, a target may move before landing and still count as still. {@link #DEFAULT_STILL} unless set. */
        public Builder<E> still(DoubleSupplier blocks) {
            this.still = Validate.notNull(blocks, "blocks");
            return this;
        }

        /** The chance a target is in a cell when a block lands, past which it waits. {@link #DEFAULT_AVOID_CHANCE} unless set. */
        public Builder<E> avoidChance(DoubleSupplier chance) {
            this.avoidChance = Validate.notNull(chance, "chance");
            return this;
        }

        /** Optional: where you look now, from your rotation manager, so clicks needing least turn are chosen. */
        public Builder<E> looking(Supplier<Vec2> looking) {
            this.looking = Validate.notNull(looking, "looking");
            return this;
        }

        /** Optional: refuses any trap {@code filter} does not accept. Several are all required. */
        public Builder<E> filter(TrapFilter<E> filter) {
            this.filters.add(Validate.notNull(filter, "filter"));
            return this;
        }

        /** How traps are ranked, best first: {@link TrapSearch#nearlyDone()} unless set. */
        public Builder<E> rank(Comparator<TrapOption<E>> rank) {
            this.rank = Validate.notNull(rank, "rank");
            return this;
        }

        /** How many ticks to wait for a placed block to show up before planning its cell again. */
        public Builder<E> pendingTicks(IntSupplier ticks) {
            this.pendingTicks = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** Optional: the search's own log ticks itself on this bus. */
        public Builder<E> bus(EventBus bus) {
            this.bus = Validate.notNull(bus, "bus");
            return this;
        }

        /** Optional: a log shared with your other searches, so traps, fills and crystals keep out of each other's way. */
        public Builder<E> log(AttackLog log) {
            this.log = Validate.notNull(log, "log");
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public TrapSearch<E> build() {
            StringBuilder missing = new StringBuilder();
            if (prediction == null) {
                missing.append(" prediction");
            }
            if (entities == null) {
                missing.append(" entities");
            }
            if (enemies == null) {
                missing.append(" targets");
            }
            if (planner == null) {
                missing.append(" planner");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("a TrapSearch needs:" + missing);
            }
            return new TrapSearch<>(this);
        }
    }
}
