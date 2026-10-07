package dev.px.projectile.test;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.test.harness.Checks;
import dev.px.core.world.BlockView;
import dev.px.projectile.Launch;
import dev.px.projectile.ProjectileRules;
import dev.px.projectile.Shooter;
import dev.px.projectile.flight.Flight;
import dev.px.projectile.flight.Hit;
import dev.px.projectile.flight.Motion;
import dev.px.projectile.flight.Trajectory;
import dev.px.projectile.test.Arena.Shot;
import dev.px.projectile.test.Arena.Thing;

import java.util.Locale;

/**
 * Where a projectile goes in a world of blocks and bodies set by hand in this
 * file, and what stops it: a floor, a wall, someone in the way, you.
 */
public final class FlightTests {

    /** Facing east, level: yaw -90 looks along +X. */
    private static final Vec2 EAST = Vec2.rotation(-90f, 0f);

    private FlightTests() {
    }

    public static void run() {
        Checks.section("Flight");

        throughNothing();
        blocks();
        entities();
        you();
        following();
        spread();
        building();
    }

    // ---------------------------------------------------------- through nothing

    private static void throughNothing() {
        int[] limit = { 50 };
        Flight flight = Flight.builder(WikiRules.PEARL).blocks(BlockView.EMPTY).maxTicks(() -> limit[0]).build();
        Vec3 start = Vec3.of(0, 100, 0);
        Vec3 velocity = Vec3.of(1, 0.5, -0.3);
        Trajectory path = flight.from(start, velocity);
        Motion motion = Motion.of(WikiRules.PEARL, start, velocity);
        boolean same = path.at(0).equals(start) && path.velocityAt(0).equals(velocity);
        for (int t = 1; t <= 50; t++) {
            motion.tick();
            same &= path.at(t).equals(motion.getPosition()) && path.velocityAt(t).equals(motion.getVelocity());
        }
        Checks.check("through nothing, a flight is its motion, tick for tick", same);
        Checks.check("and runs out of ticks hitting nothing (" + path.getHit() + ")", !path.getHit().isHit()
                && path.getHit().getType() == Hit.Type.NONE && path.getTicks() == 50 && path.getHit().getTick() == 50
                && path.getEnd().equals(path.getHit().getPoint()));
        limit[0] = 20;
        Checks.check("its limit is read at each flight", flight.from(start, velocity).getTicks() == 20
                && flight.currentMaxTicks() == 20);
    }

    // ------------------------------------------------------------------ blocks

    private static void blocks() {
        Blocks ground = new Blocks().floor(63, -60, 60);
        Flight pearls = Flight.builder(WikiRules.PEARL).blocks(ground).build();
        Vec3 start = Vec3.of(0.5, 70, 0.5);
        Vec3 velocity = Vec3.of(1.5, 0, 0);
        Trajectory path = pearls.from(start, velocity);
        Hit hit = path.getHit();
        Checks.check("a pearl thrown level lands on the floor, on its top (" + hit + ")",
                hit.getType() == Hit.Type.BLOCK && hit.getFace() == Direction.UP
                        && Math.abs(hit.getPoint().getY() - 64d) < 1e-9 && hit.getCell().getY() == 63);
        Motion motion = Motion.of(WikiRules.PEARL, start, velocity);
        for (int t = 1; t < hit.getTick(); t++) {
            motion.tick();
        }
        double before = motion.getY();
        motion.tick();
        Checks.check(String.format(Locale.ROOT, "in the tick it would have passed through it (%.3f, then %.3f)",
                before, motion.getY()), before > 64d && motion.getY() < 64d && hit.getTick() == path.getTicks());
        Checks.check("its last point is where it hit, part of the way along that tick's move",
                path.getEnd().equals(hit.getPoint()) && hit.getFraction() > 0d && hit.getFraction() < 1d);

        Blocks walled = new Blocks().floor(63, -60, 60).wallAtX(10, 64, 80, -5, 5);
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(walled).build();
        Trajectory shot = arrows.launch(WikiRules.FULL_BOW.launch(Shooter.still(Vec3.of(0.5, 70, 0.5)), EAST));
        Checks.check("an arrow shot east meets a wall on its west face (" + shot.getHit() + ")",
                shot.getHit().getType() == Hit.Type.BLOCK && shot.getHit().getFace() == Direction.WEST
                        && Math.abs(shot.getEnd().getX() - 10d) < 1e-9);
    }

    // ---------------------------------------------------------------- entities

