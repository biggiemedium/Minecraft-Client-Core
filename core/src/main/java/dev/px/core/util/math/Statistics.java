package dev.px.core.util.math;

import java.util.Arrays;

/**
 * Summary statistics over a set of samples.
 *
 * <p>An average alone is a poor description of anything a client measures.
 * Two connections both averaging 40ms are not the same connection if one varies
 * by 2ms and the other by 60, and it is the variation that a player feels as
 * rubber-banding. The same goes for frame times, where the average is fine
 * precisely when the stutters are not.
 *
 * <p>{@link dev.px.core.util.collect.RollingAverage} keeps a moving window and
 * defers to these for the parts that need more than a running sum.
 *
 * <p><b>Complexity.</b> {@link #sum}, {@link #mean}, {@link #variance},
 * {@link #standardDeviation}, {@link #min} and {@link #max} are O(n) in one pass;
 * {@link #median} and {@link #percentile} sort a copy and are O(n log n) time,
 * O(n) space.
 *
 * <p>Every method tolerates an empty input and returns 0 rather than
 * {@link Double#NaN}: these numbers end up on screen, and a HUD reading "NaN ms"
 * before the first sample is worse than one reading zero.
 */
public final class Statistics {

    private Statistics() {
    }

    public static double sum(double... values) {
        double total = 0d;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    public static double mean(double... values) {
        return values.length == 0 ? 0d : sum(values) / values.length;
    }

    /**
     * @return the mean squared deviation from the mean
     *
     * <pre>
     * σ² = (1/n)·Σ(xᵢ - μ)²
     * </pre>
     *
     * <p>The population variance, not the sample estimate: these are complete
     * windows of what actually happened, not a sample drawn from something larger.
     */
    public static double variance(double... values) {
        if (values.length == 0) {
            return 0d;
        }
        double mean = mean(values);
        double total = 0d;
        for (double value : values) {
            double deviation = value - mean;
            total += deviation * deviation;
        }
        return total / values.length;
    }

    /**
     * @return how far a typical sample sits from the mean
     *
     * <pre>
     * σ = √(σ²)
     * </pre>
     *
     * <p>In the same unit as the samples, which is why this is the one to show:
     * "40ms ± 12" means something to a reader in a way variance does not.
     */
    public static double standardDeviation(double... values) {
        return Math.sqrt(variance(values));
    }

    /**
     * @return the middle sample
     *
     * <p>Worth preferring to the mean whenever one bad sample can dominate. A
     * single 400ms frame while a chunk loads moves the mean of a 60-frame window
     * by six milliseconds and the median by nothing.
     */
    public static double median(double... values) {
        if (values.length == 0) {
            return 0d;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        // An even count has two middle samples and the median is between them.
        // Taking either one instead reports a value that is off by half the gap,
        // which on a two-mode distribution is the entire distance between them.
        return sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2d;
    }

    /**
     * @param fraction which point of the sorted samples to read, 0..1
     * @return the sample at that point, by nearest rank
     *
     * <pre>
     * index = round(clamp(fraction, 0, 1) · (n - 1))   over the sorted samples
     * </pre>
     *
     * <p>{@code percentile(0.99, frameTimes)} is the honest way to describe
     * smoothness: the frame time all but the worst one percent come in under.
     */
    public static double percentile(double fraction, double... values) {
        if (values.length == 0) {
            return 0d;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double clamped = fraction < 0d ? 0d : fraction > 1d ? 1d : fraction;
        int index = (int) Math.round(clamped * (sorted.length - 1));
        return sorted[index];
    }

    public static double min(double... values) {
        double lowest = Double.MAX_VALUE;
        for (double value : values) {
            lowest = Math.min(lowest, value);
        }
        return values.length == 0 ? 0d : lowest;
    }

    public static double max(double... values) {
        double highest = -Double.MAX_VALUE;
        for (double value : values) {
            highest = Math.max(highest, value);
        }
        return values.length == 0 ? 0d : highest;
    }
}
