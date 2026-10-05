package dev.px.combat.vector;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.state.TargetState;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

import java.util.Locale;
import java.util.Objects;

/**
 * One real explosion, kept as a test case: everything needed to predict it
 * again without the game, and what it actually did.
 *
 * <ul>
 *   <li>the explosive, and where it went off
 *   <li>the target: where it stood, its size and eye height, and its
 *       {@link TargetState} &mdash; the values your mitigation reads
 *   <li>the blocks between them, as a {@link BlockSnapshot}
 *   <li>what happened: the damage taken, or, when the target {@link #isPopped()
 *       popped}, a lower bound on it
 *   <li>what your model predicted at the time, for reference
 * </ul>
 *
 * <p>Immutable. Equal when every part is.
 */
public final class TestVector {

    private final Explosive explosive;
    private final Vec3 origin;
    private final Vec3 position;
    private final double width;
    private final double height;
    private final double eyeHeight;
    private final TargetState state;
    private final BlockSnapshot blocks;
    private final double observed;
    private final boolean popped;
    private final DamageEstimate recorded;

    private TestVector(Builder builder) {
        this.explosive = builder.explosive;
        this.origin = builder.origin;
        this.position = builder.position;
        this.width = builder.width;
        this.height = builder.height;
        this.eyeHeight = builder.eyeHeight;
        this.state = builder.state;
        this.blocks = builder.blocks;
        this.observed = builder.observed;
        this.popped = builder.popped;
        this.recorded = builder.recorded;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Explosive getExplosive() {
        return explosive;
    }

    public Vec3 getOrigin() {
        return origin;
    }

    /** @return where the target stood: the bottom centre of its box */
    public Vec3 getPosition() {
        return position;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public double getEyeHeight() {
        return eyeHeight;
    }

    /** @return the target's box, from its position and size */
    public Box getBox() {
        return Box.around(position, width, height);
    }

    public TargetState getState() {
        return state;
    }

    public BlockSnapshot getBlocks() {
        return blocks;
    }

    /** @return the damage taken; when {@link #isPopped()}, only a lower bound on it */
    public double getObserved() {
        return observed;
    }

    /** @return whether the target popped a totem or died, so {@link #getObserved()} is a lower bound */
    public boolean isPopped() {
        return popped;
    }

    /** @return what the client's model predicted when this was recorded; null if nothing was */
    public DamageEstimate getRecorded() {
        return recorded;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TestVector)) {
            return false;
        }
        TestVector that = (TestVector) other;
        return explosive.equals(that.explosive) && origin.equals(that.origin) && position.equals(that.position)
                && Double.compare(width, that.width) == 0 && Double.compare(height, that.height) == 0
                && Double.compare(eyeHeight, that.eyeHeight) == 0 && state.equals(that.state)
                && blocks.equals(that.blocks) && Double.compare(observed, that.observed) == 0
                && popped == that.popped && sameEstimate(recorded, that.recorded);
    }

    @Override
    public int hashCode() {
        return Objects.hash(explosive, origin, position, width, height, state, observed, popped);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "TestVector(%s at %s, target %s, observed %s%.2f, %s)",
                explosive.getName(), origin, position, popped ? ">=" : "", observed, blocks);
    }

    private static boolean sameEstimate(DamageEstimate a, DamageEstimate b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.isInRange() == b.isInRange() && Double.compare(a.getDistance(), b.getDistance()) == 0
                && Double.compare(a.getExposure(), b.getExposure()) == 0
                && Double.compare(a.getRaw(), b.getRaw()) == 0 && Double.compare(a.getDamage(), b.getDamage()) == 0;
    }

    public static final class Builder {

        private Explosive explosive;
        private Vec3 origin;
        private Vec3 position;
        private double width = Double.NaN;
        private double height = Double.NaN;
        private double eyeHeight;
        private TargetState state = TargetState.empty();
        private BlockSnapshot blocks = BlockSnapshot.empty();
        private double observed = Double.NaN;
        private boolean popped;
        private DamageEstimate recorded;

        private Builder() {
        }

        public Builder explosive(Explosive explosive) {
            this.explosive = Validate.notNull(explosive, "explosive");
            return this;
        }

        public Builder origin(Vec3 origin) {
            this.origin = Validate.notNull(origin, "origin");
            return this;
        }

        /** The target's position, size and eye height. */
        public Builder target(Vec3 position, double width, double height, double eyeHeight) {
            this.position = Validate.notNull(position, "position");
            this.width = width;
            this.height = height;
            this.eyeHeight = eyeHeight;
            return this;
        }

        public Builder state(TargetState state) {
            this.state = Validate.notNull(state, "state");
            return this;
        }

        public Builder blocks(BlockSnapshot blocks) {
            this.blocks = Validate.notNull(blocks, "blocks");
            return this;
        }

        /** @param popped whether {@code damage} is only a lower bound */
        public Builder observed(double damage, boolean popped) {
            this.observed = damage;
            this.popped = popped;
            return this;
        }

        public Builder recorded(DamageEstimate recorded) {
            this.recorded = recorded;
            return this;
        }

        /** @throws IllegalStateException if the explosive, origin, target or observed damage is missing */
        public TestVector build() {
            Validate.check(explosive != null && origin != null && position != null, "a vector needs its explosive, origin and target");
            Validate.check(!Double.isNaN(width) && !Double.isNaN(height), "a vector needs the target's size");
            if (Double.isNaN(observed)) {
                throw new IllegalStateException("a vector needs what was observed");
            }
            return new TestVector(this);
        }
    }
}