    private static void entities() {
        Arena arena = new Arena();
        Thing target = arena.body("target", 6, 69, 0.5);
        arena.refresh();
        Blocks open = new Blocks();
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(open).entities(arena.things).build();
        Flight pearls = Flight.builder(WikiRules.PEARL).blocks(open).entities(arena.things).build();

        Trajectory straight = arrows.from(Vec3.of(0.5, 70, 0.5), Vec3.of(3, 0, 0));
        Checks.check("an arrow flying at someone hits them, on their box (" + straight.getHit() + ")",
                straight.getHit().getType() == Hit.Type.ENTITY && straight.getHit().getEntity().get() == target
                        && Math.abs(straight.getEnd().getX() - 5.7d) < 1e-9);

        // Their box reaches z 0.8; this passes at 0.95.
        Trajectory arrowBeside = arrows.from(Vec3.of(0.5, 70, 0.95), Vec3.of(3, 0, 0));
        Trajectory pearlBeside = pearls.from(Vec3.of(0.5, 70, 0.95), Vec3.of(1.5, 0, 0));
        Checks.check("an arrow passing 0.15 beside someone misses them: it finds boxes as they are",
                !arrowBeside.getHit().isHit());
        Checks.check("a pearl hits them: it finds them 0.3 bigger on every side (" + pearlBeside.getHit() + ")",
                pearlBeside.getHit().getType() == Hit.Type.ENTITY && pearlBeside.getHit().getEntity().get() == target);

        Blocks wall = new Blocks().wallAtX(4, 60, 80, -5, 5);
        Flight walled = Flight.builder(WikiRules.ARROW).blocks(wall).entities(arena.things).build();
        Checks.check("someone behind a wall is not hit: the wall is",
                walled.from(Vec3.of(0.5, 70, 0.5), Vec3.of(3, 0, 0)).getHit().getType() == Hit.Type.BLOCK);
        Blocks farWall = new Blocks().wallAtX(8, 60, 80, -5, 5);
        Flight beforeWall = Flight.builder(WikiRules.ARROW).blocks(farWall).entities(arena.things).build();
        Trajectory blocked = beforeWall.from(Vec3.of(0.5, 70, 0.5), Vec3.of(4, 0, 0));
        Checks.check("someone in front of one is, even in the tick the arrow would reach the wall",
                blocked.getHit().getType() == Hit.Type.ENTITY && blocked.getHit().getTick() == 2);

        Arena against = new Arena();
        Thing flush = against.body("flush", 6.3, 69, 0.5);
        against.refresh();
        Blocks wallAtSix = new Blocks().wallAtX(6, 60, 80, -5, 5);
        Trajectory pressed = Flight.builder(WikiRules.ARROW).blocks(wallAtSix).entities(against.things).build()
                .from(Vec3.of(0.5, 70, 0.5), Vec3.of(3, 0, 0));
        Checks.check("someone pressed against a wall's face is hit, not the wall: entities are checked up to "
                + "the block (" + pressed.getHit() + ")", pressed.getHit().getEntity() != null
                && pressed.getHit().getEntity().get() == flush);

        Thing nearer = arena.body("nearer", 3, 69, 0.5);
        arena.refresh();
        Trajectory both = arrows.from(Vec3.of(0.5, 70, 0.5), Vec3.of(8, 0, 0));
        Checks.check("of two in one tick's move, the nearer is hit",
                both.getHit().getTick() == 1 && both.getHit().getEntity().get() == nearer);
        Flight sparing = Flight.builder(WikiRules.ARROW).blocks(open).entities(arena.things)
                .filter(entity -> entity.get() != nearer).build();
        Checks.check("a filter lets it pass through whoever it refuses",
                sparing.from(Vec3.of(0.5, 70, 0.5), Vec3.of(8, 0, 0)).getHit().getEntity().get() == target);
        Launch shot = WikiRules.FULL_BOW.launch(Shooter.still(Vec3.of(0.5, 70.1, 0.5)), EAST);
        Trajectory ignoring = arrows.launch(shot, entity -> entity.get() == nearer);
        Checks.check("and a launch can pass through anyone it is told to",
                ignoring.getHit().getEntity() != null && ignoring.getHit().getEntity().get() == target);
        Flight blind = Flight.builder(WikiRules.ARROW).blocks(open).build();
        Checks.check("a flight given no entities hits none",
                !blind.from(Vec3.of(0.5, 70, 0.5), Vec3.of(3, 0, 0)).getHit().isHit());
    }

    // --------------------------------------------------------------------- you

    private static void you() {
        Arena arena = new Arena();
        arena.you(0.5, 69, 0.5);
        arena.refresh();
        Blocks open = new Blocks();
        Flight withYou = Flight.builder(WikiRules.ARROW).blocks(open).entities(arena.entities, arena.things).build();
        Trajectory yours = withYou.launch(WikiRules.FULL_BOW.launch(Shooter.still(arena.eyes()), EAST));
        Checks.check("an arrow you shoot from inside your own box never hits you", !yours.getHit().isHit());
        Trajectory incoming = withYou.from(Vec3.of(-8, 70, 0.5), Vec3.of(3, 0, 0));
        Checks.check("one shot at you does (" + incoming.getHit() + ")",
                incoming.getHit().getType() == Hit.Type.ENTITY
                        && incoming.getHit().getEntity() == arena.entities.getSelf());
        Flight withoutYou = Flight.builder(WikiRules.ARROW).blocks(open).entities(arena.things).build();
        Checks.check("given only trackers, a flight never hits you",
                !withoutYou.from(Vec3.of(-8, 70, 0.5), Vec3.of(3, 0, 0)).getHit().isHit());
    }

