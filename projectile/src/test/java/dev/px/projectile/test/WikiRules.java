package dev.px.projectile.test;

import dev.px.projectile.DragRule;
import dev.px.projectile.LaunchOrigin;
import dev.px.projectile.LaunchRules;
import dev.px.projectile.ProjectileRules;
import dev.px.projectile.ShooterVelocity;
import dev.px.projectile.StepOrder;
import dev.px.core.world.CellTest;

/**
 * The wiki's numbers for current Java, written as a client would write them:
 * the library ships none of these.
 *
 * <p>Motion from https://minecraft.wiki/w/Entity#Motion, collision from
 * https://minecraft.wiki/w/Projectile#Collision, launches from
 * https://minecraft.wiki/w/Projectile#Initial_conditions and
 * https://minecraft.wiki/w/Arrow#Movement. Where a player's projectile appears
 * is not on the wiki; these tests put it 0.1 under the eyes.
 */
final class WikiRules {

    static final double SPREAD = 0.0172275d;

    static final ProjectileRules ARROW = arrow(DragRule.constant(0.99f));

    static final ProjectileRules PEARL = ProjectileRules.builder("pearl")
            .gravity(0.03).drag(0.99f).order(StepOrder.ACCELERATION_DRAG_POSITION).entityMargin(0.3).build();

    /** A pearl before 1.21.2, which moved before it accelerated and dragged. */
    static final ProjectileRules OLD_PEARL = ProjectileRules.builder("pearl before 1.21.2")
            .gravity(0.03).drag(0.99f).order(StepOrder.POSITION_DRAG_ACCELERATION).entityMargin(0.3).build();

    static final ProjectileRules POTION = ProjectileRules.builder("potion")
            .gravity(0.05).drag(0.99f).order(StepOrder.ACCELERATION_DRAG_POSITION).entityMargin(0.3).build();

    static final ProjectileRules BOTTLE = ProjectileRules.builder("experience bottle")
            .gravity(0.07).drag(0.99f).order(StepOrder.ACCELERATION_DRAG_POSITION).entityMargin(0.3).build();

    static final ProjectileRules SPIT = ProjectileRules.builder("llama spit")
            .gravity(0.06).drag(0.99f).order(StepOrder.POSITION_DRAG_ACCELERATION).entityMargin(0).build();

    static final LaunchRules THROW = LaunchRules.builder()
            .power(1.5).spread(SPREAD)
            .shooterVelocity(ShooterVelocity.ALL_BUT_VERTICAL_ON_GROUND)
            .origin(LaunchOrigin.below(0.1)).build();

    static final LaunchRules POTION_THROW = LaunchRules.builder()
            .power(0.5).pitchOffset(-20f).spread(SPREAD)
            .shooterVelocity(ShooterVelocity.ALL_BUT_VERTICAL_ON_GROUND)
            .origin(LaunchOrigin.below(0.1)).build();

    /** A bow drawn all the way: power 3, inaccuracy 1. */
    static final LaunchRules FULL_BOW = LaunchRules.builder()
            .power(3).spread(SPREAD)
            .shooterVelocity(ShooterVelocity.ALL_BUT_VERTICAL_ON_GROUND)
            .origin(LaunchOrigin.below(0.1)).build();

    private WikiRules() {
    }

    /** An arrow: 0.99 drag in air, or whatever {@code drag} says. */
    static ProjectileRules arrow(DragRule drag) {
        return ProjectileRules.builder("arrow")
                .gravity(0.05).drag(drag).order(StepOrder.POSITION_DRAG_ACCELERATION).entityMargin(0).build();
    }

    /** An arrow slowed to 0.6 in the cells {@code water} names. */
    static ProjectileRules arrowIn(CellTest water) {
        return arrow(DragRule.inside(water, 0.6f, 0.99f));
    }
}
