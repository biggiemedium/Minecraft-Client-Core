package dev.px.combat.test;

import dev.px.combat.hole.Hole;
import dev.px.combat.hole.HoleEntry;
import dev.px.combat.hole.HoleFinder;
import dev.px.combat.hole.HoleRules;
import dev.px.combat.hole.HoleShape;
import dev.px.combat.hole.HoleWatch;
import dev.px.combat.world.Obstructions;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.test.harness.MovementRig.Body;
import dev.px.core.test.harness.MovementRig.Mover;
import dev.px.core.util.math.PhysicsProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holes: finding them by the test's own block rules, and watching who might get
 * into one.
 *
 * <p>The world is an obsidian ground, one block thick on another, with holes cut
 * into the top layer: pits a block deep, as PvP holes usually are. Players are
 * Core's movement rig: scripted bodies seen only through their positions.
 */
public final class HoleTests {

    private static final int DIRT = 1;
    private static final int OBSIDIAN = 2;
    private static final int BEDROCK = 3;

    private HoleTests() {
    }

    public static void run() {
        Checks.section("Holes");

        finding();
        containing();
        rules();
        watching();
        speedHacker();
        insideAndOccupied();
    }

    // ---------------------------------------------------------------- finding

    private static void finding() {
        Ground ground = new Ground();
        ground.cut(0, 0);                                     // a single
        ground.cut(5, 0);
        ground.cut(6, 0);                                     // a double along x
        ground.cut(10, 0);
        ground.cut(10, 1);                                    // a double along z
        ground.cut(15, 0);
        ground.cut(16, 0);
        ground.cut(15, 1);
        ground.cut(16, 1);                                    // a quad
        ground.cut(20, 0);
        ground.bedrockAround(20, 0);                          // a single walled and floored in bedrock
        ground.cut(25, 0);
        ground.cut(26, 0);
        ground.cut(27, 0);                                    // a trench of three: no hole
        ground.cut(30, 0);
        ground.set(30, 1, 0, OBSIDIAN);                       // a single with a block over it: no headroom
        ground.cut(35, 0);
        ground.set(36, 0, 0, DIRT);                           // a single walled on one side by dirt
        ground.cut(40, 0);
        ground.bedrockAround(40, 0);
        ground.set(40, -1, 0, OBSIDIAN);                      // bedrock walls on an obsidian floor

        HoleFinder finder = new HoleFinder(ground.rules());
        List<Hole> holes = finder.around(Vec3.of(18d, 1d, 0.5d), 40d);
        Map<String, Hole> byShape = new HashMap<>();
        for (Hole hole : holes) {
            byShape.put(hole.getShape() + "@" + hole.getCells().get(0), hole);
        }
        Checks.check("a single, two doubles, a quad and two bedrock-walled singles are found, and nothing else ("
                + holes + ")", holes.size() == 6);
        Hole single = byShape.get(HoleShape.SINGLE + "@" + Vec3i.of(0, 0, 0));
        Checks.check("a single is one cell at the height feet stand in it",
                single != null && single.getCells().size() == 1 && single.getY() == 0);
        Checks.check("a double along x", byShape.containsKey(HoleShape.DOUBLE + "@" + Vec3i.of(5, 0, 0)));
        Checks.check("and one along z", byShape.containsKey(HoleShape.DOUBLE + "@" + Vec3i.of(10, 0, 0)));
        Hole quad = byShape.get(HoleShape.QUAD + "@" + Vec3i.of(15, 0, 0));
        Checks.check("a quad's four cells", quad != null && quad.getCells().size() == 4);
        Hole safe = byShape.get(HoleShape.SINGLE + "@" + Vec3i.of(20, 0, 0));
        Hole floored = byShape.get(HoleShape.SINGLE + "@" + Vec3i.of(40, 0, 0));
        Checks.check("bedrock all round is safe, obsidian is not, nor bedrock walls on an obsidian floor",
                safe != null && safe.isSafe() && !single.isSafe() && !quad.isSafe() && floored != null
                        && !floored.isSafe());
        Checks.check("nearest first", holes.get(0).getCentre().distanceTo(Vec3.of(18d, 1d, 0.5d))
                <= holes.get(holes.size() - 1).getCentre().distanceTo(Vec3.of(18d, 1d, 0.5d)));
        Checks.check("only those within the radius",
                finder.around(Vec3.of(0.5d, 1d, 0.5d), 2d).size() == 1);
        Checks.check("a hole is found by any of its cells",
                byShape.get(HoleShape.DOUBLE + "@" + Vec3i.of(5, 0, 0)).equals(finder.at(6, 0, 0))
                        && quad.equals(finder.at(16, 0, 1)) && finder.at(26, 0, 0) == null);
        Checks.check("and is equal to itself found again",
                single.equals(finder.around(Vec3.of(0.5d, 1d, 0.5d), 2d).get(0))
                        && single.hashCode() == finder.at(0, 0, 0).hashCode());

        HoleFinder singles = new HoleFinder(ground.rules(HoleShape.SINGLE));
        Checks.check("the shapes looked for are yours to choose",
                singles.around(Vec3.of(18d, 1d, 0.5d), 40d).size() == 3);
    }

