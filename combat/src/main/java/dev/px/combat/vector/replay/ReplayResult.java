package dev.px.combat.vector.replay;

import dev.px.combat.monitor.DamageReport;
import dev.px.combat.monitor.DamageSample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Every vector in a set replayed against a profile: how many it reproduces,
 * which it does not, and which rule those point at.
 *
 * <pre>{@code
 * ReplayResult result = VectorReplay.against(myProfile).run(set);
 * if (!result.isPassed()) {
 *     System.out.println(result);              // the failures, and the suspect rule
 * }
 * }</pre>
 *
 * <p>Immutable.
 */
public final class ReplayResult {

    private final List<VectorOutcome> outcomes;
    private final Map<DamageSample.Bucket, Integer> failedBy;
    private final int failed;
    private final int failedPops;
    private final DamageReport.Suspect suspect;

    ReplayResult(List<VectorOutcome> outcomes) {
        this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
        Map<DamageSample.Bucket, Integer> counts = new EnumMap<>(DamageSample.Bucket.class);
        for (DamageSample.Bucket bucket : DamageSample.Bucket.values()) {
            counts.put(bucket, 0);
        }
        int failures = 0;
        int pops = 0;
        for (VectorOutcome outcome : outcomes) {
            if (outcome.isPassed()) {
                continue;
            }
            failures++;
            if (outcome.getVector().isPopped()) {
                pops++;
            } else {
                counts.put(outcome.getBucket(), counts.get(outcome.getBucket()) + 1);
            }
        }
        this.failedBy = Collections.unmodifiableMap(counts);
        this.failed = failures;
        this.failedPops = pops;
        this.suspect = counts.get(DamageSample.Bucket.OPEN) > 0 ? DamageReport.Suspect.FALLOFF
                : counts.get(DamageSample.Bucket.ARMOURED) > 0 ? DamageReport.Suspect.MITIGATION
                : counts.get(DamageSample.Bucket.COVERED) > 0 ? DamageReport.Suspect.EXPOSURE
                : pops > 0 ? DamageReport.Suspect.UNKNOWN
                : DamageReport.Suspect.NONE;
    }

    /** @return whether every vector was reproduced */
    public boolean isPassed() {
        return failed == 0;
    }

    public int getPassedCount() {
        return outcomes.size() - failed;
    }

    public int getFailedCount() {
        return failed;
    }

    /** @return exact vectors that failed, by the rule they test */
    public int getFailed(DamageSample.Bucket bucket) {
        return failedBy.get(bucket);
    }

    /** @return popped vectors the profile says the target would have survived */
    public int getFailedPops() {
        return failedPops;
    }

    /**
     * @return the rule the failures point at: the falloff if fully exposed,
     *         barely armoured vectors fail, then mitigation, then exposure
     */
    public DamageReport.Suspect getSuspect() {
        return suspect;
    }

    public List<VectorOutcome> getOutcomes() {
        return outcomes;
    }

    /** @return the vectors that failed */
    public List<VectorOutcome> getFailures() {
        List<VectorOutcome> failures = new ArrayList<>();
        for (VectorOutcome outcome : outcomes) {
            if (!outcome.isPassed()) {
                failures.add(outcome);
            }
        }
        return failures;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("ReplayResult(").append(getPassedCount()).append('/')
                .append(outcomes.size()).append(" passed");
        if (!isPassed()) {
            text.append(", suspect ").append(suspect);
            for (VectorOutcome failure : getFailures()) {
                text.append("\n  ").append(failure);
            }
        }
        return text.append(')').toString();
    }
}
