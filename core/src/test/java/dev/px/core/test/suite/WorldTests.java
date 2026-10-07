package dev.px.core.test.suite;

import dev.px.core.math.Box;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;
import dev.px.core.world.RayHit;
import dev.px.core.world.Rays;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The world seams, against blocks and bodies set by hand in this file: block
 * shapes, lines through them and where they first meet something, a world with
 * cells taken out, and entities in the way. None of it knows a block by name.
 */
public final class WorldTests {

    private WorldTests() {
    }

    public static void run() {
        Checks.section("World");

        shapes();
        rays();
        firstHits();
        boxClips();
        without();
        obstructions();
    }

    // ------------------------------------------------------------- shapes

    private static void shapes() {
        Checks.check("a full cell stops a line through it",
                BlockShape.FULL.intersects(0, 0, 0, -1, 0.5, 0.5, 2, 0.5, 0.5));
        Checks.check("and not one passing beside it", !BlockShape.FULL.intersects(0, 0, 0, -1, 1.5, 0.5, 2, 1.5, 0.5));
        Checks.check("an empty cell stops nothing", !BlockShape.EMPTY.intersects(0, 0, 0, -1, 0.5, 0.5, 2, 0.5, 0.5));

        BlockShape slab = BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1));
        Checks.check("a slab stops a line through its half", slab.intersects(5, 0, 5, 4, 0.25, 5.5, 7, 0.25, 5.5));
        Checks.check("and not one passing over it", !slab.intersects(5, 0, 5, 4, 0.75, 5.5, 7, 0.75, 5.5));
        Checks.check("its boxes are relative to whichever cell it is in",
                slab.intersects(-3, 10, 2, -4, 10.25, 2.5, -1, 10.25, 2.5));
        Checks.check("a line starting inside a shape is stopped", BlockShape.FULL.intersects(0, 0, 0, 0.5, 0.5, 0.5, 9, 9, 9));
        Checks.check("one along its top is not: what stands on a block is not hidden by it",
                !BlockShape.FULL.intersects(0, 0, 0, -1, 1, 0.5, 2, 1, 0.5)
                        && !BlockShape.FULL.intersects(0, 0, 0, 0.5, 1, 0.5, 0.5, 3, 0.5));
        Checks.check("but one along its bottom is: so the seam inside a solid wall is solid",
                BlockShape.FULL.intersects(0, 1, 0, -1, 1, 0.5, 2, 1, 0.5));
        Checks.check("touching only an edge is not", !BlockShape.FULL.intersects(0, 0, 0, 2, 0, 0.5, 0, 2, 0.5));
        Checks.check("shapes compare by their boxes",
                BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1)).equals(slab) && BlockShape.of().equals(BlockShape.EMPTY));
        Checks.check("and give them back relative to the cell", slab.getBoxes().size() == 1
                && slab.getBoxes().get(0).equals(Box.of(0, 0, 0, 1, 0.5, 1)) && BlockShape.EMPTY.getBoxes().isEmpty());
    }

    // --------------------------------------------------------------- rays

    private static void rays() {
        Blocks wall = new Blocks();
        for (int y = 0; y < 4; y++) {
            wall.set(2, y, 0, BlockShape.FULL);
        }
        Checks.check("a wall stops a ray", !Rays.clear(Vec3.of(0.5, 1.5, 0.5), Vec3.of(4.5, 1.5, 0.5), wall));
        Checks.check("over it, the ray is clear", Rays.clear(Vec3.of(0.5, 4.5, 0.5), Vec3.of(4.5, 4.5, 0.5), wall));
        Checks.check("either way along it", !Rays.clear(Vec3.of(4.5, 1.5, 0.5), Vec3.of(0.5, 1.5, 0.5), wall));

        Blocks low = new Blocks();
        low.set(2, 0, 0, BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1)));
        Checks.check("a ray over a slab is clear", Rays.clear(Vec3.of(0.5, 0.9, 0.5), Vec3.of(4.5, 0.9, 0.5), low));
        Checks.check("one through it is not", !Rays.clear(Vec3.of(0.5, 0.3, 0.5), Vec3.of(4.5, 0.3, 0.5), low));
        Checks.check("a point inside a block sees nothing, even itself",
                !Rays.clear(Vec3.of(2.5, 1.5, 0.5), Vec3.of(2.5, 1.5, 0.5), wall));
        Checks.check("an empty world stops nothing",
                Rays.clear(Vec3.of(-9, -9, -9), Vec3.of(9, 9, 9), BlockView.EMPTY));
    }

    // ---------------------------------------------------------- first hits

    private static void firstHits() {
        Blocks wall = new Blocks();
        for (int y = 0; y < 4; y++) {
            wall.set(2, y, 0, BlockShape.FULL);
        }
        RayHit east = Rays.first(Vec3.of(0.5, 1.5, 0.5), Vec3.of(4.5, 1.5, 0.5), wall);
        Checks.check("a ray into a wall meets it on the face it comes in through (" + east + ")",
                east != null && east.getCell().equals(Vec3i.of(2, 1, 0)) && east.getFace() == Direction.WEST
                        && Math.abs(east.getPoint().getX() - 2d) < 1e-9 && Math.abs(east.getFraction() - 0.375d) < 1e-9);
        RayHit west = Rays.first(Vec3.of(4.5, 1.5, 0.5), Vec3.of(0.5, 1.5, 0.5), wall);
        Checks.check("and from the other side, on the other face (" + west + ")",
                west != null && west.getFace() == Direction.EAST && Math.abs(west.getPoint().getX() - 3d) < 1e-9);
        RayHit down = Rays.first(Vec3.of(2.5, 6.5, 0.5), Vec3.of(2.5, 2.5, 0.5), wall);
        Checks.check("a ray falling onto it meets its top (" + down + ")",
                down != null && down.getFace() == Direction.UP && down.getCell().equals(Vec3i.of(2, 3, 0))
                        && Math.abs(down.getPoint().getY() - 4d) < 1e-9);
        Checks.check("a ray over it meets nothing", Rays.first(Vec3.of(0.5, 4.5, 0.5), Vec3.of(4.5, 4.5, 0.5), wall) == null);
        Checks.check("nor one stopping short of it", Rays.first(Vec3.of(0.5, 1.5, 0.5), Vec3.of(1.9, 1.5, 0.5), wall) == null);
        Checks.check("one along the top of it meets nothing, one along a seam inside it does",
                Rays.first(Vec3.of(0.5, 4, 0.5), Vec3.of(4.5, 4, 0.5), wall) == null
                        && Rays.first(Vec3.of(0.5, 2, 0.5), Vec3.of(4.5, 2, 0.5), wall) != null);
        RayHit onFace = Rays.first(Vec3.of(2, 1.5, 0.5), Vec3.of(4.5, 1.5, 0.5), wall);
        Checks.check("a ray starting on a block's face, heading in, meets it there, through that face",
                onFace != null && onFace.getFraction() == 0d && onFace.getFace() == Direction.WEST);
        Checks.check("a point meets the block it is in, and nothing in empty air",
                Rays.first(Vec3.of(2.5, 1.5, 0.5), Vec3.of(2.5, 1.5, 0.5), wall) != null
                        && Rays.first(Vec3.of(0.5, 1.5, 0.5), Vec3.of(0.5, 1.5, 0.5), wall) == null);
        RayHit inside = Rays.first(Vec3.of(2.5, 1.5, 0.5), Vec3.of(9.5, 1.5, 0.5), wall);
        Checks.check("a ray starting inside a block meets it where it starts, through no face",
                inside != null && inside.getFace() == null && inside.getFraction() == 0d
                        && inside.getPoint().equals(Vec3.of(2.5, 1.5, 0.5)));

        Blocks low = new Blocks();
        low.set(2, 0, 0, BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1)));
        RayHit slab = Rays.first(Vec3.of(0.5, 0.25, 0.5), Vec3.of(4.5, 0.25, 0.5), low);
        Checks.check("a slab is met on its own box", slab != null && slab.getFace() == Direction.WEST
                && Math.abs(slab.getPoint().getX() - 2d) < 1e-9);
        RayHit onto = Rays.first(Vec3.of(0.5, 2.5, 0.5), Vec3.of(4.5, -1.5, 0.5), low);
        Checks.check("and from above, on its top half a block up (" + onto + ")",
                onto != null && onto.getFace() == Direction.UP && Math.abs(onto.getPoint().getY() - 0.5d) < 1e-9);

        // A stair: a bottom slab, and a back half on top of it.
        Blocks stair = new Blocks();
        stair.set(0, 0, 0, BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1), Box.of(0.5, 0.5, 0, 1, 1, 1)));
        RayHit step = Rays.first(Vec3.of(0.75, 3, 0.5), Vec3.of(0.75, -1, 0.5), stair);
        Checks.check("of a shape's boxes, the one the line meets first is the hit (" + step + ")",
                step != null && Math.abs(step.getPoint().getY() - 1d) < 1e-9 && step.getFace() == Direction.UP);

        // A fence post: half a block taller than its cell.
        Blocks fence = new Blocks();
        fence.set(2, 0, 0, BlockShape.of(Box.of(0.375, 0, 0.375, 0.625, 1.5, 0.625)));
        Checks.check("a shape taller than its cell is not met by a ray only passing over the cell",
                Rays.first(Vec3.of(0.5, 1.2, 0.5), Vec3.of(4.5, 1.2, 0.5), fence) == null);
        RayHit post = Rays.first(Vec3.of(0.5, 1.2, 0.5), Vec3.of(4.5, 0.8, 0.5), fence);
        Checks.check("but met above its cell by one that comes through the cell (" + post + ")",
                post != null && post.getCell().equals(Vec3i.of(2, 0, 0)) && post.getPoint().getY() > 1d
                        && Math.abs(post.getPoint().getX() - 2.375d) < 1e-9);

        // The cells are tried in the order the line crosses them. A line starting in a
        // fence's cell tries the fence first, and meets it above its cell, inside the
        // block on top: the block it went through first is never tried.
        Blocks capped = new Blocks();
        capped.set(2, 0, 0, BlockShape.of(Box.of(0.375, 0, 0.375, 0.625, 1.5, 0.625)));
        capped.set(2, 1, 0, BlockShape.FULL);
        RayHit under = Rays.first(Vec3.of(2.1, 0.5, 0.5), Vec3.of(2.5, 1.4, 0.5), capped);
        Checks.check("the first cell crossed whose shape the line meets is the hit, even where a later cell's "
                + "block was in the way sooner (" + under + ")", under != null
                && under.getCell().equals(Vec3i.of(2, 0, 0)) && under.getPoint().getY() > 1d);

        // first() and clear() are one rule: the same random rays through the same random world agree.
        Random random = new Random(7);
        Blocks rubble = new Blocks();
        Blocks inCells = new Blocks();
        BlockShape[] shapes = { BlockShape.FULL, BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1)),
                BlockShape.of(Box.of(0.375, 0, 0.375, 0.625, 1.5, 0.625)) };
        for (int i = 0; i < 60; i++) {
            int x = random.nextInt(8);
            int y = random.nextInt(8);
            int z = random.nextInt(8);
            int shape = random.nextInt(shapes.length);
            rubble.set(x, y, z, shapes[shape]);
            inCells.set(x, y, z, shapes[shape % 2]);
        }
        int disagree = 0;
        int met = 0;
        int badPoint = 0;
        for (int i = 0; i < 2000; i++) {
            Vec3 a = Vec3.of(random.nextDouble() * 8, random.nextDouble() * 8, random.nextDouble() * 8);
            Vec3 b = Vec3.of(random.nextDouble() * 8, random.nextDouble() * 8, random.nextDouble() * 8);
            RayHit hit = Rays.first(a, b, rubble);
            if ((hit == null) != Rays.clear(a, b, rubble)) {
                disagree++;
            }
            if (hit != null) {
                met++;
            }
            // With every shape inside its cell, nothing stops the line before the point reported.
            RayHit within = Rays.first(a, b, inCells);
            if (within != null && within.getFraction() > 1e-6
                    && !Rays.clear(a, a.lerp(b, within.getFraction() - 1e-6), inCells)) {
                badPoint++;
            }
        }
        Checks.check("first() meets something exactly when clear() says the way is blocked, over 2000 random rays ("
                + disagree + " disagree, " + met + " met)", disagree == 0 && met > 200);
        Checks.check("and with every shape inside its cell, the way up to the point it reports is clear ("
                + badPoint + " not)", badPoint == 0);
    }

    // ----------------------------------------------------------- box clips

    private static void boxClips() {
        Box box = Box.of(2, 0, -1, 3, 2, 1);
        Checks.check("a segment through a box meets it where it comes in",
                Math.abs(box.clip(Vec3.of(0, 1, 0), Vec3.of(4, 1, 0)) - 0.5d) < 1e-9);
        Checks.check("and from the other way, at the other side",
                Math.abs(box.clip(Vec3.of(4, 1, 0), Vec3.of(0, 1, 0)) - 0.25d) < 1e-9);
        Checks.check("one passing over it misses", Double.isNaN(box.clip(Vec3.of(0, 3, 0), Vec3.of(4, 3, 0))));
        Checks.check("one stopping short of it misses", Double.isNaN(box.clip(Vec3.of(0, 1, 0), Vec3.of(1.9, 1, 0))));
        Checks.check("one starting inside meets it at once", box.clip(Vec3.of(2.5, 1, 0), Vec3.of(9, 1, 0)) == 0d);
        Checks.check("one grazing its top meets it: an entity's box is closed",
                Math.abs(box.clip(Vec3.of(0, 2, 0), Vec3.of(4, 2, 0)) - 0.5d) < 1e-9);
        Checks.check("a diagonal comes in through whichever face it reaches last",
                Math.abs(box.clip(Vec3.of(0, 4.5, 0), Vec3.of(4, 0.5, 0)) - 0.625d) < 1e-9);
        Checks.check("touching only its corner meets it there",
                Math.abs(box.clip(Vec3.of(0, 0, 0), Vec3.of(4, 4, 0)) - 0.5d) < 1e-9);
        Checks.check("a point is in it or not", box.clip(Vec3.of(2.5, 1, 0), Vec3.of(2.5, 1, 0)) == 0d
                && Double.isNaN(box.clip(Vec3.of(5, 1, 0), Vec3.of(5, 1, 0))));
    }

    // ------------------------------------------------------------- without

    private static void without() {
        Blocks blocks = new Blocks();
        blocks.set(2, 1, 0, BlockShape.FULL);
        blocks.set(3, 1, 0, BlockShape.FULL);
        BlockView gone = blocks.without(Vec3i.of(2, 1, 0));
        Checks.check("a world without a cell has nothing in it", gone.shapeAt(2, 1, 0).isEmpty());
        Checks.check("and the rest as it was", gone.shapeAt(3, 1, 0) == BlockShape.FULL && blocks.shapeAt(2, 1, 0) == BlockShape.FULL);
        Checks.check("so a line from inside the cell taken out gets out",
                Rays.clear(Vec3.of(2.5, 1.5, 0.5), Vec3.of(2.5, 5.5, 0.5), gone)
                        && !Rays.clear(Vec3.of(2.5, 1.5, 0.5), Vec3.of(2.5, 5.5, 0.5), blocks));
    }

    // -------------------------------------------------------- obstructions

    private static void obstructions() {
        Checks.check("NONE: nothing is ever in the way", !Obstructions.NONE.any(Box.block(0, 0, 0)));

        MovementRig rig = new MovementRig(new GridCollisionSpace());
        MovementRig.Script still = tick -> MovementInput.none(0f);
        rig.self(Vec3.of(0.5, 1, 0.5), still);
        rig.add(Vec3.of(5.5, 1, 0.5), still);
        rig.tick();
        Obstructions everyone = Obstructions.of(rig.entities, rig.bodies);
        Checks.check("someone standing in a cell is in the way of it", everyone.any(Box.block(5, 1, 0))
                && everyone.any(Box.block(5, 2, 0)));
        Checks.check("not of the cell beside them, nor the one under their feet, which they only touch",
                !everyone.any(Box.block(6, 1, 0)) && !everyone.any(Box.block(5, 0, 0)));
        Checks.check("you are always counted", everyone.any(Box.block(0, 1, 0)));
        Obstructions onlyYou = Obstructions.of(rig.entities);
        Checks.check("with no trackers, only you are", onlyYou.any(Box.block(0, 1, 0)) && !onlyYou.any(Box.block(5, 1, 0)));
        Checks.check("a region larger than a cell finds them too", everyone.any(Box.of(3, 0, -2, 8, 4, 3)));
    }

    /** Blocks by cell, set by hand. */
    private static final class Blocks implements BlockView {
        private final Map<Long, BlockShape> shapes = new HashMap<>();

        void set(int x, int y, int z, BlockShape shape) {
            shapes.put(Vec3i.asLong(x, y, z), shape);
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            BlockShape shape = shapes.get(Vec3i.asLong(x, y, z));
            return shape != null ? shape : BlockShape.EMPTY;
        }
    }
}
