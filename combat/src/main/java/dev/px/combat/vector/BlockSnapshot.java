package dev.px.combat.vector;

import dev.px.combat.world.BlockShape;
import dev.px.combat.world.BlockView;
import dev.px.core.math.Box;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks around one explosion, frozen: every non-empty cell in a region,
 * with each distinct shape stored once.
 *
 * <p>A {@link BlockView} itself, so a recorded explosion replays against exactly
 * the blocks it went off among, with no game. Anything outside the region is
 * empty: the region is chosen to hold every ray the explosion could cast.
 *
 * <p>Immutable. Equal when the shapes in every cell are.
 */
public final class BlockSnapshot implements BlockView {

    private static final BlockSnapshot EMPTY = new BlockSnapshot(
            Collections.<BlockShape>emptyList(), Collections.<Long, Integer>emptyMap());

    private final List<BlockShape> palette;
    /** Cell key to palette index, in the order cells were captured. */
    private final Map<Long, Integer> cells;

    private BlockSnapshot(List<BlockShape> palette, Map<Long, Integer> cells) {
        this.palette = palette;
        this.cells = cells;
    }

    /** @return every non-empty cell {@code blocks} has inside {@code region} */
    public static BlockSnapshot capture(BlockView blocks, Box region) {
        Validate.notNull(blocks, "blocks");
        Validate.notNull(region, "region");
        List<BlockShape> palette = new ArrayList<>();
        Map<BlockShape, Integer> indices = new HashMap<>();
        Map<Long, Integer> cells = new LinkedHashMap<>();
        int minX = floor(region.getMin().getX());
        int minY = floor(region.getMin().getY());
        int minZ = floor(region.getMin().getZ());
        int maxX = floor(region.getMax().getX());
        int maxY = floor(region.getMax().getY());
        int maxZ = floor(region.getMax().getZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockShape shape = blocks.shapeAt(x, y, z);
                    if (shape == null || shape.isEmpty()) {
                        continue;
                    }
                    Integer index = indices.get(shape);
                    if (index == null) {
                        index = palette.size();
                        palette.add(shape);
                        indices.put(shape, index);
                    }
                    cells.put(key(x, y, z), index);
                }
            }
        }
        return cells.isEmpty() ? EMPTY : new BlockSnapshot(Collections.unmodifiableList(palette),
                Collections.unmodifiableMap(cells));
    }

    /**
     * @param palette each distinct shape once
     * @param cells each non-empty cell as {@code {x, y, z, paletteIndex}}
     */
    public static BlockSnapshot of(List<BlockShape> palette, List<int[]> cells) {
        Validate.notNull(palette, "palette");
        Validate.notNull(cells, "cells");
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (int[] cell : cells) {
            Validate.check(cell.length == 4, "a cell is {x, y, z, paletteIndex}");
            Validate.check(cell[3] >= 0 && cell[3] < palette.size(), "palette index out of range: " + cell[3]);
            map.put(key(cell[0], cell[1], cell[2]), cell[3]);
        }
        return map.isEmpty() ? EMPTY : new BlockSnapshot(Collections.unmodifiableList(new ArrayList<>(palette)),
                Collections.unmodifiableMap(map));
    }

    public static BlockSnapshot empty() {
        return EMPTY;
    }

    @Override
    public BlockShape shapeAt(int x, int y, int z) {
        Integer index = cells.get(key(x, y, z));
        return index == null ? BlockShape.EMPTY : palette.get(index);
    }

    /** @return each distinct shape, once */
    public List<BlockShape> getPalette() {
        return palette;
    }

    /** @return each non-empty cell as {@code {x, y, z, paletteIndex}}, in capture order */
    public List<int[]> getCells() {
        List<int[]> out = new ArrayList<>(cells.size());
        for (Map.Entry<Long, Integer> entry : cells.entrySet()) {
            long key = entry.getKey();
            out.add(new int[] { unpack(key, 42), unpack(key, 21), unpack(key, 0), entry.getValue() });
        }
        return out;
    }

    public int size() {
        return cells.size();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BlockSnapshot)) {
            return false;
        }
        BlockSnapshot that = (BlockSnapshot) other;
        if (cells.size() != that.cells.size()) {
            return false;
        }
        for (Map.Entry<Long, Integer> entry : cells.entrySet()) {
            Integer theirs = that.cells.get(entry.getKey());
            if (theirs == null || !palette.get(entry.getValue()).equals(that.palette.get(theirs))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (Map.Entry<Long, Integer> entry : cells.entrySet()) {
            hash += Long.hashCode(entry.getKey()) ^ palette.get(entry.getValue()).hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        return "BlockSnapshot(" + cells.size() + " cells, " + palette.size() + " shapes)";
    }

    /** 21 bits a coordinate, signed: plenty for the blocks around one explosion anywhere in a world. */
    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) << 42 | ((long) y & 0x1FFFFFL) << 21 | ((long) z & 0x1FFFFFL);
    }

    private static int unpack(long key, int shift) {
        int value = (int) ((key >>> shift) & 0x1FFFFFL);
        return value >= 0x100000 ? value - 0x200000 : value;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
