package dev.px.navigation.test;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.rotation.RotationPriority;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.PathProvider;
import dev.px.core.navigation.Progress;
import dev.px.core.navigation.Route;
import dev.px.core.test.harness.Checks;
import dev.px.navigation.Navigator;
import dev.px.navigation.NavigatorStats;
import dev.px.navigation.Replan;
import dev.px.navigation.plan.LocalPlanner;

import java.util.ArrayList;
import java.util.List;

/**
 * The navigator: following routes through Core's controls, and noticing when to
 * plan again.
 *
 * <p>Each test drives a player through {@link Course}, where the game is Core's
 * own simulation and the keys are whatever the control service resolves. A route
 * followed with nothing disturbing it must arrive having planned exactly once;
 * everything else the navigator promises is provoked on purpose &mdash; a shove,
 * a wall built in the way, a goal that walks off, a current the simulation does
 * not know about &mdash; and the reason it planned again checked by name.
 */
public final class NavigatorTests {

    private NavigatorTests() {
    }

    public static void run() {
        Checks.section("Navigator");

        arrives();
        drift();
        blocksChange();
        movingGoal();
        coarseWithPlanner();
        coarseSteered();
        driving();
        failing();
        stuck();
        claims();
        builder();
    }

    private static void arrives() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        Navigator navigator = navigator(course, planner(course)).build();
        Checks.check("a navigator with no goal is not travelling", !navigator.isTravelling());
        navigator.travel(Goal.block(10, 64, 6));
        Progress progress = course.run(navigator, 200);
        NavigatorStats stats = navigator.getStats();
        Checks.check("it arrives (" + progress + " after " + course.ticks + " ticks)", progress.isArrived());
        Checks.check("with the feet in the goal", Goal.block(10, 64, 6).isMet(course.player.getPosition()));
        Checks.checkEquals("having planned once, since the game did exactly what was planned", 1, stats.getPlans());
        Checks.checkEquals("for the start", 1, stats.count(Replan.START));
        Checks.check("following the route key for key (" + stats.getFollowedTicks() + " ticks)",
                stats.getFollowedTicks() > 0 && stats.getFollowedTicks() == course.ticks - 1);

        boolean faced = true;
        for (int i = 0; i < course.head.applied.size() && i < course.keys.moves.size(); i++) {
            faced &= course.head.applied.get(i).getYaw() == course.keys.moves.get(i).getYaw();
        }
        Checks.check("facing the yaw each tick's keys are meant for", faced && !course.head.applied.isEmpty());

