package dev.px.core.math;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * An immutable inclusive numeric interval.
 *
 * <p>Replaces {@code BetweenInteger}, which mutated itself to keep min below max
 * and so could not be shared or compared safely. Construction normalises the
 * bounds once and the value never changes afterwards.
 */
@Getter
@EqualsAndHashCode
public final class Range {

    private final double lower;
    private final double upper;

    private Range(double lower, double upper) {
        this.lower = lower;
        this.upper = upper;
    }

    /** Bounds are swapped if given the wrong way round, so callers cannot build an inverted range. */
    public static Range of(double lower, double upper) {
        return lower <= upper ? new Range(lower, upper) : new Range(upper, lower);
    }

    public boolean contains(double value) {
        return value >= lower && value <= upper;
    }

    public double span() {
        return upper - lower;
    }

    public double midpoint() {
        return (lower + upper) / 2d;
    }

    public double clamp(double value) {
        return value < lower ? lower : value > upper ? upper : value;
    }

    /** @return a uniformly random value inside the range. The common use: randomised delays and CPS. */
    public double random() {
        return lower + Math.random() * span();
    }

    public int randomInt() {
        return (int) Math.round(random());
    }

    public Range withLower(double newLower) {
        return of(newLower, upper);
    }

    public Range withUpper(double newUpper) {
        return of(lower, newUpper);
    }

    public int lowerInt() {
        return (int) lower;
    }

    public int upperInt() {
        return (int) upper;
    }

    public float lowerFloat() {
        return (float) lower;
    }

    public float upperFloat() {
        return (float) upper;
    }

    @Override
    public String toString() {
        boolean integral = lower == Math.rint(lower) && upper == Math.rint(upper);
        return integral
                ? (long) lower + " - " + (long) upper
                : String.format("%.2f - %.2f", lower, upper);
    }
}
