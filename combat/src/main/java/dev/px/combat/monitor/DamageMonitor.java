package dev.px.combat.monitor;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.world.BlockView;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.collect.CircularQueue;
import dev.px.core.util.math.Statistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Checks an {@link ExplosionModel} against what explosions actually do in game,
 * so a version that changes the rules shows up as a warning, not as a crystal
 * aura that quietly gets worse.
 *
 * <pre>{@code
 * DamageMonitor<EntityLivingBase> monitor = DamageMonitor.<EntityLivingBase>builder()
 *         .model(myExplosionModel)
 *         .blocks(myBlocks)
 *         .vitals(myVitals)                                  // health + absorption, off the game's object
 *         .targets(Core.entities(), Core.entities().get(LivingTracker.class))
 *         .bus(Core.bus())                                   // ticks itself, posts DamageDriftEvent
 *         .build();
 *
 * // in your adapter, on the game thread:
 * monitor.exploded(position, crystalExplosive);              // the explosion packet, as it is handled
 * monitor.popped(entity);                                    // a totem pop, if your version reports them
 *
 * if (!monitor.isReliable()) {
 *     DamageReport report = monitor.report();                // what is off, and which rule it points at
 * }
 * }</pre>
 *
 * <h2>How it measures</h2>
 *
 * <ol>
 *   <li>When an explosion is reported, every watched target in range gets the
 *       model's prediction, and its {@link Vitals#pool} from just before.
 *       "Before" is the larger of now and the end of the last tick, so a health
 *       update that arrived ahead of the explosion is still caught.
 *   <li>For a few ticks after, the pool is watched; the most it fell is what
 *       the explosion did.
 *   <li>Several explosions in one tick count as the strongest of them, which is
 *       what the wiki gives current Java as applying. A target that pops a totem
 *       or dies only shows that the damage was at least everything it had.
 *   <li>Samples that measure something else are thrown away: a target still
 *       recovering from a hit, one whose health is not {@linkplain
 *       Vitals#isTrusted trusted}, one that leaves before it settles.
 * </ol>
 *
 * <h2>How it judges</h2>
 *
 * <p>By medians over the last {@link Builder#window samples}, so a sword hit
 * landing in the same moment cannot sway it; and separately for the samples
 * that test each rule (see {@link DamageSample.Bucket}), so the
 * {@link DamageReport} can say <em>which</em> rule is wrong. Predictions are
 * trusted until there is evidence otherwise.
 *
 * <p>Every number here &mdash; how long to wait, how many samples, how far off is
 * too far &mdash; is a tuning knob with a default, not a fact about any game.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class DamageMonitor<E> {

    public static final int DEFAULT_SETTLE_TICKS = 3;
    public static final int DEFAULT_WINDOW = 64;
    public static final int DEFAULT_MIN_SAMPLES = 8;
    public static final double DEFAULT_ABSOLUTE_TOLERANCE = 0.75d;
    public static final double DEFAULT_RELATIVE_TOLERANCE = 0.10d;

    /** Pops the model did not expect before they alone mark it unreliable. */
    private static final int MIN_UNEXPECTED_POPS = 3;

    private final ExplosionModel<E> model;
    private final BlockView blocks;
    private final Vitals<? super E> vitals;
    private final EntityService entities;
    private final List<EntityTracker<? extends E>> targets;
    private final int settleTicks;
    private final int minSamples;
    private final double absoluteTolerance;
    private final double relativeTolerance;
    private final EventBus bus;
    private final SampleRecorder<? super E> recorder;
    private final Listener listener = new Listener();

    private final CircularQueue<DamageSample> samples;
    private final List<Pending> pending = new ArrayList<>();
    private Map<Tracked<?>, Double> lastPools = new IdentityHashMap<>();
    private long tick;
    private int discarded;
    private DamageReport report;
    private boolean reliable = true;

    private DamageMonitor(Builder<E> builder) {
        this.model = builder.model;
        this.blocks = builder.blocks;
        this.vitals = builder.vitals;
        this.entities = builder.entities;
        this.targets = Collections.unmodifiableList(new ArrayList<>(builder.targets));
        this.settleTicks = builder.settleTicks;
        this.minSamples = builder.minSamples;
        this.absoluteTolerance = builder.absoluteTolerance;
        this.relativeTolerance = builder.relativeTolerance;
        this.bus = builder.bus;
        this.recorder = builder.recorder;
        this.samples = CircularQueue.of(builder.window);
        this.report = evaluate();
        if (bus != null) {
            bus.subscribe(listener);
        }
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    // ------------------------------------------------------------ reporting in

    /**
     * An explosion went off. Call it as the game learns of it &mdash; the
     * explosion packet, say &mdash; on the game thread, before the health updates
     * that follow are handled if you can.
     */
    public void exploded(Vec3 origin, Explosive explosive) {
        exploded(origin, explosive, blocks);
    }

    /**
     * An explosion went off, in a world that differs from your blocks: one where
     * the explosive was itself a block, and is gone. The game may tell you of a
     * bed's explosion before it tells you the bed was removed, so pass
     * {@code BedRules.blocksWhenFired(bed)}, or your blocks {@code without} its cells.
     */
    public void exploded(Vec3 origin, Explosive explosive, BlockView asFired) {
        Validate.notNull(origin, "origin");
        Validate.notNull(explosive, "explosive");
        Validate.notNull(asFired, "asFired");
        double range = model.getFalloff().range(explosive.getPower());
        Tracked<? extends E> self = self();
        if (self != null) {
            consider(self, true, origin, explosive, asFired);
        }
        for (EntityTracker<? extends E> tracker : targets) {
            tracker.forEachWithin(origin, range, target -> consider(target, false, origin, explosive, asFired));
        }
    }

    /**
     * {@code entity} popped a totem, or died, from an explosion just reported: its
     * damage is then only known to be at least everything it had.
     *
     * @param entity the game's own object
     */
    public void popped(Object entity) {
        for (Pending sample : pending) {
            if (sample.target.get() == entity) {
                sample.popped = true;
            }
        }
    }

    /** Advances one game tick: settles what has waited long enough. {@link Builder#bus} does this for you. */
    public void tick() {
        List<Pending> done = new ArrayList<>();
        Iterator<Pending> waiting = pending.iterator();
        while (waiting.hasNext()) {
            Pending sample = waiting.next();
            sample.ticksWaited++;
            if (!sample.target.isTracked()) {
                sample.gone = true;
            } else {
                double now = vitals.pool(sample.target.get());
                if (!Double.isNaN(now)) {
                    sample.lowest = Math.min(sample.lowest, now);
                }
            }
            if (sample.ticksWaited >= settleTicks || sample.gone) {
                waiting.remove();
                done.add(sample);
            }
        }
        // Settled after the walk: a drift handler may report another explosion.
        for (Pending sample : done) {
            settle(sample);
        }
        snapshot();
        tick++;
    }

    // ---------------------------------------------------------------- reading

    /** @return whether predictions can be relied on: true until there is evidence they cannot */
    public boolean isReliable() {
        return report.isReliable();
    }

    /** @return what the evidence says, now */
    public DamageReport report() {
        return report;
    }

    /** @return the most recent samples, oldest first */
    public List<DamageSample> getSamples() {
        return samples.toList();
    }

    /** @return samples thrown away because they measured something other than the explosion */
    public int getDiscarded() {
        return discarded;
    }

    /** Forgets every sample and everything in flight: after fixing the model, say. */
    public void clear() {
        samples.clear();
        pending.clear();
        lastPools = new IdentityHashMap<>();
        discarded = 0;
        report = evaluate();
        reliable = report.isReliable();
    }

    /** Stops listening to the bus given at build time. */
    public void close() {
        if (bus != null) {
            bus.unsubscribe(listener);
        }
    }

    // ------------------------------------------------------------ internals

    @SuppressWarnings("unchecked")
    private Tracked<? extends E> self() {
        return entities != null ? (Tracked<? extends E>) entities.getSelf() : null;
    }

    private void consider(Tracked<? extends E> target, boolean isSelf, Vec3 origin, Explosive explosive,
                          BlockView asFired) {
        E entity = target.get();
        if (!isSelf && !vitals.isTrusted(entity)) {
            return;
        }
        double now = vitals.pool(entity);
        if (Double.isNaN(now)) {
            return;
        }
        DamageEstimate predicted = model.estimate(origin, explosive, target, asFired);
        if (!predicted.isInRange()) {
            return;
        }
        for (Pending sample : pending) {
            if (sample.target == target && sample.tick == tick) {
                // Only the strongest explosion of a tick lands.
                if (predicted.getDamage() > sample.predicted.getDamage()) {
                    sample.predicted = predicted;
                    sample.explosive = explosive;
                    sample.origin = origin;
                    if (recorder != null) {
                        recorder.discard(sample.token);
                        sample.token = recorder.begin(origin, explosive, target, predicted, asFired);
                    }
                }
                return;
            }
        }
        Double last = lastPools.get(target);
        double before = last != null ? Math.max(last, now) : now;
        Pending sample = new Pending(target, isSelf, origin, explosive, predicted, before, now,
                vitals.isRecentlyHurt(entity), tick);
        if (recorder != null) {
            sample.token = recorder.begin(origin, explosive, target, predicted, asFired);
        }
        pending.add(sample);
    }

    private void settle(Pending sample) {
        if (sample.recentlyHurt || (sample.gone && !sample.popped)) {
            discarded++;
            if (recorder != null) {
                recorder.discard(sample.token);
            }
            return;
        }
        double observed = sample.popped ? sample.before : Math.max(0d, sample.before - sample.lowest);
        if (recorder != null) {
            recorder.finish(sample.token, sample.predicted, sample.before, observed, sample.popped);
        }
        samples.add(new DamageSample(sample.explosive, sample.origin, sample.position, sample.self,
                sample.predicted, sample.before, observed, sample.popped, sample.tick));
        report = evaluate();
        if (report.isReliable() != reliable) {
            reliable = report.isReliable();
            if (bus != null) {
                bus.post(new DamageDriftEvent(report));
            }
        }
    }

    private void snapshot() {
        Map<Tracked<?>, Double> pools = new IdentityHashMap<>();
        Tracked<? extends E> self = self();
        if (self != null) {
            record(pools, self);
        }
        for (EntityTracker<? extends E> tracker : targets) {
            for (Tracked<? extends E> target : tracker.getAll()) {
                record(pools, target);
            }
        }
        lastPools = pools;
    }

    private void record(Map<Tracked<?>, Double> pools, Tracked<? extends E> target) {
        double pool = vitals.pool(target.get());
        if (!Double.isNaN(pool)) {
            pools.put(target, pool);
        }
    }

    private DamageReport evaluate() {
        List<DamageSample> exact = new ArrayList<>();
        Map<DamageSample.Bucket, List<DamageSample>> byBucket = new EnumMap<>(DamageSample.Bucket.class);
        for (DamageSample.Bucket bucket : DamageSample.Bucket.values()) {
            byBucket.put(bucket, new ArrayList<>());
        }
        int pops = 0;
        int unexpectedPops = 0;
        for (DamageSample sample : samples) {
            if (sample.isPopped()) {
                pops++;
                if (sample.getPredicted().getDamage() < sample.getBefore()) {
                    unexpectedPops++;
                }
            } else {
                exact.add(sample);
                byBucket.get(sample.getBucket()).add(sample);
            }
        }
        DamageReport.Stats overall = stats(exact);
        Map<DamageSample.Bucket, DamageReport.Stats> buckets = new EnumMap<>(DamageSample.Bucket.class);
        for (Map.Entry<DamageSample.Bucket, List<DamageSample>> entry : byBucket.entrySet()) {
            buckets.put(entry.getKey(), stats(entry.getValue()));
        }
        boolean popsOff = unexpectedPops >= MIN_UNEXPECTED_POPS && unexpectedPops * 2 > pops;
        DamageReport.Suspect suspect;
        if (buckets.get(DamageSample.Bucket.OPEN).isOff()) {
            suspect = DamageReport.Suspect.FALLOFF;
        } else if (buckets.get(DamageSample.Bucket.ARMOURED).isOff()) {
            suspect = DamageReport.Suspect.MITIGATION;
        } else if (buckets.get(DamageSample.Bucket.COVERED).isOff()) {
            suspect = DamageReport.Suspect.EXPOSURE;
        } else if (overall.isOff() || popsOff) {
            suspect = DamageReport.Suspect.UNKNOWN;
        } else {
            suspect = DamageReport.Suspect.NONE;
        }
        boolean evidence = overall.getCount() >= minSamples || pops >= MIN_UNEXPECTED_POPS;
        return new DamageReport(overall, buckets, pops, unexpectedPops, popsOff, suspect, evidence);
    }

    private DamageReport.Stats stats(List<DamageSample> group) {
        if (group.isEmpty()) {
            return new DamageReport.Stats(0, Double.NaN, Double.NaN, false);
        }
        double[] errors = new double[group.size()];
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < group.size(); i++) {
            DamageSample sample = group.get(i);
            errors[i] = sample.getError();
            if (sample.getPredicted().getDamage() > 0d) {
                ratios.add(sample.getObserved() / sample.getPredicted().getDamage());
            }
        }
        double medianError = Statistics.median(errors);
        double medianRatio = ratios.isEmpty() ? Double.NaN : Statistics.median(unbox(ratios));
        boolean off = group.size() >= minSamples
                && Math.abs(medianError) > absoluteTolerance
                && (Double.isNaN(medianRatio) || Math.abs(medianRatio - 1d) > relativeTolerance);
        return new DamageReport.Stats(group.size(), medianError, medianRatio, off);
    }

    private static double[] unbox(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** One target, one tick of explosions, waiting to see what happened. */
    private final class Pending {
        final Tracked<? extends E> target;
        final boolean self;
        final Vec3 position;
        final double before;
        final boolean recentlyHurt;
        final long tick;
        Vec3 origin;
        Explosive explosive;
        DamageEstimate predicted;
        double lowest;
        int ticksWaited;
        boolean popped;
        boolean gone;
        Object token;

        Pending(Tracked<? extends E> target, boolean self, Vec3 origin, Explosive explosive,
                DamageEstimate predicted, double before, double now, boolean recentlyHurt, long tick) {
            this.target = target;
            this.self = self;
            this.position = target.getPosition();
            this.origin = origin;
            this.explosive = explosive;
            this.predicted = predicted;
            this.before = before;
            this.lowest = now;
            this.recentlyHurt = recentlyHurt;
            this.tick = tick;
        }
    }

    /** Ticks from Core's bus, and forgets what is in flight on leaving a world. */
    private final class Listener {

        @Subscribe(stage = Stage.POST)
        private void onTick(TickEvent event) {
            tick();
        }

        @Subscribe
        private void onWorld(WorldEvent event) {
            if (event.isUnloaded()) {
                pending.clear();
                lastPools = new IdentityHashMap<>();
            }
        }
    }

    public static final class Builder<E> {

        private ExplosionModel<E> model;
        private BlockView blocks;
        private Vitals<? super E> vitals;
        private EntityService entities;
        private final List<EntityTracker<? extends E>> targets = new ArrayList<>();
        private int settleTicks = DEFAULT_SETTLE_TICKS;
        private int window = DEFAULT_WINDOW;
        private int minSamples = DEFAULT_MIN_SAMPLES;
        private double absoluteTolerance = DEFAULT_ABSOLUTE_TOLERANCE;
        private double relativeTolerance = DEFAULT_RELATIVE_TOLERANCE;
        private EventBus bus;
        private SampleRecorder<? super E> recorder;

        private Builder() {
        }

        /** Required: the model being checked. */
        public Builder<E> model(ExplosionModel<E> model) {
            this.model = Validate.notNull(model, "model");
            return this;
        }

        /** Required: the blocks predictions are made against. */
        public Builder<E> blocks(BlockView blocks) {
            this.blocks = Validate.notNull(blocks, "blocks");
            return this;
        }

        /** Required: how much damage a target can still take. */
        public Builder<E> vitals(Vitals<? super E> vitals) {
            this.vitals = Validate.notNull(vitals, "vitals");
            return this;
        }

        /**
         * Required, at least the first: who to watch. The local player, from
         * {@code entities}, is the best evidence there is, since its health is
         * always real; {@code trackers} add everyone else in range.
         *
         * @param entities null to leave the local player out
         */
        @SafeVarargs
        public final Builder<E> targets(EntityService entities, EntityTracker<? extends E>... trackers) {
            this.entities = entities;
            this.targets.addAll(Arrays.asList(trackers.clone()));
            return this;
        }

        /** @param ticks how long after an explosion to keep watching health; {@value #DEFAULT_SETTLE_TICKS} unless set */
        public Builder<E> settleTicks(int ticks) {
            Validate.check(ticks > 0, "settleTicks must be positive");
            this.settleTicks = ticks;
            return this;
        }

        /** @param samples how many recent samples to judge by; {@value #DEFAULT_WINDOW} unless set */
        public Builder<E> window(int samples) {
            Validate.check(samples > 0, "window must be positive");
            this.window = samples;
            return this;
        }

        /** @param samples how many a group needs before it can be called off; {@value #DEFAULT_MIN_SAMPLES} unless set */
        public Builder<E> minSamples(int samples) {
            Validate.check(samples > 0, "minSamples must be positive");
            this.minSamples = samples;
            return this;
        }

        /**
         * A group is off only when its median error is beyond both tolerances, so a
         * small model is not failed for being one point out, nor a big one for 5%.
         *
         * @param absolute in damage; {@value #DEFAULT_ABSOLUTE_TOLERANCE} unless set
         * @param relative as a share of the prediction; {@value #DEFAULT_RELATIVE_TOLERANCE} unless set
         */
        public Builder<E> tolerance(double absolute, double relative) {
            Validate.check(absolute >= 0d && relative >= 0d, "tolerances must not be negative");
            this.absoluteTolerance = absolute;
            this.relativeTolerance = relative;
            return this;
        }

        /** Optional: ticks itself on this bus's {@code TickEvent} and posts {@link DamageDriftEvent} on it. */
        public Builder<E> bus(EventBus bus) {
            this.bus = Validate.notNull(bus, "bus");
            return this;
        }

        /** Optional: also tells {@code recorder} about every sample, such as a vector recorder saving test cases. */
        public Builder<E> recorder(SampleRecorder<? super E> recorder) {
            this.recorder = Validate.notNull(recorder, "recorder");
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public DamageMonitor<E> build() {
            StringBuilder missing = new StringBuilder();
            if (model == null) {
                missing.append(" model");
            }
            if (blocks == null) {
                missing.append(" blocks");
            }
            if (vitals == null) {
                missing.append(" vitals");
            }
            if (entities == null && targets.isEmpty()) {
                missing.append(" targets");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("a DamageMonitor needs:" + missing);
            }
            return new DamageMonitor<>(this);
        }
    }
}
