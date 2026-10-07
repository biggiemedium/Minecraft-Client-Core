package dev.px.projectile.test;

import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Lookahead;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.test.harness.MovementRig.Body;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;
import dev.px.projectile.Launch;
import dev.px.projectile.LaunchOrigin;
import dev.px.projectile.LaunchRules;
import dev.px.projectile.Shooter;
import dev.px.projectile.ShooterVelocity;
import dev.px.projectile.aim.Aim;
import dev.px.projectile.aim.AimPoint;
import dev.px.projectile.aim.AimSolver;
import dev.px.projectile.aim.AimStats;
import dev.px.projectile.aim.Arc;
import dev.px.projectile.flight.Flight;
import dev.px.projectile.flight.Motion;
import dev.px.projectile.flight.Trajectory;
import dev.px.projectile.test.Arena.Thing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aiming: at points, over walls, past people in the way, and at someone running
 * &mdash; checked by flying the aim and seeing what it hits, never by asking the
 * solver whether it believes itself.
 */
public final class AimTests {

    private static final Vec3 EYE = Vec3.of(0.5, 65.62, 0.5);

    private AimTests() {
    }

    public static void run() {
        Checks.section("Aim");

        points();
        arcs();
        outOfRange();
        moving();
        walls();
        people();
        running();
        settling();
        building();
    }

    // ------------------------------------------------------------------ points

    private static void points() {
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(BlockView.EMPTY).build();
        AimSolver bow = AimSolver.builder(arrows, WikiRules.FULL_BOW).build();
        Checks.check("before its first aim, a solver has no stats", bow.getLastStats() == null);
        Vec3[] points = { Vec3.of(20, 65, 3), Vec3.of(-40, 70, -10), Vec3.of(60, 58, 25), Vec3.of(4, 75, -3),
                Vec3.of(0.5, 40, 9) };
        double worst = 0d;
        int found = 0;
        for (Vec3 point : points) {
            Aim aim = bow.at(Shooter.still(EYE), point);
            if (aim != null) {
                found++;
                worst = Math.max(worst, passes(aim.getTrajectory(), point));
            }
        }
        Checks.check(String.format(Locale.ROOT, "an arrow aimed at a point passes through it, flown and measured "
                + "(%d of %d aimed, %.2e blocks off at worst)", found, points.length, worst),
                found == points.length && worst < 1e-3);
        AimStats stats = bow.getLastStats();
        Checks.check("and the stats say it aimed, with one flight through the world (" + stats + ")",
                stats.getOutcome() == AimStats.Outcome.AIMED && stats.getFlights() == 1 && stats.getMotions() > 0);

        Flight potions = Flight.builder(WikiRules.POTION).blocks(BlockView.EMPTY).build();
        Vec3 spot = Vec3.of(4, 64, 2);
        Aim splash = AimSolver.builder(potions, WikiRules.POTION_THROW).build().at(Shooter.still(EYE), spot);
        Checks.check(String.format(Locale.ROOT, "a potion, thrown 20 degrees above where you look, lands on its spot "
                + "(%.2e blocks off)", splash == null ? -1 : passes(splash.getTrajectory(), spot)),
                splash != null && passes(splash.getTrajectory(), spot) < 1e-3);
        Checks.check("but not 8.5 blocks: at 0.5 a potion does not carry that far",
                AimSolver.builder(potions, WikiRules.POTION_THROW).arcs(Arc.LOW, Arc.HIGH).build()
                        .at(Shooter.still(EYE), Vec3.of(8, 64, 3)) == null);
        Checks.check("its impact is the point, and it arrives when its flight passes it",
                splash != null && splash.getImpact().distanceTo(spot) < 1e-3
                        && splash.getTrajectory().at(splash.getArrivalTick() - 1).distanceTo(spot) > 1e-3);
    }

    // -------------------------------------------------------------------- arcs

