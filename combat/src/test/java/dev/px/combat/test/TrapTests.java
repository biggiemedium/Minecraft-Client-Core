package dev.px.combat.test;

import dev.px.combat.place.Occupancy;
import dev.px.combat.place.PlacementPlanner;
import dev.px.combat.place.PlacementStyle;
import dev.px.combat.place.Plan;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.trap.TrapOption;
import dev.px.combat.trap.TrapOrder;
import dev.px.combat.trap.TrapPattern;
import dev.px.combat.trap.TrapSearch;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.prediction.Prediction;
import dev.px.core.movement.prediction.Scenario;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig.Body;
import dev.px.core.test.harness.MovementRig.Mover;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Auto-trap: who to trap, which cells, the likeliest way out first, and this
 * tick's placements in the style your server wants.
 *
 * <p>The world is an obsidian ground with one-block pits cut into it. You stand
 * at the origin; enemies and friends are scripted bodies. Placing is pretend:
 * a test that "places" a plan sets its blocks in the ground.
 */
public final class TrapTests {

    private static final MovementRig.Script STILL = tick -> MovementInput.none(0f);

    private TrapTests() {
    }

    public static void run() {
        Checks.section("Auto-trap");

        patterns();
        occupancy();
        styles();
        inAHole();
        inTheOpen();
        oneATick();
        moving();
        pending();
        choosing();
        lifecycle();
    }

    // --------------------------------------------------------------- patterns

    private static void patterns() {
        Box one = Box.of(3.2d, 1d, 0.2d, 3.8d, 2.8d, 0.8d);           // a player in one column, feet at y 1
        List<TrapPattern.Cell> full = TrapPattern.FULL.cells(one);
        Checks.checkEquals("full: four at the feet, four at the head, one roof", 9, full.size());
        Checks.check("bottom up, each cell where its part says (" + full + ")",
                count(full, TrapPattern.Part.FEET, 1) == 4 && count(full, TrapPattern.Part.HEAD, 2) == 4
                        && count(full, TrapPattern.Part.ROOF, 3) == 1
                        && full.get(8).getCell().equals(Vec3i.of(3, 3, 0)));
        List<TrapPattern.Cell> top = TrapPattern.TOP_ONLY.cells(one);
        Checks.check("top only: the head ring and roof, nothing at the feet",
                top.size() == 5 && count(top, TrapPattern.Part.FEET, 1) == 0);
        List<TrapPattern.Cell> step = TrapPattern.ANTI_STEP.cells(one);
        Checks.check("anti-step: a ring above the head ring too, so they cannot step up and out",
                step.size() == 13 && count(step, TrapPattern.Part.EXTRA, 3) == 4);
        TrapPattern custom = TrapPattern.builder().roof().offset(TrapPattern.Part.EXTRA, 0, 3, 0).build();
        List<TrapPattern.Cell> mine = custom.cells(one);
        Checks.check("your own offsets, from each cell their feet are in (" + mine + ")", mine.size() == 2
                && mine.get(1).getCell().equals(Vec3i.of(3, 4, 0))
                && mine.get(1).getPart() == TrapPattern.Part.EXTRA);
        Checks.check("an offset into the player is left out",
                TrapPattern.builder().offset(TrapPattern.Part.EXTRA, 0, 1, 0).build().cells(one).isEmpty());

        Box straddling = Box.of(3.7d, 1d, 0.2d, 4.3d, 2.8d, 0.8d);    // across two columns
        List<TrapPattern.Cell> wide = TrapPattern.FULL.cells(straddling);
        Checks.check("across two columns, rings around both and a roof over both (" + wide.size() + ")",
                count(wide, TrapPattern.Part.FEET, 1) == 6 && count(wide, TrapPattern.Part.ROOF, 3) == 2);
        Checks.checkThrows("a pattern with nothing in it is refused", IllegalStateException.class,
                () -> TrapPattern.builder().build());
    }

    private static int count(List<TrapPattern.Cell> cells, TrapPattern.Part part, int y) {
        int n = 0;
        for (TrapPattern.Cell cell : cells) {
            if (cell.getPart() == part && cell.getCell().getY() == y) {
                n++;
            }
        }
        return n;
    }

    // -------------------------------------------------------------- occupancy

