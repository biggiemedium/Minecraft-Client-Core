package dev.px.core.network.anticheat;

import dev.px.core.event.Event;
import lombok.Getter;

import java.util.List;
import java.util.Optional;

/**
 * What the server is thought to run changed: something was detected, grew more
 * certain, or was forgotten on leaving.
 *
 * <p>Posted on the game thread.
 *
 * <pre>{@code
 * @Subscribe
 * private void onAntiCheat(AntiCheatChangeEvent event) {
 *     event.getPrimary().ifPresent(found ->
 *             Core.notifications().info("Anticheat", found.getName()));
 * }
 * }</pre>
 */
@Getter
public final class AntiCheatChangeEvent extends Event {

    /** Most certain first. */
    private final List<Detection> previous;

    /** Most certain first; empty when nothing is detected. */
    private final List<Detection> current;

    public AntiCheatChangeEvent(List<Detection> previous, List<Detection> current) {
        this.previous = previous;
        this.current = current;
    }

    /** @return the most certain current detection */
    public Optional<Detection> getPrimary() {
        return current.isEmpty() ? Optional.<Detection>empty() : Optional.of(current.get(0));
    }
}