    private static void arcs() {
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(BlockView.EMPTY).build();
        Vec3 point = Vec3.of(30, 64, 10);
        Aim low = AimSolver.builder(arrows, WikiRules.FULL_BOW).build().at(Shooter.still(EYE), point);
        Aim high = AimSolver.builder(arrows, WikiRules.FULL_BOW).arcs(Arc.HIGH).build().at(Shooter.still(EYE), point);
        Checks.check(String.format(Locale.ROOT, "a point can be reached flat or lobbed: pitch %.2f arriving in %d ticks, "
                        + "or %.2f in %d", low.getRotation().getPitch(), low.getArrivalTick(),
                high.getRotation().getPitch(), high.getArrivalTick()),
                low.getArc() == Arc.LOW && high.getArc() == Arc.HIGH
                        && high.getRotation().getPitch() < low.getRotation().getPitch() - 20f
                        && high.getArrivalTick() > low.getArrivalTick() * 2);
        Checks.check("and both pass through it", passes(low.getTrajectory(), point) < 1e-3
                && passes(high.getTrajectory(), point) < 1e-3);
    }

    // ------------------------------------------------------------ out of range

    private static void outOfRange() {
        Flight pearls = Flight.builder(WikiRules.PEARL).blocks(BlockView.EMPTY).build();
        AimSolver throwing = AimSolver.builder(pearls, WikiRules.THROW).arcs(Arc.LOW, Arc.HIGH).build();
        Checks.check("a pearl can not be thrown 200 blocks", throwing.at(Shooter.still(EYE), Vec3.of(200, 65, 0)) == null
                && throwing.getLastStats().getOutcome() == AimStats.Outcome.OUT_OF_RANGE);
        Checks.check("nor 40 blocks up", throwing.at(Shooter.still(EYE), Vec3.of(5, 105, 0)) == null
                && throwing.getLastStats().getOutcome() == AimStats.Outcome.OUT_OF_RANGE);
        Checks.check("nor straight up, where no turn of the head helps",
                throwing.at(Shooter.still(EYE), EYE.add(0, 5, 0)) == null
                        && throwing.getLastStats().getOutcome() == AimStats.Outcome.OUT_OF_RANGE);
    }

    // ------------------------------------------------------------------ moving

    private static void moving() {
        Flight pearls = Flight.builder(WikiRules.PEARL).blocks(BlockView.EMPTY).build();
        AimSolver throwing = AimSolver.builder(pearls, WikiRules.THROW).build();
        Shooter strafing = Shooter.of(EYE, Vec3.of(0.3, 0.2, 0.1), false);
        Vec3 point = Vec3.of(0.5, 62, 30);
        Aim aim = throwing.at(strafing, point);
        float direct = EYE.rotationTo(point).getYaw();
        Checks.check(String.format(Locale.ROOT, "throwing while running sideways, it turns against the run "
                        + "(yaw %.2f, %.2f facing it) and still lands on the point (%.2e off)",
                aim.getRotation().getYaw(), direct, passes(aim.getTrajectory(), point)),
                passes(aim.getTrajectory(), point) < 1e-3 && Math.abs(aim.getRotation().getYaw() - direct) > 0.1f);
    }

    // ------------------------------------------------------------------- walls