    private static void containing() {
        Ground ground = new Ground();
        ground.cut(0, 0);
        Hole hole = new HoleFinder(ground.rules()).at(0, 0, 0);
        Checks.check("standing in it is in it", hole.contains(MotionState.at(Vec3.of(0.5d, 0d, 0.5d)), 0.6d));
        Checks.check("against one wall is still in it", hole.contains(MotionState.at(Vec3.of(0.3d, 0d, 0.5d)), 0.6d));
        Checks.check("half over the edge is not", !hole.contains(MotionState.at(Vec3.of(0.9d, 0d, 0.5d)), 0.6d));
        Checks.check("nor standing over it, on the ground", !hole.contains(MotionState.at(Vec3.of(0.5d, 1d, 0.5d)), 0.6d));
        Checks.check("dropping into it is in it once below the top of its walls",
                hole.contains(MotionState.at(Vec3.of(0.5d, 0.9d, 0.5d)), 0.6d));
        Checks.check("its centre is the middle of its floor", hole.getCentre().equals(Vec3.of(0.5d, 0d, 0.5d)));
    }

    private static void rules() {
        String missing;
        try {
            HoleRules.builder().open((x, y, z) -> true).build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("rules missing parts name them (" + missing + ")",
                missing != null && missing.contains("walls") && missing.contains("headroom"));
        Checks.checkThrows("headroom of nothing is refused", IllegalArgumentException.class,
                () -> HoleRules.builder().headroom(0));
    }

    // --------------------------------------------------------------- watching

    private static void watching() {
        // Someone walking to a hole and stopping in it.
        Ground ground = new Ground();
        ground.cut(4, 0);
        MovementRig rig = new MovementRig(ground.space);
        Body walker = rig.add(Vec3.of(-4.5d, 1d, 0.5d), tick -> MovementInput.none(0f))
                .moving(toward(Vec3.of(4.5d, 0d, 0.5d), MovementRig.VANILLA, false));
        HoleWatch watch = watch(rig, ground, 25);
        for (int i = 0; i < 25; i++) {
            rig.tick();
        }
        List<HoleEntry> entries = watch.watch(rig.tracked(walker));
        HoleEntry entry = entries.isEmpty() ? null : entries.get(0);
        Checks.check("someone heading for a hole is likely to get in (" + entry + ")",
                entry != null && entry.getChance() > 0.5d && entry.getLikelyTick() > 0 && !entry.isInside());
        int arrived = -1;
        for (int tick = 1; tick <= 25 && arrived < 0; tick++) {
            rig.tick();
            if (entry.getHole().contains(rig.tracked(walker))) {
                arrived = tick;
            }
        }
        Checks.check("and gets in no sooner than the bound said, and near when it was likely (possible "
                        + entry.getEarliestPossible() + ", likely " + entry.getLikelyTick() + ", really " + arrived + ")",
                arrived >= entry.getEarliestPossible() && Math.abs(arrived - entry.getLikelyTick()) <= 2);

        // Someone sprinting past it, a few blocks to the side.
        Ground beside = new Ground();
        beside.cut(4, 0);
        MovementRig passing = new MovementRig(beside.space);
        Body runner = passing.add(Vec3.of(-4.5d, 1d, 3.5d), tick -> MovementInput.forward(-90f).withSprint(true));
        HoleWatch past = watch(passing, beside, 15);
        for (int i = 0; i < 25; i++) {
            passing.tick();
        }
        List<HoleEntry> by = past.watch(passing.tracked(runner));
        Checks.check("someone running past it is not (" + by + ")",
                !by.isEmpty() && by.get(0).getChance() < 0.2d);

        // A hole too far to reach in time is not offered.
        HoleWatch quick = watch(passing, beside, 2);
        Checks.check("a hole it could not reach within the horizon is left out",
                quick.watch(passing.tracked(runner)).isEmpty());
    }

    private static void speedHacker() {
        PhysicsProfile doubled = MovementRig.VANILLA.withMoveSpeedAttribute(0.2d);
        Ground ground = new Ground();
        ground.cut(20, 0);
        MovementRig fair = new MovementRig(ground.space);
        MovementRig fast = new MovementRig(ground.space);
        Vec3 centre = Vec3.of(20.5d, 0d, 0.5d);
        Body legit = fair.add(Vec3.of(-10.5d, 1d, 0.5d), tick -> MovementInput.none(0f))
                .moving(toward(centre, MovementRig.VANILLA, true));
        Body cheat = fast.add(Vec3.of(-10.5d, 1d, 0.5d), tick -> MovementInput.none(0f))
                .rules(doubled).moving(toward(centre, doubled, true));
        while (legit.state.getPosition().getX() < 16.5d) {
            fair.tick();
        }
        while (cheat.state.getPosition().getX() < 16.5d) {
            fast.tick();
        }
        HoleEntry legitEntry = watch(fair, ground, 20).watch(fair.tracked(legit)).get(0);
        HoleEntry cheatEntry = watch(fast, ground, 20).watch(fast.tracked(cheat)).get(0);
        Checks.check("from the same spot, a speed hacker heading for a hole would be in sooner (" + cheatEntry
                        + " vs " + legitEntry + ")",
                cheatEntry.getArrivalTick() > 0 && cheatEntry.getArrivalTick() < legitEntry.getArrivalTick());
        Checks.check("and could possibly be in sooner", cheatEntry.getEarliestPossible() < legitEntry.getEarliestPossible());
        Checks.check("sprinting straight at it moves just like sprinting over it, so the evidence splits the chance ("
                + legitEntry.getChance() + ")", legitEntry.getChance() > 0.2d && legitEntry.getChance() < 0.5d);
        HoleWatch leaning = HoleWatch.builder().finder(new HoleFinder(ground.rules())).prediction(fair.prediction)
                .horizon(() -> 20).radius(() -> 12d).prior(() -> 4d).build();
        HoleEntry leant = leaning.watch(fair.tracked(legit)).get(0);
        Checks.check("leaning toward holes, someone running at one is likely in, on time (" + leant + ")",
                leant.getChance() > 0.5d && leant.getLikelyTick() == leant.getArrivalTick());
    }

    private static void insideAndOccupied() {
        Ground ground = new Ground();
        ground.cut(0, 0);
        ground.cut(3, 0);
        MovementRig rig = new MovementRig(ground.space);
        Body sitting = rig.add(Vec3.of(0.5d, 0d, 0.5d), tick -> MovementInput.none(0f));
        Body other = rig.add(Vec3.of(3.5d, 0d, 0.5d), tick -> MovementInput.none(0f));
        HoleWatch watch = HoleWatch.builder()
                .finder(new HoleFinder(ground.rules()))
                .prediction(rig.prediction)
                .obstructions(Obstructions.of(rig.entities, rig.bodies))
                .horizon(() -> 20)
                .radius(() -> 5d)
                .build();
        for (int i = 0; i < 10; i++) {
            rig.tick();
        }
        List<HoleEntry> entries = watch.watch(rig.tracked(sitting));
        Checks.check("the hole it is in comes first, and is not occupied by itself (" + entries + ")",
                entries.size() == 2 && entries.get(0).isInside() && !entries.get(0).isOccupied()
                        && entries.get(0).getEarliestPossible() == 0);
        Checks.check("a hole someone else is in is occupied", entries.get(1).isOccupied() && other != null);

        String missing;
        try {
            HoleWatch.builder().prediction(rig.prediction).build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("a watch missing parts names them", missing != null && missing.contains("finder")
                && missing.contains("horizon") && missing.contains("radius"));
    }

    // ---------------------------------------------------------------- harness

    private static HoleWatch watch(MovementRig rig, Ground ground, int horizon) {
        return HoleWatch.builder()
                .finder(new HoleFinder(ground.rules()))
                .prediction(rig.prediction)
                .horizon(() -> horizon)
                .radius(() -> 12d)
                .build();
    }

    /** Walks, or sprints, straight for {@code point} and stops over it, as a player heading into a hole does. */
    private static Mover toward(Vec3 point, PhysicsProfile rules, boolean sprint) {
        return (body, state, tick, world) -> {
            double dx = point.getX() - state.getPosition().getX();
            double dz = point.getZ() - state.getPosition().getZ();
            MovementInput keys = dx * dx + dz * dz < 0.05d * 0.05d
                    ? MovementInput.none(0f)
                    : MovementInput.forward((float) Math.toDegrees(Math.atan2(-dx, dz))).withSprint(sprint);
            body.yaw = keys.getYaw();
            return Simulation.step(rules, state, keys, world);
        };
    }

    /** Obsidian ground two blocks thick, at y -1 and 0, with holes cut into the top layer. */
    private static final class Ground {
        final GridCollisionSpace space = new GridCollisionSpace();
        final Map<Long, Integer> blocks = new HashMap<>();

        Ground() {
            for (int x = -20; x <= 45; x++) {
                for (int z = -10; z <= 10; z++) {
                    set(x, -1, z, OBSIDIAN);
                    set(x, 0, z, OBSIDIAN);
                }
            }
        }

        void set(int x, int y, int z, int kind) {
            blocks.put(key(x, y, z), kind);
            space.solid(x, y, z);
        }

        void cut(int x, int z) {
            blocks.remove(key(x, 0, z));
            space.remove(x, 0, z);
        }

        void bedrockAround(int x, int z) {
            set(x, -1, z, BEDROCK);
            set(x + 1, 0, z, BEDROCK);
            set(x - 1, 0, z, BEDROCK);
            set(x, 0, z + 1, BEDROCK);
            set(x, 0, z - 1, BEDROCK);
        }

        int kind(int x, int y, int z) {
            Integer kind = blocks.get(key(x, y, z));
            return kind == null ? 0 : kind;
        }

        HoleRules rules(HoleShape... shapes) {
            HoleRules.Builder builder = HoleRules.builder()
                    .walls((x, y, z) -> kind(x, y, z) == OBSIDIAN || kind(x, y, z) == BEDROCK)
                    .open((x, y, z) -> kind(x, y, z) == 0)
                    .headroom(2)
                    .safe((x, y, z) -> kind(x, y, z) == BEDROCK);
            if (shapes.length > 0) {
                builder.shapes(shapes);
            }
            return builder.build();
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }
    }
}