    // --------------------------------------------------------------- following

    private static void following() {
        Arena arena = new Arena();
        Blocks ground = new Blocks().floor(63, -80, 80);
        Flight pearls = Flight.builder(WikiRules.PEARL).blocks(ground).entities(arena.things, arena.shots).build();
        ProjectileRules rules = WikiRules.PEARL;

        Motion truth = Motion.of(rules, Vec3.of(0.5, 80, 0.5), Vec3.of(1.2, 0.4, -0.3));
        Shot pearl = arena.shot("their pearl", truth.getPosition());
        arena.refresh();
        Tracked<Shot> seen = arena.tracked(pearl);
        Trajectory fresh = pearls.from(seen);
        Checks.check("a projectile seen for one tick has no move to go on: it is shown falling from where it is",
                fresh.at(1).getX() == seen.getX() && fresh.at(1).getY() < seen.getY());

        truth.tick();
        pearl.at = truth.getPosition();
        arena.refresh();
        Trajectory followed = pearls.from(seen);
        Trajectory exact = pearls.from(seen, truth.getVelocity());
        double worst = 0d;
        for (int t = 0; t <= Math.min(followed.getTicks(), exact.getTicks()); t++) {
            worst = Math.max(worst, followed.at(t).distanceTo(exact.at(t)));
        }
        Checks.check(String.format(Locale.ROOT, "seen for two, it is followed to where it lands as if its velocity "
                        + "were known (%.2e blocks off, %s)", worst, followed.getHit()),
                worst < 1e-9 && followed.getTicks() == exact.getTicks()
                        && followed.getHit().getType() == Hit.Type.BLOCK);
        Checks.check("and it never hits itself, though its own tracker is one the flight looks in",
                followed.getHit().getEntity() == null && exact.getHit().getEntity() == null);
        Checks.check("from a bare position inside its own box it would: so a tracked projectile is given as one",
                pearls.from(seen.getPosition(), truth.getVelocity()).getHit().getEntity() == seen);
    }

    // ------------------------------------------------------------------ spread

    private static void spread() {
        Flight flight = Flight.builder(WikiRules.PEARL).blocks(BlockView.EMPTY).maxTicks(30).build();
        Launch launch = WikiRules.THROW.launch(Shooter.still(Vec3.of(0, 70, 0)), Vec2.rotation(-90f, -10f));
        Trajectory path = flight.launch(launch);
        double half = launch.getSpread();
        double furthest = 0d;
        boolean inside = true;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 nudge = Vec3.of((corner & 1) == 0 ? -half : half, (corner & 2) == 0 ? -half : half,
                    (corner & 4) == 0 ? -half : half);
            Motion pushed = Motion.of(WikiRules.PEARL, launch.getPosition(), launch.getVelocity().add(nudge));
            for (int t = 0; t < 30; t++) {
                pushed.tick();
            }
            Vec3 off = pushed.getPosition().subtract(path.getEnd());
            furthest = Math.max(furthest, Math.max(Math.abs(off.getX()), Math.max(Math.abs(off.getY()), Math.abs(off.getZ()))));
            inside &= path.landingSpread().expand(1e-9).contains(pushed.getPosition());
        }
        Checks.check(String.format(Locale.ROOT, "a throw's spread bounds where its randomness can take it, and "
                + "is reached (%.4f blocks at most, %.4f bound)", furthest, path.spreadAt(30)),
                inside && Math.abs(furthest - path.spreadAt(30)) < 1e-9 && furthest > 0.1d);
        Checks.check("a flight not made from a launch has none",
                flight.from(launch.getPosition(), launch.getVelocity()).spreadAt(30) == 0d && path.spreadAt(0) == 0d);
    }

    // ---------------------------------------------------------------- building

    private static void building() {
        try {
            Flight.builder(WikiRules.ARROW).build();
            Checks.check("a flight with no blocks is refused", false);
        } catch (IllegalStateException refused) {
            Checks.check("a flight with no blocks is refused, saying so (" + refused.getMessage() + ")",
                    refused.getMessage().contains("blocks"));
        }
        Checks.checkThrows("asking for a tick past the end is refused", IllegalArgumentException.class,
                () -> Flight.builder(WikiRules.ARROW).blocks(BlockView.EMPTY).maxTicks(3).build()
                        .from(Vec3.ZERO, Vec3.of(1, 0, 0)).at(4));
    }
}
