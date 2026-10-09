package dev.px.navigation;

/** Why a {@link Navigator} asked for a route. */
public enum Replan {

    /** There was none yet: travelling began, or began again after arriving. */
    START,

    /** The player was not where the route expected: knocked back, slowed, or the simulation is off. */
    DRIFT,

    /** The goal follows something, and it moved far enough. */
    GOAL_MOVED,

    /** Played again through the world as it is now, the rest of the route no longer ended where it did. */
    BLOCKS_CHANGED,

    /** The route ran out without the goal being met: a partial route's end, or a goal that moved a little. */
    ROUTE_ENDED,

    /** The next stretch of a coarse route, planned precisely by the local planner. */
    SECTION,

    /** Planned again because a {@link Navigator.Builder#replanEvery refresh} was due. */
    REFRESH
}
