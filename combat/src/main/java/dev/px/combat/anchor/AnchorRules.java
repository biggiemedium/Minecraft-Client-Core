package dev.px.combat.anchor;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;

import java.util.function.BooleanSupplier;

/**
 * Everything about respawn anchors as explosives that depends on the game
 * version, in one place, built from small rules you supply: the counterpart of
 * {@code CrystalRules} and {@code BedRules}.
 *
 * <pre>{@code
 * AnchorRules<LivingEntity> anchors = AnchorRules.<LivingEntity>builder()
 *         .placement(AnchorPlacement.clearance(myBlocks::isReplaceable))
 *         .explodingAt(0.5, 0.5, 0.5)                      // from the block's corner
 *         .explodesWhen(() -> !myWorld.anchorsWork())      // the Overworld, the End, ...
 *         .lookup(myBlocks::anchorChargesAt)               // anchors already in the world
 *         .explosive(Explosive.of("respawn anchor", 5))
 *         .model(myExplosionModel)                         // shared with crystals and beds
 *         .blocks(myBlocks)
 *         .obstructions(Obstructions.of(Core.entities(), players))
 *         .build();
 * }</pre>
 *
 * <h2>What the wiki gives, for current Java</h2>
 *
 * <ul>
 *   <li>Using a glowstone block on an anchor adds a charge, up to four.
 *   <li>Using a <em>charged</em> anchor in the Overworld, the End, or a dimension
 *       where anchors are disabled makes it explode, power 5, setting fire; the
 *       anchor is destroyed, "similar to when a bed is used in the Nether".
 *   <li>Shields block its explosion since 1.19.3: your {@code Mitigation}'s.
 * </ul>
 *
 * <p>The wiki does not say where the explosion is centred, nor which held items
 * charge an anchor rather than set it off: those are your rules and your module's.
 * Only your game knows which dimension you are in.
 *
 * <h2>The anchor is gone when it explodes</h2>
 *
 * <p>The explosion starts inside a whole block. Asked of the world as it stands,
 * every ray would end inside the anchor and every explosion would look harmless,
 * so every prediction here is made against {@link #blocksWhenFired}: your blocks
 * with the anchor's cell empty.
 *
 * <p>Immutable and safe to share. Ask on the game thread, where the world is.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class AnchorRules<E> {

    private final AnchorPlacement placement;
    private final double explosionX;
    private final double explosionY;
    private final double explosionZ;
    private final BooleanSupplier explodes;
    private final AnchorLookup lookup;
    private final Explosive explosive;
    private final ExplosionModel<E> model;
    private final BlockView blocks;
    private final Obstructions obstructions;

    private AnchorRules(Builder<E> builder) {
        this.placement = builder.placement;
        this.explosionX = builder.explosionX;
        this.explosionY = builder.explosionY;
        this.explosionZ = builder.explosionZ;
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

    /** @return whether anchors explode here now; when not, none is worth placing or using */
    public boolean explodes() {
        return explodes.getAsBoolean();
    }

    /** @return whether an anchor can be placed into {@code cell} now */
    public boolean canPlace(Vec3i cell) {
        Validate.notNull(cell, "cell");
        return placement.canPlace(cell, obstructions);
    }

    /** @return the anchor in this cell, or null if there is none */
    public Anchor anchorAt(int x, int y, int z) {
        int charges = lookup.chargesAt(x, y, z);
        return charges < 0 ? null : Anchor.of(Vec3i.of(x, y, z), charges);
    }

    // ------------------------------------------------------------- damage

    /** @return where an anchor in {@code cell} explodes from */
    public Vec3 origin(Vec3i cell) {
        return Vec3.of(cell.getX() + explosionX, cell.getY() + explosionY, cell.getZ() + explosionZ);
    }

    /** @return your blocks as the explosion of an anchor in {@code cell} sees them: with the anchor gone */
    public BlockView blocksWhenFired(Vec3i cell) {
        return blocks.without(cell);
    }

    /** @return what an anchor in {@code cell}, placed or not, would do to {@code target} if set off now, step by step */
    public DamageEstimate estimate(Vec3i cell, Tracked<? extends E> target) {
        Validate.notNull(cell, "cell");
        return model.estimate(origin(cell), explosive, target, blocksWhenFired(cell));
    }

    /** @return the damage an anchor in {@code cell} would do to {@code target} if set off now */
    public double damage(Vec3i cell, Tracked<? extends E> target) {
        return estimate(cell, target).getDamage();
    }

    // ------------------------------------------------------------ the rules

    public AnchorPlacement getPlacement() {
        return placement;
    }

    public AnchorLookup getLookup() {
        return lookup;
    }

    public Explosive getExplosive() {
        return explosive;
    }

    public ExplosionModel<E> getModel() {
        return model;
    }

    /** @return your blocks, as they are: with every anchor in them */
    public BlockView getBlocks() {
        return blocks;
    }

    /** @return how far an anchor hurts anything, from where it explodes */
    public double getRange() {
        return model.getFalloff().range(explosive.getPower());
    }

    public static final class Builder<E> {

        private AnchorPlacement placement;
        private double explosionX;
        private double explosionY;
        private double explosionZ;
        private boolean exploding;
        private BooleanSupplier explodes;
        private AnchorLookup lookup;
        private Explosive explosive;
        private ExplosionModel<E> model;
        private BlockView blocks;
        private Obstructions obstructions;

        private Builder() {
        }

        /** Required. */
        public Builder<E> placement(AnchorPlacement placement) {
            this.placement = Validate.notNull(placement, "placement");
            return this;
        }

        /** Required: where the explosion starts, from the anchor block's lowest corner. */
        public Builder<E> explodingAt(double x, double y, double z) {
            this.explosionX = x;
            this.explosionY = y;
            this.explosionZ = z;
            this.exploding = true;
            return this;
        }

        /** Required: whether anchors explode where you are, read live. Your game knows the dimension; Core does not. */
        public Builder<E> explodesWhen(BooleanSupplier explodes) {
            this.explodes = Validate.notNull(explodes, "explodes");
            return this;
        }

        /** Required: how anchors already in the world are found, with their charges. */
        public Builder<E> lookup(AnchorLookup lookup) {
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

        /** Required: your blocks, anchors included; predictions take each anchor's own cell out themselves. */
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
        public AnchorRules<E> build() {
            StringBuilder missing = new StringBuilder();
            if (placement == null) {
                missing.append(" placement");
            }
            if (!exploding) {
                missing.append(" explodingAt");
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
                throw new IllegalStateException("AnchorRules needs every rule; missing:" + missing);
            }
            return new AnchorRules<>(this);
        }
    }
}
