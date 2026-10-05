package dev.px.core.hook;

import java.util.Collections;
import java.util.List;

/**
 * One line of {@link GameHooks#report()}: how often a hook has fired, and who is
 * waiting on it.
 *
 * <p>Immutable; a snapshot taken when the report was made.
 */
public final class HookStatus {

    private final Hook hook;
    private final long timesFired;
    private final List<String> listeners;

    HookStatus(Hook hook, long timesFired, List<String> listeners) {
        this.hook = hook;
        this.timesFired = timesFired;
        this.listeners = Collections.unmodifiableList(listeners);
    }

    public Hook getHook() {
        return hook;
    }

    /** @return how many times the hook's event has been posted since Core started */
    public long getTimesFired() {
        return timesFired;
    }

    public boolean hasFired() {
        return timesFired > 0;
    }

    /** @return who is listening for the hook's event right now: what goes idle without it */
    public List<String> getListeners() {
        return listeners;
    }

    /** @return whether anything is listening, so the hook matters now */
    public boolean isNeeded() {
        return !listeners.isEmpty();
    }

    /** @return needed, and never fired: something is waiting on a hook nobody calls */
    public boolean isMissing() {
        return isNeeded() && !hasFired();
    }

    @Override
    public String toString() {
        String state = isMissing() ? "MISSING" : hasFired() ? timesFired + "x" : "unused";
        return String.format("%-12s %-8s %s", hook, state, listeners.isEmpty() ? "-" : String.join(", ", listeners));
    }
}
