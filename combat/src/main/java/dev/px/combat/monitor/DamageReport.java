package dev.px.combat.monitor;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the {@link DamageMonitor} has concluded: how far the model's predictions
 * are from what happens, overall and by which rule each sample tests, and which
 * rule is most likely wrong.
 *
 * <p>Immutable: a snapshot taken when it was asked for.
 */
public final class DamageReport {

    /** Which rule the evidence points at. */
    public enum Suspect {
        /** Nothing is off, or there is not enough evidence to say. */
        NONE,
        /** Fully exposed, barely armoured targets are off: the formula itself. */
        FALLOFF,
        /** Only armoured targets are off: armour, enchantments, effects, difficulty. */
        MITIGATION,
        /** Only partly covered targets are off: how rays or block shapes are worked out. */
        EXPOSURE,
        /** Off, but in no one place: or targets popping that the model said would survive. */
        UNKNOWN
    }

    private final Stats overall;
    private final Map<DamageSample.Bucket, Stats> buckets;
    private final int pops;
    private final int unexpectedPops;
    private final boolean popsOff;
    private final Suspect suspect;
    private final boolean evidence;

    DamageReport(Stats overall, Map<DamageSample.Bucket, Stats> buckets, int pops, int unexpectedPops,
                 boolean popsOff, Suspect suspect, boolean evidence) {
        this.overall = overall;
        this.buckets = Collections.unmodifiableMap(new EnumMap<>(buckets));
        this.pops = pops;
        this.unexpectedPops = unexpectedPops;
        this.popsOff = popsOff;
        this.suspect = suspect;
        this.evidence = evidence;
    }

    /** @return whether the predictions can be relied on: true until there is evidence they cannot */
    public boolean isReliable() {
        return suspect == Suspect.NONE;
    }

    /** @return whether there were enough samples for any conclusion at all */
    public boolean hasEvidence() {
        return evidence;
    }

    public Suspect getSuspect() {
        return suspect;
    }

    /** @return every exact sample together */
    public Stats getOverall() {
        return overall;
    }

    /** @return the samples testing one rule */
    public Stats get(DamageSample.Bucket bucket) {
        return buckets.get(bucket);
    }

    /** @return targets that popped or died, whose damage is only known to be at least their health */
    public int getPops() {
        return pops;
    }

    /** @return pops the model said the target would survive: under-prediction, however it is bucketed */
    public int getUnexpectedPops() {
        return unexpectedPops;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("DamageReport(")
                .append(isReliable() ? "reliable" : "unreliable, suspect " + suspect)
                .append("; overall ").append(overall);
        for (Map.Entry<DamageSample.Bucket, Stats> entry : buckets.entrySet()) {
            text.append("; ").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        text.append("; pops ").append(pops).append(" (").append(unexpectedPops).append(" unexpected")
                .append(popsOff ? ", too many" : "").append("))");
        return text.toString();
    }

    /** How predictions compared with what happened, for one group of samples. */
    public static final class Stats {

        private final int count;
        private final double medianError;
        private final double medianRatio;
        private final boolean off;

        Stats(int count, double medianError, double medianRatio, boolean off) {
            this.count = count;
            this.medianError = medianError;
            this.medianRatio = medianRatio;
            this.off = off;
        }

        public int getCount() {
            return count;
        }

        /** @return the median of observed minus predicted: positive means under-predicting. NaN with no samples */
        public double getMedianError() {
            return medianError;
        }

        /** @return the median of observed over predicted: 1 is exact. NaN with no samples */
        public double getMedianRatio() {
            return medianRatio;
        }

        /** @return whether there are enough samples and they are further off than the tolerances allow */
        public boolean isOff() {
            return off;
        }

        @Override
        public String toString() {
            return count == 0 ? "no samples" : String.format(Locale.ROOT, "%d samples, error %+.2f, ratio %.2f%s",
                    count, medianError, medianRatio, off ? " OFF" : "");
        }
    }
}