    private static void occupancy() {
        Field field = new Field();
        Body still = field.enemy(Vec3.of(3.5d, 1d, 3.5d), STILL);
        Body walker = field.enemy(Vec3.of(-3.5d, 1d, -6.5d), field.walking(0f));   // yaw 0 walks toward +z
        field.run(25);
        Prediction standing = field.rig.prediction.predict(field.rig.tracked(still), 10);
        Occupancy here = Occupancy.of(standing, 0, 10);
        Checks.check("someone standing still is surely in their own cells, and not beside them ("
                        + here.chance(Vec3i.of(3, 1, 3)) + ")",
                here.chance(Vec3i.of(3, 1, 3)) > 0.9d && here.chance(Vec3i.of(3, 2, 3)) > 0.9d
                        && here.chance(Vec3i.of(4, 1, 3)) < 0.05d);
        Prediction walking = field.rig.prediction.predict(field.rig.tracked(walker), 10);
        Occupancy ahead = Occupancy.of(walking, 0, 10);
        List<Occupancy.Visit> visits = ahead.cells(0.5d);
        boolean inOrder = true;
        for (int i = 1; i < visits.size(); i++) {
            inOrder &= visits.get(i).getFirstTick() >= visits.get(i - 1).getFirstTick();
        }
        Box body = field.rig.tracked(walker).getBox();
        Vec3i next = Vec3i.of(-4, 1, (int) Math.floor(body.getMaxZ()) + 1);
        Vec3i behind = Vec3i.of(-4, 1, (int) Math.floor(body.getMinZ()) - 1);
        Checks.check("someone walking is likely in the cell ahead, later, and not the one behind (" + visits + ")",
                inOrder && ahead.chance(next) > 0.5d && ahead.visit(next).getFirstTick() > 0
                        && ahead.chance(behind) < 0.05d);
        Checks.check("likely cells are the cells past the chance, soonest first",
                ahead.likelyCells(0.5d).size() == visits.size() && ahead.likelyCells(0.5d).get(0).equals(visits.get(0).getCell()));
    }

    // ----------------------------------------------------------------- styles

    private static void styles() {
        Ground ground = new Ground();
        Reach reach = Reach.of(5.5d, 5.5d);
        PlacementStyle relaxed = PlacementStyle.vanilla(reach, ground);
        PlacementStyle careful = PlacementStyle.strict(reach, ground);
        Checks.check("the vanilla style places as many a tick as you like, the strict one one",
                relaxed.getPerTick().getAsInt() == Integer.MAX_VALUE && careful.getPerTick().getAsInt() == 1
                        && relaxed.getSupports() == 2 && careful.getSupports() == 2);
        Vec3 eye = Vec3.of(0.5d, 2.62d, 0.5d);
        Vec3i farSide = Vec3i.of(-1, 0, 0);                             // a cell whose west wall faces away from you
        ground.cut(-1, 0);
        int any = relaxed.clicks(ground::solid, ground::open).into(eye, farSide).size();
        int seen = careful.clicks(ground::solid, ground::open).into(eye, farSide).size();
        Checks.check("strictly, fewer faces count: only those turned toward you, seen and in reach (" + any + " vs "
                + seen + ")", seen > 0 && seen < any);
        PlacementStyle mine = careful.withPerTick(() -> 3).withSupports(1);
        Checks.check("a preset of yours starts from one and changes what it needs",
                mine.getPerTick().getAsInt() == 3 && mine.getSupports() == 1 && mine.getFaces() == careful.getFaces());
        PlacementPlanner planner = mine.planner(mine.clicks(ground::solid, ground::open), region -> false).build();
        List<Vec3i> row = new ArrayList<>();
        for (int x = 1; x <= 5; x++) {
            row.add(Vec3i.of(x, 1, 2));
        }
        Checks.check("and its planner keeps to its limit", planner.plan(eye, row).getSteps().size() == 3);
        Checks.checkThrows("negative supports are refused", IllegalArgumentException.class,
                () -> careful.withSupports(-1));
    }

    // --------------------------------------------------------------- in a hole

