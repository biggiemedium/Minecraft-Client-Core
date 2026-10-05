package dev.px.combat.world;

/**
 * A yes-or-no question about one block cell, answered by your game:
 * "can a crystal sit on this?", "is this free to place into?".
 *
 * <pre>{@code
 * CellTest base  = (x, y, z) -> isObsidianOrBedrock(x, y, z);
 * CellTest clear = (x, y, z) -> isAirOrReplaceable(x, y, z);
 * }</pre>
 */
@FunctionalInterface
public interface CellTest {

    boolean test(int x, int y, int z);
}
