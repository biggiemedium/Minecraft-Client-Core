package dev.px.combat.place;

import dev.px.combat.search.rule.Reach;
import dev.px.core.util.Validate;
import dev.px.core.world.BlockView;
import dev.px.core.world.CellTest;
import dev.px.core.world.Obstructions;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * How careful to be about placing, as one value: which faces, where on them,
 * whether against nothing, how many a tick, how many supports. Save one per
 * server your client plays on, and hand it to every module that places.
 *
 * <pre>{@code
 * PlacementStyle relaxed = PlacementStyle.vanilla(reach, blocks);              // any face, as many as you like
 * PlacementStyle careful = PlacementStyle.strict(reach, blocks);               // faces you can see, one a tick
 *
 * // your own presets, named however your client names them
 * PlacementStyle myStrictServer = PlacementStyle.strict(reach, blocks).withHit(HitPoint.NEAREST);
 *
 * Clicks clicks = careful.clicks(Game::isSolid, Game::isAirOrReplaceable);
 * PlacementPlanner planner = careful.planner(clicks, Obstructions.of(Core.entities(), players)).build();
 * }</pre>
 *
 * <h2>Strict</h2>
 *
 * <p>Servers with strict placement checks &mdash; anticheats that replay your
 * look ray against every click &mdash; want each click to be on a face turned
 * toward you, seen along a clear line, in reach; against something, never in mid
 * air; and agreeing with the one rotation you send each tick, so one placement a
 * tick. {@link #strict} is that, made of the library's own {@link FaceRule}s. It
 * names no anticheat: whichever your server runs, start from it and adjust.
 *
 * <p>Immutable.
 */
public final class PlacementStyle {

    private final FaceRule faces;
    private final HitPoint hit;
    private final BooleanSupplier airPlace;
    private final IntSupplier perTick;
    private final int supports;

    private PlacementStyle(FaceRule faces, HitPoint hit, BooleanSupplier airPlace, IntSupplier perTick, int supports) {
        this.faces = faces;
        this.hit = hit;
        this.airPlace = airPlace;
        this.perTick = perTick;
        this.supports = supports;
    }

    /**
     * Any face within reach, at its centre, as many a tick as you like, never in
     * mid air, two supports deep: what a vanilla server accepts.
     */
    public static PlacementStyle vanilla(Reach reach, BlockView blocks) {
        return new PlacementStyle(FaceRule.reach(reach, blocks), HitPoint.CENTRE, () -> false,
                () -> Integer.MAX_VALUE, 2);
    }

    /**
     * Only a face turned toward you, with a clear line to its centre, within
     * reach; never in mid air; one placement a tick; two supports deep.
     */
    public static PlacementStyle strict(Reach reach, BlockView blocks) {
        FaceRule faces = FaceRule.facingEye().and(FaceRule.visible(blocks)).and(FaceRule.reach(reach, blocks));
        return new PlacementStyle(faces, HitPoint.CENTRE, () -> false, () -> 1, 2);
    }

    public PlacementStyle withFaces(FaceRule faces) {
        return new PlacementStyle(Validate.notNull(faces, "faces"), hit, airPlace, perTick, supports);
    }

    public PlacementStyle withHit(HitPoint hit) {
        return new PlacementStyle(faces, Validate.notNull(hit, "hit"), airPlace, perTick, supports);
    }

    public PlacementStyle withAirPlace(BooleanSupplier allowed) {
        return new PlacementStyle(faces, hit, Validate.notNull(allowed, "allowed"), perTick, supports);
    }

    /** @param blocks how many placements a tick, read live */
    public PlacementStyle withPerTick(IntSupplier blocks) {
        return new PlacementStyle(faces, hit, airPlace, Validate.notNull(blocks, "blocks"), supports);
    }

    public PlacementStyle withSupports(int blocks) {
        Validate.check(blocks >= 0, "supports must not be negative");
        return new PlacementStyle(faces, hit, airPlace, perTick, blocks);
    }

    public FaceRule getFaces() {
        return faces;
    }

    public HitPoint getHit() {
        return hit;
    }

    public IntSupplier getPerTick() {
        return perTick;
    }

    public int getSupports() {
        return supports;
    }

    /** @return click rules in this style, for your blocks */
    public Clicks clicks(CellTest support, CellTest replaceable) {
        return Clicks.builder().support(support).replaceable(replaceable).faces(faces).hit(hit).airPlace(airPlace)
                .build();
    }

    /** @return a planner builder in this style, its per-tick limit and supports set: add anything else, then build */
    public PlacementPlanner.Builder planner(Clicks clicks, Obstructions obstructions) {
        return PlacementPlanner.builder().clicks(clicks).obstructions(obstructions).perTick(perTick).supports(supports);
    }
}
