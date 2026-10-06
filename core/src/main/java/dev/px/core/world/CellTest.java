package dev.px.core.world;

/**
 * A yes-or-no question about one block cell, answered by your game: "is this
 * free to place into?", "can this be clicked against?", "can a crystal sit on
 * this?". Core and the libraries on it ask; only your game knows.
 *
 * <pre>{@code
 * CellTest clear = (x, y, z) -> isAirOrReplaceable(x, y, z);
 * CellTest solid = (x, y, z) -> isFullBlock(x, y, z);
 * }</pre>
 */
@FunctionalInterface
public interface CellTest {

    boolean test(int x, int y, int z);
}
