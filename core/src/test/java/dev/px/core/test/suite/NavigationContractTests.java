package dev.px.core.test.suite;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.navigation.Gap;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.PathProvider;
import dev.px.core.navigation.Progress;
import dev.px.core.navigation.Route;
import dev.px.core.test.harness.Checks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The navigation contract every pathfinder shares: goals, gaps, routes and
 * progress.
 *
 * <p>The promise that matters most is the gap's: it is a lower bound, never more
 * than the truth, because a planner's heuristic built on a gap that overshoots
 * misses the quickest route without any sign that it did. It is checked the only
 * honest way, against positions where each goal really is met, sampled at
 * random, rather than against the formula it was written from.
 */
public final class NavigationContractTests {

    private NavigationContractTests() {
    }

    public static void run() {
        Checks.section("Navigation contract");

        shapes();
        gaps();
        lowerBounds();
        routes();
        progress();
        providers();
    }

    private static void shapes() {
        Goal block = Goal.block(3, 64, -2);
        Checks.check("a block goal is met with the feet anywhere in the block",
                block.isMet(Vec3.of(3.5, 64.0, -1.5)) && block.isMet(Vec3.of(3.01, 64.9, -1.99)));
        Checks.check("including a hair under its floor, where landing arithmetic leaves the feet",
                block.isMet(Vec3.of(3.5, 63.99999, -1.5)));
        Checks.check("but not standing on top of it, nor beside it",
                !block.isMet(Vec3.of(3.5, 65.0, -1.5)) && !block.isMet(Vec3.of(4.01, 64.0, -1.5)));
        Checks.checkEquals("its anchor is the middle of its floor", Vec3.of(3.5, 64, -1.5), block.anchor());

        Goal near = Goal.near(Vec3.of(0, 64, 0), 2);
        Checks.check("a near goal is met within its radius of the feet",
                near.isMet(Vec3.of(1.9, 64, 0)) && !near.isMet(Vec3.of(1.5, 65.5, 0)));

        final Vec3[] moving = {Vec3.of(0, 64, 0)};
        Goal following = Goal.near(() -> moving[0], 1);
        Checks.check("a goal following a point is met where the point is now", following.isMet(Vec3.of(0.5, 64, 0)));
        moving[0] = Vec3.of(10, 64, 0);
        Checks.check("and not once it has moved on", !following.isMet(Vec3.of(0.5, 64, 0)));
        Checks.checkEquals("its anchor moves with it", Vec3.of(10, 64, 0), following.anchor());

        Goal column = Goal.column(5, 5);
        Checks.check("a column goal is met at any height in the column",
                column.isMet(Vec3.of(5.5, -30, 5.5)) && column.isMet(Vec3.of(5.1, 300, 5.9))
                        && !column.isMet(Vec3.of(6.1, 64, 5.5)));
        Checks.check("and has no anchor, being no one point", column.anchor() == null);

        Goal level = Goal.level(70);
        Checks.check("a level goal is met with the feet in any block at that height",
                level.isMet(Vec3.of(100, 70.5, -100)) && !level.isMet(Vec3.of(0, 71, 0)));

        Box lava = Box.of(0, 60, 0, 4, 62, 4);
        Goal avoid = Goal.avoid(lava);
        Checks.check("an avoid goal is met anywhere outside its region",
                avoid.isMet(Vec3.of(5, 61, 2)) && !avoid.isMet(Vec3.of(2, 61, 2)));
        Checks.checkEquals("and promises no gap, since the way out could be any way", Gap.NONE,
                avoid.gap(Vec3.of(2, 61, 2)));

        Goal any = Goal.anyOf(Goal.block(0, 64, 0), Goal.block(10, 64, 0));
        Checks.check("anyOf is met where either is", any.isMet(Vec3.of(0.5, 64, 0.5)) && any.isMet(Vec3.of(10.5, 64, 0.5))
                && !any.isMet(Vec3.of(5.5, 64, 0.5)));
        Goal all = Goal.allOf(Goal.column(2, 2), Goal.avoid(lava));
        Checks.check("allOf only where every one is", all.isMet(Vec3.of(2.5, 70, 2.5)) && !all.isMet(Vec3.of(2.5, 61, 2.5)));
        Checks.checkEquals("a combination's anchor is its first part's that has one", Vec3.of(0.5, 64, 0.5),
                any.anchor());
        Checks.checkThrows("a combination of nothing is refused", IllegalArgumentException.class,
                () -> Goal.anyOf());
        Checks.checkThrows("and so is a negative radius", IllegalArgumentException.class,
                () -> Goal.near(Vec3.ZERO, -1));
    }

