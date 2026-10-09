package dev.px.navigation.plan;

import dev.px.core.movement.simulation.MovementInput;

/**
 * Which keys a move holds, besides the heading.
 *
 * <p>The keys themselves, not game concepts: walking is holding forward,
 * sprinting is holding it with sprint, and so on. What each one does &mdash; how
 * fast, how high &mdash; comes from the {@link dev.px.core.util.math.PhysicsProfile}
 * the planner simulates with.
 *
 * <p>The planner tries every gait it is given at every heading, so each one added
 * multiplies the work. {@link LocalPlanner.Builder#gaits} chooses them.
 */
public enum Gait {

    /** Forward. */
    WALK,

    /** Forward with sprint. */
    SPRINT,

    /** Forward while sneaking. */
    SNEAK,

    /** Forward with jump: a jump whenever on the ground. */
    JUMP,

    /** Forward with sprint and jump: the fastest way across open ground. */
    SPRINT_JUMP,

    /** No keys at all: standing still, or coasting. Tried once, not at every heading. */
    WAIT;

    /** @return the keys this gait holds, facing {@code yaw} */
    public MovementInput input(float yaw) {
        switch (this) {
            case WALK:
                return MovementInput.forward(yaw);
            case SPRINT:
                return MovementInput.forward(yaw).withSprint(true);
            case SNEAK:
                return MovementInput.forward(yaw).withSneak(true);
            case JUMP:
                return MovementInput.forward(yaw).withJump(true);
            case SPRINT_JUMP:
                return MovementInput.forward(yaw).withSprint(true).withJump(true);
            default:
                return MovementInput.none(yaw);
        }
    }

    /** @return whether this gait sprints, and so needs sprinting to be allowed */
    public boolean isSprint() {
        return this == SPRINT || this == SPRINT_JUMP;
    }

    /** @return whether the heading matters to this gait */
    public boolean isDirectional() {
        return this != WAIT;
    }
}
