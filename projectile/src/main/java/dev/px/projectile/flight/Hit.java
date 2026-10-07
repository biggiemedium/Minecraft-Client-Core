package dev.px.projectile.flight;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import lombok.Getter;

/**
 * What ended a flight: a block, an entity, or nothing before it ran out of ticks.
 *
 * <pre>{@code
 * Hit hit = path.getHit();
 * if (hit.getType() == Hit.Type.ENTITY) {
 *     highlight(hit.getEntity());
 * } else if (hit.getType() == Hit.Type.BLOCK) {
 *     drawFace(hit.getCell(), hit.getFace());
 * }
 * }</pre>
 *
 * <p>Immutable.
 */
@Getter
public final class Hit {

    /** What the flight ended on. */
    public enum Type {
        /** Nothing: it was still flying when the flight ran out of ticks. */
        NONE,
        /** A block's shape. */
        BLOCK,
        /** An entity's box, widened by the projectile's entity margin. */
        ENTITY
    }

    private final Type type;
    /** Where it ended: the point it hit, or where it was when the ticks ran out. */
    private final Vec3 point;
    /** The tick it hit in, counting the first tick of flight as 1; the ticks flown if it hit nothing. */
    private final int tick;
    /** How far along that tick's move it hit, 0 to 1; 1 if it hit nothing. */
    private final double fraction;
    /** The cell whose shape it hit; {@code null} unless it hit a block. */
    private final Vec3i cell;
    /**
     * The face of the block's shape it hit; {@code null} unless it hit a block,
     * or when it started inside the block.
     */
    private final Direction face;
    /** The entity it hit; {@code null} unless it hit one. */
    private final Tracked<?> entity;

    private Hit(Type type, Vec3 point, int tick, double fraction, Vec3i cell, Direction face, Tracked<?> entity) {
        this.type = type;
        this.point = point;
        this.tick = tick;
        this.fraction = fraction;
        this.cell = cell;
        this.face = face;
        this.entity = entity;
    }

    static Hit none(Vec3 point, int tick) {
        return new Hit(Type.NONE, point, tick, 1d, null, null, null);
    }

    static Hit block(Vec3 point, int tick, double fraction, Vec3i cell, Direction face) {
        return new Hit(Type.BLOCK, point, tick, fraction, cell, face, null);
    }

    static Hit entity(Vec3 point, int tick, double fraction, Tracked<?> entity) {
        return new Hit(Type.ENTITY, point, tick, fraction, null, null, entity);
    }

    /** @return whether it hit anything */
    public boolean isHit() {
        return type != Type.NONE;
    }

    @Override
    public String toString() {
        switch (type) {
            case BLOCK:
                return String.format("Hit(block %s, face %s, tick %d)", cell, face, tick);
            case ENTITY:
                return String.format("Hit(entity %s, tick %d)", entity.get(), tick);
            default:
                return String.format("Hit(nothing in %d ticks)", tick);
        }
    }
}