    private static void gaps() {
        Checks.checkEquals("a block's gap from beside it is all across", Gap.of(2.5, 0),
                Goal.block(0, 64, 0).gap(Vec3.of(3.5, 64, 0.5)));
        Gap above = Goal.block(0, 64, 0).gap(Vec3.of(0.5, 70, 0.5));
        Checks.check("from above it is a drop to below its top (" + above + ")",
                above.getDrop() == 5d && above.getRise() == 0d && above.getAcross() == 0d);
        Gap below = Goal.block(0, 64, 0).gap(Vec3.of(0.5, 60, 0.5));
        Checks.check("from below, a climb to its floor (" + below + ")", below.getRise() == 4d && below.getDrop() == 0d);
        Checks.checkEquals("a met goal has no gap", Gap.NONE, Goal.block(0, 64, 0).gap(Vec3.of(0.5, 64, 0.5)));
        Checks.checkEquals("a gap's distance combines across and up", 5d, Gap.of(3, 4).distance());
        Checks.checkEquals("a negative across is no distance", Gap.NONE, Gap.of(-2, 0));

        Gap a = Gap.of(2, 3);
        Gap b = Gap.of(5, -1);
        Checks.checkEquals("the nearer of two gaps is the smaller of each part, up and down cancelling to level",
                Gap.of(2, 0), a.min(b));
        Gap both = a.max(b);
        Checks.check("the furthest is the larger of each part, so needing both up and down keeps both ("
                + both + ")", both.getAcross() == 5d && both.getRise() == 3d && both.getDrop() == 1d);
    }

    /** Every gap, from anywhere, is at most the distance to any position the goal is met at. */
    private static void lowerBounds() {
        final Vec3[] point = {Vec3.of(1, 66, -3)};
        List<Goal> goals = Arrays.asList(
                Goal.block(2, 64, -1),
                Goal.near(Vec3.of(0, 64, 0), 3),
                Goal.near(() -> point[0], 1.5),
                Goal.column(-4, 6),
                Goal.level(62),
                Goal.anyOf(Goal.block(4, 60, 4), Goal.near(Vec3.of(-5, 68, 2), 2)),
                Goal.allOf(Goal.column(1, 1), Goal.level(65)),
                Goal.allOf(Goal.near(Vec3.of(0, 64, 0), 4), Goal.avoid(Box.of(-1, 63, -1, 1, 66, 1))));
        Random random = new Random(42);
        int pairs = 0;
        int broken = 0;
        String example = "";
        for (Goal goal : goals) {
            List<Vec3> metAt = new ArrayList<>();
            for (int i = 0; i < 40000 && metAt.size() < 60; i++) {
                Vec3 candidate = Vec3.of(random.nextDouble() * 24 - 12, 56 + random.nextDouble() * 16,
                        random.nextDouble() * 24 - 12);
                if (goal.isMet(candidate)) {
                    metAt.add(candidate);
                }
            }
            for (int i = 0; i < 80; i++) {
                Vec3 from = Vec3.of(random.nextDouble() * 30 - 15, 50 + random.nextDouble() * 28,
                        random.nextDouble() * 30 - 15);
                Gap gap = goal.gap(from);
                for (Vec3 there : metAt) {
                    pairs++;
                    double across = from.horizontalDistanceTo(there);
                    double up = there.getY() - from.getY();
                    boolean ok = gap.getAcross() <= across + 1e-9
                            && gap.getRise() <= Math.max(0d, up) + 1e-9
                            && gap.getDrop() <= Math.max(0d, -up) + 1e-9;
                    if (!ok) {
                        broken++;
                        example = goal + " from " + from + " to " + there + ": " + gap;
                    }
                }
            }
        }
        Checks.check("no gap is ever more than the way to somewhere its goal is met, across, up or down ("
                + pairs + " pairs, " + broken + " over" + (broken > 0 ? ", e.g. " + example : "") + ")",
                broken == 0 && pairs > 10000);
    }

