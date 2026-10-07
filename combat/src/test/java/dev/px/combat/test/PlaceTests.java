package dev.px.combat.test;

import dev.px.combat.place.Click;
import dev.px.combat.place.Clicks;
import dev.px.combat.place.FaceRule;
import dev.px.combat.place.HitPoint;
import dev.px.combat.place.PlacementPlanner;
import dev.px.combat.place.Plan;
import dev.px.combat.place.Shapes;
import dev.px.combat.search.rule.Reach;
import dev.px.core.entity.Tracked;
import dev.px.core.math.Box;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.prediction.Lookahead;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Placing blocks: every way to click a cell by the test's own server rules, and
 * planning several placements a tick.
 *
 * <p>The world is a set of solid cells. "Strict direction" is the geometry, not
 * any anticheat's code: a face only counts while your eyes are on its outside.
 */
public final class PlaceTests {

    private PlaceTests() {
    }

    public static void run() {
        Checks.section("Placing blocks");

        clicking();
        rules();
        hitPoints();
        planning();
        shapes();
        webbing();
    }

    // --------------------------------------------------------------- clicking

    private static void clicking() {
        World world = new World();
        world.hole(0, 0, 0);                                  // a floor under it, a wall each side
        Vec3 above = Vec3.of(0.5d, 2.62d, 0.5d);
        Clicks any = world.clicks(FaceRule.ANY);
        List<Click> ways = any.into(above, Vec3i.of(0, 0, 0));
        Checks.checkEquals("a hole can be filled by clicking its floor or any of its four walls", 5, ways.size());
        boolean faceBack = true;
        for (Click click : ways) {
            faceBack &= click.getBlock().add(click.getFace().toVec3i()).equals(Vec3i.of(0, 0, 0))
                    && click.getCell().equals(Vec3i.of(0, 0, 0)) && !click.isAirPlace();
        }
        Checks.check("each on the face that turns toward it", faceBack);
        Checks.check("nothing can be placed into a solid block", any.into(above, Vec3i.of(0, -1, 0)).isEmpty());

        Vec3 aside = Vec3.of(5.5d, 2.62d, 0.5d);
        List<Click> strict = world.clicks(FaceRule.facingEye()).into(aside, Vec3i.of(0, 0, 0));
        boolean noFarWall = true;
        for (Click click : strict) {
            noFarWall &= !click.getBlock().equals(Vec3i.of(1, 0, 0));
        }
        Checks.check("strictly, from the side, the wall whose face turns away from you cannot be clicked ("
                + strict.size() + " ways)", strict.size() == 4 && noFarWall);

        Click nearest = any.best(aside, Vec3i.of(0, 0, 0));
        Checks.check("the best way is the one nearest your eyes", nearest != null
                && nearest.getBlock().equals(Vec3i.of(1, 0, 0)));
        Click turned = any.best(aside, Vec3i.of(0, 0, 0), aside.rotationTo(Vec3.of(0.5d, -0.5d, 0.5d)));
        Checks.check("or, given where you look, the one needing least turn (" + turned + ")",
                turned != null && turned.getFace() == Direction.UP);

        Clicks on = world.clicks(FaceRule.facingEye());
        List<Click> base = on.on(Vec3.of(0.5d, 2.62d, 3.5d), Vec3i.of(0, 0, 3));
        Checks.check("clicking a block itself, for a crystal on a base: from above, its top and the sides you can see",
                base.size() >= 1 && base.stream().anyMatch(click -> click.getFace() == Direction.UP
                        && click.getCell().equals(Vec3i.of(0, 1, 3))));
        List<Click> under = on.on(Vec3.of(0.5d, -2d, 3.5d), Vec3i.of(0, 0, 3));
        Checks.check("from below, never its top", under.stream().noneMatch(click -> click.getFace() == Direction.UP));

        Clicks floating = world.clicks(FaceRule.ANY);
        Vec3i midair = Vec3i.of(20, 10, 20);
        Checks.check("a cell in mid-air has nothing to click against", floating.into(above, midair).isEmpty());
        Clicks air = Clicks.builder().support(world::solid).replaceable(world::clear).airPlace(() -> true).build();
        List<Click> airPlaced = air.into(Vec3.of(20.5d, 12d, 20.5d), midair);
        Checks.check("unless your server takes an air place: the cell itself, clicked on the face toward you",
                airPlaced.size() == 1 && airPlaced.get(0).isAirPlace() && airPlaced.get(0).getBlock().equals(midair)
                        && airPlaced.get(0).getFace() == Direction.UP);
    }

