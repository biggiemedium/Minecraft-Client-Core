package dev.px.combat.place;

import dev.px.core.math.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a {@link PlacementPlanner} would place this tick, in order, and what it
 * left out and why.
 *
 * <p>Immutable.
 */
public final class Plan {

    /** Why a cell asked for was left out. */
    public enum Skip {

        /** Something is already there: your replaceable test said no. */
        FILLED,

        /** An entity is in the way. */
        OCCUPIED,

        /** No click your rules accept reaches it, not even with supports under it. */
        UNREACHABLE,

        /** It would have been placed, but this tick's limit was reached first. */
        LIMIT
    }

    /** One placement, in the order to make it. */
    public static final class Step {
        private final Vec3i cell;
        private final Click click;
        private final boolean support;

        Step(Vec3i cell, Click click, boolean support) {
            this.cell = cell;
            this.click = click;
            this.support = support;
        }

        /** @return the cell the block goes into */
        public Vec3i getCell() {
            return cell;
        }

        /** @return how to place it */
        public Click getClick() {
            return click;
        }

        /** @return whether it is there only to give a cell you asked for something to click against */
        public boolean isSupport() {
            return support;
        }

        @Override
        public String toString() {
            return "Step(" + cell + (support ? ", support" : "") + ", " + click + ")";
        }
    }

    private final List<Step> steps;
    private final Map<Vec3i, Skip> skipped;

    Plan(List<Step> steps, Map<Vec3i, Skip> skipped) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
    }

    /** @return each placement to make this tick, in order: a support before what it supports */
    public List<Step> getSteps() {
        return steps;
    }

    /** @return each cell asked for and left out, and why, in the order asked */
    public Map<Vec3i, Skip> getSkipped() {
        return skipped;
    }

    /** @return whether every cell asked for is placed by this plan, or was already filled */
    public boolean isComplete() {
        for (Skip skip : skipped.values()) {
            if (skip != Skip.FILLED) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    @Override
    public String toString() {
        return "Plan(" + steps + (skipped.isEmpty() ? "" : ", skipped " + skipped) + ")";
    }
}
