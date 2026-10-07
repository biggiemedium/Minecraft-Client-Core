package dev.px.projectile;

import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * How one kind of projectile flies, in your game: how hard gravity pulls it, how
 * much speed it keeps each tick, the order it does those in, and how much bigger
 * than they are it finds the entities it can hit.
 *
 * <pre>{@code
 * // From the wiki's Entity#Motion and Projectile#Collision, for current Java
 * ProjectileRules arrow = ProjectileRules.builder("arrow")
 *         .gravity(0.05)
 *         .drag(DragRule.inside((x, y, z) -> Game.isWater(x, y, z), 0.6f, 0.99f))
 *         .order(StepOrder.POSITION_DRAG_ACCELERATION)
 *         .entityMargin(0)
 *         .build();
 *
 * ProjectileRules pearl = ProjectileRules.builder("pearl")
 *         .gravity(0.03)
 *         .drag(0.99f)
 *         .order(StepOrder.ACCELERATION_DRAG_POSITION)          // POSITION_DRAG_ACCELERATION before 1.21.2
 *         .entityMargin(0.3)
 *         .build();
 * }</pre>
 *
 * <p>The library has no list of projectiles: you make one of these for each
 * thing your client throws or shoots, from your game's numbers. The
 * <a href="https://minecraft.wiki/w/Entity#Motion">wiki</a> gives gravity, drag
 * and order for each projectile in current Java, and
 * <a href="https://minecraft.wiki/w/Projectile#Entity_collision">Projectile</a>
 * says thrown ones find entities 0.3 bigger on every side while arrows and
 * tridents do not. It marks drag as a {@code float}: write {@code 0.99f}, as the
 * game multiplies by the float, to follow it exactly.
 *
 * <p>Immutable.
 */
@Getter
public final class ProjectileRules {

    /** What you call it, for logs and debugging. */
    private final String name;
    /** Taken off the vertical velocity at the acceleration step, in blocks per tick per tick: positive falls. */
    private final double gravity;
    private final DragRule drag;
    private final StepOrder order;
    /** How far out from every side of an entity's box it hits that entity, in blocks. */
    private final double entityMargin;

    private ProjectileRules(Builder builder) {
        this.name = builder.name;
        this.gravity = builder.gravity;
        this.drag = builder.drag;
        this.order = builder.order;
        this.entityMargin = builder.entityMargin;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String toString() {
        return "ProjectileRules(" + name + ", gravity " + gravity + ", " + order + ", margin " + entityMargin + ")";
    }

    public static final class Builder {

        private final String name;
        private double gravity = Double.NaN;
        private DragRule drag;
        private StepOrder order;
        private double entityMargin = Double.NaN;

        private Builder(String name) {
            this.name = Validate.notBlank(name, "name");
        }

        /**
         * Required: taken off the vertical velocity each tick. Positive pulls it
         * down; the wiki's table writes it as a negative acceleration.
         */
        public Builder gravity(double blocksPerTickSquared) {
            Validate.check(!Double.isNaN(blocksPerTickSquared) && !Double.isInfinite(blocksPerTickSquared),
                    "gravity must be a number");
            this.gravity = blocksPerTickSquared;
            return this;
        }

        /** Required: the velocity is multiplied by this each tick, everywhere. */
        public Builder drag(double drag) {
            this.drag = DragRule.constant(drag);
            return this;
        }

        /** Required, or {@link #drag(double)}: drag that depends on where it is. */
        public Builder drag(DragRule drag) {
            this.drag = Validate.notNull(drag, "drag");
            return this;
        }

        /** Required: the order it accelerates, drags and moves in each tick. */
        public Builder order(StepOrder order) {
            this.order = Validate.notNull(order, "order");
            return this;
        }

        /**
         * Required: how far out from every side of an entity's box it hits that
         * entity. 0 for a box as it is.
         */
        public Builder entityMargin(double blocks) {
            Validate.check(blocks >= 0d, "the entity margin can not be negative");
            this.entityMargin = blocks;
            return this;
        }

        /** @throws IllegalStateException naming each required part not given */
        public ProjectileRules build() {
            StringBuilder missing = new StringBuilder();
            if (Double.isNaN(gravity)) {
                missing.append(" gravity");
            }
            if (drag == null) {
                missing.append(" drag");
            }
            if (order == null) {
                missing.append(" order");
            }
            if (Double.isNaN(entityMargin)) {
                missing.append(" entityMargin");
            }
            if (missing.length() > 0) {
                throw new IllegalStateException("ProjectileRules (" + name + ") need:" + missing);
            }
            return new ProjectileRules(this);
        }
    }
}
