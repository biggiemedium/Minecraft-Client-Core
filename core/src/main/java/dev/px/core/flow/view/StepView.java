package dev.px.core.flow.view;

import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One step as the live view shows it: where it stands, why, how long it has
 * taken, what it touched, and the steps inside it.
 *
 * <p>Immutable: a snapshot. Built by {@link dev.px.core.flow.FlowService}.
 */
@Getter
public final class StepView {

    private final String name;
    private final StepState state;
    /** Why it failed or was paused; null otherwise. */
    private final String reason;
    /** The controls it declared it uses. */
    private final Set<String> uses;
    /** Times it has been started, resumes included. */
    private final int starts;
    /** Ticks it has been ticked since it last began. */
    private final int ticks;
    /** Time spent inside its own tick calls since it last began, in microseconds. */
    private final long micros;
    /** The tick it last began on, or -1. */
    private final long startedTick;
    /** The tick it last finished on, or -1 while it has not. */
    private final long finishedTick;
    private final Set<String> keysRead;
    private final Set<String> keysWritten;
    private final Set<String> facts;
    private final List<StepView> children;

    public StepView(String name, StepState state, String reason, Set<String> uses, int starts, int ticks,
                    long micros, long startedTick, long finishedTick, Set<String> keysRead,
                    Set<String> keysWritten, Set<String> facts, List<StepView> children) {
        this.name = name;
        this.state = state;
        this.reason = reason;
        this.uses = Collections.unmodifiableSet(uses);
        this.starts = starts;
        this.ticks = ticks;
        this.micros = micros;
        this.startedTick = startedTick;
        this.finishedTick = finishedTick;
        this.keysRead = Collections.unmodifiableSet(keysRead);
        this.keysWritten = Collections.unmodifiableSet(keysWritten);
        this.facts = Collections.unmodifiableSet(facts);
        this.children = Collections.unmodifiableList(children);
    }

    /** @return the first step here or below with this name, or null */
    public StepView find(String name) {
        if (this.name.equals(name)) {
            return this;
        }
        for (StepView child : children) {
            StepView found = child.find(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** @return the deepest running steps' names, outermost first: where the flow is now */
    public String describe() {
        StringBuilder text = new StringBuilder(name);
        for (StepView child : children) {
            if (child.state == StepState.RUNNING) {
                text.append(" > ").append(child.describe());
                break;
            }
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return name + " (" + state.name().toLowerCase() + (reason == null ? "" : ": " + reason) + ")";
    }
}