    private static void inAHole() {
        Field field = new Field(3, 0);
        Body enemy = field.enemy(Vec3.of(3.5d, 0d, 0.5d), STILL);
        field.run(25);
        TrapSearch<Body> search = field.search(field.vanilla(), b -> b);
        TrapOption<Body> trap = search.findTrap();
        Checks.check("an enemy in a hole is offered for trapping (" + trap + ", " + search.getLastStats() + ")",
                trap != null && trap.getTarget().get() == enemy && trap.isEnclosed());
        Checks.check("walled in already, only the head ring and roof are missing",
                trap.getMissing().size() == 5 && !trap.getMissing().contains(Vec3i.of(4, 0, 0)));
        Checks.check("the roof first: jumping is their only way out (" + trap.getCells() + ")",
                trap.getCells().get(0).getPart() == TrapPattern.Part.ROOF
                        && trap.getMissing().get(0).equals(Vec3i.of(3, 2, 0)));
        Plan plan = trap.getPlan();
        int roofAt = stepOf(plan, Vec3i.of(3, 2, 0));
        Checks.check("its supports go first: a block on the head ring and one on top of it, then the roof (" + plan + ")",
                roofAt == 2 && plan.getSteps().get(0).isSupport() && plan.getSteps().get(0).getCell().getY() == 1
                        && plan.getSteps().get(1).isSupport() && plan.getSteps().get(1).getCell().getY() == 2);
        Checks.check("nothing goes into the hole's head: the roof's support is beside it, never under it",
                stepOf(plan, Vec3i.of(3, 1, 0)) < 0);
        Checks.check("roofed by this tick's plan, and the rest of the head ring after",
                trap.isRoofed() && plan.isComplete() && plan.getSteps().size() == 6 && !trap.isSealed());

        field.place(plan);
        TrapOption<Body> again = search.findTrap();
        Checks.check("once all placed, they are sealed and not offered (" + search.getLastStats() + ")",
                again == null && search.getLastStats().getSealed() == 1);
    }

