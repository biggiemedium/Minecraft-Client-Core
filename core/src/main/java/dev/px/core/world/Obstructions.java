package dev.px.core.world;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

import java.util.Arrays;
import java.util.List;

/**
 * Whether any entity is in a region: in the way of a block being placed, of a
 * step, of anything that needs the room empty.
 *
 * <pre>{@code
 * // everything you track, and the local player
 * Obstructions entities = Obstructions.of(Core.entities(), Core.entities().get(EverythingTracker.class));
 * }</pre>
 *
 * <p>A box only touching the region does not count, the same as {@link Box#intersects}.
 */
@FunctionalInterface
public interface Obstructions {

    /** @return whether some entity's box overlaps {@code region} */
    boolean any(Box region);

    /** Nothing is ever in the way. */
    Obstructions NONE = region -> false;

    /**
     * Entities from Core's trackers, plus the local player.
     *
     * <p>Only what the trackers hold is seen. If your game lets items, arrows or
     * experience orbs block a placement, give a tracker that holds them.
     */
    static Obstructions of(EntityService entities, EntityTracker<?>... trackers) {
        Validate.notNull(entities, "entities");
        List<EntityTracker<?>> sources = Arrays.asList(trackers.clone());
        return region -> {
            Tracked<?> self = entities.getSelf();
            if (self != null && self.getBox().intersects(region)) {
                return true;
            }
            // Any box overlapping the region comes within half its diagonal of its centre.
            Vec3 centre = region.getCenter();
            double radius = region.getMin().distanceTo(region.getMax()) / 2d;
            boolean[] hit = { false };
            for (EntityTracker<?> tracker : sources) {
                tracker.forEachWithin(centre.getX(), centre.getY(), centre.getZ(), radius, entity -> {
                    if (entity.getBox().intersects(region)) {
                        hit[0] = true;
                    }
                });
                if (hit[0]) {
                    return true;
                }
            }
            return false;
        };
    }
}
