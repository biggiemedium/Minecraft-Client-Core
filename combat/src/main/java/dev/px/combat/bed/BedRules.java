package dev.px.combat.bed;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Obstructions;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Everything about beds as explosives that depends on the game version, in one
 * place, built from small rules you supply: the counterpart of
 * {@code CrystalRules}.
 *
 * <pre>{@code
 * BedRules<LivingEntity> beds = BedRules.<LivingEntity>builder()
 *         .placement(BedPlacement.clearance(myBlocks::isReplaceable, 0.5625, 0.5625))
 *         .explodingAt(0.5, 0.5, 0.5)                      // from the head block's corner
 *         .usableFrom(BedPart.FOOT, BedPart.HEAD)          // which halves set it off
 *         .explodesWhen(() -> !myWorld.bedsWork())         // the Nether, the End, ...
 *         .lookup(myBlocks::bedHeadAt)                     // beds already in the world
 *         .explosive(Explosive.of("bed", 5))
 *         .model(myExplosionModel)                         // shared with crystals
 *         .blocks(myBlocks)
 *         .obstructions(Obstructions.of(Core.entities(), Core.entities().get(EverythingTracker.class)))
 *         .build();
 *
 * Bed bed = Bed.of(Vec3i.of(x, y, z), Direction.NORTH);
 * if (beds.explodes() && beds.canPlace(bed)) {
 *     double toThem = beds.damage(bed, target);
 * }
 * }</pre>
 *
 * <h2>What the wiki gives, for current Java</h2>
 *
 * <ul>
 *   <li>A bed takes two blocks: the foot on the block you select, the head one
 *       further on in the direction you face. It needs no blocks beneath it.
 *   <li>Using a bed in the Nether, the End, or a dimension where beds are
 *       disabled makes it explode, with power 5, centred on the head.
 * </ul>
 *
 * <p>Each of those is a rule here rather than a fact in the code: the support
 * rule has already changed twice, Bedrock differs, and only your game knows
 * which dimension you are in.
 *
 * <h2>The bed is gone when it explodes</h2>
 *
 * <p>The explosion starts inside the head, and a bed is a solid half block. Asked
 * of the world as it stands, every ray would end inside the bed and every
 * explosion would look harmless. So every prediction here is made against
 * {@link #blocksWhenFired}: your blocks with the bed's own two cells empty.
 *
 * <p>Immutable and safe to share. Ask on the game thread, where the world is.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class BedRules<E> {

    private final BedPlacement placement;
    private final double explosionX;
    private final double explosionY;
    private final double explosionZ;
    private final Set<BedPart> usable;
    private final BooleanSupplier explodes;
    private final BedLookup lookup;
    private final Explosive explosive;
    private final ExplosionModel<E> model;
    private final BlockView blocks;
    private final Obstructions obstructions;

    private BedRules(Builder<E> builder) {
        this.placement = builder.placement;
        this.explosionX = builder.explosionX;
        this.explosionY = builder.explosionY;
        this.explosionZ = builder.explosionZ;
        this.usable = Collections.unmodifiableSet(EnumSet.copyOf(builder.usable));
        this.explodes = builder.explodes;
        this.lookup = builder.lookup;
        this.explosive = builder.explosive;
        this.model = builder.model;
        this.blocks = builder.blocks;
        this.obstructions = builder.obstructions;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    // ------------------------------------------------------------ placing

    /** @return whether beds explode here now; when not, no bed is worth placing or using */
    public boolean explodes() {
        return explodes.getAsBoolean();
    }

    /** @return whether {@code bed} can be placed now */
    public boolean canPlace(Bed bed) {
        Validate.notNull(bed, "bed");
        return placement.canPlace(bed, obstructions);
    }

    /** @return whether a bed with its foot here, facing {@code facing}, can be placed now */
    public boolean canPlace(int x, int y, int z, Direction facing) {
        return canPlace(Bed.of(Vec3i.of(x, y, z), facing));
    }

    /** @return the bed whose head is in this cell, or null if there is none */
    public Bed bedAt(int x, int y, int z) {
        Direction facing = lookup.headAt(x, y, z);
        return facing == null ? null : Bed.ofHead(Vec3i.of(x, y, z), facing);
    }

    /** @return whether using this half sets a bed off */
    public boolean isUsable(BedPart part) {
        return usable.contains(part);
    }

    // ------------------------------------------------------------- damage

    /** @return where {@code bed} explodes from */
    public Vec3 origin(Bed bed) {
        Vec3i head = bed.getHead();
        return Vec3.of(head.getX() + explosionX, head.getY() + explosionY, head.getZ() + explosionZ);
    }

    /** @return your blocks as {@code bed}'s explosion sees them: with the bed gone */
    public BlockView blocksWhenFired(Bed bed) {
        return blocks.without(bed.getFoot(), bed.getHead());
    }

    /** @return what {@code bed}, placed or not, would do to {@code target} if set off now, step by step */
    public DamageEstimate estimate(Bed bed, Tracked<? extends E> target) {
        Validate.notNull(bed, "bed");
        return model.estimate(origin(bed), explosive, target, blocksWhenFired(bed));
    }

    /** @return the damage {@code bed} would do to {@code target} if set off now */
    public double damage(Bed bed, Tracked<? extends E> target) {
        return estimate(bed, target).getDamage();
    }

    // ------------------------------------------------------------ the rules

    public BedPlacement getPlacement() {
        return placement;
    }

    /** @return the halves that set a bed off when used */
    public Set<BedPart> getUsable() {
        return usable;
    }

    public BedLookup getLookup() {
        return lookup;
    }

    public Explosive getExplosive() {
        return explosive;
    }

    public ExplosionModel<E> getModel() {
        return model;
    }

    /** @return your blocks, as they are: with every bed in them */
    public BlockView getBlocks() {
        return blocks;
    }

    /** @return how far a bed hurts anything, from where it explodes */
    public double getRange() {
        return model.getFalloff().range(explosive.getPower());
    }

    public static final class Builder<E> {

        private BedPlacement placement;
        private double explosionX;
        private double explosionY;
        private double explosionZ;
        private boolean exploding;
        private Set<BedPart> usable;
        private BooleanSupplier explodes;
        private BedLookup lookup;
        private Explosive explosive;
        private ExplosionModel<E> model;
        private BlockView blocks;
        private Obstructions obstructions;

        private Builder() {
        }

        /** Required. */
        public Builder<E> placement(BedPlacement placement) {
            this.placement = Validate.notNull(placement, "placement");
            return this;
        }

        /** Required: where the explosion starts, from the head block's lowest corner. */
        public Builder<E> explodingAt(double x, double y, double z) {
            this.explosionX = x;
            this.explosionY = y;
            this.explosionZ = z;
            this.exploding = true;
            return this;
        }

        /** Required: which halves set a bed off when used. */
        public Builder<E> usableFrom(BedPart... parts) {
            Validate.check(parts.length > 0, "at least one half must be usable");
            this.usable = EnumSet.copyOf(Arrays.asList(parts));
            return this;
        }

        /** Required: whether beds explode where you are, read live. Your game knows the dimension; Core does not. */
        public Builder<E> explodesWhen(BooleanSupplier explodes) {
            this.explodes = Validate.notNull(explodes, "explodes");
            return this;
        }

        /** Required: how beds already in the world are found. */
        public Builder<E> lookup(BedLookup lookup) {
            this.lookup = Validate.notNull(lookup, "lookup");
            return this;
        }

        /** Required. */
        public Builder<E> explosive(Explosive explosive) {
            this.explosive = Validate.notNull(explosive, "explosive");
            return this;
        }

        /** Required. */
        public Builder<E> model(ExplosionModel<E> model) {
            this.model = Validate.notNull(model, "model");
            return this;
        }

        /** Required: your blocks, beds included; predictions take each bed's own cells out themselves. */
        public Builder<E> blocks(BlockView blocks) {
            this.blocks = Validate.notNull(blocks, "blocks");
            return this;
        }

        /** Required; {@link Obstructions#NONE} to say entities never block a placement. */
        public Builder<E> obstructions(Obstructions obstructions) {
            this.obstructions = Validate.notNull(obstructions, "obstructions");
            return this;
        }

        /** @throws IllegalStateException naming each rule not given */
        public BedRules<E> build() {
            StringBuilder missing = new StringBuilder();
            if (placement == null) {
                missing.append(" placement");
            }
            if (!exploding) {
                missing.append(" explodingAt");
            }
            if (usable == null) {
                missing.append(" usableFrom");
            }
            if (explodes == null) {
                missing.append(" explodesWhen");
            }
            if (lookup == null) {
                missing.append(" lookup");
            }
            if (explosive == null) {
                missing.append(" explosive");
            }
            if (model == null) {
                missing.append(" model");
            }
            if (blocks == null) {
                missing.append(" blocks");
            }
            if (obstructions == null) {
                missing.append(" obstructions");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("BedRules needs every rule; missing:" + missing);
            }
            return new BedRules<>(this);
        }
    }
}
