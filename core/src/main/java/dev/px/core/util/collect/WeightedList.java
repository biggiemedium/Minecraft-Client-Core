package dev.px.core.util.collect;

import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A list whose entries are drawn at random in proportion to a weight.
 *
 * <p>Uniform randomness is easy and rarely what is wanted. The times a client
 * randomises something &mdash; a click delay, a rotation jitter, which of several
 * behaviours to run &mdash; it is trying to look like a distribution that is not
 * flat, because nothing a person does is flat. Weights are how that gets said out
 * loud instead of encoded in a chain of {@code if (random < 0.6)}.
 *
 * <pre>{@code
 * private final WeightedList<Integer> delays = WeightedList.<Integer>of()
 *         .add(80, 1d)      // occasionally fast
 *         .add(110, 6d)     // usually around here
 *         .add(160, 2d);    // sometimes a pause
 *
 * clickTimer.tryConsume(delays.pick());
 * }</pre>
 *
 * <p>Selection is a binary search over cumulative weights, so a list of any size
 * costs a logarithmic number of comparisons rather than a walk. The cumulative
 * table is rebuilt lazily on the first pick after a change, which keeps building
 * a list cheap and picking from a stable one cheaper.
 *
 * <p><b>Complexity.</b> {@link #add} is O(1). {@link #pick()} and {@link #at} are
 * O(log n) by binary search over the cumulative table, plus the O(n) rebuild of
 * that table on the first pick after a change. {@link #chanceOf} is O(n).
 *
 * <pre>
 * C[i]   = w[0] + w[1] + ... + w[i]
 * pick   = the first i where roll &lt; C[i],  roll uniform on [0, C[n-1])
 * P(i)   = w[i] / C[n-1]
 * </pre>
 *
 * <p>Not thread-safe; {@link #pick()} draws from {@link ThreadLocalRandom}, so the
 * randomness is, but the list itself must not be mutated while picked from.
 *
 * @param <T> the entry type
 */
public final class WeightedList<T> {

    private final List<T> entries = new ArrayList<>();
    private final List<Double> weights = new ArrayList<>();

    private double[] cumulative;
    private double total;

    private WeightedList() {
    }

    public static <T> WeightedList<T> of() {
        return new WeightedList<>();
    }

    /**
     * Adds an entry.
     *
     * @param weight relative likelihood; it is a share of the total, not a
     *               probability, so weights need not add to anything in particular
     * @throws IllegalArgumentException if the weight is not positive. A zero
     *         weight is almost always a bug rather than a request never to pick
     *         something, and silently accepting it hides the mistake
     */
    public WeightedList<T> add(T entry, double weight) {
        Validate.notNull(entry, "entry");
        Validate.check(weight > 0d, "weight must be positive");
        entries.add(entry);
        weights.add(weight);
        cumulative = null;
        return this;
    }

    /** @return a random entry, or {@code null} if the list is empty. */
    public T pick() {
        return pick(ThreadLocalRandom.current());
    }

    /**
     * @param random the source to draw from. A seeded {@link Random} makes a
     *               sequence reproducible, which is the only way to test anything
     *               built on this
     */
    public T pick(Random random) {
        if (entries.isEmpty()) {
            return null;
        }
        double[] table = table();
        double roll = random.nextDouble() * total;
        int index = binarySearch(table, roll);
        return entries.get(index);
    }

    /** @return the entry at a given point of the distribution, 0..1. */
    public T at(double fraction) {
        if (entries.isEmpty()) {
            return null;
        }
        double[] table = table();
        return entries.get(binarySearch(table, Math.max(0d, Math.min(1d, fraction)) * total));
    }

    /** @return the chance of drawing {@code entry}, 0..1, summed over duplicates. */
    public double chanceOf(T entry) {
        table();
        if (total <= 0d) {
            return 0d;
        }
        double matched = 0d;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).equals(entry)) {
                matched += weights.get(i);
            }
        }
        return matched / total;
    }

    public double totalWeight() {
        table();
        return total;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** @return the entries in insertion order, without their weights. */
    public List<T> values() {
        return Collections.unmodifiableList(entries);
    }

    public void clear() {
        entries.clear();
        weights.clear();
        cumulative = null;
        total = 0d;
    }

    @Override
    public String toString() {
        return "WeightedList[" + entries.size() + " entries]";
    }

    /** Rebuilds the cumulative table if an add invalidated it. */
    private double[] table() {
        if (cumulative != null) {
            return cumulative;
        }
        double[] built = new double[entries.size()];
        double running = 0d;
        for (int i = 0; i < weights.size(); i++) {
            running += weights.get(i);
            built[i] = running;
        }
        total = running;
        cumulative = built;
        return built;
    }

    /** @return the first index whose cumulative weight exceeds {@code roll}. */
    private static int binarySearch(double[] table, double roll) {
        int low = 0;
        int high = table.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (roll < table[middle]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