    private static void walls() {
        Blocks wall = new Blocks().wallAtX(10, 55, 70, -10, 10);
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(wall).build();
        Vec3 behind = Vec3.of(20, 65.5, 0.5);
        AimSolver flat = AimSolver.builder(arrows, WikiRules.FULL_BOW).build();
        Checks.check("behind a wall, a flat shot is blocked", flat.at(Shooter.still(EYE), behind) == null
                && flat.getLastStats().getOutcome() == AimStats.Outcome.BLOCKED
                && flat.getLastStats().getBlockedArcs() == 1);
        // A pane just in front of the point: met in the very tick the arrow would reach the point.
        Blocks pane = new Blocks();
        for (int y = 55; y <= 70; y++) {
            for (int z = -10; z <= 10; z++) {
                pane.set(12, y, z, BlockShape.of(Box.of(0, 0, 0, 0.1, 1, 1)));
            }
        }
        AimSolver paned = AimSolver.builder(Flight.builder(WikiRules.ARROW).blocks(pane).build(), WikiRules.FULL_BOW).build();
        Checks.check("a pane a fifth of a block in front of the point blocks it, though the arrow meets both in one tick",
                paned.at(Shooter.still(EYE), Vec3.of(12.3, 65.5, 0.5)) == null
                        && paned.getLastStats().getOutcome() == AimStats.Outcome.BLOCKED);

        AimSolver either = AimSolver.builder(arrows, WikiRules.FULL_BOW).arcs(Arc.LOW, Arc.HIGH).build();
        Aim lob = either.at(Shooter.still(EYE), behind);
        Checks.check("allowed to lob, it goes over (" + (lob == null ? "none" : lob.toString()) + ")",
                lob != null && lob.getArc() == Arc.HIGH && passes(lob.getTrajectory(), behind) < 1e-3
                        && either.getLastStats().getBlockedArcs() == 1 && either.getLastStats().getFlights() == 2);
    }

    // ------------------------------------------------------------------ people

    private static void people() {
        Arena arena = new Arena();
        arena.you(0.5, 64, 0.5);
        Thing target = arena.body("target", 20.5, 64, 0.5);
        Thing bystander = arena.body("bystander", 10.5, 64, 0.5);
        arena.refresh();
        Blocks open = new Blocks();
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(open).entities(arena.entities, arena.things).build();
        AimSolver bow = AimSolver.builder(arrows, WikiRules.FULL_BOW).build();
        Tracked<Thing> them = arena.tracked(target);
        Checks.check("someone in the way blocks the shot", bow.at(Shooter.still(arena.eyes()), them) == null
                && bow.getLastStats().getOutcome() == AimStats.Outcome.BLOCKED);

        Flight sparing = Flight.builder(WikiRules.ARROW).blocks(open).entities(arena.entities, arena.things)
                .filter(entity -> entity.get() != bystander).build();
        Aim aim = AimSolver.builder(sparing, WikiRules.FULL_BOW).build().at(Shooter.still(arena.eyes()), them);
        Checks.check("unless the flight may pass them; then it aims at the target's centre, standing still",
                aim != null && aim.getAimPoint().distanceTo(them.getCenter()) < 1e-9 && aim.getTarget() == them
                        && them.getBox().contains(aim.getImpact()));
        Checks.check("and the target itself never blocks its own aim", aim != null
                && (!aim.getTrajectory().getHit().isHit()
                || aim.getTrajectory().getHit().getTick() > aim.getArrivalTick()));

        AimSolver eyes = AimSolver.builder(sparing, WikiRules.FULL_BOW).aimPoint(AimPoint.EYES).build();
        AimSolver high = AimSolver.builder(sparing, WikiRules.FULL_BOW).aimPoint(AimPoint.height(0.75)).build();
        Aim atEyes = eyes.at(Shooter.still(arena.eyes()), them);
        Aim atThreeQuarters = high.at(Shooter.still(arena.eyes()), them);
        Checks.check("an aim point picks where on them",
                atEyes != null && atEyes.getAimPoint().equals(them.getEyePosition())
                        && atThreeQuarters != null && Math.abs(atThreeQuarters.getAimPoint().getY() - (64 + 1.35)) < 1e-9);
    }

    // ----------------------------------------------------------------- running

