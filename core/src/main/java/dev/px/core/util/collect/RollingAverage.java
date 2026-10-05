package dev.px.core.util.collect;

import dev.px.core.util.Validate;
import dev.px.core.util.math.Statistics;

import java.util.Arrays;

/**
 * A fixed-window average over the last N numbers.
 *
 * <p>Every number a client puts on screen is noisy: FPS, ping, TPS, CPS, packet
 * rate. Shown raw they flicker so fast the digits are unreadable, and the usual
 * fix &mdash; only updating the label twice a second &mdash; just samples the
 * noise less often. Averaging a window smooths the value instead of hiding it.
 *
 * <p>A {@code CircularQueue<Double>} would do the same job, but every sample
 * would box into a {@link Double} on the heap; at sixty frames a second that is
 * real garbage for a HUD label. This keeps a {@code double[]} and a running sum,
 * so {@link #push} allocates nothing and {@link #average()} is one division
 * rather than a walk of the window.
 *
 * <p><b>Complexity.</b> {@link #push} and {@link #average()} are O(1), the
 * running sum being what buys the second one:
 *
 * <pre>
 * sum   += x_new - x_evicted
 * mean   = sum / count
 * </pre>
 *
 * <p>{@link #min()}, {@link #max()}, {@link #toArray()} and
 * {@link #standardDeviation()} walk the window and are O(n).
 *
 * <p>Not thread-safe.
 */
public final class RollingAverage {

    private final double[] samples;

    private double sum;
    private int cursor;
    private int count;

    private RollingAverage(int window) {
        Validate.check(window > 0, "window must be positive");
        this.samples = new double[window];
    }

    /** @param window how many samples to average over. 20 is a reasonable HUD default */
    public static RollingAverage of(int window) {
        return new RollingAverage(window);
    }

    /**
     * Records a sample, dropping the oldest once the window is full.
     *
     * @return the value passed in, so a call can be inlined into an expression
     */
    public double push(double value) {
        if (count == samples.length) {
            sum -= samples[cursor];
        } else {
            count++;
        }
        samples[cursor] = value;
        sum += value;
        cursor = (cursor + 1) % samples.length;
        return value;
    }

    /** @return the mean of the window, or 0 before the first sample. */
    public double average() {
        return count == 0 ? 0d : sum / count;
    }

    /** @return the mean rounded to an int, the form most HUD labels want. */
    public int averageInt() {
        return (int) Math.round(average());
    }

    /** @return the most recent sample, or 0 before the first. */
    public double last() {
        return count == 0 ? 0d : samples[(cursor - 1 + samples.length) % samples.length];
    }

    public double min() {
        double lowest = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            lowest = Math.min(lowest, samples[i]);
        }
        return count == 0 ? 0d : lowest;
    }

    public double max() {
        double highest = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            highest = Math.max(highest, samples[i]);
        }
        return count == 0 ? 0d : highest;
    }

    public double sum() {
        return sum;
    }

    /**
     * @return how far a typical sample sits from the average, in the same unit
     *
     * <p>The number that says whether an average is worth trusting. A ping
     * averaging 40ms with a deviation of 3 is a stable connection; the same
     * average with a deviation of 40 is what a player experiences as rubber
     * banding, and the average alone cannot tell them apart.
     */
    public double standardDeviation() {
        return Statistics.standardDeviation(toArray());
    }

    /**
     * @return the samples in the window, oldest first
     *
     * <p>A copy, for handing to {@link Statistics}. Everything here that can be
     * answered from a running sum already is; this is for the questions &mdash;
     * median, percentiles &mdash; that need to see every sample.
     */
    public double[] toArray() {
        double[] copy = new double[count];
        for (int i = 0; i < count; i++) {
            copy[i] = samples[(cursor - count + i + samples.length) % samples.length];
        }
        return copy;
    }

    /** @return how many samples are in the window, up to {@link #window()}. */
    public int count() {
        return count;
    }

    public int window() {
        return samples.length;
    }

    /**
     * @return whether the window has filled.
     *
     * <p>Worth checking before trusting the average for anything but display:
     * one sample in a twenty-wide window still reports an average.
     */
    public boolean isFull() {
        return count == samples.length;
    }

    public void clear() {
        Arrays.fill(samples, 0d);
        sum = 0d;
        cursor = 0;
        count = 0;
    }

    @Override
    public String toString() {
        return String.format("%.2f (avg of %d)", average(), count);
    }
}
