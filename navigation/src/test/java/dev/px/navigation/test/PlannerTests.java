package dev.px.navigation.test;

import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.Route;
import dev.px.core.test.harness.Checks;
import dev.px.navigation.plan.Gait;
import dev.px.navigation.plan.LocalPlanner;
import dev.px.navigation.plan.PlanStats;

import java.util.List;

/**
 * The local planner: exact keys, tick by tick, that the rules really carry out.
 *
 * <p>Every route is checked against a replay: its inputs played through
 * {@link dev.px.core.movement.simulation.Simulation} from where it starts, which
 * is what the game will do with them. A planner that writes down a state it
 * would not actually reach fails there, whatever its route looks like.
 */
public final class PlannerTests {

    private PlannerTests() {
    }

    public static void run() {
        Checks.section("Local planner");

        openGround();
        replaysExactly();
        quickest();
        gapJump();
        ledges();
        hole();
        drops();
        rules();
        limits();
        nothingToDo();
        builder();
    }

    private static void openGround() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        LocalPlanner planner = planner(course).build();
        Route route = planner.plan(Goal.block(12, 64, 5), course.player);
        PlanStats stats = planner.getLastStats();
        Checks.check("a route across open ground is found (" + stats + ")",
                route != null && stats.getOutcome() == PlanStats.Outcome.FOUND && route.isComplete());
        Checks.check("it is precise: keys and an expected state for every tick", route.isPrecise()
                && route.getTicks() == route.getStates().size() && route.getTicks() > 0);
        Checks.check("and ends with the feet in the goal",
                Goal.block(12, 64, 5).isMet(route.getEnd()));
        boolean earlier = false;
        for (int i = 0; i < route.getTicks() - 1; i++) {
            earlier |= Goal.block(12, 64, 5).isMet(route.stateAt(i).getPosition());
        }
        Checks.check("on the tick it first gets there", !earlier);

