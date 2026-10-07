package dev.px.projectile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The order a projectile does its three things in each tick: speed up by its
 * acceleration, slow down by its drag, and move by its velocity.
 *
 * <pre>{@code
 * ProjectileRules.builder("arrow").order(StepOrder.POSITION_DRAG_ACCELERATION) ...
 * }</pre>
 *
 * <p>Every order there is, so whatever your game does, one fits. The order
 * changes the path: the same gravity and drag land somewhere else when the move
 * comes first. The <a href="https://minecraft.wiki/w/Entity#Motion">wiki</a>
 * gives each projectile's order for current Java: arrows, tridents and llama
 * spit move first, then drag, then accelerate; thrown eggs, snowballs, pearls
 * and potions accelerate, drag, then move, which changed in 1.21.2 from moving
 * first. Which projectile ticks which way is your game's to say.
 */
public enum StepOrder {

    ACCELERATION_DRAG_POSITION(Step.ACCELERATION, Step.DRAG, Step.POSITION),
    ACCELERATION_POSITION_DRAG(Step.ACCELERATION, Step.POSITION, Step.DRAG),
    DRAG_ACCELERATION_POSITION(Step.DRAG, Step.ACCELERATION, Step.POSITION),
    DRAG_POSITION_ACCELERATION(Step.DRAG, Step.POSITION, Step.ACCELERATION),
    POSITION_ACCELERATION_DRAG(Step.POSITION, Step.ACCELERATION, Step.DRAG),
    POSITION_DRAG_ACCELERATION(Step.POSITION, Step.DRAG, Step.ACCELERATION);

    /** One of the three things a projectile does each tick. */
    public enum Step {
        /** Its acceleration is added to its velocity: gravity pulls it down. */
        ACCELERATION,
        /** Its velocity is multiplied by its drag. */
        DRAG,
        /** Its velocity is added to its position: it moves, and may hit something. */
        POSITION
    }

    private final List<Step> steps;

    StepOrder(Step... steps) {
        this.steps = Collections.unmodifiableList(Arrays.asList(steps));
    }

    /** @return the three steps, in the order they happen */
    public List<Step> getSteps() {
        return steps;
    }
}
