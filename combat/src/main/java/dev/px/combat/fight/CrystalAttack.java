package dev.px.combat.fight;

import dev.px.combat.search.CrystalSearch;
import dev.px.combat.search.option.BreakOption;
import dev.px.combat.search.option.PlaceOption;
import dev.px.core.control.Click;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.function.BooleanSupplier;

/**
 * A ready-made {@link Attack} on a {@link CrystalSearch}: breaks the crystal the
 * search finds worth breaking, otherwise places one where it finds worth placing.
 *
 * <pre>{@code
 * CrystalAttack<EntityPlayer> crystals = CrystalAttack.<EntityPlayer>builder()
 *         .search(search)                                // the same search your AutoCrystal uses
 *         .placing(() -> holding(Items.END_CRYSTAL))     // yours: whether a use places a crystal now
 *         .breaking(breakSetting::get)                   // optional; on by default
 *         .build();
 *
 * Fight<EntityPlayer> fight = Fight.<EntityPlayer>builder().attack(crystals).build();
 * }</pre>
 *
 * <p>One action a tick, breaking first: a break is an attack on the crystal,
 * clicked once the head looks at its box; a place is a use on the block the
 * search's option clicks, once the head looks at that block. The head is turned
 * to the option's {@linkplain dev.px.combat.search.option.Option#getAim aim}. The
 * search is told what went out only when it did &mdash; {@code attacked} for a
 * break, {@code placed} for a place &mdash; so a click a higher claim took never
 * inhibits a crystal or reserves a base.
 *
 * <p>It never holds an item. Whether a use places a crystal right now is a fact
 * about your inventory, so {@link Builder#placing} is yours, and nothing is
 * placed until you say. The search's targets are its own: the fight's target
 * says only when the fight is over.
 *
 * <p>The search must be ticked as it always is, by its bus or by you.
 *
 * <p>Immutable settings; it remembers the option it last offered, until it hears
 * whether it went out. Game thread only.
 *
 * @param <E> the game's type for what is fought
 */
public final class CrystalAttack<E> implements Attack<E> {

    private final CrystalSearch<E> search;
    private final BooleanSupplier placing;
    private final BooleanSupplier breaking;

    private Strike offered;
    private BreakOption<E> breakOffered;
    private PlaceOption<E> placeOffered;
    private int breaks;
    private int places;

    private CrystalAttack(Builder<E> builder) {
        this.search = builder.search;
        this.placing = builder.placing;
        this.breaking = builder.breaking;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    @Override
    public Strike tick(Bout<? extends E> b) {
        offered = null;
        breakOffered = null;
        placeOffered = null;
        if (breaking.getAsBoolean()) {
            BreakOption<E> hit = search.findBreak();
            if (hit != null) {
                breakOffered = hit;
                offered = Strike.at(hit.getAim()).click(Click.ATTACK).whenFacing(hit.getCrystal().getBox());
                return offered;
            }
        }
        if (placing.getAsBoolean()) {
            PlaceOption<E> spot = search.findPlace();
            if (spot != null) {
                placeOffered = spot;
                Vec3i block = spot.getClick() != null ? spot.getClick().getBlock()
                        : Vec3i.of(spot.getX(), spot.getY(), spot.getZ());
                offered = Strike.at(spot.getAim()).click(Click.USE)
                        .whenFacing(Box.of(block.getX(), block.getY(), block.getZ(),
                                block.getX() + 1, block.getY() + 1, block.getZ() + 1));
                return offered;
            }
        }
        return Strike.none();
    }

    @Override
    public void struck(Bout<? extends E> b, Strike strike) {
        if (strike != offered) {
            return;
        }
        if (breakOffered != null) {
            search.attacked(breakOffered.getCrystal());
            breaks++;
        } else if (placeOffered != null) {
            search.placed(placeOffered);
            places++;
        }
        offered = null;
    }

    /** @return crystals attacked since it was built */
    public int getBreaks() {
        return breaks;
    }

    /** @return crystals placed since it was built */
    public int getPlaces() {
        return places;
    }

    /**
     * Builds a {@link CrystalAttack}. Needs the search and your rule for
     * whether a use places a crystal.
     *
     * @param <E> the game's type for what is fought
     */
    public static final class Builder<E> {

        private CrystalSearch<E> search;
        private BooleanSupplier placing;
        private BooleanSupplier breaking = () -> true;

        private Builder() {
        }

        public Builder<E> search(CrystalSearch<E> search) {
            this.search = Validate.notNull(search, "search");
            return this;
        }

        /** Whether a use places a crystal right now, read each tick: crystals in hand. Yours; the library never holds an item. */
        public Builder<E> placing(BooleanSupplier placing) {
            this.placing = Validate.notNull(placing, "placing");
            return this;
        }

        /** Whether crystals are broken, read each tick. On by default. */
        public Builder<E> breaking(BooleanSupplier breaking) {
            this.breaking = Validate.notNull(breaking, "breaking");
            return this;
        }

        public CrystalAttack<E> build() {
            StringBuilder missing = new StringBuilder();
            if (search == null) {
                missing.append(", search");
            }
            if (placing == null) {
                missing.append(", placing");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("a CrystalAttack needs: " + missing.substring(2));
            }
            return new CrystalAttack<>(this);
        }
    }
}