        course.tick(navigator);
        Checks.check("once there, it lets go of the keys", course.controls.getMovementHolder() == null);
        Checks.check("and of the head", course.rotations.getHolder() == null);
        Checks.check("but keeps the goal", navigator.isTravelling() && navigator.getProgress().isArrived());
    }

    private static void drift() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        Navigator navigator = navigator(course, planner(course)).build();
        navigator.travel(Goal.block(12, 64, 0));
        for (int i = 0; i < 8; i++) {
            course.tick(navigator);
        }
        course.player = course.player.withVelocity(Vec3.of(-0.6, 0.4, 0.8));
        Progress progress = course.run(navigator, 250);
        NavigatorStats stats = navigator.getStats();
        Checks.check("knocked off its route, it plans again for drift (" + stats + ")",
                stats.count(Replan.DRIFT) >= 1);
        Checks.check("noting how far off it was (" + String.format("%.2f", stats.getWorstDrift()) + " blocks)",
                stats.getWorstDrift() > Navigator.DEFAULT_DRIFT);
        Checks.check("and still arrives (" + progress + ")", progress.isArrived());
    }

    private static void blocksChange() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        Navigator navigator = navigator(course, planner(course)).build();
        navigator.travel(Goal.block(16, 64, 0));
        course.tick(navigator);
        course.world.wallAtX(11, 64, -3, 3);
        Progress progress = course.run(navigator, 300);
        NavigatorStats stats = navigator.getStats();
        Checks.check("a wall built across the route is noticed before it is reached (" + stats + ")",
                stats.count(Replan.BLOCKS_CHANGED) >= 1 && stats.count(Replan.DRIFT) == 0);
        Checks.check("and gone round (" + progress + ")", progress.isArrived());
    }

    private static void movingGoal() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        final Vec3[] target = {Vec3.of(10.5, 64, 0.5)};
        Navigator navigator = navigator(course, planner(course)).build();
        navigator.travel(Goal.near(() -> target[0], 1));
        for (int i = 0; i < 10; i++) {
            course.tick(navigator);
        }
        target[0] = Vec3.of(6.5, 64, 9.5);
        Progress progress = course.run(navigator, 250);
        NavigatorStats stats = navigator.getStats();
        Checks.check("a goal that moves is planned for again (" + stats + ")", stats.count(Replan.GOAL_MOVED) >= 1);
        Checks.check("and reached where it went (" + progress + ")",
                progress.isArrived() && course.player.getPosition().distanceTo(target[0]) <= 1d);

        target[0] = Vec3.of(14.5, 64, 9.5);
        Progress again = course.run(navigator, 250);
        Checks.check("after arriving, a goal that moves away is followed again (" + again + ")",
                again.isArrived() && course.player.getPosition().distanceTo(target[0]) <= 1d);
    }

    private static void coarseWithPlanner() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 50);
        List<Vec3> waypoints = new ArrayList<>();
        for (int x = 4; x <= 40; x += 4) {
            waypoints.add(Vec3.of(x + 0.5, 64, 0.5));
        }
        CoarseProvider coarse = new CoarseProvider(waypoints);
        Navigator navigator = navigator(course, coarse).local(plannerBuilder(course).range(20).build()).sectionReach(12).build();
        navigator.travel(Goal.block(40, 64, 0));
        Progress progress = course.run(navigator, 400);
        NavigatorStats stats = navigator.getStats();
        Checks.check("a coarse route is followed a stretch at a time by the local planner (" + stats + ")",
                stats.count(Replan.SECTION) >= 3 && coarse.plans == 1);
        Checks.check("precisely, key for key", stats.getFollowedTicks() > 0 && stats.getSteeredTicks() == 0);
        Checks.check("all the way (" + progress + ")", progress.isArrived());
    }

    private static void coarseSteered() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 50);
        List<Vec3> waypoints = new ArrayList<>();
        waypoints.add(Vec3.of(6.5, 64, 0.5));
        waypoints.add(Vec3.of(6.5, 64, 8.5));
        waypoints.add(Vec3.of(12.5, 65, 8.5));
        for (int z = 6; z <= 11; z++) {
            for (int x = 9; x <= 14; x++) {
                course.world.solid(x, 64, z);
            }
        }
        Navigator navigator = navigator(course, new CoarseProvider(waypoints)).build();
        navigator.travel(Goal.block(12, 65, 8));
        Progress progress = course.run(navigator, 400);
        NavigatorStats stats = navigator.getStats();
        Checks.check("without a local planner a coarse route is steered along, jumping up a block on the way ("
                + progress + ", " + stats + ")", progress.isArrived() && stats.getSteeredTicks() > 0
                && stats.getFollowedTicks() == 0);
    }

    private static void driving() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 10);
        DrivingProvider baritone = new DrivingProvider();
        Navigator navigator = navigator(course, baritone).build();
        navigator.travel(Goal.block(5, 64, 5));
        Progress first = course.tick(navigator);
        Checks.check("a provider that drives is asked to follow the goal each tick", baritone.follows == 1
                && first.isRunning() && first.getRemaining() == 7d);
        baritone.arrived = true;
        Checks.check("and its progress is passed on", course.tick(navigator).isArrived());
        Checks.check("the navigator claims nothing itself", course.controls.getMovementHolder() == null);
        navigator.stop();
        Checks.check("stopping cancels it", baritone.cancels == 1 && !navigator.isTravelling());
    }

    private static void failing() {
        Course course = new Course(Vec3.of(0.5, 64, 0.5));
        course.world.solid(0, 63, 0);
        for (int y = 64; y <= 67; y++) {
            course.world.solid(1, y, 0).solid(-1, y, 0).solid(0, y, 1).solid(0, y, -1);
        }
        Navigator navigator = navigator(course, planner(course)).build();
        navigator.travel(Goal.block(6, 64, 0));
        Progress progress = course.tick(navigator);
        Checks.check("with no way there it fails, done", progress.isFailed() && progress.isDone());
        Checks.check("saying why, in the planner's words: " + progress.getReason(),
                progress.getReason().contains("found no way") && progress.getReason().contains("no route"));
        Checks.check("claiming nothing", course.controls.getMovementHolder() == null);
        for (int i = 0; i < 5; i++) {
            course.tick(navigator);
        }
        Checks.checkEquals("and it stays failed rather than planning every tick", 1, navigator.getStats().getPlans());
        navigator.travel(Goal.block(0, 64, 0));
        Checks.check("until it is sent somewhere again", navigator.isTravelling());
    }

    private static void stuck() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 20);
        PathProvider standing = (goal, from) -> {
            List<MovementInput> inputs = new ArrayList<>();
            List<MotionState> states = new ArrayList<>();
            MotionState state = from;
            for (int i = 0; i < 60; i++) {
                state = dev.px.core.movement.simulation.Simulation.step(Course.VANILLA, state,
                        MovementInput.none(0f), course.world);
                inputs.add(MovementInput.none(0f));
                states.add(state);
            }
            return Route.precise(goal, from, inputs, states, false);
        };
        Navigator navigator = navigator(course, standing).stuckTicks(20).build();
        navigator.travel(Goal.block(8, 64, 0));
        Progress progress = null;
        for (int i = 0; i < 25; i++) {
            progress = course.tick(navigator);
        }
        Checks.check("getting no closer for its stuck time, it reports stuck: " + progress,
                progress.isStuck() && progress.getReason().contains("no closer"));
        Checks.check("but is not done: what to do about it is yours", !progress.isDone() && navigator.isTravelling());

        Course current = Course.flat(Vec3.of(0.5, 64, 0.5), 40);
        current.push = Vec3.of(0, 0, 0.35);
        Navigator swept = navigator(current, planner(current)).stuckTicks(0).build();
        swept.travel(Goal.block(15, 64, 0));
        Progress carried = null;
        for (int i = 0; i < 12; i++) {
            carried = current.tick(swept);
        }
        Checks.check("pushed off every route it plans by something the simulation does not know, it says so: "
                + carried, carried.isStuck() && carried.getReason().contains("simulation"));
    }

    private static void claims() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 20);
        Navigator navigator = navigator(course, planner(course)).priority(RotationPriority.LOW).build();
        navigator.travel(Goal.block(10, 64, 0));
        Object aura = new Object();
        course.rotations.beginTick();
        course.controls.beginTick();
        navigator.tick(course.player);
        course.controls.move(aura, MovementInput.none(0f), RotationPriority.HIGH);
        course.rotations.request(aura, Vec2.rotation(90f, 0f), RotationPriority.HIGH);
        Checks.check("a higher claim from elsewhere takes the keys from the navigator",
                course.controls.getMovementHolder() == aura);
        Checks.check("and the head", course.rotations.getHolder() == aura);
        navigator.stop();
        Checks.check("stopping lets go of its own claims, and only those",
                !course.controls.hasMovementClaim(navigator) && course.controls.hasMovementClaim(aura));
    }

    private static void builder() {
        try {
            Navigator.builder().build();
            Checks.check("a navigator with nothing names everything it needs", false);
        } catch (IllegalStateException expected) {
            Checks.checkEquals("a navigator with nothing names everything it needs",
                    "a Navigator needs: provider, controls, simulation", expected.getMessage());
        }
    }

    // ------------------------------------------------------------- harness

    private static Navigator.Builder navigator(Course course, PathProvider provider) {
        return Navigator.builder()
                .provider(provider)
                .simulation(course.simulation)
                .controls(course.controls)
                .rotations(course.rotations);
    }

    private static LocalPlanner.Builder plannerBuilder(Course course) {
        return LocalPlanner.builder().simulation(course.simulation);
    }

    private static LocalPlanner planner(Course course) {
        return plannerBuilder(course).build();
    }

    /** A long-range pathfinder's answer: blocks to pass through, nothing about how. */
    private static final class CoarseProvider implements PathProvider {

        private final List<Vec3> waypoints;
        int plans;

        private CoarseProvider(List<Vec3> waypoints) {
            this.waypoints = waypoints;
        }

        @Override
        public Route plan(Goal goal, MotionState from) {
            plans++;
            return Route.coarse(goal, waypoints, true);
        }
    }

    /** Stands in for Baritone: drives the player itself and reports how it is going. */
    private static final class DrivingProvider implements PathProvider {

        int follows;
        int cancels;
        boolean arrived;

        @Override
        public Route plan(Goal goal, MotionState from) {
            return null;
        }

        @Override
        public boolean drives() {
            return true;
        }

        @Override
        public Progress follow(Goal goal) {
            follows++;
            return arrived ? Progress.arrived() : Progress.running(7d);
        }

        @Override
        public void cancel() {
            cancels++;
        }
    }
}