        Course second = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        LocalPlanner long_ = planner(second).moveTicks(20).build();
        Route midway = long_.plan(Goal.block(3, 64, 0), second.player);
        Checks.check("even halfway through a move: with moves twenty ticks long, three blocks takes fewer ("
                        + (midway == null ? "none" : midway.getTicks() + " ticks") + ")",
                midway != null && midway.isComplete() && midway.getTicks() < 20);
        boolean sprints = false;
        for (MovementInput input : route.getInputs()) {
            sprints |= input.isSprint();
        }
        Checks.check("sprinting, since nothing stops it (" + route.getTicks() + " ticks for 13 blocks)", sprints);
        Checks.check("in under a tenth of a second here (" + stats.getMicros() + "us, "
                + stats.getExpanded() + " states)", stats.getMicros() < 100_000L);
        Checks.check("asking the world about each block once (" + course.counted.asked.size() + " blocks, "
                        + course.counted.repeats + " asked twice)",
                course.counted.repeats == 0 && stats.getWorldQueries() == course.counted.asked.size());
    }

    private static void replaysExactly() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        course.world.wallAtX(5, 64, -3, 3);
        course.world.solid(8, 64, 6, 0.5);
        LocalPlanner planner = planner(course).build();
        Route route = planner.plan(Goal.block(10, 64, 0), course.player);
        Checks.check("a route round a wall is found (" + planner.getLastStats() + ")", route != null && route.isComplete());
        List<MotionState> replayed = Course.replay(route, course.world);
        boolean same = replayed.size() == route.getTicks();
        for (int i = 0; same && i < replayed.size(); i++) {
            same = replayed.get(i).equals(route.stateAt(i));
        }
        Checks.check("its states are exactly what the rules do with its keys, every tick", same);
        boolean clear = true;
        for (MotionState state : route.getStates()) {
            double x = state.getPosition().getX();
            double z = state.getPosition().getZ();
            clear &= !(x > 4.7 && x < 6.3 && z > -3.3 && z < 4.3);
        }
        Checks.check("and it goes round the wall, not through it", clear);
    }

    private static void quickest() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        LocalPlanner strict = planner(course).greed(1).maxStates(30000).build();
        Route best = strict.plan(Goal.block(14, 64, -6), course.player);
        LocalPlanner greedy = planner(course).build();
        Route quick = greedy.plan(Goal.block(14, 64, -6), course.player);
        LocalPlanner walking = planner(course).gaits(Gait.WALK).build();
        Route walked = walking.plan(Goal.block(14, 64, -6), course.player);
        Checks.check("a strict search finds the quickest route, which takes no longer than the default search's ("
                + best.getTicks() + " vs " + quick.getTicks() + " ticks)", best.getTicks() <= quick.getTicks());
        Checks.check("the default's route is at most twice as slow as promised, here no slower than a tenth ("
                + quick.getTicks() + " ticks)", quick.getTicks() <= best.getTicks() * 1.1);
        Checks.check("for far fewer states (" + greedy.getLastStats().getExpanded() + " vs "
                + strict.getLastStats().getExpanded() + ")",
                greedy.getLastStats().getExpanded() * 10 < strict.getLastStats().getExpanded());
        Checks.check("and walking only takes longer than sprinting (" + walked.getTicks() + " vs "
                + best.getTicks() + " ticks)", walked.getTicks() > best.getTicks());
        boolean walksOnly = true;
        for (MovementInput input : walked.getInputs()) {
            walksOnly &= !input.isSprint() && !input.isJump();
        }
        Checks.check("using only the gaits it was given", walksOnly);
    }

    /** A three-block gap over the void: only a sprint-jump with a run-up clears it. */
    private static void gapJump() {
        Course course = new Course(Vec3.of(-3.5, 64, 0.5));
        for (int x = -8; x <= 12; x++) {
            if (x >= 2 && x <= 4) {
                continue;
            }
            for (int z = -2; z <= 2; z++) {
                course.world.solid(x, 63, z);
            }
        }
        LocalPlanner planner = planner(course).build();
        Route route = planner.plan(Goal.block(8, 64, 0), course.player);
        Checks.check("a three-block gap is crossed (" + planner.getLastStats() + ")",
                route != null && route.isComplete());
        boolean jumped = false;
        boolean sprintJumped = false;
        double lowest = Double.MAX_VALUE;
        for (int i = 0; route != null && i < route.getTicks(); i++) {
            jumped |= route.inputAt(i).isJump();
            sprintJumped |= route.inputAt(i).isJump() && route.inputAt(i).isSprint();
            lowest = Math.min(lowest, route.stateAt(i).getPosition().getY());
        }
        Checks.check("by sprint-jumping", jumped && sprintJumped);
        Checks.check("without ever dropping into it (lowest feet " + lowest + ")", lowest >= 64d);

        LocalPlanner noSprint = planner(course).canSprint(() -> false).build();
        Route walked = noSprint.plan(Goal.block(8, 64, 0), course.player);
        Checks.check("without sprinting it cannot be crossed, and the plan says so ("
                        + noSprint.getLastStats().getOutcome() + ")",
                walked == null || !walked.isComplete());
    }

    private static void ledges() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 20);
        for (int z = -20; z <= 20; z++) {
            course.world.solid(4, 64, z);
            course.world.solid(5, 64, z);
            course.world.solid(6, 64, z);
        }
        LocalPlanner planner = planner(course).build();
        Route up = planner.plan(Goal.block(5, 65, 0), course.player);
        boolean jumped = false;
        for (int i = 0; up != null && i < up.getTicks(); i++) {
            jumped |= up.inputAt(i).isJump();
        }
        Checks.check("a block-high ledge is jumped onto (" + planner.getLastStats() + ")",
                up != null && up.isComplete() && jumped);

        Course slabs = Course.flat(Vec3.of(0.5, 64, 0.5), 20);
        for (int z = -20; z <= 20; z++) {
            slabs.world.solid(4, 64, z, 0.5);
            slabs.world.solid(5, 64, z, 0.5);
        }
        LocalPlanner walking = planner(slabs).gaits(Gait.WALK).build();
        Route stepped = walking.plan(Goal.block(5, 64, 0), slabs.player);
        Checks.check("a half-block step is walked up without jumping",
                stepped != null && stepped.isComplete() && Math.abs(stepped.getEnd().getY() - 64.5) < 1e-6);
    }

    private static void hole() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 20);
        course.world.remove(6, 63, 3);
        course.world.solid(6, 62, 3);
        LocalPlanner planner = planner(course).build();
        Route route = planner.plan(Goal.block(6, 63, 3), course.player);
        Checks.check("a one-by-one hole is lined up with and stepped into (" + planner.getLastStats() + ")",
                route != null && route.isComplete());
        MotionState last = route == null ? null : route.stateAt(route.getTicks() - 1);
        Checks.check("its route ends with the feet down in it",
                last != null && Goal.block(6, 63, 3).isMet(last.getPosition()));
    }

    private static void drops() {
        Course course = Course.flat(Vec3.of(0.5, 72, 0.5), 20);
        course.world.floor(72, -2, 2);
        Goal below = Goal.block(7, 64, 0);
        LocalPlanner careless = planner(course).build();
        Checks.check("with no drop limit, an eight-block drop is taken",
                careless.plan(below, course.player) != null && careless.getLastStats().getOutcome() == PlanStats.Outcome.FOUND);
        LocalPlanner careful = planner(course).maxDrop(3).build();
        Route route = careful.plan(below, course.player);
        PlanStats stats = careful.getLastStats();
        Checks.check("with a three-block limit it is refused (" + stats + ")",
                (route == null || !route.isComplete()) && stats.getFellTooFar() > 0);
        Checks.check("and the reason names the limit: " + stats.getReason(),
                stats.getReason().contains("drop limit"));
    }

    private static void rules() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 20);
        LocalPlanner refusing = planner(course)
                .allowed(state -> state.getPosition().getX() < 3 || state.getPosition().getX() > 5)
                .build();
        Route route = refusing.plan(Goal.block(9, 64, 0), course.player);
        PlanStats stats = refusing.getLastStats();
        Checks.check("a band your rule refuses everywhere cannot be crossed (" + stats.getOutcome() + ")",
                route == null || !route.isComplete());
        Checks.check("and the reason says your rule refused it: " + stats.getReason(),
                stats.getRefused() > 0 && stats.getReason().contains("refused by your rule"));

        LocalPlanner around = planner(course)
                .allowed(state -> !(state.getPosition().getX() > 3 && state.getPosition().getX() < 6
                        && state.getPosition().getZ() < 4))
                .build();
        Route detour = around.plan(Goal.block(9, 64, 0), course.player);
        boolean outside = detour != null;
        for (int i = 0; detour != null && i < detour.getTicks(); i++) {
            Vec3 at = detour.stateAt(i).getPosition();
            outside &= !(at.getX() > 3 && at.getX() < 6 && at.getZ() < 4);
        }
        Checks.check("one it refuses only in part is gone round, every tick outside it", outside && detour.isComplete());
    }

    private static void limits() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 60);
        LocalPlanner short_ = planner(course).range(12).build();
        Route route = short_.plan(Goal.block(40, 64, 0), course.player);
        PlanStats stats = short_.getLastStats();
        Checks.check("a goal out of range gets a partial route towards it (" + stats.getOutcome() + ")",
                route != null && !route.isComplete() && stats.getOutcome() == PlanStats.Outcome.PARTIAL);
        double furthest = 0d;
        for (int i = 0; route != null && i < route.getTicks(); i++) {
            furthest = Math.max(furthest, route.stateAt(i).getPosition().distanceTo(course.player.getPosition()));
        }
        Checks.check("never leaving the range (furthest " + String.format("%.2f", furthest) + " of 12)",
                furthest <= 12d);
        Checks.check("ending closer than it began, on the ground", route != null
                && route.getEnd().getX() > 8 && route.stateAt(route.getTicks() - 1).isOnGround());

        LocalPlanner tight = planner(course).maxStates(5).build();
        Route rushed = tight.plan(Goal.block(20, 64, 0), course.player);
        Checks.check("a spent budget still returns the closest route found (" + tight.getLastStats() + ")",
                rushed != null && tight.getLastStats().isBudgetSpent() && !rushed.isComplete());

        Course shaft = new Course(Vec3.of(0.5, 64, 0.5));
        shaft.world.solid(0, 63, 0);
        for (int y = 64; y <= 67; y++) {
            shaft.world.solid(1, y, 0).solid(-1, y, 0).solid(0, y, 1).solid(0, y, -1);
        }
        LocalPlanner trapped = planner(shaft).build();
        Route none = trapped.plan(Goal.block(5, 64, 0), shaft.player);
        Checks.check("walled into a shaft, there is no route at all (" + trapped.getLastStats().getOutcome() + ")",
                none == null && trapped.getLastStats().getOutcome() == PlanStats.Outcome.NO_ROUTE);
        Checks.check("and the reason starts so: " + trapped.getLastStats().getReason(),
                trapped.getLastStats().getReason().startsWith("no route"));
    }

    private static void nothingToDo() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 10);
        LocalPlanner planner = planner(course).build();
        Route route = planner.plan(Goal.block(0, 64, 0), course.player);
        Checks.check("already at the goal, the route is empty and complete",
                route != null && route.getTicks() == 0 && route.isComplete()
                        && planner.getLastStats().getOutcome() == PlanStats.Outcome.ALREADY_THERE);
    }

    private static void builder() {
        Checks.checkThrows("a planner without a simulation names what it needs", IllegalStateException.class,
                () -> LocalPlanner.builder().build());
        try {
            LocalPlanner.builder().build();
        } catch (IllegalStateException expected) {
            Checks.checkEquals("in the usual words", "a LocalPlanner needs: simulation", expected.getMessage());
        }
        Checks.checkThrows("and a greed below one is refused", IllegalArgumentException.class,
                () -> LocalPlanner.builder().greed(0.5));
    }

    static LocalPlanner.Builder planner(Course course) {
        return LocalPlanner.builder().simulation(course.simulation);
    }
}
