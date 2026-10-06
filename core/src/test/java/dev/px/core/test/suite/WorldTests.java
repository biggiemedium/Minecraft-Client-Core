package dev.px.core.test.suite;

import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;
import dev.px.core.world.Rays;

import java.util.HashMap;
import java.util.Map;

/**
 * The world seams, against blocks and bodies set by hand in this file: block
 * shapes, lines through them, a world with cells taken out, and entities in the
 * way. None of it knows a block by name.
 */
public final class WorldTests {

    private WorldTests() {
    }

    public static void run() {
        Checks.section("World");

        shapes();
        rays();
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