    private static void rules() {
        World world = new World();
        world.hole(0, 0, 0);
        world.set(0, 2, -3);                                  // a pillar between you and the hole's north wall
        world.set(0, 1, -3);
        Vec3 behind = Vec3.of(0.5d, 1.62d, -5.5d);
        Click hidden = null;
        for (Click click : world.clicks(FaceRule.ANY).into(behind, Vec3i.of(0, 0, 0))) {
            if (click.getBlock().equals(Vec3i.of(0, -1, 0))) {
                hidden = click;
            }
        }
        boolean seen = false;
        for (Click click : world.clicks(FaceRule.visible(world)).into(behind, Vec3i.of(0, 0, 0))) {
            seen |= click.getBlock().equals(Vec3i.of(0, -1, 0));
        }
        Checks.check("a face behind a wall is clicked by any rule, and not by one that must see it",
                hidden != null && !seen);

        Clicks near = world.clicks(FaceRule.reach(Reach.of(3d, 3d), world));
        Checks.check("nor out of reach", near.into(Vec3.of(10.5d, 1.62d, 0.5d), Vec3i.of(0, 0, 0)).isEmpty()
                && !near.into(Vec3.of(0.5d, 1.62d, 2.5d), Vec3i.of(0, 0, 0)).isEmpty());

        world.set(0, 1, 5);
        world.set(0, 0, 5);
        world.set(0, 0, 6);
        List<Click> covered = world.clicks(FaceRule.exposed(world::solid)).on(Vec3.of(0.5d, 3d, 5.5d), Vec3i.of(0, 0, 5));
        Checks.check("a face covered by the block beside it cannot be clicked: not the top under a block, nor the side "
                        + "against another",
                covered.stream().noneMatch(click -> click.getFace() == Direction.UP || click.getFace() == Direction.SOUTH));

        String missing;
        try {
            Clicks.builder().build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("click rules missing parts name them", missing != null && missing.contains("support")
                && missing.contains("replaceable"));
    }

    private static void hitPoints() {
        Vec3i block = Vec3i.of(2, 0, 2);
        Vec3 eye = Vec3.of(0d, 5d, 0d);
        Checks.check("the centre of a face", HitPoint.CENTRE.on(eye, block, Direction.UP).equals(Vec3.of(2.5d, 1d, 2.5d))
                && HitPoint.CENTRE.on(eye, block, Direction.WEST).equals(Vec3.of(2d, 0.5d, 2.5d)));
        Vec3 nearest = HitPoint.NEAREST.on(eye, block, Direction.UP);
        Checks.check("the point of it nearest your eyes, just inside its edge (" + nearest + ")",
                nearest.getY() == 1d && Math.abs(nearest.getX() - 2.001d) < 1e-9 && Math.abs(nearest.getZ() - 2.001d) < 1e-9);
        World world = new World();
        world.hole(0, 0, 0);
        Click click = world.clicks(FaceRule.ANY).best(Vec3.of(0.5d, 2.62d, 0.5d), Vec3i.of(0, 0, 0));
        Checks.check("and as older versions send it, within the block clicked (" + click.getRelativeHit() + ")",
                click.getRelativeHit().equals(click.getHit().subtract(click.getBlock().toVec3())));
        Vec2 rotation = click.getRotation(Vec3.of(0.5d, 2.62d, 0.5d));
        Checks.check("with the rotation that looks at it", Math.abs(rotation.getPitch() - 90f) < 1e-3
                || click.getFace() != Direction.UP);
    }

    // --------------------------------------------------------------- planning

    private static void planning() {
        World world = new World();
        world.ground(-10, 10);                                // solid at y 0 and below, surface at y 1
        Vec3 eye = Vec3.of(0.5d, 2.62d, 0.5d);
        List<Vec3i> surround = Arrays.asList(Vec3i.of(1, 1, 0), Vec3i.of(-1, 1, 0), Vec3i.of(0, 1, 1), Vec3i.of(0, 1, -1));
        Set<Vec3i> standing = new HashSet<>();
        PlacementPlanner planner = world.planner(FaceRule.ANY, 0, () -> 2, standing);
        Plan two = planner.plan(eye, surround);
        Checks.check("a surround of four, two a tick: the first two, in the order asked (" + two + ")",
                two.getSteps().size() == 2 && two.getSteps().get(0).getCell().equals(Vec3i.of(1, 1, 0))
                        && two.getSteps().get(1).getCell().equals(Vec3i.of(-1, 1, 0)));
        Checks.check("and the rest left for the next tick", two.getSkipped().get(Vec3i.of(0, 1, 1)) == Plan.Skip.LIMIT
                && !two.isComplete());
        Checks.check("each clicked against the ground under it", two.getSteps().get(0).getClick().getFace() == Direction.UP
                && !two.getSteps().get(0).isSupport());

        world.set(0, 1, 1);
        standing.add(Vec3i.of(0, 1, -1));
        Plan around = world.planner(FaceRule.ANY, 0, () -> 10, standing).plan(eye, surround);
        Checks.check("a cell already filled, or with someone in it, is left out and says why (" + around + ")",
                around.getSkipped().get(Vec3i.of(0, 1, 1)) == Plan.Skip.FILLED
                        && around.getSkipped().get(Vec3i.of(0, 1, -1)) == Plan.Skip.OCCUPIED);

        // A line out over a drop: each block is clicked against the one placed before it.
        World ledge = new World();
        ledge.set(0, 0, 0);
        List<Vec3i> line = Arrays.asList(Vec3i.of(1, 0, 0), Vec3i.of(2, 0, 0), Vec3i.of(3, 0, 0));
        Plan bridge = ledge.planner(FaceRule.ANY, 0, () -> 10, new HashSet<>()).plan(Vec3.of(0.5d, 1.62d, 0.5d), line);
        Checks.check("a bridge out over a drop: each placed against the last (" + bridge + ")",
                bridge.getSteps().size() == 3 && bridge.isComplete()
                        && bridge.getSteps().get(2).getClick().getBlock().equals(Vec3i.of(2, 0, 0)));
        Plan backwards = ledge.planner(FaceRule.ANY, 0, () -> 10, new HashSet<>())
                .plan(Vec3.of(0.5d, 1.62d, 0.5d), Arrays.asList(Vec3i.of(3, 0, 0), Vec3i.of(2, 0, 0), Vec3i.of(1, 0, 0)));
        Checks.check("asked out of order, what has nothing to click yet is unreachable",
                backwards.getSkipped().get(Vec3i.of(3, 0, 0)) == Plan.Skip.UNREACHABLE);

        // A cell a block above the ground: nothing beside it, so a support goes under it first.
        Vec3i high = Vec3i.of(4, 2, 4);
        Plan none = world.planner(FaceRule.ANY, 0, () -> 10, new HashSet<>()).plan(eye, Arrays.asList(high));
        Plan supported = world.planner(FaceRule.ANY, 1, () -> 10, new HashSet<>()).plan(eye, Arrays.asList(high));
        Checks.check("a cell with nothing to click is unreachable without supports",
                none.getSkipped().get(high) == Plan.Skip.UNREACHABLE);
        Checks.check("and with one allowed, a support goes under it first (" + supported + ")",
                supported.getSteps().size() == 2 && supported.getSteps().get(0).isSupport()
                        && supported.getSteps().get(0).getCell().equals(Vec3i.of(4, 1, 4))
                        && supported.getSteps().get(1).getClick().getBlock().equals(Vec3i.of(4, 1, 4)));
        Plan tight = world.planner(FaceRule.ANY, 1, () -> 1, new HashSet<>()).plan(eye, Arrays.asList(high));
        Checks.check("a support counts against the tick's limit", tight.getSkipped().get(high) == Plan.Skip.LIMIT);
        Checks.check("but the tick is not wasted: the support is started (" + tight + ")",
                tight.getSteps().size() == 1 && tight.getSteps().get(0).isSupport()
                        && tight.getSteps().get(0).getCell().equals(Vec3i.of(4, 1, 4)));
        Plan crowded = world.planner(FaceRule.ANY, 1, () -> 1, new HashSet<>())
                .plan(eye, Arrays.asList(high, Vec3i.of(-4, 1, -4)));
        Checks.check("before a later cell that would fit whole: your order comes first",
                crowded.getSteps().size() == 1 && crowded.getSteps().get(0).getCell().equals(Vec3i.of(4, 1, 4)));
        Vec3i higher = Vec3i.of(-4, 3, -4);
        Plan two_deep = world.planner(FaceRule.ANY, 2, () -> 10, new HashSet<>()).plan(eye, Arrays.asList(higher));
        Checks.check("two deep, two supports, stacked (" + two_deep + ")", two_deep.getSteps().size() == 3
                && two_deep.isComplete());

        String missing;
        try {
            PlacementPlanner.builder().build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("a planner without click rules says so", missing != null && missing.contains("clicks"));
    }

    // ----------------------------------------------------------------- shapes

    private static void shapes() {
        Box centred = Box.around(Vec3.of(0.5d, 1d, 0.5d), 0.6d, 1.8d);
        Checks.check("a player in the middle of a block is in two cells, feet then head",
                Shapes.occupied(centred).equals(Arrays.asList(Vec3i.of(0, 1, 0), Vec3i.of(0, 2, 0)))
                        && Shapes.feet(centred).equals(Arrays.asList(Vec3i.of(0, 1, 0)))
                        && Shapes.head(centred).equals(Arrays.asList(Vec3i.of(0, 2, 0))));
        Checks.check("with four beside its feet, and one over its head",
                Shapes.around(centred).size() == 4 && !Shapes.around(centred).contains(Vec3i.of(0, 1, 0))
                        && Shapes.above(centred).equals(Arrays.asList(Vec3i.of(0, 3, 0))));

        Box edge = Box.around(Vec3.of(1.0d, 1d, 0.5d), 0.6d, 1.8d);
        Box corner = Box.around(Vec3.of(1.0d, 1d, 1.0d), 0.6d, 1.8d);
        Checks.check("on an edge, its feet are in two cells and six are beside them",
                Shapes.feet(edge).size() == 2 && Shapes.around(edge).size() == 6);
        Checks.check("on a corner, four, and eight beside them",
                Shapes.feet(corner).size() == 4 && Shapes.around(corner).size() == 8 && Shapes.occupied(corner).size() == 8);

        Box flush = Box.of(0d, 1d, 0d, 1d, 2d, 1d);
        Checks.check("a box exactly filling a cell is in that cell only, not the ones it touches",
                Shapes.occupied(flush).equals(Arrays.asList(Vec3i.of(0, 1, 0))));
        Checks.check("shapes join without repeats, in the order met",
                Shapes.union(Shapes.feet(centred), Shapes.occupied(centred))
                        .equals(Arrays.asList(Vec3i.of(0, 1, 0), Vec3i.of(0, 2, 0))));
    }

    private static void webbing() {
        World world = new World();
        world.ground(-10, 10);
        Box target = Box.around(Vec3.of(0.5d, 1d, 0.5d), 0.6d, 1.8d);
        Box me = Box.around(Vec3.of(3.5d, 1d, 0.5d), 0.6d, 1.8d);
        Vec3 eye = Vec3.of(3.5d, 2.62d, 0.5d);
        Clicks clicks = world.clicks(FaceRule.facingEye());

        // A web has no collision box, so the server lets it go into a player: only you are in the way.
        PlacementPlanner webs = PlacementPlanner.builder().clicks(clicks)
                .obstructions(region -> region.intersects(me)).build();
        Plan web = webs.plan(eye, Shapes.occupied(target));
        Checks.check("webbing a player: their feet, then their head, the second clicked against the first (" + web + ")",
                web.isComplete() && web.getSteps().size() == 2
                        && web.getSteps().get(0).getClick().getBlock().equals(Vec3i.of(0, 0, 0))
                        && web.getSteps().get(1).getClick().getBlock().equals(Vec3i.of(0, 1, 0)));

        PlacementPlanner solid = PlacementPlanner.builder().clicks(clicks)
                .obstructions(region -> region.intersects(target) || region.intersects(me)).build();
        Checks.check("where a solid block would be refused, with them in it",
                solid.plan(eye, Shapes.occupied(target)).getSkipped().get(Vec3i.of(0, 1, 0)) == Plan.Skip.OCCUPIED);
        Checks.check("and never into yourself", webs.plan(eye, Shapes.occupied(me)).getSkipped()
                .get(Vec3i.of(3, 1, 0)) == Plan.Skip.OCCUPIED);
        Obstructions selfOnly = region -> region.intersects(me);
        Checks.check("(an Obstructions of only you is the one to give)", !selfOnly.any(target) && selfOnly.any(me));

        // Running: web where they will be when the web lands, not where they are.
        MovementRig rig = new MovementRig(new GridCollisionSpace().floor(1d, -60, 60));
        MovementRig.Body runner = rig.add(Vec3.of(0.5d, 1d, 0.5d), tick -> MovementInput.forward(-90f).withSprint(true));
        for (int i = 0; i < 20; i++) {
            rig.tick();
        }
        Tracked<?> now = rig.tracked(runner);
        Tracked<?> soon = Lookahead.<Object>extrapolated().at(now, 4);
        int nowX = Shapes.feet(now.getBox()).get(0).getX();
        int soonX = Shapes.feet(soon.getBox()).get(Shapes.feet(soon.getBox()).size() - 1).getX();
        Checks.check("a running target is webbed ahead of them: where the lookahead puts them (" + nowX + " now, "
                + soonX + " when it lands)", soonX > nowX);
    }

    // ---------------------------------------------------------------- harness

    /** Solid cells, and nothing else. */
    private static final class World implements BlockView {
        private final Set<Vec3i> solid = new HashSet<>();

        void set(int x, int y, int z) {
            solid.add(Vec3i.of(x, y, z));
        }

        /** A one-cell hole: a floor under it and a wall on each side. */
        void hole(int x, int y, int z) {
            set(x, y - 1, z);
            set(x + 1, y, z);
            set(x - 1, y, z);
            set(x, y, z + 1);
            set(x, y, z - 1);
        }

        void ground(int from, int to) {
            for (int x = from; x <= to; x++) {
                for (int z = from; z <= to; z++) {
                    set(x, 0, z);
                }
            }
        }

        boolean solid(int x, int y, int z) {
            return solid.contains(Vec3i.of(x, y, z));
        }

        boolean clear(int x, int y, int z) {
            return !solid(x, y, z);
        }

        Clicks clicks(FaceRule faces) {
            return Clicks.builder().support(this::solid).replaceable(this::clear).faces(faces).build();
        }

        PlacementPlanner planner(FaceRule faces, int supports, java.util.function.IntSupplier perTick,
                                 Set<Vec3i> standing) {
            return PlacementPlanner.builder()
                    .clicks(clicks(faces))
                    .obstructions(region -> standing.contains(Vec3i.floorOf(region.getCenter())))
                    .perTick(perTick)
                    .supports(supports)
                    .build();
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            return solid(x, y, z) ? BlockShape.FULL : BlockShape.EMPTY;
        }
    }
}
