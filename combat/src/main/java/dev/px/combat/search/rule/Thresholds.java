package dev.px.combat.search.rule;

import dev.px.core.util.Validate;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.ToDoubleFunction;

/**
 * What makes an option worth taking, and what makes one too dangerous: your
 * aura's settings, read live on every search, whatever it sets off.
 *
 * <pre>{@code
 * Thresholds<LivingEntity> thresholds = Thresholds.<LivingEntity>builder()
 *         .minDamage(minDamage::getDouble)
 *         .maxSelfDamage(maxSelf::getDouble)
 *         .antiSuicide(() -> 0.5)                                // never leave yourself under half a heart
 *         .maxProtectedDamage(friendMax::getDouble)               // for whoever the search protects
 *         .protectedMargin(() -> 4)                              // and never leave one of them near death
 *         .lethal(lethalMultiplier::getDouble, true)              // a kill ignores the self-damage cap
 *         .facePlace(faceHealth::getDouble, faceDamage::getDouble)
 *         .facePlaceWhen(faceKey::isDown)
 *         .armourBreak(this::lowestDurability, armourPercent::getDouble, faceDamage::getDouble)
 *         .breakMinAge(ticksExisted::getInt)
 *         .inhibit(inhibitTicks::getInt)
 *         .build();
 * }</pre>
 *
 * <h2>How an option is judged, per target</h2>
 *
 * <ol>
 *   <li><b>Lethal</b>, if on: damage times the multiplier is at least what the
 *       target can take (its {@code Vitals} pool). A lethal option needs no
 *       minimum damage, and may ignore the self-damage cap if you said so.
 *   <li>Otherwise it must do at least the <b>minimum damage</b> &mdash; or the
 *       lower <b>faceplace</b> minimum, when the target is at or below the
 *       faceplace health or faceplacing is forced, or the <b>armour-break</b>
 *       minimum, when its most worn armour is at or below that durability.
 *   <li><b>Anti-suicide</b>, if on, refuses anything that would leave you within
 *       its margin of death, lethal or not.
 *   <li><b>Protection</b>, when the search protects anyone: refuses anything that
 *       hurts one of them more than the protected cap, or leaves one within the
 *       protected margin of death, lethal or not. The margin needs their health;
 *       when your {@code Vitals} do not trust it, only the cap protects them.
 *   <li>The <b>self-damage cap</b> refuses anything that hurts you more.
 * </ol>
 *
 * <p>Your {@code OptionFilter}s, if any, then have the last word.
 *
 * <p>Everything is off, or unlimited, unless you turn it on: the library has no
 * opinion on how aggressive your aura should be.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class Thresholds<E> {

    private final DoubleSupplier minDamage;
    private final DoubleSupplier maxSelfDamage;
    private final DoubleSupplier antiSuicideMargin;
    private final DoubleSupplier maxProtectedDamage;
    private final DoubleSupplier protectedMargin;
    private final DoubleSupplier lethalMultiplier;
    private final BooleanSupplier lethalIgnoresSelfCap;
    private final DoubleSupplier facePlaceHealth;
    private final DoubleSupplier facePlaceDamage;
    private final BooleanSupplier facePlaceForced;
    private final ToDoubleFunction<? super E> armourWear;
    private final DoubleSupplier armourBreakAt;
    private final DoubleSupplier armourBreakDamage;
    private final IntSupplier breakMinAge;
    private final IntSupplier inhibitTicks;

    private Thresholds(Builder<E> builder) {
        this.minDamage = builder.minDamage;
        this.maxSelfDamage = builder.maxSelfDamage;
        this.antiSuicideMargin = builder.antiSuicideMargin;
        this.maxProtectedDamage = builder.maxProtectedDamage;
        this.protectedMargin = builder.protectedMargin;
        this.lethalMultiplier = builder.lethalMultiplier;
        this.lethalIgnoresSelfCap = builder.lethalIgnoresSelfCap;
        this.facePlaceHealth = builder.facePlaceHealth;
        this.facePlaceDamage = builder.facePlaceDamage;
        this.facePlaceForced = builder.facePlaceForced;
        this.armourWear = builder.armourWear;
        this.armourBreakAt = builder.armourBreakAt;
        this.armourBreakDamage = builder.armourBreakDamage;
        this.breakMinAge = builder.breakMinAge;
        this.inhibitTicks = builder.inhibitTicks;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    /** No thresholds at all: anything in reach counts, nothing is refused. */
    public static <E> Thresholds<E> none() {
        return new Builder<E>().build();
    }

    public double minDamage() {
        return minDamage.getAsDouble();
    }

    /** @return the self-damage cap; infinite when there is none */
    public double maxSelfDamage() {
        return maxSelfDamage.getAsDouble();
    }

    /** @return how close to death an option may leave you; NaN when anti-suicide is off */
    public double antiSuicideMargin() {
        return antiSuicideMargin == null ? Double.NaN : antiSuicideMargin.getAsDouble();
    }

    /** @return the most damage an option may do to anyone the search protects; infinite when there is no cap */
    public double maxProtectedDamage() {
        return maxProtectedDamage.getAsDouble();
    }

    /** @return how close to death an option may leave anyone the search protects; NaN when off */
    public double protectedMargin() {
        return protectedMargin == null ? Double.NaN : protectedMargin.getAsDouble();
    }

    /** @return the lethal multiplier; NaN when lethal checks are off */
    public double lethalMultiplier() {
        return lethalMultiplier == null ? Double.NaN : lethalMultiplier.getAsDouble();
    }

    public boolean lethalIgnoresSelfCap() {
        return lethalIgnoresSelfCap.getAsBoolean();
    }

    /** @return whether faceplacing applies to a target with this much left */
    public boolean facePlaces(double pool) {
        if (facePlaceDamage == null) {
            return false;
        }
        return (facePlaceForced != null && facePlaceForced.getAsBoolean())
                || (facePlaceHealth != null && !Double.isNaN(pool) && pool <= facePlaceHealth.getAsDouble());
    }

    /** @return the minimum damage while faceplacing; NaN when faceplacing is off */
    public double facePlaceDamage() {
        return facePlaceDamage == null ? Double.NaN : facePlaceDamage.getAsDouble();
    }

    /** @return whether a target's armour is worn enough to break */
    public boolean breaksArmour(E target) {
        if (armourWear == null) {
            return false;
        }
        double wear = armourWear.applyAsDouble(target);
        return !Double.isNaN(wear) && wear <= armourBreakAt.getAsDouble();
    }

    /** @return the minimum damage while breaking armour; NaN when armour breaking is off */
    public double armourBreakDamage() {
        return armourWear == null ? Double.NaN : armourBreakDamage.getAsDouble();
    }

    /** @return how many ticks an explosive already in the world must have existed before it is set off */
    public int breakMinAge() {
        return breakMinAge.getAsInt();
    }

    /** @return how many ticks to leave an explosive alone after setting it off */
    public int inhibitTicks() {
        return inhibitTicks.getAsInt();
    }

    public static final class Builder<E> {

        private DoubleSupplier minDamage = () -> 0d;
        private DoubleSupplier maxSelfDamage = () -> Double.POSITIVE_INFINITY;
        private DoubleSupplier antiSuicideMargin;
        private DoubleSupplier maxProtectedDamage = () -> Double.POSITIVE_INFINITY;
        private DoubleSupplier protectedMargin;
        private DoubleSupplier lethalMultiplier;
        private BooleanSupplier lethalIgnoresSelfCap = () -> false;
        private DoubleSupplier facePlaceHealth;
        private DoubleSupplier facePlaceDamage;
        private BooleanSupplier facePlaceForced;
        private ToDoubleFunction<? super E> armourWear;
        private DoubleSupplier armourBreakAt;
        private DoubleSupplier armourBreakDamage;
        private IntSupplier breakMinAge = () -> 0;
        private IntSupplier inhibitTicks = () -> 0;

        private Builder() {
        }

        /** The least damage to a target worth an explosion. 0 unless set. */
        public Builder<E> minDamage(DoubleSupplier damage) {
            this.minDamage = Validate.notNull(damage, "damage");
            return this;
        }

        /** The most damage to yourself any option may do. Unlimited unless set. */
        public Builder<E> maxSelfDamage(DoubleSupplier damage) {
            this.maxSelfDamage = Validate.notNull(damage, "damage");
            return this;
        }

        /**
         * Refuses any option that would leave you with {@code margin} or less of your
         * {@code Vitals} pool, lethal or not. Off unless set.
         */
        public Builder<E> antiSuicide(DoubleSupplier margin) {
            this.antiSuicideMargin = Validate.notNull(margin, "margin");
            return this;
        }

        /**
         * The most damage any option may do to any one entity the search protects,
         * lethal or not. Unlimited unless set; protects nobody until the search is
         * told who, with {@code protect}.
         */
        public Builder<E> maxProtectedDamage(DoubleSupplier damage) {
            this.maxProtectedDamage = Validate.notNull(damage, "damage");
            return this;
        }

        /**
         * Refuses any option that would leave anyone the search protects with
         * {@code margin} or less of their {@code Vitals} pool, lethal or not. Off
         * unless set. Their health must be trusted for this to apply; otherwise only
         * {@link #maxProtectedDamage} protects them.
         */
        public Builder<E> protectedMargin(DoubleSupplier margin) {
            this.protectedMargin = Validate.notNull(margin, "margin");
            return this;
        }

        /**
         * Treats an option as lethal when its damage times {@code multiplier} is at
         * least the target's pool: it then needs no minimum damage.
         *
         * @param ignoreSelfCap whether a lethal option may also hurt you more than
         *        the self-damage cap; anti-suicide still applies
         */
        public Builder<E> lethal(DoubleSupplier multiplier, boolean ignoreSelfCap) {
            return lethal(multiplier, () -> ignoreSelfCap);
        }

        /** {@link #lethal(DoubleSupplier, boolean)}, with whether a kill ignores the cap read live too. */
        public Builder<E> lethal(DoubleSupplier multiplier, BooleanSupplier ignoreSelfCap) {
            this.lethalMultiplier = Validate.notNull(multiplier, "multiplier");
            this.lethalIgnoresSelfCap = Validate.notNull(ignoreSelfCap, "ignoreSelfCap");
            return this;
        }

        /**
         * Lowers the minimum damage to {@code minDamage} against a target at or below
         * {@code health} of its pool.
         */
        public Builder<E> facePlace(DoubleSupplier health, DoubleSupplier minDamage) {
            this.facePlaceHealth = Validate.notNull(health, "health");
            this.facePlaceDamage = Validate.notNull(minDamage, "minDamage");
            return this;
        }

        /** Faceplaces every target while {@code forced} is true: a keybind, say. Needs {@link #facePlace}. */
        public Builder<E> facePlaceWhen(BooleanSupplier forced) {
            this.facePlaceForced = Validate.notNull(forced, "forced");
            return this;
        }

        /**
         * Lowers the minimum damage to {@code minDamage} against a target whose most
         * worn armour is at or below {@code atOrBelow}.
         *
         * @param wear how worn a target's most worn piece is, in your units &mdash;
         *        a fraction of durability left, say; NaN when unknown
         */
        public Builder<E> armourBreak(ToDoubleFunction<? super E> wear, DoubleSupplier atOrBelow, DoubleSupplier minDamage) {
            this.armourWear = Validate.notNull(wear, "wear");
            this.armourBreakAt = Validate.notNull(atOrBelow, "atOrBelow");
            this.armourBreakDamage = Validate.notNull(minDamage, "minDamage");
            return this;
        }

        /**
         * Leaves explosives younger than this many ticks alone. 0 unless set. Spawn
         * breaks ignore it, and a bed, whose age the game does not say, always passes.
         */
        public Builder<E> breakMinAge(IntSupplier ticks) {
            this.breakMinAge = Validate.notNull(ticks, "ticks");
            return this;
        }

        /** After you set an explosive off, leaves it alone for this many ticks. 0 unless set. */
        public Builder<E> inhibit(IntSupplier ticks) {
            this.inhibitTicks = Validate.notNull(ticks, "ticks");
            return this;
        }

        public Thresholds<E> build() {
            if (facePlaceForced != null && facePlaceDamage == null) {
                throw new IllegalStateException("facePlaceWhen needs facePlace's minimum damage");
            }
            return new Thresholds<>(this);
        }
    }
}
