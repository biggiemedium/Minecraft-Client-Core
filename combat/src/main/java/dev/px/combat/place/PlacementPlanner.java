package dev.px.combat.place;

import dev.px.core.math.Box;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.util.Validate;
import dev.px.core.world.Obstructions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Which blocks to place this tick, in what order, and how: the part of a
 * surround, an auto-fill, a self-trap or a scaffold that is the same in all of
 * them.
 *
 * <pre>{@code
 * PlacementPlanner planner = PlacementPlanner.builder()
 *         .clicks(clicks)                                          // how placing works on your server
 *         .obstructions(Obstructions.of(Core.entities(), players)) // nothing goes where someone stands
 *         .perTick(blocksPerTick::getInt)
 *         .supports(1)                                             // add a block under one with nothing to click
 *         .build();
 *
 * Plan plan = planner.plan(eye, surroundCells(), looking);        // most important first
 * for (Plan.Step step : plan.getSteps()) {
 *     rotations.lookAt(step.getClick().getRotation(eye));
 *     Game.interact(step.getClick());
 * }
 * }</pre>
 *
 * <ul>
 *   <li><b>In your order.</b> Cells are placed in the order given, so the most
 *       important comes first, until the tick's limit.
 *   <li><b>Each placement helps the next.</b> A block planned this tick counts as
 *       there for every cell after it: something to click against, and no longer
 *       room to place into.
 *   <li><b>Supports.</b> A cell with nothing your rules let you click gets up to
 *       {@link Builder#supports} blocks beside it first, placed so that it then
 *       can be clicked; below it is tried first. When the tick's limit leaves no
 *       room for the cell itself, its supports are started with what is left.
 *   <li><b>Nobody is built into.</b> A cell an entity stands in is left out: the
 *       server refuses it.
 * </ul>
 *
 * <p>Every skipped cell says why. Game thread only.
 */
public final class PlacementPlanner {

    private final Clicks clicks;
    private final Obstructions obstructions;
    private final IntSupplier perTick;
    private final int supports;

    private PlacementPlanner(Builder builder) {
        this.clicks = builder.clicks;
        this.obstructions = builder.obstructions;
        this.perTick = builder.perTick;
        this.supports = builder.supports;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Clicks getClicks() {
        return clicks;
    }

    /** @return a plan for {@code cells}, clicking whatever is nearest your eyes */
    public Plan plan(Vec3 eye, Collection<Vec3i> cells) {
        return plan(eye, cells, null);
    }

    /**
     * @param looking where you look now, to choose the clicks needing least turn;
     *                null for the nearest
     * @return a plan for {@code cells}, the most important first
     */
    public Plan plan(Vec3 eye, Collection<Vec3i> cells, Vec2 looking) {
        Validate.notNull(eye, "eye");
        Validate.notNull(cells, "cells");
        int limit = Math.max(0, perTick.getAsInt());
        Set<Vec3i> placed = new LinkedHashSet<>();
        List<Plan.Step> steps = new ArrayList<>();
        Map<Vec3i, Plan.Skip> skipped = new LinkedHashMap<>();
        for (Vec3i cell : new LinkedHashSet<>(cells)) {
            if (placed.contains(cell)) {
                continue;                                    // already planned, as a support for another
            }
            if (!clicks.isReplaceable(cell)) {
                skipped.put(cell, Plan.Skip.FILLED);
                continue;
            }
            if (occupied(cell)) {
                skipped.put(cell, Plan.Skip.OCCUPIED);
                continue;
            }
            Click click = Clicks.pick(eye, clicks.into(eye, cell, placed), looking);
            List<Plan.Step> under = Collections.emptyList();
            if (click == null) {
                under = supportFor(eye, cell, placed, supports, looking);
                if (under == null) {
                    skipped.put(cell, Plan.Skip.UNREACHABLE);
                    continue;
                }
            }
            if (steps.size() + under.size() + 1 > limit) {
                // Start its supports with what the tick has left: the most important cell still gets nearer.
                for (int i = 0; i < under.size() && steps.size() < limit; i++) {
                    steps.add(under.get(i));
                    placed.add(under.get(i).getCell());
                }
                skipped.put(cell, Plan.Skip.LIMIT);
                continue;
            }
            for (Plan.Step step : under) {
                steps.add(step);
                placed.add(step.getCell());
            }
            if (click == null) {
                click = Clicks.pick(eye, clicks.into(eye, cell, placed), looking);
            }
            steps.add(new Plan.Step(cell, click, false));
            placed.add(cell);
        }
        return new Plan(steps, skipped);
    }

    /**
     * @return supports, at most {@code depth} deep, after which {@code cell} can be
     *         clicked: in the order to place them; null when there are none
     */
    private List<Plan.Step> supportFor(Vec3 eye, Vec3i cell, Set<Vec3i> placed, int depth, Vec2 looking) {
        if (depth <= 0) {
            return null;
        }
        for (Direction side : SUPPORT_ORDER) {
            Vec3i beside = cell.add(side.toVec3i());
            if (placed.contains(beside) || !clicks.isReplaceable(beside) || occupied(beside)) {
                continue;
            }
            Click there = Clicks.pick(eye, clicks.into(eye, beside, placed), looking);
            List<Plan.Step> chain = new ArrayList<>();
            Set<Vec3i> with = new LinkedHashSet<>(placed);
            if (there == null) {
                // Nothing to click for the support either: support it in turn, if depth allows.
                List<Plan.Step> deeper = supportFor(eye, beside, placed, depth - 1, looking);
                if (deeper == null) {
                    continue;
                }
                chain.addAll(deeper);
                for (Plan.Step step : deeper) {
                    with.add(step.getCell());
                }
                there = Clicks.pick(eye, clicks.into(eye, beside, with), looking);
                if (there == null) {
                    continue;
                }
            }
            chain.add(new Plan.Step(beside, there, true));
            with.add(beside);
            if (!clicks.into(eye, cell, with).isEmpty()) {
                return chain;
            }
        }
        return null;
    }

    private boolean occupied(Vec3i cell) {
        return obstructions.any(Box.block(cell.getX(), cell.getY(), cell.getZ()));
    }

    /** Below first: a block underneath is the support that stands on its own and is clicked from anywhere. */
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };

    public static final class Builder {

        private Clicks clicks;
        private Obstructions obstructions = Obstructions.NONE;
        private IntSupplier perTick = () -> Integer.MAX_VALUE;
        private int supports;

        private Builder() {
        }

        /** Required: how placing works on your server. */
        public Builder clicks(Clicks clicks) {
            this.clicks = Validate.notNull(clicks, "clicks");
            return this;
        }

        /** What stands in the way of a placement. Nothing unless set. */
        public Builder obstructions(Obstructions obstructions) {
            this.obstructions = Validate.notNull(obstructions, "obstructions");
            return this;
        }

        /** The most blocks to place a tick, supports included, read live. No limit unless set. */
        public Builder perTick(IntSupplier blocks) {
            this.perTick = Validate.notNull(blocks, "blocks");
            return this;
        }

        /** @param blocks how many support blocks deep to go for a cell with nothing to click; none unless set */
        public Builder supports(int blocks) {
            Validate.check(blocks >= 0, "supports must not be negative");
            this.supports = blocks;
            return this;
        }

        /** @throws IllegalStateException when clicks were not given */
        public PlacementPlanner build() {
            if (clicks == null) {
                throw new IllegalStateException("a PlacementPlanner needs: clicks");
            }
            return new PlacementPlanner(this);
        }
    }
}
