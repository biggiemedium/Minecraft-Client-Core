package dev.px.combat.search.engine;

import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.search.option.Option;
import dev.px.combat.search.option.Trigger;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.rule.ReachPoint;
import dev.px.combat.search.rule.Score;
import dev.px.combat.search.rule.Thresholds;
import dev.px.combat.search.timing.AttackLog;
import dev.px.combat.world.BlockView;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.Tracked;
import dev.px.core.event.EventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The search every explosive shares: where to place one, which one already in
 * the world to set off, and whether either is worth it. What differs between
 * crystals, beds and anything else is a {@link Device}; everything else is here.
 *
 * <p>{@code CrystalSearch} and {@code BedSearch} are this with their device
 * plugged in, and are what an aura normally uses. Use this directly to search
 * for an explosive of your own:
 *
 * <pre>{@code
 * ExplosiveSearch<LivingEntity> search = ExplosiveSearch.<LivingEntity>builder()
 *         .entities(Core.entities())
 *         .targets(Core.targets(), enemies)
 *         .vitals(myVitals)
 *         .thresholds(myThresholds)
 *         .placeReach(Reach.of(5, 3))
 *         .useReach(Reach.of(5, 3))
 *         .log(sharedLog)                                  // shared with your other auras
 *         .build();
 *
 * Found<LivingEntity, AnchorSpot> spot = search.findPlace(myAnchorDevice);
 * }</pre>
 *
 * <h2>Placing</h2>
 *
 * <ol>
 *   <li><b>Scan</b> every cell in a cube around your eyes that reaches the place
 *       range, cheapest test first: distance, then the device's spots in the
 *       cell, then, past the wall range, whether the cell can be seen.
 *   <li><b>Bound</b> each spot against each target: the damage it would do with
 *       nothing in the way, which costs no raycasting. A spot whose bound cannot
 *       meet any target's threshold is dropped there.
 *   <li><b>Branch and bound.</b> Spots are tried best bound first, with exact,
 *       raycast estimates; once the best option found scores higher than the next
 *       bound, nothing left can win and the search stops.
 * </ol>
 *
 * <h2>Setting off</h2>
 *
 * <p>The device offers the explosives near you. One is skipped if you set it off
 * within the inhibit window, if it is younger than the minimum age &mdash;
 * unless asked about as {@code fresh}, the point of breaking a crystal on spawn
 * &mdash; or if it is out of reach.
 *
 * <h2>One explosion a tick</h2>
 *
 * <p>Only the highest explosion damage a target takes in a tick applies. Once
 * you report an explosive set off with {@link #used}, another only counts
 * against a target if it hits it harder, and the same goes for you. Placing
 * counts this too when the device {@link PlaceDevice#firesAtOnce fires at once}.
 * Searches that share an {@link AttackLog} share this knowledge, whatever their
 * explosives.
 *
 * <h2>Cost</h2>
 *
 * <p>With R the place range, P the spots per cell (1 for a crystal, up to 4 for
 * a bed), T the targets, S the sample points an {@code Exposure} casts from, L
 * the cells a ray crosses (about √3 × its length), B the spots that pass the
 * scan, V those viable after bounding, and K the explosives the device offers:
 *
 * <pre>
 * scan                (2⌈R⌉ + 1)³ cells     distance + P placement tests each; one sight test each past the wall range
 * bound               O(B · T)              no rays
 * order               O(V log V)            one sort
 * exact, worst case   O(V · (T + 1) · S · L)
 * exact, typically    a few spots           the bound stops the search early
 * set off             O(find + K · (T + 1) · S · L)    find: the device's own way of listing them
 * one, used           O((T + 1) · S · L)
 * </pre>
 *
 * <p>Pruning assumes damage never falls as exposure rises, and that a
 * {@link Score} never exceeds the damage to the target; turn it off with
 * {@link Settings#pruning(boolean)} for a model or score that breaks either.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class ExplosiveSearch<E> {

    private final EntityService entities;
    private final TargetService targetService;
    private final TargetSelector<? extends E> selector;
    private final int maxTargets;
    private final Vitals<? super E> vitals;
    private final Thresholds<? super E> thresholds;
    private final Reach placeReach;
    private final Reach useReach;
    private final Score score;
    private final boolean pruning;
    private final AttackLog log;
    /** Whether the log was made here, and so is closed here. */
    private final boolean ownsLog;

    private SearchStats lastPlace = new SearchStats();
    private SearchStats lastUse = new SearchStats();

    private ExplosiveSearch(Settings<E, ?> settings) {
        this.entities = settings.entities;
        this.targetService = settings.targetService;
        this.selector = settings.selector;
        this.maxTargets = settings.maxTargets;
        this.vitals = settings.vitals;
        this.thresholds = settings.thresholds;
        this.placeReach = settings.placeReach;
        this.useReach = settings.useReach;
        this.score = settings.score;
        this.pruning = settings.pruning;
        this.ownsLog = settings.log == null;
        if (settings.log != null) {
            this.log = settings.log;
        } else if (settings.bus != null) {
            this.log = AttackLog.ticking(settings.bus);
        } else {
            this.log = new AttackLog();
        }
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    // ---------------------------------------------------------------- placing

    /** @return the best spot to place {@code device}'s explosive now, or null when nowhere is worth it */
    public <S> Found<E, S> findPlace(PlaceDevice<E, S> device) {
        Validate.notNull(device, "device");
        SearchStats stats = new SearchStats();
        lastPlace = stats;
        Context context = device.active() ? context() : null;
        if (context == null) {
            return null;
        }
        Blast blast = new Blast(device);
        boolean atOnce = device.firesAtOnce();
        Vec3 eye = context.self.getEyePosition();
        double range = placeReach.range();
        double wall = placeReach.wallRange();
        int reach = (int) Math.ceil(range);
        int ex = floor(eye.getX());
        int ey = floor(eye.getY());
        int ez = floor(eye.getZ());

        List<Candidate<S>> candidates = new ArrayList<>();
        List<S> spots = new ArrayList<>(4);
        for (int x = ex - reach; x <= ex + reach; x++) {
            for (int y = ey - reach; y <= ey + reach; y++) {
                for (int z = ez - reach; z <= ez + reach; z++) {
                    stats.cells++;
                    double distance = placeDistance(eye, x, y, z);
                    if (distance > range) {
                        continue;
                    }
                    stats.inReach++;
                    spots.clear();
                    device.spotsAt(x, y, z, spots::add);
                    if (spots.isEmpty()) {
                        continue;
                    }
                    stats.placeable += spots.size();
                    if (distance > wall && !device.visible(eye, x, y, z)) {
                        continue;
                    }
                    stats.visible += spots.size();
                    for (S spot : spots) {
                        Candidate<S> candidate = bound(spot, device.origin(spot), blast, context, stats);
                        if (candidate != null) {
                            stats.viable++;
                            candidates.add(candidate);
                        }
                    }
                }
            }
        }
        // Branch and bound: the best possible first, and stop once nothing left can win.
        candidates.sort((a, b) -> Double.compare(b.best, a.best));
        Found<E, S> best = null;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate<S> candidate = candidates.get(i);
            if (pruning && best != null && candidate.best < best.getScore()) {
                stats.pruned += candidates.size() - i;
                break;
            }
            Found<E, S> option = judgeAll(candidate.spot, candidate.origin, device.blocksWhenFired(candidate.spot),
                    blast, candidate.bounds, atOnce, context, stats);
            if (option != null && option.beats(best)) {
                best = option;
            }
        }
        return best;
    }

    /** The most each target could take from a spot: no rays. Null when none could reach its threshold. */
    private <S> Candidate<S> bound(S spot, Vec3 origin, Blast blast, Context context, SearchStats stats) {
        double[] bounds = new double[context.targets.size()];
        double best = Double.NEGATIVE_INFINITY;
        for (int t = 0; t < bounds.length; t++) {
            TargetInfo<E> info = context.targets.get(t);
            stats.bounds++;
            bounds[t] = blast.bound.damage(origin, blast.explosive, info.target, BlockView.EMPTY);
            if (bounds[t] >= info.floor && bounds[t] > 0d) {
                best = Math.max(best, bounds[t]);
            }
        }
        return best == Double.NEGATIVE_INFINITY ? null : new Candidate<>(spot, origin, bounds, best);
    }

    // ------------------------------------------------------------ setting off

    /** @return the best of {@code device}'s explosives to set off now, or null when none is worth it */
    public <X> Found<E, X> findUse(UseDevice<E, X> device) {
        Validate.notNull(device, "device");
        SearchStats stats = new SearchStats();
        lastUse = stats;
        Context context = device.active() ? context() : null;
        if (context == null) {
            return null;
        }
        Blast blast = new Blast(device);
        List<X> near = new ArrayList<>();
        device.forEachWithin(context.self.getEyePosition(), useReach.range(), near::add);
        Found<E, X> best = null;
        for (X explosive : near) {
            stats.existing++;
            Found<E, X> option = judgeUse(device, explosive, blast, context, stats, false);
            if (option != null && option.beats(best)) {
                best = option;
            }
        }
        return best;
    }

    /**
     * Whether one explosive is worth setting off now: everything {@link #findUse}
     * checks, for one you already have &mdash; a crystal from its spawn packet, say.
     *
     * @param fresh true to skip the minimum age, which a just-spawned crystal cannot meet
     * @return the option to take now, or null to leave it
     */
    public <X> Found<E, X> evaluate(UseDevice<E, X> device, X explosive, boolean fresh) {
        Validate.notNull(device, "device");
        Validate.notNull(explosive, "explosive");
        SearchStats stats = new SearchStats();
        lastUse = stats;
        Context context = device.active() ? context() : null;
        if (context == null) {
            return null;
        }
        stats.existing++;
        return judgeUse(device, explosive, new Blast(device), context, stats, fresh);
    }

    private <X> Found<E, X> judgeUse(UseDevice<E, X> device, X explosive, Blast blast, Context context,
                                     SearchStats stats, boolean fresh) {
        if (log.isInhibited(device.key(explosive))) {
            stats.inhibited++;
            return null;
        }
        if (!fresh && device.age(explosive) < thresholds.breakMinAge()) {
            stats.tooYoung++;
            return null;
        }
        if (!device.inReach(explosive, context.self.getEyePosition(), useReach)) {
            return null;
        }
        return judgeAll(explosive, device.origin(explosive), device.blocksWhenFired(explosive), blast,
                null, true, context, stats);
    }

    /**
     * You set {@code explosive} off: it is inhibited, and what it does is recorded
     * against everyone it reaches for the rest of this tick.
     */
    public <X> void used(UseDevice<E, X> device, X explosive) {
        Validate.notNull(device, "device");
        Validate.notNull(explosive, "explosive");
        log.attacked(device.key(explosive), thresholds.inhibitTicks());
        Context context = context();
        if (context == null) {
            return;
        }
        Vec3 origin = device.origin(explosive);
        BlockView world = device.blocksWhenFired(explosive);
        ExplosionModel<E> model = device.model();
        for (TargetInfo<E> info : context.targets) {
            log.dealt(info.target.get(), model.damage(origin, device.explosive(), info.target, world));
        }
        log.dealt(context.self.get(), model.damage(origin, device.explosive(), context.self, world));
    }

    // ---------------------------------------------------------------- timing

    /**
     * Starts a new tick on this search's log. A log made with a bus, or shared
     * through {@link Settings#log}, ticks itself; tick a shared one only once.
     */
    public void tick() {
        log.newTick();
    }

    public AttackLog getLog() {
        return log;
    }

    /** @return what the last {@link #findPlace} did */
    public SearchStats getLastPlaceStats() {
        return lastPlace;
    }

    /** @return what the last {@link #findUse} or {@link #evaluate} did */
    public SearchStats getLastUseStats() {
        return lastUse;
    }

    /** Stops the search's own log listening to the bus given at build time. A shared log is yours to close. */
    public void close() {
        if (ownsLog) {
            log.close();
        }
    }

    // ------------------------------------------------------------ internals

    /**
     * The best target {@code subject} is worth setting off against, if any.
     *
     * @param bounds each target's bound, to skip ones that cannot pass; null to try every target
     * @param counted whether what has gone off this tick counts against it
     */
    private <T> Found<E, T> judgeAll(T subject, Vec3 origin, BlockView world, Blast blast, double[] bounds,
                                     boolean counted, Context context, SearchStats stats) {
        double self = Double.NaN;
        Found<E, T> best = null;
        for (int t = 0; t < context.targets.size(); t++) {
            TargetInfo<E> info = context.targets.get(t);
            if (bounds != null && (bounds[t] < info.floor || bounds[t] <= 0d)) {
                continue;
            }
            stats.evaluated++;
            double damage = blast.model.damage(origin, blast.explosive, info.target, world);
            Trigger trigger = judge(damage, counted ? log.dealtTo(info.target.get()) : 0d, info);
            if (trigger == null) {
                continue;
            }
            if (Double.isNaN(self)) {
                stats.selfEvaluated++;
                self = blast.model.damage(origin, blast.explosive, context.self, world);
                if (counted) {
                    // Only the highest explosion a tick lands, on you as on them.
                    self = Math.max(self, log.dealtTo(context.self.get()));
                }
                if (context.suicidal(self)) {
                    return null;                 // too dangerous for anyone
                }
            }
            if (!context.selfAllows(self, trigger)) {
                continue;
            }
            double value = score.score(damage, self);
            if (better(value, self, best)) {
                best = new Found<>(subject, origin, info.target, damage, self, value, trigger);
            }
        }
        return best;
    }

    /**
     * @return whether an option passes the target's thresholds, and why; null if not
     * @param dealt what the target already takes this tick: an option only helps if it does more
     */
    private Trigger judge(double damage, double dealt, TargetInfo<E> info) {
        if (damage <= dealt || damage <= 0d) {
            return null;
        }
        if (info.lethalNeed <= damage) {
            return Trigger.LETHAL;
        }
        if (damage >= info.minimum) {
            return Trigger.MINIMUM;
        }
        if (damage >= info.facePlace) {
            return Trigger.FACEPLACE;
        }
        if (damage >= info.armourBreak) {
            return Trigger.ARMOUR_BREAK;
        }
        return null;
    }

    /** @return whether an option scoring {@code value} with {@code self} damage beats {@code best}, before making it */
    private static boolean better(double value, double self, Option<?> best) {
        return best == null || value > best.getScore() || (value == best.getScore() && self < best.getSelfDamage());
    }

    private double placeDistance(Vec3 eye, int x, int y, int z) {
        if (placeReach.getPoint() == ReachPoint.NEAREST) {
            return Box.block(x, y, z).distanceTo(eye);
        }
        double dx = x + 0.5 - eye.getX();
        double dy = y + 0.5 - eye.getY();
        double dz = z + 0.5 - eye.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Everything a search reads once: you, the targets and their thresholds. Null when there is nothing to do. */
    private Context context() {
        Tracked<? extends E> self = entities.<E>getSelf();
        if (self == null) {
            return null;
        }
        List<Tracked<? extends E>> targets = collect(selector);
        if (targets.isEmpty()) {
            return null;
        }
        List<TargetInfo<E>> infos = new ArrayList<>(targets.size());
        for (Tracked<? extends E> target : targets) {
            infos.add(new TargetInfo<>(target, vitals, thresholds));
        }
        return new Context(self, infos);
    }

    private <T extends E> List<Tracked<? extends E>> collect(TargetSelector<T> chosen) {
        return new ArrayList<Tracked<? extends E>>(targetService.all(chosen, maxTargets));
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** A device's explosion, with its no-ray bound built once per search. */
    private final class Blast {
        final ExplosionModel<E> model;
        final ExplosionModel<E> bound;
        final Explosive explosive;

        Blast(Device<E> device) {
            this.model = device.model();
            this.bound = model.withExposure(Exposure.FULL);
            this.explosive = device.explosive();
        }
    }

    /** A placeable spot, and the most each target could take from it. */
    private static final class Candidate<S> {
        final S spot;
        final Vec3 origin;
        final double[] bounds;
        final double best;

        Candidate(S spot, Vec3 origin, double[] bounds, double best) {
            this.spot = spot;
            this.origin = origin;
            this.bounds = bounds;
            this.best = best;
        }
    }

    /** One target, with its thresholds worked out once per search. */
    private static final class TargetInfo<E> {
        final Tracked<? extends E> target;
        /** Damage at or past which it is lethal; infinite when lethal checks are off or its health is unknown. */
        final double lethalNeed;
        final double minimum;
        /** The faceplace minimum, or infinity when it does not apply to this target. */
        final double facePlace;
        /** The armour-break minimum, or infinity when it does not apply to this target. */
        final double armourBreak;
        /** The least damage that could pass any threshold: for discarding by bound. */
        final double floor;

        TargetInfo(Tracked<? extends E> target, Vitals<? super E> vitals, Thresholds<? super E> thresholds) {
            this.target = target;
            E entity = target.get();
            double pool = vitals.isTrusted(entity) ? vitals.pool(entity) : Double.NaN;
            double multiplier = thresholds.lethalMultiplier();
            this.lethalNeed = Double.isNaN(multiplier) || Double.isNaN(pool) || multiplier <= 0d
                    ? Double.POSITIVE_INFINITY : pool / multiplier;
            this.minimum = thresholds.minDamage();
            this.facePlace = thresholds.facePlaces(pool) ? thresholds.facePlaceDamage() : Double.POSITIVE_INFINITY;
            this.armourBreak = thresholds.breaksArmour(entity) ? thresholds.armourBreakDamage() : Double.POSITIVE_INFINITY;
            this.floor = Math.min(Math.min(lethalNeed, minimum), Math.min(facePlace, armourBreak));
        }
    }

    /** You, and what you can survive. */
    private final class Context {
        final Tracked<? extends E> self;
        final List<TargetInfo<E>> targets;
        /** Self damage at or past which an option is suicide; infinite when anti-suicide is off. */
        final double suicide;
        final double selfCap;

        Context(Tracked<? extends E> self, List<TargetInfo<E>> targets) {
            this.self = self;
            this.targets = Collections.unmodifiableList(targets);
            double margin = thresholds.antiSuicideMargin();
            double pool = vitals.pool(self.get());
            this.suicide = Double.isNaN(margin) || Double.isNaN(pool) ? Double.POSITIVE_INFINITY : pool - margin;
            this.selfCap = thresholds.maxSelfDamage();
        }

        boolean suicidal(double selfDamage) {
            return selfDamage >= suicide;
        }

        boolean selfAllows(double selfDamage, Trigger trigger) {
            return selfDamage <= selfCap || (trigger == Trigger.LETHAL && thresholds.lethalIgnoresSelfCap());
        }
    }

    // ---------------------------------------------------------------- building

    /**
     * What every search is built from, whatever it searches for: who you are, who
     * counts, what is worth it, and how far you reach. {@code CrystalSearch} and
     * {@code BedSearch} build on this, adding their rules.
     *
     * @param <B> the builder itself, so each setting returns it
     */
    public abstract static class Settings<E, B extends Settings<E, B>> {

        private EntityService entities;
        private TargetService targetService;
        private TargetSelector<? extends E> selector;
        private int maxTargets = Integer.MAX_VALUE;
        private Vitals<? super E> vitals;
        private Thresholds<? super E> thresholds;
        private Reach placeReach;
        private Reach useReach;
        private Score score = Score.DAMAGE;
        private boolean pruning = true;
        private EventBus bus;
        private AttackLog log;

        protected Settings() {
        }

        /** @return this builder, as its own type */
        protected abstract B self();

        /** Required: where the local player comes from. */
        public B entities(EntityService entities) {
            this.entities = Validate.notNull(entities, "entities");
            return self();
        }

        /** Required: who counts as a target, chosen by a selector of yours. */
        public B targets(TargetService service, TargetSelector<? extends E> selector) {
            this.targetService = Validate.notNull(service, "service");
            this.selector = Validate.notNull(selector, "selector");
            return self();
        }

        /** @param targets how many of the selector's best to weigh each option against; all unless set */
        public B maxTargets(int targets) {
            Validate.check(targets > 0, "maxTargets must be positive");
            this.maxTargets = targets;
            return self();
        }

        /** Required: how much damage you and your targets can still take. */
        public B vitals(Vitals<? super E> vitals) {
            this.vitals = Validate.notNull(vitals, "vitals");
            return self();
        }

        /** Required: what is worth doing, and what is too dangerous; {@code Thresholds.none()} for no limits. */
        public B thresholds(Thresholds<? super E> thresholds) {
            this.thresholds = Validate.notNull(thresholds, "thresholds");
            return self();
        }

        /** Required: how far away you place. */
        public B placeReach(Reach reach) {
            this.placeReach = Validate.notNull(reach, "reach");
            return self();
        }

        /** How options are ranked; {@link Score#DAMAGE} unless set. */
        public B score(Score score) {
            this.score = Validate.notNull(score, "score");
            return self();
        }

        /** Whether to skip spots that cannot win; on unless set. See the class notes. */
        public B pruning(boolean pruning) {
            this.pruning = pruning;
            return self();
        }

        /**
         * Optional: the search's own log ticks itself on this bus's {@code TickEvent}
         * and forgets on leaving a world. Not needed with a shared {@link #log}.
         */
        public B bus(EventBus bus) {
            this.bus = Validate.notNull(bus, "bus");
            return self();
        }

        /**
         * Optional: a log shared with your other searches, so the one-explosion-a-tick
         * rule holds across them all. Tick it once: {@link AttackLog#ticking} does.
         */
        public B log(AttackLog log) {
            this.log = Validate.notNull(log, "log");
            return self();
        }

        /** Required, under whatever name the search gives it: how far away you set things off. */
        protected final B reachToUse(Reach reach) {
            this.useReach = Validate.notNull(reach, "reach");
            return self();
        }

        /**
         * Adds the name of each shared part not given to {@code missing}.
         *
         * @param useReachName what this search calls the reach to set things off
         */
        protected final void missing(StringBuilder missing, String useReachName) {
            if (entities == null) {
                missing.append(" entities");
            }
            if (selector == null) {
                missing.append(" targets");
            }
            if (vitals == null) {
                missing.append(" vitals");
            }
            if (thresholds == null) {
                missing.append(" thresholds");
            }
            if (placeReach == null) {
                missing.append(" placeReach");
            }
            if (useReach == null) {
                missing.append(' ').append(useReachName);
            }
        }

        /** @return the engine these settings describe; call once {@link #missing} found nothing */
        protected final ExplosiveSearch<E> engine() {
            return new ExplosiveSearch<>(this);
        }
    }

    public static final class Builder<E> extends Settings<E, Builder<E>> {

        private Builder() {
        }

        @Override
        protected Builder<E> self() {
            return this;
        }

        /** Required: how far away you set things off. */
        public Builder<E> useReach(Reach reach) {
            return reachToUse(reach);
        }

        /** @throws IllegalStateException naming each required part not given */
        public ExplosiveSearch<E> build() {
            StringBuilder missing = new StringBuilder();
            missing(missing, "useReach");
            if (missing.length() > 0) {
                throw new IllegalStateException("an ExplosiveSearch needs:" + missing);
            }
            return engine();
        }
    }
}
