package dev.px.projectile.test;

import dev.px.core.math.Vec3i;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;

import java.util.HashMap;
import java.util.Map;

/** Blocks by cell, set by hand: the world a test's projectiles fly through. */
final class Blocks implements BlockView {

    private final Map<Long, BlockShape> shapes = new HashMap<>();

    Blocks set(int x, int y, int z, BlockShape shape) {
        shapes.put(Vec3i.asLong(x, y, z), shape);
        return this;
    }

    /** A floor of full blocks whose top is at {@code y + 1}, from {@code from} to {@code to} on X and Z. */
    Blocks floor(int y, int from, int to) {
        for (int x = from; x <= to; x++) {
            for (int z = from; z <= to; z++) {
                set(x, y, z, BlockShape.FULL);
            }
        }
        return this;
    }

    /** A wall of full blocks across Z at {@code x}, from {@code bottom} up to {@code top}. */
    Blocks wallAtX(int x, int bottom, int top, int from, int to) {
        for (int y = bottom; y <= top; y++) {
            for (int z = from; z <= to; z++) {
                set(x, y, z, BlockShape.FULL);
            }
        }
        return this;
    }

    @Override
    public BlockShape shapeAt(int x, int y, int z) {
        BlockShape shape = shapes.get(Vec3i.asLong(x, y, z));
        return shape != null ? shape : BlockShape.EMPTY;
    }
}
