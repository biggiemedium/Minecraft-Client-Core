package dev.px.combat.fight;

import dev.px.core.entity.Tracked;
import dev.px.core.flow.FlowContext;
import dev.px.core.math.Box;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;
import dev.px.core.util.math.RotationMath;

/**
 * A {@link Fight} as its parts see it, this tick: who is fought, where the eyes
 * are, where the head is, and the flow it runs in.
 *
 * <pre>{@code
 * public Strike tick(Bout<? extends EntityPlayer> b) {
 *     Vec3 aim = b.target().aimPoint(b.eyes(), 0.1);
 *     if (b.ticks() < 2) return Strike.at(aim);                 // settle the aim first
 *     return Strike.at(aim).click(Click.ATTACK).whenFacing(b.target().getBox());
 * }
 * }</pre>
 *
 * <p>Made fresh each tick; keep nothing from it. {@link #context()} reaches the
 * flow's keys, shared memory and the host's services, as any step would.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what is fought
 */
public final class Bout<E> {

    private final FlowContext context;
    private final Tracked<E> target;
    private final Vec3 eyes;
    private final int ticks;

    Bout(FlowContext context, Tracked<E> target, Vec3 eyes, int ticks) {
        this.context = context;
        this.target = target;
        this.eyes = eyes;
        this.ticks = ticks;
    }

    /** @return who is fought */
    public Tracked<E> target() {
        return target;
    }

    /** @return where the player's eyes are */
    public Vec3 eyes() {
        return eyes;
    }

    /**
     * @return where the head is this tick, as Core's rotation service has it:
     *         once the fight's aim is filed, where it will be
     */
    public Vec2 rotation() {
        return context.rotations().getRotation();
    }

    /** @return the rotation that looks at {@code point} from the eyes */
    public Vec2 rotationTo(Vec3 point) {
        return eyes.rotationTo(Validate.notNull(point, "point"));
    }

    /** @return whether the head, as it is this tick, looks at {@code box} from the eyes, however far */
    public boolean isFacing(Box box) {
        Validate.notNull(box, "box");
        if (box.contains(eyes)) {
            return true;
        }
        double reach = eyes.distanceTo(box.getCenter()) + box.getWidth() + box.getHeight() + box.getDepth();
        return !Double.isNaN(box.clip(eyes, RotationMath.project(eyes, rotation(), reach)));
    }

    /** @return ticks fought since the fight began; a pause does not start it over */
    public int ticks() {
        return ticks;
    }

    /** @return the flow's context: its keys, shared memory and services */
    public FlowContext context() {
        return context;
    }

    @Override
    public String toString() {
        return "Bout(" + target + ", tick " + ticks + ")";
    }
}