    private static void running() {
        MovementRig rig = new MovementRig(new GridCollisionSpace().floor(0d, -300, 300));
        Body you = rig.self(Vec3.of(0.5, 0, 0.5), tick -> MovementInput.none(0f));
        Body runner = rig.add(Vec3.of(-6.5, 0, 28.5), tick -> MovementInput.forward(-90f).withSprint(true));
        for (int i = 0; i < 40; i++) {
            rig.tick();
        }
        BlockView ground = (x, y, z) -> y < 0 ? BlockShape.FULL : BlockShape.EMPTY;
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(ground).entities(rig.entities, rig.bodies).build();
        AimSolver predicted = AimSolver.builder(arrows, WikiRules.FULL_BOW)
                .lookahead(Lookahead.predicted(rig.prediction)).build();
        AimSolver now = AimSolver.builder(arrows, WikiRules.FULL_BOW).build();
        Shooter shooter = Shooter.still(you.state.getPosition().add(0, 1.62, 0));
        Tracked<Body> target = rig.tracked(runner);
        Aim leading = predicted.at(shooter, target);
        Aim lagging = now.at(shooter, target);
        Checks.check("an aim at someone running looks ahead to where they will be, and settles ("
                        + predicted.getLastStats() + ")",
                leading != null && predicted.getLastStats().getRounds() > 1 && leading.getTarget() != target);

        int hitLeading = fly(rig, runner, leading.getLaunch(), 60);
        // A fresh rig, the same run, so the second arrow meets the runner as the first did.
        MovementRig again = new MovementRig(new GridCollisionSpace().floor(0d, -300, 300));
        again.self(Vec3.of(0.5, 0, 0.5), tick -> MovementInput.none(0f));
        Body second = again.add(Vec3.of(-6.5, 0, 28.5), tick -> MovementInput.forward(-90f).withSprint(true));
        for (int i = 0; i < 40; i++) {
            again.tick();
        }
        int hitLagging = fly(again, second, lagging.getLaunch(), 60);
        Checks.check(String.format(Locale.ROOT, "flown against them running, it hits them, in the tick it said "
                        + "(tick %d, expected %d)", hitLeading, leading.getArrivalTick()),
                hitLeading > 0 && Math.abs(hitLeading - leading.getArrivalTick()) <= 1);
        Checks.check("aimed where they are now, the same arrow misses (" + (hitLagging < 0 ? "missed" : "hit") + ")",
                hitLagging < 0);
    }

    /**
     * Flies an arrow tick by tick while everyone moves, against the runner's real box.
     *
     * @return the tick it hits them in, or -1
     */
    private static int fly(MovementRig rig, Body runner, Launch launch, int ticks) {
        Motion arrow = Motion.of(WikiRules.ARROW, launch.getPosition(), launch.getVelocity());
        Vec3 last = arrow.getPosition();
        for (int t = 1; t <= ticks; t++) {
            rig.tick();
            arrow.tick();
            Vec3 now = arrow.getPosition();
            Box body = Box.around(runner.state.getPosition(), 0.6, 1.8);
            if (!Double.isNaN(body.clip(last, now))) {
                return t;
            }
            if (now.getY() < 0d) {
                return -1;
            }
            last = now;
        }
        return -1;
    }

    // ---------------------------------------------------------------- settling

