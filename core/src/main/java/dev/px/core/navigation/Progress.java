package dev.px.core.navigation;

import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * How getting to a {@link Goal} is going, this tick.
 *
 * <pre>{@code
 * Progress progress = navigator.tick(player);
 * if (progress.isArrived()) { ... }
 * if (progress.isFailed()) { log(progress.getReason()); }
 * hud.text(String.format("%.1f blocks left", progress.getRemaining()));
 * }</pre>
 *
 * <p>{@link #getRemaining()} is what notices being stuck: when it stops going
 * down, nothing is getting closer, whatever the provider believes.
 *
 * <p>Immutable.
 */
@Getter
public final class Progress {

    /** Where getting there stands. */
    public enum State {
        /** Still on the way. */
        RUNNING,
        /** The goal is met. */
        ARRIVED,
        /** It cannot be reached, as far as the provider can tell; {@link #getReason()} says why. */
        FAILED,
        /** Still trying, but nothing is getting closer; {@link #getReason()} says why. */
        STUCK
    }

    private static final Progress ARRIVED = new Progress(State.ARRIVED, null, 0d);

    private final State state;

    /** Why it failed or is stuck; null otherwise. */
    private final String reason;

    /** Blocks still to go, at least: the goal's {@link Gap#distance()}. 0 once arrived. */
    private final double remaining;

    private Progress(State state, String reason, double remaining) {
        this.state = state;
        this.reason = reason;
        this.remaining = remaining;
    }

    public static Progress running(double remaining) {
        return new Progress(State.RUNNING, null, Math.max(0d, remaining));
    }

    public static Progress arrived() {
        return ARRIVED;
    }

    public static Progress failed(String reason) {
        Validate.notNull(reason, "reason");
        return new Progress(State.FAILED, reason, Double.NaN);
    }

    public static Progress failed(String reason, double remaining) {
        Validate.notNull(reason, "reason");
        return new Progress(State.FAILED, reason, Math.max(0d, remaining));
    }

    public static Progress stuck(String reason, double remaining) {
        Validate.notNull(reason, "reason");
        return new Progress(State.STUCK, reason, Math.max(0d, remaining));
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isArrived() {
        return state == State.ARRIVED;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    public boolean isStuck() {
        return state == State.STUCK;
    }

    /** @return whether there is nothing more to do: arrived or failed */
    public boolean isDone() {
        return state == State.ARRIVED || state == State.FAILED;
    }

    @Override
    public String toString() {
        switch (state) {
            case RUNNING:
                return String.format("Progress(running, %.2f left)", remaining);
            case ARRIVED:
                return "Progress(arrived)";
            default:
                return "Progress(" + state.name().toLowerCase() + ": " + reason + ")";
        }
    }
}
