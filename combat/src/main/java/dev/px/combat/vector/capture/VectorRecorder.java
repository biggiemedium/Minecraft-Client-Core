package dev.px.combat.vector.capture;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.state.StateCapture;
import dev.px.combat.explosion.state.TargetState;
import dev.px.combat.monitor.SampleRecorder;
import dev.px.combat.vector.BlockSnapshot;
import dev.px.combat.vector.TestVector;
import dev.px.combat.vector.VectorSet;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.collect.CircularQueue;
import dev.px.core.world.BlockView;

import java.util.List;
import java.util.Map;

/**
 * Saves real explosions as {@link TestVector}s while you play, by listening to a
 * {@code DamageMonitor}: every sample it keeps becomes a vector.
 *
 * <pre>{@code
 * VectorRecorder<LivingEntity> recorder = VectorRecorder.<LivingEntity>builder()
 *         .state(myCapture)        // the values your mitigation reads, off the game's object
 *         .blocks(myBlocks)
 *         .build();
 *
 * DamageMonitor<LivingEntity> monitor = DamageMonitor.<LivingEntity>builder()
 *         ...
 *         .recorder(recorder)
 *         .build();
 *
 * // later: a command, a key, leaving the server
 * VectorJson.write(recorder.toSet("1.21.1 hard"), writer);
 * }</pre>
 *
 * <p>The target's state and the blocks around it are captured the moment the
 * explosion is reported, as the explosion found them. The blocks kept are those
 * in the box holding both the target and the explosion, which holds every ray
 * between them, plus {@link Builder#margin a margin}.
 *
 * <p>Game thread only, like the monitor.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class VectorRecorder<E> implements SampleRecorder<E> {

    public static final int DEFAULT_CAPACITY = 1000;
    public static final double DEFAULT_MARGIN = 1d;

    private final StateCapture<? super E> capture;
    private final BlockView blocks;
    private final double margin;
    private final CircularQueue<TestVector> vectors;

    private VectorRecorder(Builder<E> builder) {
        this.capture = builder.capture;
        this.blocks = builder.blocks;
        this.margin = builder.margin;
        this.vectors = CircularQueue.of(builder.capacity);
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    @Override
    public Object begin(Vec3 origin, Explosive explosive, Tracked<? extends E> target, DamageEstimate predicted) {
        return begin(origin, explosive, target, predicted, blocks);
    }

    /** Snapshots {@code asFired} rather than the recorder's own blocks: the world the explosion actually saw. */
    @Override
    public Object begin(Vec3 origin, Explosive explosive, Tracked<? extends E> target, DamageEstimate predicted,
                        BlockView asFired) {
        Box box = target.getBox();
        Box region = Box.of(
                Math.min(box.getMin().getX(), origin.getX()), Math.min(box.getMin().getY(), origin.getY()),
                Math.min(box.getMin().getZ(), origin.getZ()), Math.max(box.getMax().getX(), origin.getX()),
                Math.max(box.getMax().getY(), origin.getY()), Math.max(box.getMax().getZ(), origin.getZ()))
                .expand(margin);
        return TestVector.builder()
                .explosive(explosive)
                .origin(origin)
                .target(target.getPosition(), target.getWidth(), target.getHeight(), target.getEyeHeight())
                .state(capture != null ? capture.capture(target.get()) : TargetState.empty())
                .blocks(BlockSnapshot.capture(asFired, region));
    }

    @Override
    public void finish(Object token, DamageEstimate predicted, double before, double observed, boolean popped) {
        vectors.add(((TestVector.Builder) token).observed(observed, popped).recorded(predicted).build());
    }

    /** @return every vector kept, oldest first */
    public List<TestVector> getVectors() {
        return vectors.toList();
    }

    public int size() {
        return vectors.size();
    }

    public void clear() {
        vectors.clear();
    }

    /** @return the vectors kept, as a set to save */
    public VectorSet toSet(String label) {
        return VectorSet.of(label, getVectors());
    }

    public VectorSet toSet(String label, Map<String, String> metadata) {
        return VectorSet.of(label, metadata, getVectors());
    }

    public static final class Builder<E> {

        private StateCapture<? super E> capture;
        private BlockView blocks;
        private double margin = DEFAULT_MARGIN;
        private int capacity = DEFAULT_CAPACITY;

        private Builder() {
        }

        /**
         * The values your mitigation reads, captured off each target. Optional, but
         * without it a recording can only check exposure and falloff: armour cannot
         * be replayed from nothing.
         */
        public Builder<E> state(StateCapture<? super E> capture) {
            this.capture = Validate.notNull(capture, "capture");
            return this;
        }

        /** Required: the blocks to snapshot around each explosion. */
        public Builder<E> blocks(BlockView blocks) {
            this.blocks = Validate.notNull(blocks, "blocks");
            return this;
        }

        /** @param blocks how far past the explosion and target to keep blocks; {@value #DEFAULT_MARGIN} unless set */
        public Builder<E> margin(double blocks) {
            Validate.check(blocks >= 0d, "margin must not be negative");
            this.margin = blocks;
            return this;
        }

        /** @param vectors how many to keep, newest winning; {@value #DEFAULT_CAPACITY} unless set */
        public Builder<E> capacity(int vectors) {
            Validate.check(vectors > 0, "capacity must be positive");
            this.capacity = vectors;
            return this;
        }

        /** @throws IllegalStateException without blocks */
        public VectorRecorder<E> build() {
            if (blocks == null) {
                throw new IllegalStateException("a VectorRecorder needs the blocks to snapshot");
            }
            return new VectorRecorder<>(this);
        }
    }
}
