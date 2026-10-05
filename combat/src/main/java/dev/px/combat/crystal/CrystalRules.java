package dev.px.combat.crystal;

import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Obstructions;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

/**
 * Everything about end crystals that depends on the game version, in one place,
 * built from small rules you supply.
 *
 * <pre>{@code
 * CrystalRules<EntityLivingBase> crystals = CrystalRules.<EntityLivingBase>builder()
 *         .placement(Placement.clearance(myBlocks::isCrystalBase, 2, myBlocks::isReplaceable, 2.0))
 *         .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0))
 *         .explosive(Explosive.of("end crystal", 6))
 *         .model(myExplosionModel)                 // shared with anchors and beds
 *         .blocks(myBlocks)
 *         .obstructions(Obstructions.of(Core.entities(), Core.entities().get(EverythingTracker.class)))
 *         .build();
 *
 * if (crystals.canPlace(x, y, z)) {
 *     double toThem = crystals.damage(x, y, z, target);
 *     double toMe   = crystals.damage(x, y, z, Core.entities().getSelf());
 * }
 * }</pre>
 *
 * <h2>What is yours, and why</h2>
 *
 * <table summary="">
 *   <tr><td>{@link Placement}</td><td>which blocks hold a crystal, how much clear space it needs</td></tr>
 *   <tr><td>{@link CrystalBody}</td><td>where it sits, its hitbox, where it explodes from</td></tr>
 *   <tr><td>{@link Explosive}</td><td>its power</td></tr>
 *   <tr><td>{@link ExplosionModel}</td><td>how an explosion hurts: exposure, falloff, mitigation</td></tr>
 *   <tr><td>{@link BlockView}, {@link Obstructions}</td><td>your world's blocks and entities</td></tr>
 * </table>
 *
 * <p>Every one is required, and every one is a small thing: a version that
 * changes how crystals or explosions work changes the one rule it touched. The
 * search, scoring and timing built on this never change for a version.
 *
 * <p>This answers questions about single explosions. It does not decide what
 * several do together: the wiki gives current Java as applying only the highest
 * explosion damage a target takes in a tick, which is for whatever plans more
 * than one explosion at once to respect.
 *
 * <p>Immutable and safe to share. Ask on the game thread, where the world is.
 *
 * @param <E> the game's type for what can be hurt
 */
public final class CrystalRules<E> {

    private final Placement placement;
    private final CrystalBody body;
    private final Explosive explosive;
    private final ExplosionModel<E> model;
    private final BlockView blocks;
    private final Obstructions obstructions;

    private CrystalRules(Builder<E> builder) {
        this.placement = builder.placement;
        this.body = builder.body;
        this.explosive = builder.explosive;
        this.model = builder.model;
        this.blocks = builder.blocks;
        this.obstructions = builder.obstructions;
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    // ------------------------------------------------------------ placing

    /** @return whether a crystal can be placed on this base block right now */
    public boolean canPlace(int x, int y, int z) {
        return placement.canPlace(x, y, z, obstructions);
    }

    /** @return where a crystal placed on this base block would be */
    public Vec3 position(int x, int y, int z) {
        return body.position(x, y, z);
    }

    /** @return the hitbox a crystal placed on this base block would have */
    public Box box(int x, int y, int z) {
        return body.box(x, y, z);
    }

    /** @return where a crystal placed on this base block would explode from */
    public Vec3 origin(int x, int y, int z) {
        return body.explosion(x, y, z);
    }

    // ------------------------------------------------------------- damage

    /** @return what a crystal placed on this base block would do to {@code target}, step by step */
    public DamageEstimate estimate(int x, int y, int z, Tracked<? extends E> target) {
        return model.estimate(origin(x, y, z), explosive, target, blocks);
    }

    /** @return the damage a crystal placed on this base block would do to {@code target} */
    public double damage(int x, int y, int z, Tracked<? extends E> target) {
        return estimate(x, y, z, target).getDamage();
    }

    /** @return where the crystal already at {@code crystal} explodes from */
    public Vec3 origin(Tracked<?> crystal) {
        Validate.notNull(crystal, "crystal");
        return body.explosionOf(crystal.getPosition());
    }

    /** @return what the crystal already at {@code crystal} would do to {@code target} if it went off now */
    public DamageEstimate estimate(Tracked<?> crystal, Tracked<? extends E> target) {
        return model.estimate(origin(crystal), explosive, target, blocks);
    }

    /** @return the damage the crystal already at {@code crystal} would do to {@code target} */
    public double damage(Tracked<?> crystal, Tracked<? extends E> target) {
        return estimate(crystal, target).getDamage();
    }

    // ------------------------------------------------------------ the rules

    public Placement getPlacement() {
        return placement;
    }

    public CrystalBody getBody() {
        return body;
    }

    public Explosive getExplosive() {
        return explosive;
    }

    public ExplosionModel<E> getModel() {
        return model;
    }

    public BlockView getBlocks() {
        return blocks;
    }

    /** @return how far a crystal hurts anything, from where it explodes */
    public double getRange() {
        return model.getFalloff().range(explosive.getPower());
    }

    public static final class Builder<E> {

        private Placement placement;
        private CrystalBody body;
        private Explosive explosive;
        private ExplosionModel<E> model;
        private BlockView blocks;
        private Obstructions obstructions;

        private Builder() {
        }

        /** Required. */
        public Builder<E> placement(Placement placement) {
            this.placement = Validate.notNull(placement, "placement");
            return this;
        }

        /** Required, with its size and explosion point given. */
        public Builder<E> body(CrystalBody body) {
            this.body = Validate.notNull(body, "body");
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

        /** Required. */
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
        public CrystalRules<E> build() {
            StringBuilder missing = new StringBuilder();
            if (placement == null) {
                missing.append(" placement");
            }
            if (body == null) {
                missing.append(" body");
            } else if (!body.isComplete()) {
                missing.append(" body.size/explodingAt");
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
                throw new IllegalStateException("CrystalRules needs every rule; missing:" + missing);
            }
            return new CrystalRules<>(this);
        }
    }
}
