package dev.px.core.movement.prediction;

import java.util.Locale;

/**
 * How an entity has actually moved, learned while it has been tracked: how fast,
 * how high, how far it drops, and how much of it the rules explain.
 *
 * <p>The rules say how someone <em>should</em> move; this says how they
 * <em>do</em>. Where the two differ &mdash; a speed hack, a timer, a hole snap, or
 * something your {@link EntityPhysics} did not know about &mdash; predictions lean
 * on this instead:
 *
 * <ul>
 *   <li><b>Likely.</b> When their ground movement keeps running faster than the
 *       rules allow, {@link #getSpeedFactor()} scales the rules up for them, so
 *       "keeps going" and "heads for the hole" get there as fast as they do.
 *   <li><b>Possible.</b> The fastest they have been seen to move, rise and drop
 *       bounds how soon they could be anywhere: {@code Prediction.earliestPossible}.
 * </ul>
 *
 * <p>Nothing here assumes a particular cheat. It is what the server let them do,
 * measured. A tick faster than {@code PredictionService}'s teleport speed is a
 * teleport, a pearl or a setback, and is not learned from.
 *
 * <p>Immutable: a snapshot. Learned per {@code Tracked}, so it starts again when
 * the entity is tracked again.
 */
public final class Behaviour {

    /** Nothing seen yet. */
    static final Behaviour NONE = new Behaviour(0, 0d, 0d, 0d, 1d, 0, Double.NaN, 0);

    private final int samples;
    private final double topSpeed;
    private final double topRise;
    private final double topDrop;
    private final double speedFactor;
    private final int speedSamples;
    private final double explainedShare;
    private final int typicalGap;

    Behaviour(int samples, double topSpeed, double topRise, double topDrop, double speedFactor, int speedSamples,
              double explainedShare, int typicalGap) {
        this.samples = samples;
        this.topSpeed = topSpeed;
        this.topRise = topRise;
        this.topDrop = topDrop;
        this.speedFactor = speedFactor;
        this.speedSamples = speedSamples;
        this.explainedShare = explainedShare;
        this.typicalGap = typicalGap;
    }

    /** @return moves learned from, in the learning window: each the span between two positions that were news */
    public int getSamples() {
        return samples;
    }

    /** @return the fastest it has moved horizontally, in blocks per tick */
    public double getTopSpeed() {
        return topSpeed;
    }

    /** @return the fastest it has risen, in blocks per tick */
    public double getTopRise() {
        return topRise;
    }

    /** @return the fastest it has dropped, in blocks per tick */
    public double getTopDrop() {
        return topDrop;
    }

    /**
     * @return how much faster than its rules it accelerates on the ground: the
     *         median, over the moves where it was walking, of what it did against
     *         what the best-fitting keys would have done. 1 when the rules explain
     *         it, or too little has been seen to say
     */
    public double getSpeedFactor() {
        return speedFactor;
    }

    /** @return ground moves the speed factor was measured from */
    public int getSpeedSamples() {
        return speedSamples;
    }

    /**
     * @return the share of its moves, 0 to 1, that the rules explain to within
     *         the service's explained error; NaN while it moves in a way the rules
     *         do not model, or nothing has been fitted
     */
    public double getExplainedShare() {
        return explainedShare;
    }

    /** @return the most common number of ticks between its positions: what your server's update gap looks like; 0 before any */
    public int getTypicalGap() {
        return typicalGap;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "Behaviour(%d moves, top %.3f b/t, rise %.3f, drop %.3f, speed x%.3f over %d, explained %.2f, gap %d)",
                samples, topSpeed, topRise, topDrop, speedFactor, speedSamples, explainedShare, typicalGap);
    }
}
