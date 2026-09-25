package dev.px.core.network.packet;

/**
 * Turns a packet the adapter posted into something Core can record.
 *
 * <p>The same seam as {@link dev.px.core.movement.simulation.CollisionSpace}:
 * Core does the bookkeeping, the adapter knows what the game's objects are. One
 * method, one class per game version.
 *
 * <pre>{@code
 * PacketDescriber describer = packet -> {
 *     if (packet instanceof S08PacketPlayerPosLook) {
 *         S08PacketPlayerPosLook p = (S08PacketPlayerPosLook) packet;
 *         return PacketDescription.of("S08PacketPlayerPosLook", PacketKind.TELEPORT)
 *                 .withPosition(Vec3.of(p.getX(), p.getY(), p.getZ()))
 *                 .withRotation(Vec2.rotation(p.getYaw(), p.getPitch()))
 *                 .with("relative", p.func_179834_f().toString());
 *     }
 *     if (packet instanceof C0FPacketConfirmTransaction) {
 *         C0FPacketConfirmTransaction p = (C0FPacketConfirmTransaction) packet;
 *         return PacketDescription.of("C0FPacketConfirmTransaction", PacketKind.TRANSACTION)
 *                 .withCorrelationKey("transaction:" + p.getUid());
 *     }
 *     return PacketDescription.of(packet.getClass().getSimpleName(), PacketKind.OTHER);
 * };
 * }</pre>
 *
 * <p>{@link #byClass()} builds one from a rule per packet class instead, which
 * reads better and costs one lookup per packet rather than a test per branch.
 *
 * <p>Install it once, with {@code Core.network().setDescriber(...)}. The
 * timeline recorder and every network service read through that one.
 *
 * <p><b>Name packets with string literals</b>, not {@code getClass().getSimpleName()},
 * wherever the build is obfuscated: in production the class is called
 * {@code a} or {@code class_2828}, and a recording full of those is unreadable.
 *
 * <p><b>Called on the posting thread</b>, which for a received packet is the
 * network thread. Read fields off the packet; do not touch the world.
 *
 * <p>Returning null or throwing falls back to {@link #DEFAULT} for that packet,
 * so a bug in one branch costs a description, not the recording.
 */
@FunctionalInterface
public interface PacketDescriber {

    /** The class's simple name, classified {@link PacketKind#OTHER}. What the recorder uses until one is installed. */
    PacketDescriber DEFAULT = packet -> PacketDescription.of(packet.getClass().getSimpleName(), PacketKind.OTHER);

    /**
     * @param packet whatever the adapter put in the {@code PacketEvent}, never null
     * @return its description, or null to fall back to {@link #DEFAULT}
     */
    PacketDescription describe(Object packet);

    /** Starts a describer built from one rule per packet class. See {@link ClassPacketDescriber}. */
    static ClassPacketDescriber.Builder byClass() {
        return new ClassPacketDescriber.Builder();
    }
}