    private static void routes() {
        Goal goal = Goal.block(0, 64, 2);
        Route coarse = Route.coarse(goal, Arrays.asList(Vec3.of(0.5, 64, 0.5), Vec3.of(0.5, 64, 2.5)), true);
        Checks.check("a coarse route has waypoints and no ticks", !coarse.isPrecise() && coarse.getTicks() == 0
                && coarse.getWaypoints().size() == 2 && coarse.getStart() == null);
        Checks.checkEquals("and ends at its last waypoint", Vec3.of(0.5, 64, 2.5), coarse.getEnd());

        MotionState start = MotionState.at(Vec3.of(0.5, 64, 0.5));
        MotionState one = MotionState.at(Vec3.of(0.5, 64, 1.0));
        MotionState two = MotionState.at(Vec3.of(0.5, 64, 2.5));
        MovementInput forward = MovementInput.forward(0f);
        Route precise = Route.precise(goal, start, Arrays.asList(forward, forward), Arrays.asList(one, two), true);
        Checks.check("a precise route has a state after every input", precise.isPrecise() && precise.getTicks() == 2
                && precise.stateAt(1) == two && precise.inputAt(0) == forward);
        Checks.checkEquals("and its waypoints are those states' positions",
                Arrays.asList(one.getPosition(), two.getPosition()), precise.getWaypoints());
        Checks.checkThrows("a precise route with a state missing is refused", IllegalArgumentException.class,
                () -> Route.precise(goal, start, Arrays.asList(forward, forward), Collections.singletonList(one), true));
        Checks.checkThrows("and its lists cannot be changed afterwards", UnsupportedOperationException.class,
                () -> precise.getInputs().add(forward));
        Route empty = Route.precise(goal, start, Collections.<MovementInput>emptyList(),
                Collections.<MotionState>emptyList(), true);
        Checks.checkEquals("an empty precise route ends where it starts", start.getPosition(), empty.getEnd());
    }

    private static void progress() {
        Progress running = Progress.running(12.5);
        Checks.check("running is neither done nor arrived", running.isRunning() && !running.isDone()
                && running.getRemaining() == 12.5);
        Checks.check("arrived is done with nothing left", Progress.arrived().isDone()
                && Progress.arrived().getRemaining() == 0d);
        Progress failed = Progress.failed("walled in");
        Checks.check("failed is done and says why", failed.isFailed() && failed.isDone()
                && "walled in".equals(failed.getReason()));
        Progress stuck = Progress.stuck("no closer", 4);
        Checks.check("stuck is not done: still trying", stuck.isStuck() && !stuck.isDone());
        Checks.checkThrows("a failure without a reason is refused", IllegalArgumentException.class,
                () -> Progress.failed(null));
    }

    private static void providers() {
        PathProvider planner = (goal, from) -> null;
        Checks.check("a provider written as a lambda only plans", !planner.drives());
        Progress driven = planner.follow(Goal.level(64));
        Checks.check("and asked to drive, fails saying so rather than throwing (" + driven + ")",
                driven.isFailed() && driven.getReason().contains("only plans"));
        Checks.checkSurvives("cancelling one that never drove is harmless", planner::cancel);
    }
}
