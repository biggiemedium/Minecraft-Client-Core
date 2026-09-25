package dev.px.core.network.packet;

import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A {@link PacketDescriber} built from one rule per packet class, instead of an
 * {@code instanceof} chain.
 *
 * <pre>{@code
 * Core.network().setDescriber(PacketDescriber.byClass()
 *         .on(S03PacketTimeUpdate.class, p ->
 *                 PacketDescription.timeUpdate("S03PacketTimeUpdate", p.getTotalWorldTime()))
 *         .on(S32PacketConfirmTransaction.class, p ->
 *                 PacketDescription.transaction("S32PacketConfirmTransaction", p.getActionNumber()))
 *         .on(S08PacketPlayerPosLook.class, p ->
 *                 PacketDescription.of("S08PacketPlayerPosLook", PacketKind.TELEPORT)
 *                         .withPosition(Vec3.of(p.getX(), p.getY(), p.getZ())))
 *         .build());
 * }</pre>
 *
 * <p>Each rule gets the packet already cast to its class. A packet with no rule
 * of its own uses the rule of its nearest registered superclass or interface,
 * and a packet with neither goes to {@link Builder#otherwise the fallback}.
 *
 * <p><b>Cost.</b> One hash lookup per packet, whatever the number of rules: the
 * rule for each concrete class is resolved on first sight and cached. An
 * {@code instanceof} chain costs a test per branch on every packet, and the
 * packets that matter least &mdash; the chunk and entity traffic that is most of
 * the stream &mdash; fall through every branch to reach the end.
 *
 * <p>Safe to call from the network and game threads at once.
 */
public final class ClassPacketDescriber implements PacketDescriber {

    /** Cached for classes that resolve to no rule, so a miss is also one lookup. */
    private static final Function<Object, PacketDescription> NO_RULE = packet -> null;

    private final Map<Class<?>, Function<Object, PacketDescription>> rules;
    private final PacketDescriber fallback;
    private final Map<Class<?>, Function<Object, PacketDescription>> resolved = new ConcurrentHashMap<>();

    private ClassPacketDescriber(Map<Class<?>, Function<Object, PacketDescription>> rules,
                                 PacketDescriber fallback) {
        this.rules = rules;
        this.fallback = fallback;
    }

    @Override
    public PacketDescription describe(Object packet) {
        Function<Object, PacketDescription> rule = resolved.get(packet.getClass());
        if (rule == null) {
            rule = resolve(packet.getClass());
            resolved.put(packet.getClass(), rule);
        }
        PacketDescription description = rule == NO_RULE ? null : rule.apply(packet);
        return description != null ? description : fallback.describe(packet);
    }

    /** @return how many classes have rules of their own */
    public int size() {
        return rules.size();
    }

    private Function<Object, PacketDescription> resolve(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            Function<Object, PacketDescription> rule = rules.get(at);
            if (rule != null) {
                return rule;
            }
            for (Class<?> implemented : at.getInterfaces()) {
                rule = rules.get(implemented);
                if (rule != null) {
                    return rule;
                }
            }
        }
        return NO_RULE;
    }

    public static final class Builder {

        private final Map<Class<?>, Function<Object, PacketDescription>> rules = new LinkedHashMap<>();
        private PacketDescriber fallback = PacketDescriber.DEFAULT;

        Builder() {
        }

        /**
         * Describes packets of {@code type}, and of its subclasses that have no rule
         * of their own.
         *
         * @param describe given the packet already cast; may return null to use the fallback
         * @throws IllegalArgumentException if {@code type} already has a rule
         */
        public <P> Builder on(Class<P> type, Function<? super P, PacketDescription> describe) {
            Validate.notNull(type, "type");
            Validate.notNull(describe, "describe");
            Validate.check(!rules.containsKey(type), "a rule for " + type.getName() + " is already registered");
            rules.put(type, packet -> describe.apply(type.cast(packet)));
            return this;
        }

        /**
         * Gives every packet of {@code type} the same kind, named {@code name}.
         *
         * <p>For the packets that need classifying but carry nothing Core reads.
         */
        public Builder on(Class<?> type, String name, PacketKind kind) {
            PacketDescription description = PacketDescription.of(name, kind);
            return on(type, packet -> description);
        }

        /** What to use for a packet no rule covers. {@link PacketDescriber#DEFAULT} unless set. */
        public Builder otherwise(PacketDescriber fallback) {
            this.fallback = Validate.notNull(fallback, "fallback");
            return this;
        }

        public ClassPacketDescriber build() {
            return new ClassPacketDescriber(Collections.unmodifiableMap(new LinkedHashMap<>(rules)), fallback);
        }
    }
}
