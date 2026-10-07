package dev.px.projectile.test;

import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Vec3;
import dev.px.core.test.harness.RecordingLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Things standing still or moved by hand, behind Core's own entity service:
 * bodies to hit, projectiles to follow, and you.
 */
final class Arena {

    /** Something in the world: a box at a position. */
    static final class Thing {
        final String name;
        double x;
        double y;
        double z;
        final double width;
        final double height;

        Thing(String name, double x, double y, double z, double width, double height) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
        }

        void moveTo(Vec3 at) {
            x = at.getX();
            y = at.getY();
            z = at.getZ();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Projectiles: their own kind, tracked apart from bodies. */
    static final class Shot {
        final String name;
        Vec3 at;

        Shot(String name, Vec3 at) {
            this.name = name;
            this.at = at;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    final EntityService entities;
    final EntityTracker<Thing> things;
    final EntityTracker<Shot> shots;
    private final List<Object> all = new ArrayList<>();
    private Thing self;

    Arena() {
        RecordingLogger logger = new RecordingLogger();
        entities = new EntityService(logger, new CoreEventBus(logger));
        entities.setSource(new Source());
        things = entities.register(EntityTracker.of(Thing.class));
        shots = entities.register(EntityTracker.of(Shot.class));
    }

    /** A player-sized body standing with its feet at {@code (x, y, z)}. */
    Thing body(String name, double x, double y, double z) {
        Thing thing = new Thing(name, x, y, z, 0.6d, 1.8d);
        all.add(thing);
        return thing;
    }

    Shot shot(String name, Vec3 at) {
        Shot shot = new Shot(name, at);
        all.add(shot);
        return shot;
    }

    /** You: a player-sized body, eyes 1.62 up. */
    Thing you(double x, double y, double z) {
        self = new Thing("you", x, y, z, 0.6d, 1.8d);
        return self;
    }

    Vec3 eyes() {
        return Vec3.of(self.x, self.y + 1.62d, self.z);
    }

    void refresh() {
        entities.refresh();
    }

    Tracked<Thing> tracked(Thing thing) {
        return things.get(thing);
    }

    Tracked<Shot> tracked(Shot shot) {
        return shots.get(shot);
    }

    private final class Source implements EntitySource<Object> {
        private final Object nobody = new Object();

        @Override
        public Iterable<Object> entities() {
            return all;
        }

        @Override
        public Object self() {
            return self != null ? self : nobody;
        }

        @Override
        public double x(Object entity) {
            return entity instanceof Thing ? ((Thing) entity).x : entity instanceof Shot ? ((Shot) entity).at.getX() : 1e6d;
        }

        @Override
        public double y(Object entity) {
            return entity instanceof Thing ? ((Thing) entity).y : entity instanceof Shot ? ((Shot) entity).at.getY() : 1e6d;
        }

        @Override
        public double z(Object entity) {
            return entity instanceof Thing ? ((Thing) entity).z : entity instanceof Shot ? ((Shot) entity).at.getZ() : 1e6d;
        }

        @Override
        public double width(Object entity) {
            return entity instanceof Thing ? ((Thing) entity).width : 0.25d;
        }

        @Override
        public double height(Object entity) {
            return entity instanceof Thing ? ((Thing) entity).height : 0.25d;
        }

        @Override
        public double eyeHeight(Object entity) {
            return entity instanceof Thing ? 1.62d : 0d;
        }
    }
}
