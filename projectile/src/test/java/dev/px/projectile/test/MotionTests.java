package dev.px.projectile.test;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.test.harness.Checks;
import dev.px.core.util.math.RotationMath;
import dev.px.projectile.DragRule;
import dev.px.projectile.Launch;
import dev.px.projectile.LaunchOrigin;
import dev.px.projectile.LaunchRules;
import dev.px.projectile.ProjectileRules;
import dev.px.projectile.Shooter;
import dev.px.projectile.ShooterVelocity;
import dev.px.projectile.StepOrder;
import dev.px.projectile.flight.Motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a projectile moves through nothing, and how it leaves whoever launched it.
 *
 * <p>The truth is the wiki: its closed-form position for each of the six orders
 * (https://minecraft.wiki/w/Entity#Formulas), its terminal velocities and
 * travel distances, and its launch rules. None of it is the code under test
 * checked against itself.
 */
public final class MotionTests {

    private MotionTests() {
    }

    public static void run() {
        Checks.section("Motion");

        closedForms();
        wikiTable();
        orderMatters();
        water();
        following();
        reach();

        Checks.section("Launch");

        launching();
        rules();
    }

    // --------------------------------------------------------- closed forms

    private static void closedForms() {
        double gravity = 0.05d;
        double drag = 0.99f;
        Vec3 start = Vec3.of(3, 70, -2);
        Vec3 velocity = Vec3.of(1.2, 0.8, -0.4);
        for (StepOrder order : StepOrder.values()) {
            ProjectileRules rules = ProjectileRules.builder("test").gravity(gravity).drag(drag).order(order)
                    .entityMargin(0).build();
            Motion motion = Motion.of(rules, start, velocity);
            double worst = 0d;
            for (int t = 1; t <= 120; t++) {
                motion.tick();
                Vec3 expected = Vec3.of(
                        closedForm(order, start.getX(), velocity.getX(), 0d, drag, t),
                        closedForm(order, start.getY(), velocity.getY(), -gravity, drag, t),
                        closedForm(order, start.getZ(), velocity.getZ(), 0d, drag, t));
                worst = Math.max(worst, expected.distanceTo(motion.getPosition()));
            }
            Checks.check(String.format(Locale.ROOT, "%s follows the wiki's formula for 120 ticks (%.2e blocks off at worst)",
                    order, worst), worst < 1e-9);
        }
    }

    /** The wiki's position after {@code t} ticks, per axis: https://minecraft.wiki/w/Entity#Position */
    private static double closedForm(StepOrder order, double p0, double v0, double a, double d, int t) {
        double dt = Math.pow(d, t);
        switch (order) {
            case POSITION_DRAG_ACCELERATION:
                return p0 + (1 - dt) / (1 - d) * (v0 - a / (1 - d)) + t / (1 - d) * a;
            case POSITION_ACCELERATION_DRAG:
                return p0 + (1 - dt) / (1 - d) * (v0 - d / (1 - d) * a) + d * t / (1 - d) * a;
            case ACCELERATION_POSITION_DRAG:
                return p0 + (1 - dt) / (1 - d) * (v0 - d / (1 - d) * a) + t / (1 - d) * a;
            case DRAG_ACCELERATION_POSITION:
                return p0 + d * (1 - dt) / (1 - d) * (v0 - a / (1 - d)) + t / (1 - d) * a;
            case DRAG_POSITION_ACCELERATION:
                return p0 + d * (1 - dt) / (1 - d) * (v0 - a / (1 - d)) + d * t / (1 - d) * a;
            default:
                return p0 + d * (1 - dt) / (1 - d) * (v0 - d / (1 - d) * a) + d * t / (1 - d) * a;
        }
    }

    // ------------------------------------------------------------ wiki table

    private static void wikiTable() {
        // Terminal velocity and travel per unit of speed, from the wiki's table.
        Object[][] table = {
                { WikiRules.ARROW, 5.00f, 100f },
                { WikiRules.SPIT, 6.00f, 100f },
                { WikiRules.PEARL, 2.97f, 99f },
                { WikiRules.POTION, 4.95f, 99f },
                { WikiRules.BOTTLE, 6.93f, 99f },
        };
        for (Object[] row : table) {
            ProjectileRules rules = (ProjectileRules) row[0];
            Motion motion = Motion.of(rules, Vec3.ZERO, Vec3.of(1, 0, 0));
            for (int t = 0; t < 4000; t++) {
                motion.tick();
            }
            Checks.checkEquals(rules.getName() + " falls no faster than the wiki's terminal velocity",
                    (Float) row[1], (float) -motion.getVelocity().getY(), 0.1f);
            Checks.checkEquals(rules.getName() + " travels the wiki's distance for each block per tick it starts with",
                    (Float) row[2], (float) motion.getX(), 0.1f);
        }
    }

    // ----------------------------------------------------------- order matters

    private static void orderMatters() {
        Vec3 start = Vec3.of(0, 100, 0);
        Vec3 velocity = Vec3.of(1.5, 0, 0);
        Motion now = Motion.of(WikiRules.PEARL, start, velocity);
        Motion before = Motion.of(WikiRules.OLD_PEARL, start, velocity);
        now.tick();
        before.tick();
        Checks.check("a pearl before 1.21.2 moved its whole starting velocity in its first tick",
                before.getPosition().distanceTo(start.add(velocity)) < 1e-12);
        Checks.check("since then, it accelerates and drags first, and moves less",
                now.getPosition().distanceTo(start.add(velocity.add(0, -0.03, 0).scale(0.99f))) < 1e-12);
        for (int t = 1; t < 40; t++) {
            now.tick();
            before.tick();
        }
        Checks.check(String.format(Locale.ROOT, "so the same throw is somewhere else 40 ticks on (%.2f blocks apart)",
                now.getPosition().distanceTo(before.getPosition())), now.getPosition().distanceTo(before.getPosition()) > 0.5d);
    }

    // ------------------------------------------------------------------ water

    private static void water() {
        // The wiki's arrow formulas in water: "change 0.99 with 0.6 and 100 with 2.5".
        Motion soaked = Motion.of(WikiRules.arrowIn((x, y, z) -> true), Vec3.ZERO, Vec3.of(3, 0, 0));
        for (int t = 0; t < 200; t++) {
            soaked.tick();
        }
        Checks.checkEquals("an arrow in water travels 2.5 blocks for each block per tick it starts with",
                7.5f, (float) soaked.getX(), 0.01f);

        DragRule underwater = DragRule.inside((x, y, z) -> x == -1 && y == -3 && z == -1, 0.6, 0.99);
        Checks.check("the cell asked about is the one the position is in, below zero too",
                underwater.at(-0.5, -2.5, -0.01) == 0.6 && underwater.at(0.5, -2.5, -0.5) == 0.99);

        // Drag is asked where the tick began.
        List<Vec3> asked = new ArrayList<>();
        ProjectileRules watching = WikiRules.arrow((x, y, z) -> {
            asked.add(Vec3.of(x, y, z));
            return 0.99f;
        });
        Motion motion = Motion.of(watching, Vec3.of(0, 10, 0), Vec3.of(2, 0.5, 0));
        List<Vec3> starts = new ArrayList<>();
        for (int t = 0; t < 5; t++) {
            starts.add(motion.getPosition());
            motion.tick();
        }
        Checks.checkEquals("drag is asked for once a tick, with where the tick began", starts, asked);

        // Into water: the tick that ends in it is dragged as air, the next as water.
        ProjectileRules arrow = WikiRules.arrowIn((x, y, z) -> x >= 5);
        Motion entering = Motion.of(arrow, Vec3.of(3, 10, 0), Vec3.of(2.5, 0, 0));
        entering.tick();
        double first = entering.getVelocity().getX();
        entering.tick();
        double second = entering.getVelocity().getX();
        Checks.check(String.format(Locale.ROOT, "an arrow flying into water keeps 0.99 for the tick it splashes in, "
                + "then 0.6 (%.4f, then %.4f)", first, second), Math.abs(first - 2.5 * 0.99f) < 1e-9
                && Math.abs(second - first * 0.6f) < 1e-9);
    }

    // --------------------------------------------------------------- following

    private static void following() {
        StepOrder[] orders = StepOrder.values();
        ProjectileRules[] cases = new ProjectileRules[orders.length + 1];
        for (int i = 0; i < orders.length; i++) {
            cases[i] = ProjectileRules.builder(orders[i].name()).gravity(0.04).drag(0.97f).order(orders[i])
                    .entityMargin(0).build();
        }
        cases[orders.length] = WikiRules.arrowIn((x, y, z) -> y < 50);
        for (ProjectileRules rules : cases) {
            Motion truth = Motion.of(rules, Vec3.of(0, 60, 0), Vec3.of(1.1, 0.6, 0.3));
            for (int t = 0; t < 7; t++) {
                truth.tick();
            }
            Vec3 previous = truth.getPosition();
            truth.tick();
            Motion seen = Motion.following(rules, truth.getPosition(), truth.getPosition().subtract(previous));
            double worst = 0d;
            for (int t = 0; t < 60; t++) {
                truth.tick();
                seen.tick();
                worst = Math.max(worst, truth.getPosition().distanceTo(seen.getPosition()));
            }
            Checks.check(String.format(Locale.ROOT, "a projectile seen only by its positions, %s, is followed exactly "
                    + "(%.2e blocks off)", rules.getName(), worst), worst < 1e-9);
        }
    }

    // ------------------------------------------------------------------- reach

    private static void reach() {
        double nudge = 0.0172275d * 3d;
        for (ProjectileRules rules : new ProjectileRules[] { WikiRules.ARROW, WikiRules.PEARL }) {
            Motion middle = Motion.of(rules, Vec3.ZERO, Vec3.of(2, 1, 0.5));
            Motion pushed = Motion.of(rules, Vec3.ZERO, Vec3.of(2 + nudge, 1 - nudge, 0.5 + nudge));
            double worst = 0d;
            for (int t = 0; t < 50; t++) {
                middle.tick();
                pushed.tick();
                Vec3 apart = pushed.getPosition().subtract(middle.getPosition());
                double expected = nudge * middle.getReach();
                worst = Math.max(worst, Math.max(Math.abs(apart.getX() - expected),
                        Math.max(Math.abs(apart.getY() + expected), Math.abs(apart.getZ() - expected))));
            }
            Checks.check(String.format(Locale.ROOT, "the %s's reach is how far a nudge to its starting velocity carries it "
                    + "(%.2e off)", rules.getName(), worst), worst < 1e-9);
        }
    }

    // --------------------------------------------------------------- launching

    private static void launching() {
        LaunchRules plain = LaunchRules.builder().power(2).shooterVelocity(ShooterVelocity.NONE)
                .origin(LaunchOrigin.EYE).build();
        Launch south = plain.launch(Shooter.still(Vec3.of(0, 65, 0)), Vec2.rotation(0f, 0f));
        Checks.check("facing yaw 0, level, it leaves south at its power (" + south.getVelocity() + ")",
                south.getVelocity().distanceTo(Vec3.of(0, 0, 2)) < 1e-9 && south.getPosition().equals(Vec3.of(0, 65, 0)));
        Launch up = plain.launch(Shooter.still(Vec3.ZERO), Vec2.rotation(90f, -30f));
        Checks.check("its direction is where the shooter looks",
                up.getVelocity().distanceTo(RotationMath.direction(90f, -30f).scale(2)) < 1e-9);

        Launch potion = WikiRules.POTION_THROW.launch(Shooter.still(Vec3.of(0, 65, 0)), Vec2.rotation(0f, 0f));
        Checks.check("a potion thrown level goes 20 degrees up, at 0.5 (" + potion.getVelocity() + ")",
                potion.getVelocity().distanceTo(RotationMath.direction(0f, -20f).scale(0.5)) < 1e-9
                        && potion.getVelocity().getY() > 0d);
        Checks.check("from 0.1 under the eyes, where these tests' client says",
                potion.getPosition().equals(Vec3.of(0, 64.9, 0)));

        Vec3 running = Vec3.of(0.2, -0.3, 0.1);
        Shooter grounded = Shooter.of(Vec3.ZERO, running, true);
        Shooter airborne = Shooter.of(Vec3.ZERO, running, false);
        Vec2 level = Vec2.rotation(0f, 0f);
        Vec3 own = Vec3.of(0, 0, 1.5);
        Checks.check("a thrown pearl takes the shooter's velocity, all but its vertical part on the ground",
                WikiRules.THROW.launch(grounded, level).getVelocity().distanceTo(own.add(0.2, 0, 0.1)) < 1e-9
                        && WikiRules.THROW.launch(airborne, level).getVelocity().distanceTo(own.add(running)) < 1e-9);
        LaunchRules all = LaunchRules.builder().power(1.5).shooterVelocity(ShooterVelocity.ALL)
                .origin(LaunchOrigin.EYE).build();
        LaunchRules none = LaunchRules.builder().power(1.5).shooterVelocity(ShooterVelocity.NONE)
                .origin(LaunchOrigin.EYE).build();
        Checks.check("ALL takes all of it on the ground too, NONE takes none of it",
                all.launch(grounded, level).getVelocity().distanceTo(own.add(running)) < 1e-9
                        && none.launch(airborne, level).getVelocity().distanceTo(own) < 1e-9);

        double[] draw = { 0.5 };
        LaunchRules bow = LaunchRules.builder().power(() -> draw[0]).shooterVelocity(ShooterVelocity.NONE)
                .origin(LaunchOrigin.EYE).spread(0.01).build();
        double weak = bow.launch(Shooter.still(Vec3.ZERO), level).getVelocity().length();
        draw[0] = 3d;
        Launch strong = bow.launch(Shooter.still(Vec3.ZERO), level);
        Checks.check("a bow's power is read at each launch, so it follows the draw",
                Math.abs(weak - 0.5) < 1e-9 && Math.abs(strong.getVelocity().length() - 3) < 1e-9
                        && bow.currentPower() == 3d);
        Checks.check("and its spread is scaled by the power", Math.abs(strong.getSpread() - 0.03) < 1e-12
                && Math.abs(strong.getPower() - 3) < 1e-12);
    }

    // ------------------------------------------------------------------- rules

    private static void rules() {
        try {
            ProjectileRules.builder("half").gravity(0.05).build();
            Checks.check("rules missing parts are refused", false);
        } catch (IllegalStateException refused) {
            Checks.check("rules missing parts are refused, naming each one (" + refused.getMessage() + ")",
                    refused.getMessage().contains("drag") && refused.getMessage().contains("order")
                            && refused.getMessage().contains("entityMargin") && !refused.getMessage().contains("gravity"));
        }
        try {
            LaunchRules.builder().power(1).build();
            Checks.check("launch rules missing parts are refused", false);
        } catch (IllegalStateException refused) {
            Checks.check("launch rules missing parts are refused, naming each one (" + refused.getMessage() + ")",
                    refused.getMessage().contains("shooterVelocity") && refused.getMessage().contains("origin")
                            && !refused.getMessage().contains("power"));
        }
        Checks.checkThrows("a negative drag is refused", IllegalArgumentException.class, () -> DragRule.constant(-0.1));
        Checks.checkThrows("a negative margin is refused", IllegalArgumentException.class,
                () -> ProjectileRules.builder("x").entityMargin(-1));
        Motion falling = Motion.of(WikiRules.ARROW, Vec3.ZERO, Vec3.ZERO);
        falling.tick();
        falling.tick();
        Checks.check("positive gravity pulls it down", falling.getY() < 0d && falling.getTicks() == 2);
    }
}
