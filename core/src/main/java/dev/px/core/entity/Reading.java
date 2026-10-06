package dev.px.core.entity;

/**
 * One entity's geometry, read through the source once and handed to every
 * tracker that wants the entity, so being in three trackers costs one read.
 *
 * <p>Owned by {@link EntityService} and reused for every entity.
 */
final class Reading {

    double x;
    double y;
    double z;
    double width;
    double height;
    double eyeHeight;
    float yaw;
    float pitch;
    long stamp;

    /** Fills this from the source; throws whatever the source throws. */
    void read(EntitySource<Object> source, Object entity) {
        x = source.x(entity);
        y = source.y(entity);
        z = source.z(entity);
        width = Math.max(0d, source.width(entity));
        height = Math.max(0d, source.height(entity));
        eyeHeight = source.eyeHeight(entity);
        yaw = source.yaw(entity);
        pitch = source.pitch(entity);
        stamp = source.positionStamp(entity);
    }
}
