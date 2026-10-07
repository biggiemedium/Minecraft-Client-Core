package dev.px.combat.search.engine;

import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.search.option.Harm;
import dev.px.combat.search.option.Option;
import dev.px.combat.search.option.Proposal;
import dev.px.combat.search.option.Trigger;
import dev.px.combat.search.rule.AimCost;
import dev.px.combat.search.rule.OptionFilter;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.rule.ReachPoint;
import dev.px.combat.search.rule.Score;
import dev.px.combat.search.rule.Thresholds;
import dev.px.combat.search.timing.AttackLog;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.Tracked;
import dev.px.core.event.EventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Lookahead;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

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
 * Found<LivingEntity, MySpot> spot = search.findPlace(myDevice);          // an explosive of your own
 * List<Found<LivingEntity, MySpot>> ranked = search.findPlaces(myDevice, 3);
 * }</pre>
 *
 * <h2>Placing</h2>
 *
 * <ol>
 *   <li><b>Scan</b> every cell in a cube around your eyes that reaches the place
 *       range, cheapest test first: distance, then the device's spots in the
 *       cell, then, past the wall range, whether the cell can be seen, then
 *       whether you can turn to each spot in time.
 *   <li><b>Bound</b> each spot against each target: the damage it would do with
 *       nothing in the way, which costs no raycasting. A spot whose bound cannot
 *       meet any target's threshold is dropped there.
 *   <li><b>Branch and bound.</b> Spots are tried best bound first, with exact,
 *       raycast estimates; once the best option found ranks higher than the next
 *       bound, nothing left can win and the search stops. A spot's bound is
 *       lowered by its {@linkplain AimCost aim cost}, worked out without rays, so
 *       aiming keeps this exact. Asked for the best
 *       {@code n} with {@link #findPlaces}, it stops once the {@code n}th best does.
 * </ol>
 *
 * <p>Each spot is offered once, against the target it is best for. The best
 * {@code n} are alternatives &mdash; somewhere to go when you cannot reach the
 * first &mdash; not a set to place together: two may overlap.
 *
 * <h2>Aiming</h2>
 *
 * <p>Every option says where you would look to act on it &mdash;
 * {@link Option#getAim()}, from the device &mdash; and an {@link AimCost} of yours,
 * one for placing and one for setting off, says what turning there is worth in
 * damage. Options rank by score less that cost, so a spot you can hit now can beat
 * a stronger one behind you, and one you cannot turn to in time is dropped before
 * anything is raycast. The search never turns you: your rotation manager, whichever
 * it is, turns to {@code getAim()}.
 *
 * <h2>Looking ahead</h2>
 *
 * <p>With a {@link Settings#placeDelay} or {@link Settings#useDelay}, damage is
 * scored where your {@link Lookahead} says each target, you, and everyone
 * protected will be when the explosion lands. {@link Lookahead#none()} unless
 * set; the search never knows how it is answered.
 *
 * <h2>Placing, then breaking</h2>
 *
 * <p>{@link #placed} remembers what you placed until it shows up: until then no
 * spot whose {@linkplain PlaceDevice#needs room} it takes up is offered, and
 * {@link #planPlaces} plans several that do not collide. When it shows up, it is
 * {@linkplain Option#isOwn yours}.
 *
 * <h2>Protecting, and your own filters</h2>
 *
 * <p>{@link Settings#protect} names who must not be hurt &mdash; your friends,
 * say &mdash; with a selector of yours. They are never targets, and once a spot
 * passes for a target, what it would do to each of them is checked against
 * {@code Thresholds.maxProtectedDamage} and {@code protectedMargin}. Most of
 * them are cleared by the same no-ray bound the search uses for targets: only a
 * protected entity the bound says could be hurt too much is raycast.
 *
 * <p>Then each {@link OptionFilter} has the last word on an option about to be
 * chosen. It sees a {@link Proposal}: the option, and on request what it would
 * do to everyone protected, raycast once per explosion.
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
 * scan                (2⌈R⌉ + 1)³ cells     distance + P placement tests each; one sight test each past the wall range;
 *                                           one aim cost per spot seen
 * bound               O(B · T)              no rays
 * order               O(V log V)            one sort
 * exact, worst case   O(V · (T + 1) · S · L)
 * exact, typically    a few spots           the bound stops the search early
 * set off             O(find + K · (T + 1) · S · L)    find: the device's own way of listing them
 * one, used           O((T + 1) · S · L)
 * </pre>
 *
 * <p>Protecting F entities adds O(F) bounds per spot judged, and a raycast
 * estimate only for those the bound cannot clear, or that a filter asks about.
 *
 * <p>Pruning assumes damage never falls as exposure rises, and that a
 * {@link Score} never exceeds the damage to the target &mdash; an aim cost, being
 * exact, needs nothing; turn it off with
 * {@link Settings#pruning(boolean)} for a model or score that breaks either.
 * Protection then raycasts everyone it protects instead of trusting the bound.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class ExplosiveSearch<E> {

    /** Ticks a placement is waited for unless {@code pendingTicks} says otherwise: half a second. A tuning knob. */
    public static final int DEFAULT_PENDING_TICKS = 10;

    /** Ticks an explosive that showed up where you placed it is remembered as yours: longer than any lives. */
    private static final int OWN_MEMORY = 400;

    private final EntityService entities;
    private final TargetService targetService;
    private final TargetSelector<? extends E> selector;
    private final TargetSelector<? extends E> protectedSelector;
    private final int maxTargets;
    private final Vitals<? super E> vitals;
    private final Thresholds<? super E> thresholds;
    private final Reach placeReach;
    private final Reach useReach;
    private final Score score;
    private final AimCost placeAim;
    private final AimCost useAim;
    private final Lookahead<E> lookahead;
    private final IntSupplier placeDelay;
    private final IntSupplier useDelay;
    private final IntSupplier pendingTicks;
    /** Null when nobody is listening. */
    private final SearchListener<E> listener;
    private final boolean pruning;
    private final List<OptionFilter<E>> filters;
    private final AttackLog log;
    /** Whether the log was made here, and so is closed here. */
    private final boolean ownsLog;

    private SearchStats lastPlace = new SearchStats();
    private SearchStats lastUse = new SearchStats();

    private ExplosiveSearch(Settings<E, ?> settings) {
        this.entities = settings.entities;
        this.targetService = settings.targetService;
        this.selector = settings.selector;
        this.protectedSelector = settings.protectedSelector;
        this.maxTargets = settings.maxTargets;
        this.vitals = settings.vitals;
        this.thresholds = settings.thresholds;
        this.placeReach = settings.placeReach;
        this.useReach = settings.useReach;
        this.score = settings.score;
        this.placeAim = settings.placeAim;
        this.useAim = settings.useAim;
        this.lookahead = settings.lookahead;
        this.placeDelay = settings.placeDelay;
        this.useDelay = settings.useDelay;
        this.pendingTicks = settings.pendingTicks;
        this.listener = settings.listener;
        this.pruning = settings.pruning;
        this.filters = Collections.unmodifiableList(new ArrayList<>(settings.filters));
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
        List<Found<E, S>> best = findPlaces(device, 1);
        return best.isEmpty() ? null : best.get(0);
    }

    /**
     * The best {@code count} spots to place {@code device}'s explosive now, best
     * first: alternatives for when you cannot act on the first, not spots to fill
     * together.
     *
     * @return at most {@code count} options, one per spot; empty when nowhere is worth it
     */
    public <S> List<Found<E, S>> findPlaces(PlaceDevice<E, S> device, int count) {
        Validate.notNull(device, "device");
        Validate.check(count > 0, "count must be positive");
        return search(device, count, Collections.<Box>emptyList());
    }

    /**
     * Up to {@code count} spots to place {@code device}'s explosive at together:
     * each the best that does not collide with the ones before it, or with
     * anything placed and not yet shown. Several placements a tick, where
     * {@link #findPlaces} gives alternatives to one.
     *
     * <p>A search per spot. A device that does not say what its explosive
     * {@linkplain PlaceDevice#occupies occupies} gets one spot, since nothing says
     * which others would collide with it.
     *
     * @return at most {@code count} spots, best first; empty when nowhere is worth it
     */
    public <S> List<Found<E, S>> planPlaces(PlaceDevice<E, S> device, int count) {
        Validate.notNull(device, "device");
        Validate.check(count > 0, "count must be positive");
        List<Found<E, S>> plan = new ArrayList<>(count);
        List<Box> taken = new ArrayList<>(count);
        while (plan.size() < count) {
            List<Found<E, S>> next = search(device, 1, taken);
            if (next.isEmpty()) {
                break;
            }
            Found<E, S> found = next.get(0);
            plan.add(found);
            Box room = device.occupies(found.getSubject());
            if (room == null) {
                break;
            }
            taken.add(room);
        }
        return Collections.unmodifiableList(plan);
    }

    /**
     * You placed {@code device}'s explosive at {@code spot}: until it shows up, or
     * its wait runs out, nothing collides with it, and when it shows up it is
     * {@linkplain Option#isOwn yours}. Nothing for a device that fires at once.
     */
    public <S> void placed(PlaceDevice<E, S> device, S spot) {
        Validate.notNull(device, "device");
        Validate.notNull(spot, "spot");
        Box room = device.occupies(spot);
        if (room != null && !device.firesAtOnce()) {
            log.placed(room, pendingTicks.getAsInt());
        }
    }

    private <S> List<Found<E, S>> search(PlaceDevice<E, S> device, int count, List<Box> taken) {
        SearchStats stats = new SearchStats();
        lastPlace = stats;
        Context context = device.active() ? context(placeDelay) : null;
        if (context == null) {
            return Collections.emptyList();
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
                        if (blocked(device.needs(spot), taken)) {
                            stats.pending++;
                            continue;
                        }
                        if (!device.clickable(eye, spot)) {
                            stats.unclickable++;
                            continue;
                        }
                        Vec3 aim = device.aim(eye, spot);
                        double aimCost = placeAim.cost(eye, aim);
                        if (!Double.isFinite(aimCost)) {
                            stats.unaimable++;
                            continue;
                        }
                        Candidate<S> candidate = bound(spot, device.origin(spot), aim, aimCost, blast, context, stats);
                        if (candidate != null) {
                            stats.viable++;
                            candidates.add(candidate);
                        }
                    }
                }
            }
        }
        // Branch and bound: the best possible first, and stop once nothing left can make the cut.
        candidates.sort((a, b) -> Double.compare(b.best, a.best));
        List<Found<E, S>> best = new ArrayList<>(Math.min(count, candidates.size()));
        for (int i = 0; i < candidates.size(); i++) {
            Candidate<S> candidate = candidates.get(i);
            if (pruning && best.size() == count && candidate.best < best.get(count - 1).getRank()) {
                stats.pruned += candidates.size() - i;
                break;
            }
            Found<E, S> option = judgeAll(candidate.spot, candidate.origin, candidate.aim, candidate.aimCost, false,
                    device.blocksWhenFired(candidate.spot), blast, candidate.bounds, atOnce, context, stats);
            if (option != null) {
                keep(best, option, count);
            }
        }
        return Collections.unmodifiableList(best);
    }

    /** @return whether {@code needs} collides with something placed and not yet shown, or already planned */
    private boolean blocked(Box needs, List<Box> taken) {
        if (needs == null) {
            return false;
        }
        for (Box room : taken) {
            if (room.intersects(needs)) {
                return true;
            }
        }
        return log.isPendingIn(needs);
    }

    /** Puts {@code option} in its place among the best {@code count}, best first; an equal one stays behind. */
    private static <O extends Option<?>> void keep(List<O> best, O option, int count) {
        if (best.size() == count && !option.beats(best.get(count - 1))) {
            return;
        }
        int at = best.size();
        while (at > 0 && option.beats(best.get(at - 1))) {
            at--;
        }
        best.add(at, option);
        if (best.size() > count) {
            best.remove(count);
        }
    }

    /**
     * The most each target could take from a spot: no rays. Null when none could
     * reach its threshold. The spot's best rank is the most any could take, less
     * the aim cost.
     */
    private <S> Candidate<S> bound(S spot, Vec3 origin, Vec3 aim, double aimCost, Blast blast, Context context,
                                   SearchStats stats) {
        double[] bounds = new double[context.targets.size()];
        double best = Double.NEGATIVE_INFINITY;
        for (int t = 0; t < bounds.length; t++) {
            TargetInfo<E> info = context.targets.get(t);
            stats.bounds++;
            bounds[t] = blast.bound.damage(origin, blast.explosive, info.at, BlockView.EMPTY);
            if (bounds[t] >= info.floor && bounds[t] > 0d) {
                best = Math.max(best, bounds[t]);
            }
        }
        return best == Double.NEGATIVE_INFINITY ? null : new Candidate<>(spot, origin, aim, aimCost, bounds, best - aimCost);
    }

    // ------------------------------------------------------------ setting off

    /** @return the best of {@code device}'s explosives to set off now, or null when none is worth it */
    public <X> Found<E, X> findUse(UseDevice<E, X> device) {
        Validate.notNull(device, "device");
        SearchStats stats = new SearchStats();
        lastUse = stats;
        Context context = device.active() ? context(useDelay) : null;
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
        Context context = device.active() ? context(useDelay) : null;
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
        Vec3 eye = context.self.getEyePosition();
        if (!device.inReach(explosive, eye, useReach)) {
            return null;
        }
        if (!device.clickable(eye, explosive)) {
            stats.unclickable++;
            return null;
        }
        Vec3 aim = device.aim(eye, explosive);
        double aimCost = useAim.cost(eye, aim);
        if (!Double.isFinite(aimCost)) {
            stats.unaimable++;
            return null;
        }
        Box room = device.occupies(explosive);
        boolean own = room != null && log.arrived(device.key(explosive), room, OWN_MEMORY);
        return judgeAll(explosive, device.origin(explosive), aim, aimCost, own, device.blocksWhenFired(explosive),
                blast, null, true, context, stats);
    }

    /**
     * You set {@code explosive} off: it is inhibited, and what it does is recorded
     * against everyone it reaches for the rest of this tick.
     */
    public <X> void used(UseDevice<E, X> device, X explosive) {
        Validate.notNull(device, "device");
        Validate.notNull(explosive, "explosive");
        log.attacked(device.key(explosive), thresholds.inhibitTicks());
        Context context = context(useDelay);
        if (context == null) {
            return;
        }
        Vec3 origin = device.origin(explosive);
        BlockView world = device.blocksWhenFired(explosive);
        ExplosionModel<E> model = device.model();
        for (TargetInfo<E> info : context.targets) {
            log.dealt(info.target.get(), model.damage(origin, device.explosive(), info.at, world));
        }
        log.dealt(context.self.get(), model.damage(origin, device.explosive(), context.selfAt, world));
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
    private <T> Found<E, T> judgeAll(T subject, Vec3 origin, Vec3 aim, double aimCost, boolean own, BlockView world,
                                     Blast blast, double[] bounds, boolean counted, Context context, SearchStats stats) {
        double self = Double.NaN;
        Harms harms = null;
        Found<E, T> best = null;
        for (int t = 0; t < context.targets.size(); t++) {
            TargetInfo<E> info = context.targets.get(t);
            if (bounds != null && (bounds[t] < info.floor || bounds[t] <= 0d)) {
                continue;
            }
            stats.evaluated++;
            double damage = blast.model.damage(origin, blast.explosive, info.at, world);
            Trigger trigger = judge(damage, counted ? log.dealtTo(info.target.get()) : 0d, info);
            if (trigger == null) {
                report(bounds != null, subject, origin, info, damage, self, null, Judgement.Verdict.TOO_WEAK);
                continue;
            }
            if (Double.isNaN(self)) {
                stats.selfEvaluated++;
                self = blast.model.damage(origin, blast.explosive, context.selfAt, world);
                if (counted) {
                    // Only the highest explosion a tick lands, on you as on them.
                    self = Math.max(self, log.dealtTo(context.self.get()));
                }
                if (context.suicidal(self)) {
                    report(bounds != null, subject, origin, info, damage, self, trigger, Judgement.Verdict.SUICIDAL);
                    return null;                 // too dangerous for anyone
                }
                harms = new Harms(origin, world, blast, context, stats);
                if (harms.endangers()) {
                    stats.endangering++;
                    report(bounds != null, subject, origin, info, damage, self, trigger, Judgement.Verdict.ENDANGERS);
                    return null;                 // whoever it is for, it hurts someone protected
                }
            }
            if (!context.selfAllows(self, trigger)) {
                report(bounds != null, subject, origin, info, damage, self, trigger, Judgement.Verdict.SELF_CAP);
                continue;
            }
            double value = score.score(damage, self);
            if (!better(value - aimCost, self, best)) {
                report(bounds != null, subject, origin, info, damage, self, trigger, Judgement.Verdict.OUTRANKED);
                continue;
            }
            Found<E, T> option = new Found<>(subject, origin, aim, info.target, damage, self, value, aimCost, trigger,
                    own);
            if (accepted(option, harms, stats)) {
                best = option;
                report(bounds != null, subject, origin, info, damage, self, trigger, Judgement.Verdict.PASSED);
            } else {
                report(bounds != null, subject, origin, info, damage, self, trigger, Judgement.Verdict.FILTERED);
            }
        }
        return best;
    }

    /** Tells the listener, if there is one, what became of one estimate. */
    private void report(boolean placing, Object subject, Vec3 origin, TargetInfo<E> info, double damage, double self,
                        Trigger trigger, Judgement.Verdict verdict) {
        if (listener != null) {
            listener.judged(new Judgement<E>(placing, subject, origin, info.target, damage, self, trigger, verdict));
        }
    }

    /** @return whether every filter takes {@code option} */
    private boolean accepted(Option<E> option, Harms harms, SearchStats stats) {
        if (filters.isEmpty()) {
            return true;
        }
        Proposal<E> proposal = new Proposal<>(option, harms);
        for (OptionFilter<E> filter : filters) {
            if (!filter.accepts(proposal)) {
                stats.filtered++;
                return false;
            }
        }
        return true;
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

    /** @return whether an option ranking {@code rank} with {@code self} damage beats {@code best}, before making it */
    private static boolean better(double rank, double self, Option<?> best) {
        return best == null || rank > best.getRank() || (rank == best.getRank() && self < best.getSelfDamage());
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

    /**
     * Everything a search reads once: you, the targets and their thresholds, and
     * who is protected, each where the lookahead says they will be when an
     * explosion set off now lands. Null when there is nothing to do.
     *
     * @param delay ticks from now until the explosion lands
     */
    private Context context(IntSupplier delay) {
        int ticks = Math.max(0, delay.getAsInt());
        Tracked<? extends E> self = entities.<E>getSelf();
        if (self == null) {
            return null;
        }
        List<Tracked<? extends E>> guarded = protectedSelector == null
                ? Collections.<Tracked<? extends E>>emptyList() : collect(protectedSelector, Integer.MAX_VALUE);
        List<Tracked<? extends E>> targets = collect(selector, guarded.isEmpty() ? maxTargets : Integer.MAX_VALUE);
        if (!guarded.isEmpty()) {
            // The protected are never targets, whatever the selectors say; the cut to maxTargets comes after.
            Map<Object, Boolean> isGuarded = new IdentityHashMap<>();
            for (Tracked<? extends E> entity : guarded) {
                isGuarded.put(entity.get(), Boolean.TRUE);
            }
            targets.removeIf(target -> isGuarded.containsKey(target.get()));
            if (targets.size() > maxTargets) {
                targets.subList(maxTargets, targets.size()).clear();
            }
        }
        if (targets.isEmpty()) {
            return null;
        }
        List<TargetInfo<E>> infos = new ArrayList<>(targets.size());
        for (Tracked<? extends E> target : targets) {
            infos.add(new TargetInfo<>(target, ahead(target, ticks), vitals, thresholds));
        }
        List<Guard<E>> guards = new ArrayList<>(guarded.size());
        for (Tracked<? extends E> entity : guarded) {
            guards.add(new Guard<>(entity, ahead(entity, ticks), vitals));
        }
        return new Context(self, ahead(self, ticks), infos, guards);
    }

    /** @return where {@code entity} will be in {@code ticks}; itself without looking ahead */
    private Tracked<? extends E> ahead(Tracked<? extends E> entity, int ticks) {
        if (ticks == 0) {
            return entity;
        }
        Tracked<? extends E> there = lookahead.at(entity, ticks);
        return there != null ? there : entity;
    }

    private <T extends E> List<Tracked<? extends E>> collect(TargetSelector<T> chosen, int limit) {
        return new ArrayList<Tracked<? extends E>>(targetService.all(chosen, limit));
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

    /** A placeable spot, what aiming at it costs, and the most each target could take from it. */
    private static final class Candidate<S> {
        final S spot;
        final Vec3 origin;
        final Vec3 aim;
        final double aimCost;
        final double[] bounds;
        /** The best rank any option here could have. */
        final double best;

        Candidate(S spot, Vec3 origin, Vec3 aim, double aimCost, double[] bounds, double best) {
            this.spot = spot;
            this.origin = origin;
            this.aim = aim;
            this.aimCost = aimCost;
            this.bounds = bounds;
            this.best = best;
        }
    }

    /** One target, with its thresholds worked out once per search. */
    private static final class TargetInfo<E> {
        final Tracked<? extends E> target;
        /** Where it will be when the explosion lands: what damage is measured to. */
        final Tracked<? extends E> at;
        /** Damage at or past which it is lethal; infinite when lethal checks are off or its health is unknown. */
        final double lethalNeed;
        final double minimum;
        /** The faceplace minimum, or infinity when it does not apply to this target. */
        final double facePlace;
        /** The armour-break minimum, or infinity when it does not apply to this target. */
        final double armourBreak;
        /** The least damage that could pass any threshold: for discarding by bound. */
        final double floor;

        TargetInfo(Tracked<? extends E> target, Tracked<? extends E> at, Vitals<? super E> vitals,
                   Thresholds<? super E> thresholds) {
            this.target = target;
            this.at = at;
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

    /** One protected entity, and what it can take; its pool is NaN when your {@code Vitals} do not trust it. */
    private static final class Guard<E> {
        final Tracked<? extends E> entity;
        /** Where it will be when the explosion lands. */
        final Tracked<? extends E> at;
        final double pool;

        Guard(Tracked<? extends E> entity, Tracked<? extends E> at, Vitals<? super E> vitals) {
            this.entity = entity;
            this.at = at;
            E handle = entity.get();
            this.pool = vitals.isTrusted(handle) ? vitals.pool(handle) : Double.NaN;
        }
    }

    /** You, what you can survive, and who must not be hurt. */
    private final class Context {
        /** You, now: where you reach from. */
        final Tracked<? extends E> self;
        /** You, when the explosion lands: what your damage is measured to. */
        final Tracked<? extends E> selfAt;
        final List<TargetInfo<E>> targets;
        final List<Guard<E>> guards;
        /** Self damage at or past which an option is suicide; infinite when anti-suicide is off. */
        final double suicide;
        final double selfCap;
        final double protectedCap;
        /** NaN when off. */
        final double protectedMargin;
        /** Whether any protection threshold is on, so there is anything to check. */
        final boolean guarding;

        Context(Tracked<? extends E> self, Tracked<? extends E> selfAt, List<TargetInfo<E>> targets,
                List<Guard<E>> guards) {
            this.self = self;
            this.selfAt = selfAt;
            this.targets = Collections.unmodifiableList(targets);
            this.guards = Collections.unmodifiableList(guards);
            double margin = thresholds.antiSuicideMargin();
            double pool = vitals.pool(self.get());
            this.suicide = Double.isNaN(margin) || Double.isNaN(pool) ? Double.POSITIVE_INFINITY : pool - margin;
            this.selfCap = thresholds.maxSelfDamage();
            this.protectedCap = thresholds.maxProtectedDamage();
            this.protectedMargin = thresholds.protectedMargin();
            this.guarding = !guards.isEmpty()
                    && (protectedCap < Double.POSITIVE_INFINITY || !Double.isNaN(protectedMargin));
        }

        boolean suicidal(double selfDamage) {
            return selfDamage >= suicide;
        }

        boolean selfAllows(double selfDamage, Trigger trigger) {
            return selfDamage <= selfCap || (trigger == Trigger.LETHAL && thresholds.lethalIgnoresSelfCap());
        }

        /** @return whether {@code damage} to {@code guard} is more than the protection thresholds allow */
        boolean endangers(Guard<E> guard, double damage) {
            return damage > protectedCap
                    || (!Double.isNaN(protectedMargin) && !Double.isNaN(guard.pool) && damage >= guard.pool - protectedMargin);
        }
    }

    /**
     * What one explosion does to each protected entity, worked out only as far as
     * asked: by the protection thresholds, then by a filter's {@link Proposal}.
     */
    private final class Harms implements Supplier<List<Harm<E>>> {
        private final Vec3 origin;
        private final BlockView world;
        private final Blast blast;
        private final Context context;
        private final SearchStats stats;
        /** Exact damage to each guard; NaN until worked out. */
        private final double[] damage;
        private List<Harm<E>> harmed;

        Harms(Vec3 origin, BlockView world, Blast blast, Context context, SearchStats stats) {
            this.origin = origin;
            this.world = world;
            this.blast = blast;
            this.context = context;
            this.stats = stats;
            this.damage = new double[context.guards.size()];
            Arrays.fill(damage, Double.NaN);
        }

        /** @return whether it hurts anyone protected more than the thresholds allow */
        boolean endangers() {
            if (!context.guarding) {
                return false;
            }
            for (int g = 0; g < damage.length; g++) {
                Guard<E> guard = context.guards.get(g);
                // The bound costs no rays and is never less than the damage: only a guard it cannot clear is raycast.
                if (pruning && !context.endangers(guard, bound(guard))) {
                    continue;
                }
                if (context.endangers(guard, damage(g))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<Harm<E>> get() {
            if (harmed == null) {
                List<Harm<E>> list = new ArrayList<>();
                for (int g = 0; g < damage.length; g++) {
                    Guard<E> guard = context.guards.get(g);
                    double dealt = damage(g);
                    if (dealt > 0d) {
                        list.add(new Harm<E>(guard.entity, dealt, guard.pool));
                    }
                }
                harmed = Collections.unmodifiableList(list);
            }
            return harmed;
        }

        private double bound(Guard<E> guard) {
            return blast.bound.damage(origin, blast.explosive, guard.at, BlockView.EMPTY);
        }

        private double damage(int g) {
            if (Double.isNaN(damage[g])) {
                Guard<E> guard = context.guards.get(g);
                if (pruning && bound(guard) <= 0d) {
                    damage[g] = 0d;              // out of reach: nothing to raycast
                } else {
                    stats.protectedEvaluated++;
                    damage[g] = blast.model.damage(origin, blast.explosive, guard.at, world);
                }
            }
            return damage[g];
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
        private TargetSelector<? extends E> protectedSelector;
        private Score score = Score.DAMAGE;
        private AimCost placeAim = AimCost.NONE;
        private AimCost useAim = AimCost.NONE;
        private Lookahead<E> lookahead = Lookahead.none();
        private IntSupplier placeDelay = () -> 0;
        private IntSupplier useDelay = () -> 0;
        private IntSupplier pendingTicks = () -> DEFAULT_PENDING_TICKS;
        private SearchListener<E> listener;
        private boolean pruning = true;
        private final List<OptionFilter<E>> filters = new ArrayList<>();
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

        /**
         * Optional: who must not be hurt &mdash; your friends, say &mdash; chosen by a
         * selector of yours, from the {@link #targets} service. They are never targets,
         * even when the target selector would pick them, and
         * {@code Thresholds.maxProtectedDamage} and {@code protectedMargin} limit what
         * an option may do to them. Give it range enough to cover everywhere your
         * explosions reach: your place range plus your {@code Falloff}'s range.
         */
        public B protect(TargetSelector<? extends E> selector) {
            this.protectedSelector = Validate.notNull(selector, "selector");
            return self();
        }

        /**
         * Optional: refuses any option {@code filter} does not accept, after every
         * threshold. Several are all required, asked in the order given.
         */
        public B filter(OptionFilter<E> filter) {
            this.filters.add(Validate.notNull(filter, "filter"));
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

        /**
         * What turning to look at a spot costs before you place there: options rank
         * by score less this. {@link AimCost#NONE} unless set.
         */
        public B placeAimCost(AimCost cost) {
            this.placeAim = Validate.notNull(cost, "cost");
            return self();
        }

        /**
         * Where targets, you and the protected will be when an explosion lands:
         * damage is scored there. {@link Lookahead#none()}, where they are now,
         * unless set; it only matters once a delay says how far ahead to look.
         */
        public B lookahead(Lookahead<E> lookahead) {
            this.lookahead = Validate.notNull(lookahead, "lookahead");
            return self();
        }

        /**
         * Ticks from deciding to place until that explosive goes off on the server,
         * read live: your ping, and for a crystal the wait to break it. 0, now,
         * unless set.
         */
        public B placeDelay(IntSupplier ticks) {
            this.placeDelay = Validate.notNull(ticks, "ticks");
            return self();
        }

        /**
         * How many ticks to wait for something you placed to show up before
         * forgetting it, read live: about your ping, and a little more.
         * {@link #DEFAULT_PENDING_TICKS} unless set.
         */
        public B pendingTicks(IntSupplier ticks) {
            this.pendingTicks = Validate.notNull(ticks, "ticks");
            return self();
        }

        /** Ticks from deciding to set one off until it goes off on the server, read live: your ping. 0 unless set. */
        public B useDelay(IntSupplier ticks) {
            this.useDelay = Validate.notNull(ticks, "ticks");
            return self();
        }

        /** Optional: sees every exact estimate the search makes, and what became of it. */
        public B listener(SearchListener<E> listener) {
            this.listener = Validate.notNull(listener, "listener");
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

        /** What turning to look at an explosive costs before you set it off, under whatever name the search gives it. */
        protected final B aimCostToUse(AimCost cost) {
            this.useAim = Validate.notNull(cost, "cost");
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

        /** What turning to look at an explosive costs before you set it off. {@link AimCost#NONE} unless set. */
        public Builder<E> useAimCost(AimCost cost) {
            return aimCostToUse(cost);
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
