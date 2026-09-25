package dev.px.core.network.lag;

import dev.px.core.event.Event;
import lombok.Getter;

/**
 * The server went quiet for longer than {@link LagService#getSpikeThresholdMillis()},
 * or started talking again after doing so.
 *
 * <p>Posted on the game thread, from the tick, so a handler may touch the game:
 *
 * <pre>{@code
 * @Subscribe
 * private void onLag(LagSpikeEvent event) {
 *     if (event.isStarted()) {
 *         Core.notifications().warn("Lag", "Server not responding");
 *     } else {
 *         Core.notifications().info("Lag", "Back after " + event.getMillis() + "ms");
 *     }
 * }
 * }</pre>
 */
@Getter
public final class LagSpikeEvent extends Event {

    private final Phase phase;

    /**
     * For {@link Phase#STARTED}, how long the server had already been silent.
     * For {@link Phase#ENDED}, how long the silence lasted in total.
     */
    private final long millis;

    public LagSpikeEvent(Phase phase, long millis) {
        this.phase = phase;
        this.millis = millis;
    }

    public boolean isStarted() {
        return phase == Phase.STARTED;
    }

    public boolean isEnded() {
        return phase == Phase.ENDED;
    }

    public enum Phase {
        /** Nothing has arrived for longer than the threshold. */
        STARTED,
        /** Packets are arriving again. */
        ENDED
    }
}
