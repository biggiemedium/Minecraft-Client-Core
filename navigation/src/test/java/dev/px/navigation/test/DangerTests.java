package dev.px.navigation.test;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.movement.prediction.Lookahead;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.navigation.Goal;
import dev.px.core.navigation.Progress;
import dev.px.core.navigation.Route;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.MovementRig;
import dev.px.navigation.Navigator;
import dev.px.navigation.Replan;
import dev.px.navigation.danger.DangerField;
import dev.px.navigation.danger.Hostiles;
import dev.px.navigation.plan.LocalPlanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Danger: hostiles weighed where they will be, not where they are.
 *
 * <p>The hostiles are bodies in Core's own {@link MovementRig}, moved by the real
 * rules and seen by the prediction service only as positions, exactly as other
 * players and mobs are seen in game. Whether a route kept its distance is judged
 * against where each body really went, played forward separately, never against
 * the prediction the planner used.
 */
public final class DangerTests {

    /** The rule every test uses: being within two blocks costs thirty ticks a tick. */
    private static final double NEAR = 2d;

    private DangerTests() {
    }

    public static void run() {
        Checks.section("Danger");

        standingHostile();
        movingHostile();
        field();
        refreshing();
        builder();
    }

    private static void standingHostile() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        MovementRig rig = new MovementRig(course.world);
        MovementRig.Body zombie = rig.add(Vec3.of(5.5, 64, 0.5), tick -> MovementInput.none(0f));
        for (int i = 0; i < 5; i++) {
            rig.tick();
        }
        Goal goal = Goal.block(11, 64, 0);

        Route careless = PlannerTests.planner(course).build().plan(goal, course.player);
        Checks.check("with no danger, the route walks straight past a hostile standing in the way ("
                + String.format("%.2f", closest(careless, standing(zombie))) + " blocks at closest)",
                closest(careless, standing(zombie)) < NEAR);