    private static int stepOf(Plan plan, Vec3i cell) {
        for (int i = 0; i < plan.getSteps().size(); i++) {
            if (plan.getSteps().get(i).getCell().equals(cell)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ in the open

    private static void inTheOpen() {
        Field field = new Field();
        field.enemy(Vec3.of(3.5d, 1d, 0.5d), STILL);
        field.run(25);
        TrapSearch<Body> search = field.search(field.vanilla(), b -> b);
        TrapOption<Body> trap = search.findTrap();
        Checks.check("someone standing in the open is offered too (" + search.getLastStats() + ")", trap != null);
        List<TrapPattern.Part> parts = new ArrayList<>();
        for (TrapPattern.Cell cell : trap.getCells()) {
            parts.add(cell.getPart());
        }
        Checks.check("in the open, the feet ring first: walking out is the quickest way out; then the roof, so they "
                        + "cannot jump onto the ring (" + parts + ")",
                !trap.isEnclosed() && parts.subList(0, 4).stream().allMatch(p -> p == TrapPattern.Part.FEET)
                        && parts.get(4) == TrapPattern.Part.ROOF);
        Checks.check("all nine placed this tick, unlimited, and one more beside the roof to click it against ("
                        + trap.getPlan() + ")",
                trap.getPlan().isComplete() && trap.getPlan().getSteps().size() == 10 && trap.isRoofed());

        TrapOption<Body> stacked = field.search(field.vanilla(), b -> b.order(() -> TrapOrder.BOTTOM_UP)).findTrap();
        Checks.check("bottom up, if you would rather: the roof last",
                stacked.getCells().get(stacked.getCells().size() - 1).getPart() == TrapPattern.Part.ROOF);
        TrapOption<Body> top = field.search(field.vanilla(), b -> b.pattern(() -> TrapPattern.TOP_ONLY)).findTrap();
        Checks.check("your pattern, read live: top only places no feet ring",
                top.getCells().size() == 5 && top.getMissing().size() == 5);
    }

    // ------------------------------------------------------------- one a tick

    private static void oneATick() {
        Field field = new Field(3, 0);
        field.enemy(Vec3.of(3.5d, 0d, 0.5d), STILL);
        field.run(25);
        TrapSearch<Body> search = field.search(field.strict(), b -> b);
        int most = 0;
        int roofedAfter = -1;
        int placed = 0;
        List<Vec3i> order = new ArrayList<>();
        List<Boolean> roofedEach = new ArrayList<>();
        for (int tick = 0; tick < 10; tick++) {
            TrapOption<Body> trap = search.findTrap();
            if (trap == null) {
                break;
            }
            most = Math.max(most, trap.getPlan().getSteps().size());
            roofedEach.add(trap.isRoofed());
            for (Plan.Step step : trap.getPlan().getSteps()) {
                order.add(step.getCell());
            }
            placed += trap.getPlan().getSteps().size();
            field.place(trap.getPlan());
            search.placed(trap);
            field.run(1);
            if (roofedAfter < 0 && !field.ground.open(3, 2, 0)) {
                roofedAfter = placed;
            }
        }
        Checks.checkEquals("strictly, one placement a tick", 1, most);
        Checks.check("each tick spent on the roof's chain till it is up: roofed in three (" + order + ")",
                roofedAfter == 3);
        Checks.check("roofed only once this tick's plan puts the roof up (" + roofedEach + ")",
                roofedEach.size() >= 3 && !roofedEach.get(0) && !roofedEach.get(1) && roofedEach.get(2));
    }

    // ----------------------------------------------------------------- moving

    private static void moving() {
        Field field = new Field();
        Body walker = field.enemy(Vec3.of(3.5d, 1d, -5.5d), field.walking(0f));
        field.run(25);
        TrapSearch<Body> leave = field.search(field.vanilla(), b -> b.placeDelay(() -> 2));
        Checks.check("someone moving is left alone unless you say (" + leave.getLastStats() + ")",
                leave.findTrap() == null && leave.getLastStats().getMoving() == 1);

        Field fresh = new Field();
        fresh.enemy(Vec3.of(3.5d, 1d, 0.5d), STILL);
        fresh.run(5);
        TrapSearch<Body> unsure = fresh.search(fresh.vanilla(), b -> b);
        Checks.check("someone in the open not seen long enough to tell counts as moving (" + unsure.getLastStats() + ")",
                unsure.findTrap() == null && unsure.getLastStats().getMoving() == 1);

        TrapSearch<Body> chase = field.search(field.vanilla(), b -> b.placeDelay(() -> 2).trapMoving(() -> true));
        TrapOption<Body> trap = chase.findTrap();
        Checks.check("trapping moving targets, they are trapped where they will be (" + trap + ", "
                + chase.getLastStats() + ")", trap != null && trap.getTarget().get() == walker);
        Vec3i first = trap.getCells().get(0).getCell();
        int furthest = Integer.MIN_VALUE;
        for (TrapPattern.Cell cell : trap.getCells()) {
            if (cell.getPart() == TrapPattern.Part.FEET) {
                furthest = Math.max(furthest, cell.getCell().getZ());
            }
        }
        Checks.check("the side they are walking out of first (" + trap.getCells() + ")",
                trap.getCells().get(0).getPart() == TrapPattern.Part.FEET && first.getZ() == furthest);
        Box now = field.rig.tracked(walker).getBox();
        boolean neverIn = true;
        for (Plan.Step step : trap.getPlan().getSteps()) {
            neverIn &= !step.getCell().toBox().intersects(now) && !trap.getDeferred().contains(step.getCell());
        }
        Checks.check("a cell they are in, or likely in when it lands, waits (deferred " + trap.getDeferred() + ")",
                neverIn);

        // Placing well ahead of them: the cells they stand in now are in the ring around where they will be.
        TrapOption<Body> ahead = field.search(field.vanilla(), b -> b.placeDelay(() -> 5).trapMoving(() -> true))
                .findTrap();
        boolean standing = false;
        boolean planned = false;
        for (Vec3i cell : ahead.getDeferred()) {
            standing |= cell.toBox().intersects(now);
            planned |= stepOf(ahead.getPlan(), cell) >= 0;
        }
        Checks.check("placing well ahead, the cells they stand in now wait for them to leave (" + ahead.getDeferred()
                + ")", standing && !planned && ahead.getMissing().containsAll(ahead.getDeferred()));

        // A future of theirs that swerves aside: its cells, when the blocks land, are only as likely as it is.
        List<Scenario> scenarios = new ArrayList<>(field.rig.prediction.getScenarios());
        scenarios.add(Scenario.of("swerves", (estimate, state, tick) -> estimate.getInput().withKeys(1, 1)));
        field.rig.prediction.setScenarios(scenarios.toArray(new Scenario[0]));
        TrapOption<Body> wary = field.search(field.vanilla(), b -> b.placeDelay(() -> 5).trapMoving(() -> true)
                .avoidChance(() -> 1e-12d)).findTrap();
        TrapOption<Body> bold = field.search(field.vanilla(), b -> b.placeDelay(() -> 5).trapMoving(() -> true)
                .avoidChance(() -> 2d)).findTrap();
        Checks.check("and the warier your avoid chance, the more waits: any future with them there ("
                + wary.getDeferred() + " vs " + bold.getDeferred() + ")",
                wary.getDeferred().size() > bold.getDeferred().size());
    }

    // ---------------------------------------------------------------- pending

    private static void pending() {
        Field field = new Field(3, 0);
        field.enemy(Vec3.of(3.5d, 0d, 0.5d), STILL);
        field.run(25);
        TrapSearch<Body> search = field.search(field.vanilla(), b -> b.pendingTicks(() -> 3));
        TrapOption<Body> trap = search.findTrap();
        search.placed(trap);
        Checks.check("a trap you placed is not planned again while the server catches up ("
                + search.getLastStats() + ")", search.findTrap() == null && search.getLastStats().getSealed() == 1);
        for (int i = 0; i < 3; i++) {
            search.tick();
        }
        TrapOption<Body> back = search.findTrap();
        Checks.check("until its wait runs out, with the blocks still not there", back != null
                && back.getMissing().size() == 5);
    }

    // --------------------------------------------------------------- choosing

    private static void choosing() {
        Field field = new Field(3, 0, 0, 3);
        Body near = field.enemy(Vec3.of(3.5d, 0d, 0.5d), STILL);
        Body done = field.enemy(Vec3.of(0.5d, 0d, 3.5d), STILL);
        Body friend = field.friend(Vec3.of(-2.5d, 1d, 0.5d), STILL);
        field.ground.set(0, 1, 2);                                      // part of a trap on the second already
        field.ground.set(0, 1, 4);
        field.run(25);
        TrapSearch<Body> search = field.search(field.vanilla(),
                b -> b.targets(field.targets, TargetSelector.from(field.rig.bodies).range(40).build()));   // everyone
        List<TrapOption<Body>> two = search.findTraps(3);
        Checks.check("the trap nearest done first (" + two + ", " + search.getLastStats() + ")",
                two.size() == 2 && two.get(0).getTarget().get() == done && two.get(1).getTarget().get() == near);
        Checks.check("and friends are never trapped, whoever your selector picks", friend != null
                && search.getLastStats().getTargets() == 2);
        TrapSearch<Body> filtered = field.search(field.vanilla(), b -> b
                .filter(trap -> trap.getTarget().get() != done));
        Checks.check("your filters have the last word", filtered.findTraps(2).size() == 1
                && filtered.getLastStats().getFiltered() == 1);
        TrapSearch<Body> reversed = field.search(field.vanilla(), b -> b
                .rank((a, c) -> TrapSearch.<Body>nearlyDone().compare(c, a)));
        Checks.check("and your own ranking replaces nearly-done", reversed.findTrap().getTarget().get() == near);
        TrapSearch<Body> one = field.search(field.vanilla(), b -> b.maxTargets(1));
        Checks.check("weighing only as many targets as you allow",
                one.findTraps(2).size() == 1 && one.getLastStats().getTargets() == 1);
    }

    // --------------------------------------------------------------- lifecycle

    private static void lifecycle() {
        String missing;
        try {
            TrapSearch.<Body>builder().build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("a trap search missing parts names them (" + missing + ")", missing != null
                && missing.contains("prediction") && missing.contains("entities") && missing.contains("targets")
                && missing.contains("planner"));
        Field far = new Field(12, 0);
        far.enemy(Vec3.of(12.5d, 0d, 0.5d), STILL);
        far.run(10);
        TrapSearch<Body> search = far.search(far.vanilla(), b -> b);
        Checks.check("someone out of reach is not offered (" + search.getLastStats() + ")",
                search.findTrap() == null && search.getLastStats().getUnplaceable() == 1);
        Checks.checkThrows("and a count below one is refused", IllegalArgumentException.class,
                () -> search.findTraps(0));
    }

    // ---------------------------------------------------------------- harness

    /** You at the origin on obsidian, holes cut into it, and whoever the test adds. */
    private static final class Field {
        final Ground ground = new Ground();
        final MovementRig rig = new MovementRig(ground.space);
        final List<Body> enemies = new ArrayList<>();
        final List<Body> friends = new ArrayList<>();
        final TargetService targets = new TargetService(rig.entities);

        /** @param holes x, z pairs of one-block holes */
        Field(int... holes) {
            for (int i = 0; i < holes.length; i += 2) {
                ground.cut(holes[i], holes[i + 1]);
            }
            rig.self(Vec3.of(0.5d, 1d, 0.5d), STILL);
        }

        Body enemy(Vec3 at, Object move) {
            Body body = add(at, move);
            enemies.add(body);
            return body;
        }

        Body friend(Vec3 at, Object move) {
            Body body = add(at, move);
            friends.add(body);
            return body;
        }

        private Body add(Vec3 at, Object move) {
            if (move instanceof Mover) {
                return rig.add(at, STILL).moving((Mover) move);
            }
            return rig.add(at, (MovementRig.Script) move);
        }

        void run(int ticks) {
            for (int i = 0; i < ticks; i++) {
                rig.tick();
            }
        }

        /** "Places" every step of {@code plan}. */
        void place(Plan plan) {
            for (Plan.Step step : plan.getSteps()) {
                Vec3i cell = step.getCell();
                ground.set(cell.getX(), cell.getY(), cell.getZ());
            }
        }

        Obstructions everyone() {
            return region -> {
                for (Body body : rig.all) {
                    if (body.state.hitbox(0.6d, 1.8d).intersects(region)) {
                        return true;
                    }
                }
                return false;
            };
        }

        /** Walks on at {@code yaw}, never stopping. */
        Mover walking(float yaw) {
            return (body, state, tick, world) -> {
                MovementInput keys = MovementInput.forward(yaw);
                body.yaw = yaw;
                return Simulation.step(MovementRig.VANILLA, state, keys, world);
            };
        }

        PlacementStyle vanilla() {
            return PlacementStyle.vanilla(Reach.of(5.5d, 5.5d), ground);
        }

        PlacementStyle strict() {
            return PlacementStyle.strict(Reach.of(5.5d, 5.5d), ground);
        }

        TrapSearch<Body> search(PlacementStyle style, Function<TrapSearch.Builder<Body>, TrapSearch.Builder<Body>> tweak) {
            PlacementPlanner planner = style.planner(style.clicks(ground::solid, ground::open), everyone()).build();
            TrapSearch.Builder<Body> builder = TrapSearch.<Body>builder()
                    .prediction(rig.prediction)
                    .entities(rig.entities)
                    .targets(targets, TargetSelector.from(rig.bodies).range(40).where(enemies::contains).build())
                    .protect(TargetSelector.from(rig.bodies).range(40).where(friends::contains).build())
                    .planner(planner);
            return tweak.apply(builder).build();
        }
    }

    /** Obsidian two blocks thick, at y -1 and 0, with holes cut into the top layer; placed blocks anywhere. */
    private static final class Ground implements BlockView {
        final GridCollisionSpace space = new GridCollisionSpace();
        final Map<Long, Boolean> solid = new HashMap<>();

        Ground() {
            for (int x = -25; x <= 25; x++) {
                for (int z = -25; z <= 25; z++) {
                    set(x, -1, z);
                    set(x, 0, z);
                }
            }
        }

        void set(int x, int y, int z) {
            solid.put(key(x, y, z), Boolean.TRUE);
            space.solid(x, y, z);
        }

        void cut(int x, int z) {
            solid.remove(key(x, 0, z));
            space.remove(x, 0, z);
        }

        boolean solid(int x, int y, int z) {
            return solid.containsKey(key(x, y, z));
        }

        boolean open(int x, int y, int z) {
            return !solid(x, y, z);
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            return solid(x, y, z) ? BlockShape.FULL : BlockShape.EMPTY;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }
    }
}
