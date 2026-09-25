package dev.px.core.network;

import dev.px.core.network.packet.PacketDescription;

/**
 * Traffic as the network services see it: each packet once, already described.
 *
 * <p>{@link dev.px.core.event.impl.PacketEvent} is posted up to twice per inbound
 * packet and carries an opaque object. This is the same traffic after
 * {@link NetworkService} has done the two chores every consumer would otherwise
 * repeat: it picks the one post that marks arrival, and it runs the adapter's
 * describer on the packet, once, however many listeners there are.
 *
 * <pre>{@code
 * Core.network().addListener(new PacketListener() {
 *     @Override
 *     public void onInbound(PacketDescription packet, long nanos) {
 *         if (packet.getKind() == PacketKind.VELOCITY) {
 *             lastKnockback = nanos;
 *         }
 *     }
 * });
 * }</pre>
 *
 * <p><b>Runs on the posting thread</b>, which for inbound traffic is usually the
 * network thread. Record and return; hand anything that touches the game to
 * {@code Core.threads().sync(...)}.
 */
public interface PacketListener {

    /**
     * A packet arrived from the server.
     *
     * <p>Called even if a handler cancelled the packet: cancelling drops it on the
     * client, but the server still sent it, and that is what arrival measures.
     *
     * @param nanos when it arrived, on {@link NetworkService#nanoTime()}
     */
    default void onInbound(PacketDescription packet, long nanos) {
    }

    /**
     * The client sent a packet. Not called for one a handler cancelled, since
     * that one never left.
     *
     * @param nanos when it was sent, on {@link NetworkService#nanoTime()}
     */
    default void onOutbound(PacketDescription packet, long nanos) {
    }
}