        Hostiles<MovementRig.Body> hostiles = hostiles(rig, Lookahead.<MovementRig.Body>none()).build();
        LocalPlanner wary = PlannerTests.planner(course).danger(hostiles).build();
        Route around = wary.plan(goal, course.player);
        Checks.check("weighing it, the route keeps its distance (" + String.format("%.2f", closest(around, standing(zombie)))
                + " blocks at closest, " + wary.getLastStats() + ")",
                around != null && around.isComplete() && closest(around, standing(zombie)) >= NEAR);
        Checks.check("for a little longer than the straight route (" + around.getTicks() + " vs "
                + careless.getTicks() + " ticks)", around.getTicks() >= careless.getTicks());
        Checks.checkEquals("having weighed the one hostile there", 1, hostiles.getLastCount());
    }

    /**
     * A hostile walking across the path: avoided where it is now, the route walks
     * into it; avoided where it will be, it does not.
     */
    private static void movingHostile() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 40);
        MovementRig rig = new MovementRig(course.world);
        MovementRig.Body walker = rig.add(Vec3.of(6.5, 64, -10.5), tick -> MovementInput.forward(0f));
        for (int i = 0; i < 30; i++) {
            rig.tick();
        }
        List<Box> future = future(walker, course, 120);
        Goal goal = Goal.block(13, 64, 0);

        LocalPlanner naive = PlannerTests.planner(course)
                .danger(hostiles(rig, Lookahead.<MovementRig.Body>none()).build()).build();
        Route intoIt = naive.plan(goal, course.player);
        double naiveClosest = closest(intoIt, future);

        Hostiles<MovementRig.Body> predicted = hostiles(rig, null).predicted(rig.prediction).horizon(80).build();
        LocalPlanner ahead = PlannerTests.planner(course).danger(predicted).build();
        Route clear = ahead.plan(goal, course.player);
        double aheadClosest = closest(clear, future);

        Checks.check("avoiding where a walking hostile is now, the route still meets it where it goes ("
                + String.format("%.2f", naiveClosest) + " blocks at closest)", naiveClosest < 0.5d);
        Checks.check("avoiding where it will be, the route keeps clear of it (" + String.format("%.2f", aheadClosest)
                + " blocks at closest, " + ahead.getLastStats() + ")",
                clear != null && clear.isComplete() && aheadClosest >= NEAR - 0.1d);
    }

    private static void field() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        MovementRig rig = new MovementRig(course.world);
        MovementRig.Body runner = rig.add(Vec3.of(0.5, 64, 6.5), tick -> MovementInput.forward(-90f));
        rig.add(Vec3.of(0.5, 64, -20.5), tick -> MovementInput.none(0f));
        for (int i = 0; i < 6; i++) {
            rig.tick();
        }
        Box player = Box.around(Vec3.of(0.5, 64, 0.5), 0.6, 1.8);

        final List<Double> distances = new ArrayList<>();
        Hostiles<MovementRig.Body> measuring = hostiles(rig, Lookahead.<MovementRig.Body>none())
                .rule((hostile, distance, tick) -> {
                    distances.add(distance);
                    return -5d;
                })
                .build();
        DangerField field = measuring.at(MotionState.at(Vec3.of(0.5, 64, 0.5)), 10);
        double cost = field.cost(player, 3);
        Box runnerBox = rig.tracked(runner).getBox();
        Checks.check("the rule is told the gap between the boxes (" + distances + ")",
                distances.size() == 2 && (Math.abs(distances.get(0) - gap(player, runnerBox)) < 1e-9
                        || Math.abs(distances.get(1) - gap(player, runnerBox)) < 1e-9));
        Checks.checkEquals("and a rule that answers less than nothing adds nothing", 0d, cost);

        Hostiles<MovementRig.Body> one = hostiles(rig, Lookahead.<MovementRig.Body>none()).limit(1).build();
        one.at(MotionState.at(Vec3.ZERO), 10);
        Checks.checkEquals("only as many hostiles as the limit are weighed", 1, one.getLastCount());
        Hostiles<MovementRig.Body> none = hostiles(rig, Lookahead.<MovementRig.Body>none()).limit(0).build();
        Checks.check("and with none to weigh, the field is no danger at all",
                none.at(MotionState.at(Vec3.ZERO), 10) == DangerField.NONE);

        Hostiles<MovementRig.Body> short_ = hostiles(rig, Lookahead.<MovementRig.Body>extrapolated()).horizon(4).build();
        DangerField capped = short_.at(MotionState.at(Vec3.of(0.5, 64, 0.5)), 60);
        Box near = Box.around(rig.tracked(runner).extrapolate(4), 0.6, 1.8);
        Checks.check("past the horizon, where it was expected to be at the horizon stands for every later tick",
                capped.cost(near, 4) == capped.cost(near, 40) && capped.cost(near, 4) > 0d);
    }

    private static void refreshing() {
        Course course = Course.flat(Vec3.of(0.5, 64, 0.5), 30);
        Navigator navigator = Navigator.builder()
                .provider(PlannerTests.planner(course).build())
                .simulation(course.simulation)
                .controls(course.controls)
                .rotations(course.rotations)
                .replanEvery(10)
                .build();
        navigator.travel(Goal.block(14, 64, 0));
        Progress progress = course.run(navigator, 200);
        Checks.check("a navigator told to refresh plans again on schedule, for danger that moves ("
                + navigator.getStats() + ")", navigator.getStats().count(Replan.REFRESH) >= 2 && progress.isArrived());
    }

    private static void builder() {
        try {
            Hostiles.builder().build();
            Checks.check("hostiles with nothing name everything they need", false);
        } catch (IllegalStateException expected) {
            Checks.checkEquals("hostiles with nothing name everything they need",
                    "a Hostiles needs: targets, selector, where they will be (predicted or a lookahead), rule", expected.getMessage());
        }
    }

    // ------------------------------------------------------------- harness

    /** The test's hostiles: every body in the rig, within two blocks costing thirty ticks a tick. */
    static Hostiles.Builder<MovementRig.Body> hostiles(MovementRig rig, Lookahead<MovementRig.Body> lookahead) {
        Hostiles.Builder<MovementRig.Body> builder = Hostiles.<MovementRig.Body>builder()
                .targets(new TargetService(rig.entities))
                .selector(TargetSelector.from(rig.bodies).build())
                .rule((hostile, distance, tick) -> distance < NEAR ? 30d : 0d);
        return lookahead == null ? builder : builder.lookahead(lookahead);
    }

    /** Where a body really goes: its own script, played forward by the rules from where it truly is. */
    static List<Box> future(MovementRig.Body body, Course course, int ticks) {
        List<Box> boxes = new ArrayList<>();
        MotionState state = body.state;
        boxes.add(state.hitbox(0.6, 1.8));
        for (int tick = 0; tick < ticks; tick++) {
            state = Simulation.step(body.rules, state, body.script.at(body.tick + tick), course.world);
            boxes.add(state.hitbox(0.6, 1.8));
        }
        return boxes;
    }

    private static List<Box> standing(MovementRig.Body body) {
        List<Box> boxes = new ArrayList<>();
        boxes.add(body.state.hitbox(0.6, 1.8));
        return boxes;
    }

    /** @return the closest the route's player came to the body, tick for tick; the last box stands for later ticks */
    static double closest(Route route, List<Box> body) {
        if (route == null) {
            return Double.NaN;
        }
        double closest = gap(route.getStart().hitbox(0.6, 1.8), body.get(0));
        for (int i = 0; i < route.getTicks(); i++) {
            Box at = body.get(Math.min(i + 1, body.size() - 1));
            closest = Math.min(closest, gap(route.stateAt(i).hitbox(0.6, 1.8), at));
        }
        return closest;
    }

    private static double gap(Box a, Box b) {
        double dx = Math.max(0d, Math.max(b.getMinX() - a.getMaxX(), a.getMinX() - b.getMaxX()));
        double dy = Math.max(0d, Math.max(b.getMinY() - a.getMaxY(), a.getMinY() - b.getMaxY()));
        double dz = Math.max(0d, Math.max(b.getMinZ() - a.getMaxZ(), a.getMinZ() - b.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
