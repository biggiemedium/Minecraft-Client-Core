package dev.px.core.target;

import dev.px.core.entity.TrackedEntity;
import dev.px.core.math.Vec3;
import dev.px.core.social.SocialService;

/**
 * Where one query is looking from, handed to sorts and predicates that need
 * more than the entity itself.
 *
 * <p>The origin is the local player's eyes unless the query gave another point;
 * the view is the local player's rotation. Both are fixed for the length of the
 * query.
 *
 * <p>Owned by {@link TargetService} and reused between queries: read it inside
 * the sort or predicate it was passed to, and do not keep it.
 */
public final class TargetContext {

    private SocialService social;
    private TrackedEntity self;
    private double originX;
    private double originY;
    private double originZ;
    private boolean hasView;
    private double lookX;
    private double lookY;
    private double lookZ;

    TargetContext() {
    }

    void begin(SocialService social, TrackedEntity self, double x, double y, double z) {
        this.social = social;
        this.self = self;
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        this.hasView = self != null;
        if (hasView) {
            // RotationMath.direction, inlined so a query builds no vectors.
            double yaw = Math.toRadians(self.getYaw());
            double pitch = Math.toRadians(self.getPitch());
            double horizontal = Math.cos(pitch);
            lookX = -Math.sin(yaw) * horizontal;
            lookY = -Math.sin(pitch);
            lookZ = Math.cos(yaw) * horizontal;
        }
    }

    void end() {
        social = null;
        self = null;
    }

    /** @return the local player, or null when the query ran without one */
    public TrackedEntity getSelf() {
        return self;
    }

    public double getOriginX() {
        return originX;
    }

    public double getOriginY() {
        return originY;
    }

    public double getOriginZ() {
        return originZ;
    }

    public Vec3 getOrigin() {
        return Vec3.of(originX, originY, originZ);
    }

    /** @return the squared distance from the origin to the entity's hitbox */
    public double squaredDistanceTo(TrackedEntity entity) {
        return entity.squaredDistanceToBox(originX, originY, originZ);
    }

    public double distanceTo(TrackedEntity entity) {
        return Math.sqrt(squaredDistanceTo(entity));
    }

    /**
     * @return the angle in degrees between where the local player looks and the
     *         centre of the entity's box, or 0 when there is no local player
     */
    public double angleTo(TrackedEntity entity) {
        double cosine = cosineTo(entity);
        return Math.toDegrees(Math.acos(Math.max(-1d, Math.min(1d, cosine))));
    }

    /** @return whether the entity is a friend */
    public boolean isFriend(TrackedEntity entity) {
        return social != null && social.isFriend(entity.getName());
    }

    /**
     * The cosine of {@link #angleTo}, which is all a field-of-view test needs:
     * comparing against {@code cos(fov / 2)} costs no inverse trigonometry.
     */
    double cosineTo(TrackedEntity entity) {
        if (!hasView) {
            return 1d;
        }
        double dx = (entity.getMinX() + entity.getMaxX()) / 2d - originX;
        double dy = (entity.getMinY() + entity.getMaxY()) / 2d - originY;
        double dz = (entity.getMinZ() + entity.getMaxZ()) / 2d - originZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-9d) {
            return 1d;
        }
        return (dx * lookX + dy * lookY + dz * lookZ) / length;
    }
}