    private static void settling() {
        Arena arena = new Arena();
        arena.you(0.5, 64, 0.5);
        Thing target = arena.body("target", 15.5, 64, 0.5);
        arena.refresh();
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(BlockView.EMPTY).build();
        Tracked<Thing> them = arena.tracked(target);

        // Running away about as fast as an arrow flies: each look further off than the last.
        Lookahead<Object> fleeing = (entity, ticks) -> entity.projected(entity.getPosition().add(4d * ticks, 0, 0));
        AimSolver chasing = AimSolver.builder(arrows, WikiRules.FULL_BOW).lookahead(fleeing).maxRounds(3).build();
        Aim never = chasing.at(Shooter.still(arena.eyes()), them);
        Checks.check("someone outrunning the arrow never settles (" + chasing.getLastStats() + ")",
                never == null && chasing.getLastStats().getOutcome() == AimStats.Outcome.NO_CONVERGENCE
                        && chasing.getLastStats().getRounds() == 3);

        // Somewhere whose flight time is odd on even ticks and even on odd ones: the aim flicks between
        // two neighbouring ticks for ever, and either is as good as the other.
        AimSolver still = AimSolver.builder(arrows, WikiRules.FULL_BOW).build();
        double odd = Double.NaN;
        double even = Double.NaN;
        for (double distance = 6; distance < 60 && (Double.isNaN(odd) || Double.isNaN(even)); distance += 0.25) {
            Aim probe = still.at(Shooter.still(arena.eyes()), Vec3.of(distance, 64.9, 0.5));
            if (probe != null && probe.getArrivalTick() % 2 == 1 && Double.isNaN(odd)) {
                odd = distance;
                Aim next = null;
                for (double further = distance; further < distance + 4 && next == null; further += 0.05) {
                    Aim maybe = still.at(Shooter.still(arena.eyes()), Vec3.of(further, 64.9, 0.5));
                    if (maybe != null && maybe.getArrivalTick() == probe.getArrivalTick() + 1) {
                        next = maybe;
                        even = further;
                    }
                }
            }
        }
        double oddAt = odd;
        double evenAt = even;
        Lookahead<Object> flicking = (entity, ticks) -> entity.projected(Vec3.of(ticks % 2 == 0 ? oddAt : evenAt, 64, 0.5));
        Aim flicked = AimSolver.builder(arrows, WikiRules.FULL_BOW).lookahead(flicking).build()
                .at(Shooter.still(arena.eyes()), them);
        Checks.check(String.format(Locale.ROOT, "an aim flicking between two neighbouring ticks settles on one "
                + "(%.2f and %.2f blocks away)", odd, even), !Double.isNaN(odd) && !Double.isNaN(even) && flicked != null);

        List<Integer> asked = new ArrayList<>();
        Lookahead<Object> recording = (entity, ticks) -> {
            asked.add(ticks);
            return entity;
        };
        AimSolver delayed = AimSolver.builder(arrows, WikiRules.FULL_BOW).lookahead(recording).delay(() -> 2).build();
        Aim aim = delayed.at(Shooter.still(arena.eyes()), them);
        Checks.check("a delay before it leaves is looked ahead too, and counted in the arrival (asked " + asked
                        + ", arrives in " + (aim == null ? -1 : aim.getArrivalTick()) + ")",
                aim != null && asked.get(0) == 2 && aim.getArrivalTick() > 2
                        && Math.abs(asked.get(asked.size() - 1) - aim.getArrivalTick()) <= 1);
        Checks.check("standing still, it settles in two looks", delayed.getLastStats().getRounds() == 2);
    }

    // ---------------------------------------------------------------- building

    private static void building() {
        Flight arrows = Flight.builder(WikiRules.ARROW).blocks(BlockView.EMPTY).build();
        Checks.checkThrows("an aim with no arcs is refused", IllegalArgumentException.class,
                () -> AimSolver.builder(arrows, WikiRules.FULL_BOW).arcs());
        Checks.checkThrows("and one that may never look a target up", IllegalArgumentException.class,
                () -> AimSolver.builder(arrows, WikiRules.FULL_BOW).maxRounds(0));
        Checks.check("a bow not drawn reaches nothing", AimSolver.builder(arrows,
                LaunchRules.builder().power(0).shooterVelocity(ShooterVelocity.NONE)
                        .origin(LaunchOrigin.EYE).build()).build()
                .at(Shooter.still(EYE), Vec3.of(10, 65, 0)) == null);
    }

    /** @return how close the path comes to {@code point} */
    private static double passes(Trajectory path, Vec3 point) {
        double best = Double.POSITIVE_INFINITY;
        for (int t = 1; t <= path.getTicks(); t++) {
            Vec3 a = path.at(t - 1);
            Vec3 b = path.at(t);
            Vec3 along = b.subtract(a);
            double length = along.dot(along);
            double s = length < 1e-12 ? 0d : Math.max(0d, Math.min(1d, point.subtract(a).dot(along) / length));
            best = Math.min(best, a.add(along.scale(s)).distanceTo(point));
        }
        return best;
    }
}
