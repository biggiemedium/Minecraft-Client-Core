package dev.px.core.memory;

import lombok.Getter;

/**
 * One thing {@link Memory} holds, read at one moment: the value, what it is
 * about, and how old it is.
 *
 * <p>Immutable: a snapshot of the moment it was read.
 *
 * @param <T> the value it holds
 */
@Getter
public final class Recollection<T> {

    private final Fact<T> fact;
    /** What it is about; null for a fact with one value. */
    private final Object subject;
    private final T value;
    /** Ticks since it was remembered. */
    private final long ageTicks;
    /** Wall-clock milliseconds since it was remembered. */
    private final long ageMillis;
    /** Whether it never expires. */
    private final boolean permanent;

    Recollection(Fact<T> fact, Object subject, T value, long ageTicks, long ageMillis, boolean permanent) {
        this.fact = fact;
        this.subject = subject;
        this.value = value;
        this.ageTicks = ageTicks;
        this.ageMillis = ageMillis;
        this.permanent = permanent;
    }

    @Override
    public String toString() {
        return fact.getName() + (subject == null ? "" : "[" + subject + "]") + " = " + value
                + " (" + ageTicks + " ticks old" + (permanent ? "" : ", expires") + ")";
    }
}
