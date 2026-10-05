package dev.px.core.network.server;

import dev.px.core.event.Event;
import lombok.Getter;

/**
 * What Core knows about the server changed: the player connected, left, or the
 * server sent its brand or registered channels.
 *
 * <p>Posted on the game thread, at the start of the tick after the change, and
 * at most once a tick: a brand and a channel list that arrive together make one
 * event. {@link #getPrevious()} is the state the last event announced, so
 * comparing the two says what changed.
 *
 * <pre>{@code
 * @Subscribe
 * private void onServer(ServerChangeEvent event) {
 *     if (event.isJoin() && event.getCurrent().isOn("hypixel.net")) {
 *         Core.config().load("hypixel");
 *     }
 * }
 * }</pre>
 */
@Getter
public final class ServerChangeEvent extends Event {

    private final ServerInfo previous;
    private final ServerInfo current;

    public ServerChangeEvent(ServerInfo previous, ServerInfo current) {
        this.previous = previous;
        this.current = current;
    }

    /** @return whether this is the player arriving on a server, or moving to a different address */
    public boolean isJoin() {
        return current.isConnected()
                && (!previous.isConnected() || !previous.getAddress().equals(current.getAddress()));
    }

    /** @return whether this is the player leaving */
    public boolean isLeave() {
        return previous.isConnected() && !current.isConnected();
    }

    /** @return whether the brand is different from last time, including arriving for the first time */
    public boolean isBrandChanged() {
        return current.getBrand() != null && !current.getBrand().equals(previous.getBrand());
    }
}
