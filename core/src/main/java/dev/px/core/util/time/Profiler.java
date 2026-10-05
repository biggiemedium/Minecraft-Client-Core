package dev.px.core.util.time;

import dev.px.core.util.Validate;
import dev.px.core.util.collect.RollingAverage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Times named sections of work and keeps a rolling average of each.
 *
 * <p>The question this answers is the one that comes up the first time a client
 * drops frames: which part. Guessing produces a week of micro-optimising the
 * wrong loop. Ten lines of measurement produce a number.
 *
 * <p>Averaged over a window rather than reported per frame, because a single
 * frame's timing is mostly noise &mdash; a GC pause, a scheduler hiccup &mdash;
 * and the interesting figure is the one that stays high. {@link #max(String)}
 * keeps the worst case in the window alongside it, since a stutter every second
 * is a real problem that an average hides.
 *
 * <pre>{@code
 * private static final Profiler profiler = Profiler.of(120);
 *
 * profiler.begin("hud");
 * hudService.render();
 * profiler.end("hud");
 *
 * Render.text(profiler.report(), 4, 4, Color.WHITE);
 * }</pre>
 *
 * <p><b>Complexity.</b> {@link #begin} and {@link #end} are O(1) expected, as is
 * the bookkeeping {@link #measure} adds around the body it times.
 * {@link #max(String)} walks one window and is O(w); {@link #report()} is
 * O(sections · w).
 *
 * <p>Uses {@link System#nanoTime()}, so a clock adjustment cannot make a section
 * appear to take negative time. Not thread-safe: give each thread its own, which
 * is also the only way the numbers mean anything.
 */
public final class Profiler {

    private final Map<String, RollingAverage> sections = new LinkedHashMap<>();
    private final Map<String, Long> started = new LinkedHashMap<>();
    private final int window;

    private Profiler(int window) {
        Validate.check(window > 0, "window must be positive");
        this.window = window;
    }

    /** @param window how many samples of each section to average over */
    public static Profiler of(int window) {
        return new Profiler(window);
    }

    /** A profiler averaging over two seconds of frames at 60 FPS. */
    public static Profiler standard() {
        return new Profiler(120);
    }

    // ------------------------------------------------------------ measuring

    /** Starts timing {@code section}. A second call before {@link #end} restarts it. */
    public void begin(String section) {
        started.put(section, System.nanoTime());
    }

    /**
     * Stops timing {@code section} and records the sample.
     *
     * @return the elapsed time in milliseconds, or 0 if the section was never begun
     */
    public double end(String section) {
        Long start = started.remove(section);
        if (start == null) {
            return 0d;
        }
        double millis = (System.nanoTime() - start) / 1_000_000d;
        sections.computeIfAbsent(section, name -> RollingAverage.of(window)).push(millis);
        return millis;
    }

    /**
     * Times {@code body}, recording it under {@code section}.
     *
     * <p>The form to prefer: an early return or a throw inside the measured code
     * cannot leak a begun section, which is how a hand-paired begin and end ends
     * up reporting a frame time of several minutes.
     *
     * @return the elapsed time in milliseconds
     */
    public double measure(String section, Runnable body) {
        long start = System.nanoTime();
        try {
            body.run();
        } finally {
            double millis = (System.nanoTime() - start) / 1_000_000d;
            sections.computeIfAbsent(section, name -> RollingAverage.of(window)).push(millis);
        }
        return averageMillis(section);
    }

    // ------------------------------------------------------------- reading

    /** @return the average duration of {@code section} in milliseconds, or 0 if unmeasured. */
    public double averageMillis(String section) {
        RollingAverage average = sections.get(section);
        return average == null ? 0d : average.average();
    }

    /** @return the most recent sample for {@code section}, in milliseconds. */
    public double lastMillis(String section) {
        RollingAverage average = sections.get(section);
        return average == null ? 0d : average.last();
    }

    /** @return the worst sample still inside the window, in milliseconds. */
    public double max(String section) {
        RollingAverage average = sections.get(section);
        return average == null ? 0d : average.max();
    }

    /** @return the measured section names, in the order they were first measured. */
    public Set<String> sections() {
        return Collections.unmodifiableSet(sections.keySet());
    }

    /** @return the sum of every section's average. Not a frame time: sections may nest. */
    public double totalMillis() {
        double total = 0d;
        for (RollingAverage average : sections.values()) {
            total += average.average();
        }
        return total;
    }

    /** @return one line per section, ready to draw. */
    public String report() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, RollingAverage> entry : sections.entrySet()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(String.format("%s  %.2fms  (peak %.2fms)",
                    entry.getKey(), entry.getValue().average(), entry.getValue().max()));
        }
        return text.toString();
    }

    /** Forgets every sample and any section left open. */
    public void clear() {
        sections.clear();
        started.clear();
    }

    @Override
    public String toString() {
        return "Profiler[" + sections.size() + " sections]";
    }
}
