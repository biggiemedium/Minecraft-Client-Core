package dev.px.core.event.impl;

import dev.px.core.event.CancellableEvent;
import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * A packet crossing the connection, in either direction.
 *
 * <p>Core has no packet types, so the packet is an {@code Object} and only the
 * adapter knows what it is. Anything that needs to read one &mdash; the timeline
 * recorder included &mdash; goes through a
 * {@link dev.px.core.network.packet.PacketDescriber} rather than casting.
 *
 * <h2>Where to post it</h2>
 *
 * <p>An outbound packet is posted once, just before it is written, as
 * {@link Phase#SENT}. Cancelling it means it is not sent.
 *
 * <p>An inbound packet is posted <b>twice</b>, because the game handles it in two
 * places:
 *
 * <ol>
 *   <li>{@link Phase#RECEIVED} on the network thread, the moment it is decoded.
 *       This is the true arrival order. Cancelling it means it is dropped.
 *   <li>{@link Phase#APPLIED} on the game thread, when its handler actually runs.
 *       This is the tick it took effect on, which for a teleport or a velocity
 *       packet is the tick that matters.
 * </ol>
 *
 * <p>Post both with the <em>same packet instance</em>; that is how the two are
 * linked. A packet the game handles on the network thread directly (a keep-alive
 * in most versions) is posted RECEIVED and then APPLIED immediately after.
 *
 * <pre>{@code
 * // outbound, e.g. in a NetworkManager.sendPacket mixin
 * if (Core.bus().post(PacketEvent.sent(packet)).isCancelled()) ci.cancel();
 *
 * // inbound, network thread, e.g. channelRead0
 * if (Core.bus().post(PacketEvent.received(packet)).isCancelled()) ci.cancel();
 *
 * // inbound, game thread, at the top of each handler you care about
 * Core.bus().post(PacketEvent.applied(packet));
 * }</pre>
 *
 * <p><b>Threading.</b> The bus runs handlers on the posting thread, so a
 * RECEIVED handler runs on the network thread. Keep it short and do not touch
 * game state from it; hand work to {@code Core.threads().sync(...)} instead.
 */
@Getter
public final class PacketEvent extends CancellableEvent {

    private final Phase phase;
    private final Object packet;

    private PacketEvent(Phase phase, Object packet) {
        this.phase = Validate.notNull(phase, "phase");
        this.packet = Validate.notNull(packet, "packet");
    }

    /** An outbound packet, about to be written. */
    public static PacketEvent sent(Object packet) {
        return new PacketEvent(Phase.SENT, packet);
    }

    /** An inbound packet, just decoded on the network thread. */
    public static PacketEvent received(Object packet) {
        return new PacketEvent(Phase.RECEIVED, packet);
    }

    /** An inbound packet, being handled on the game thread. */
    public static PacketEvent applied(Object packet) {
        return new PacketEvent(Phase.APPLIED, packet);
    }

    public Direction getDirection() {
        return phase.getDirection();
    }

    public boolean isInbound() {
        return phase.getDirection() == Direction.INBOUND;
    }

    public boolean isOutbound() {
        return phase.getDirection() == Direction.OUTBOUND;
    }

    /** Which way a packet is travelling, from the client's point of view. */
    public enum Direction {
        /** Server to client. */
        INBOUND,
        /** Client to server. */
        OUTBOUND
    }

    /** Where in its life the packet was when the event was posted. */
    public enum Phase {
        /** Outbound, about to be written to the connection. */
        SENT(Direction.OUTBOUND),
        /** Inbound, decoded on the network thread. */
        RECEIVED(Direction.INBOUND),
        /** Inbound, being handled on the game thread. */
        APPLIED(Direction.INBOUND);

        @Getter
        private final Direction direction;

        Phase(Direction direction) {
            this.direction = direction;
        }
    }
}
