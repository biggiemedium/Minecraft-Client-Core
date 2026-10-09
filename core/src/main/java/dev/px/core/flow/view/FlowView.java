package dev.px.core.flow.view;

import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * One flow as the live view shows it, at one moment.
 *
 * <pre>{@code
 * for (FlowView flow : Core.flows().view()) {
 *     hud.line(flow.getName() + ": " + flow.getState() + "  " + flow.getRoot().describe());
 *     if (flow.getReason() != null) hud.line("  " + flow.getReason());
 * }
 * }</pre>
 *
 * <p>Headless: it draws nothing and listens to nothing. A screen or a HUD
 * element draws it however the client likes.
 *
 * <p>Immutable: a snapshot. Built by {@link dev.px.core.flow.FlowService}.
 */
@Getter
public final class FlowView {

    private final String name;
    private final int priority;
    /** The flow's state, as {@link dev.px.core.flow.FlowHandle.State}'s name. */
    private final String state;
    /** Why it failed, or what paused it; null otherwise. */
    private final String reason;
    private final boolean reflex;
    /** The tick it started on. */
    private final long startedTick;
    /** Its keys and their values, as text. */
    private final Map<String, String> keys;
    private final StepView root;

    public FlowView(String name, int priority, String state, String reason, boolean reflex, long startedTick,
                    Map<String, String> keys, StepView root) {
        this.name = name;
        this.priority = priority;
        this.state = state;
        this.reason = reason;
        this.reflex = reflex;
        this.startedTick = startedTick;
        this.keys = Collections.unmodifiableMap(keys);
        this.root = root;
    }

    @Override
    public String toString() {
        return name + " [" + state + (reason == null ? "" : ": " + reason) + "] " + root.describe();
    }
}
