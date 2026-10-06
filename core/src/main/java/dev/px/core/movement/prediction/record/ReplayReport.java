package dev.px.core.movement.prediction.record;

import dev.px.core.movement.prediction.Behaviour;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How predictions did against a recording: per horizon, how far the likeliest
 * future missed and how often the possible bound was late; per entity, what was
 * learned about how it moves.
 *
 * <p>Immutable.
 */
public final class ReplayReport {

    private final String label;
    private final List<Horizon> horizons;
    private final Map<String, Behaviour> behaviours;

    ReplayReport(String label, List<Horizon> horizons, Map<String, Behaviour> behaviours) {
        this.label = label;
        this.horizons = Collections.unmodifiableList(new ArrayList<>(horizons));
        this.behaviours = Collections.unmodifiableMap(new LinkedHashMap<>(behaviours));
    }

    public String getLabel() {
        return label;
    }

    /** @return one line per horizon asked for, shortest first */
    public List<Horizon> getHorizons() {
        return horizons;
    }

    /** @return the horizon {@code ticks} ahead, or null if it was not asked for */
    public Horizon getHorizon(int ticks) {
        for (Horizon horizon : horizons) {
            if (horizon.getTicks() == ticks) {
                return horizon;
            }
        }
        return null;
    }

    /** @return what was learned of each entity, by name, or by {@code #id} without one */
    public Map<String, Behaviour> getBehaviours() {
        return behaviours;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("Replay of ").append(label).append('\n');
        out.append(" ahead | predictions | mean error | 95th pct | worst  | reliable | bound late\n");
        for (Horizon horizon : horizons) {
            out.append(String.format(Locale.ROOT, " %5d | %11d | %10.4f | %8.4f | %6.3f | %7.1f%% | %d of %d%n",
                    horizon.getTicks(), horizon.getSamples(), horizon.getMeanError(), horizon.getP95Error(),
                    horizon.getWorstError(), horizon.getReliableShare() * 100d, horizon.getBoundLate(),
                    horizon.getBoundChecks()));
        }
        for (Map.Entry<String, Behaviour> entry : behaviours.entrySet()) {
            out.append(' ').append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return out.toString();
    }

    /** How predictions this many ticks ahead did. */
    public static final class Horizon {
        private final int ticks;
        private final int samples;
        private final double meanError;
        private final double p95Error;
        private final double worstError;
        private final double reliableShare;
        private final double reliableMeanError;
        private final int boundChecks;
        private final int boundLate;

        Horizon(int ticks, int samples, double meanError, double p95Error, double worstError, double reliableShare,
                double reliableMeanError, int boundChecks, int boundLate) {
            this.ticks = ticks;
            this.samples = samples;
            this.meanError = meanError;
            this.p95Error = p95Error;
            this.worstError = worstError;
            this.reliableShare = reliableShare;
            this.reliableMeanError = reliableMeanError;
            this.boundChecks = boundChecks;
            this.boundLate = boundLate;
        }

        public int getTicks() {
            return ticks;
        }

        /** @return predictions scored: each against a position the server really sent that many ticks later */
        public int getSamples() {
            return samples;
        }

        /** @return the mean distance, in blocks, between the likeliest future and where it really was */
        public double getMeanError() {
            return meanError;
        }

        public double getP95Error() {
            return p95Error;
        }

        public double getWorstError() {
            return worstError;
        }

        /** @return the share of predictions that called themselves reliable */
        public double getReliableShare() {
            return reliableShare;
        }

        /** @return the mean error of just those: what trusting {@code isReliable} buys you; NaN with none */
        public double getReliableMeanError() {
            return reliableMeanError;
        }

        /** @return times the possible bound was checked against where the entity really got */
        public int getBoundChecks() {
            return boundChecks;
        }

        /** @return of those, times it got somewhere sooner than the bound said it could: should be none */
        public int getBoundLate() {
            return boundLate;
        }
    }
}
