package dev.px.core.test.suite;

import dev.px.core.math.Direction;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.test.harness.Checks;
import dev.px.core.util.spatial.AStar;
import dev.px.core.util.spatial.FloodFill;
import dev.px.core.util.spatial.Path;
import dev.px.core.util.spatial.PathSpace;
import dev.px.core.util.spatial.SpatialGrid;
import dev.px.core.util.spatial.VoxelRay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The spatial algorithms, run against grids drawn in this file.
 *
 * <p>This suite is the evidence for the claim {@link PathSpace} makes: a
 * pathfinder that reaches the world through a two-method interface can be fully
 * exercised with no world at all. The mazes below are string literals, the
 * "blocks" are characters, and the search cannot tell the difference.
 */
public final class SpatialTests {

    private SpatialTests() {
    }

    public static void run() {
        grid();
        raycasting();
        flooding();
        pathfinding();
    }

    // ------------------------------------------------------------ the grid

    private static void grid() {
        Checks.section("Spatial grid");

        SpatialGrid<String> grid = SpatialGrid.of(4d);
        grid.insert(Vec3.of(0d, 0d, 0d), "origin");
        grid.insert(Vec3.of(3d, 0d, 0d), "near");
        grid.insert(Vec3.of(30d, 0d, 0d), "far");

        Checks.checkEquals("everything inserted is counted", 3, grid.size());
        List<String> close = grid.within(Vec3.ZERO, 5d);
        Checks.checkEquals("a query returns only what is in range", 2, close.size());
        Checks.checkEquals("nearest first", "origin", close.get(0));
        Checks.checkEquals("then the next", "near", close.get(1));
        Checks.checkEquals("the nearest is found directly", "origin", grid.nearest(Vec3.ZERO, 5d));
        Checks.checkEquals("a radius reaching nothing finds nothing", null,
                grid.nearest(Vec3.of(100d, 0d, 0d), 5d));
        Checks.check("presence can be asked without building a list",
                grid.anyWithin(Vec3.ZERO, 5d) && !grid.anyWithin(Vec3.of(100d, 0d, 0d), 1d));

        // The cell walk is a box, so without a per-entry distance check a query
        // would return things in the corners of that box, up to 73% too far away.
        SpatialGrid<String> corners = SpatialGrid.of(10d);
        corners.insert(Vec3.of(9d, 9d, 9d), "corner");
        Checks.checkEquals("the corner of a bucket is still distance-checked", 0,
                corners.within(Vec3.of(0.5d, 0.5d, 0.5d), 5d).size());

        // The real proof: agree with a linear scan on random data. A spatial index
        // that is fast and subtly wrong is worse than the scan it replaced.
        Random random = new Random(20260916L);
        SpatialGrid<Integer> many = SpatialGrid.of(8d);
        List<Vec3> positions = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Vec3 position = Vec3.of(
                    random.nextDouble() * 200d - 100d,
                    random.nextDouble() * 120d - 60d,
                    random.nextDouble() * 200d - 100d);
            positions.add(position);
            many.insert(position, i);
        }
        boolean agrees = true;
        for (int query = 0; query < 40; query++) {
            Vec3 centre = Vec3.of(
                    random.nextDouble() * 200d - 100d,
                    random.nextDouble() * 120d - 60d,
                    random.nextDouble() * 200d - 100d);
            double radius = 1d + random.nextDouble() * 30d;
            Set<Integer> scanned = new HashSet<>();
            for (int i = 0; i < positions.size(); i++) {
                if (positions.get(i).distanceTo(centre) <= radius) {
                    scanned.add(i);
                }
            }
            agrees &= new HashSet<>(many.within(centre, radius)).equals(scanned);
        }
        Checks.check("400 entries and 40 random queries agree with a linear scan", agrees);
        Checks.check("and the index is actually bucketing", many.cellCount() > 1);

        many.clear();
        Checks.check("clearing empties it", many.isEmpty() && many.cellCount() == 0);
    }

    // -------------------------------------------------------------- raycasts

    private static void raycasting() {
        Checks.section("Voxel raycasting");

        // A single solid pillar at x = 3.
        Set<Vec3i> solid = new HashSet<>();
        solid.add(Vec3i.of(3, 0, 0));

        Vec3 eye = Vec3.of(0.5d, 0.5d, 0.5d);
        VoxelRay.Hit hit = VoxelRay.trace(eye, Vec3.of(1d, 0d, 0d), 10d, solid::contains);
        Checks.check("a ray finds the block in its way", hit != null);
        Checks.checkEquals("at the right cell", Vec3i.of(3, 0, 0), hit.getPosition());
        Checks.checkEquals("entering through the face it met", Direction.WEST, hit.getFace());
        Checks.checkEquals("at the right distance", 2.5f, (float) hit.getDistance());
        Checks.checkEquals("the surface point is on the boundary", 3f, (float) hit.getPoint().getX());
        Checks.checkEquals("and the cell against that face is where a block would go",
                Vec3i.of(2, 0, 0), hit.getAdjacent());

        Checks.checkEquals("a ray that reaches nothing returns nothing", null,
                VoxelRay.trace(eye, Vec3.of(1d, 0d, 0d), 2d, solid::contains));
        Checks.checkEquals("nor does one pointing away", null,
                VoxelRay.trace(eye, Vec3.of(-1d, 0d, 0d), 10d, solid::contains));
        Checks.checkEquals("a zero direction traces nothing", null,
                VoxelRay.trace(eye, Vec3.ZERO, 10d, solid::contains));

        // Starting inside a block: there is no face, and saying there was one would
        // place the next block inside the player.
        VoxelRay.Hit inside = VoxelRay.trace(Vec3.of(3.5d, 0.5d, 0.5d),
                Vec3.of(1d, 0d, 0d), 10d, solid::contains);
        Checks.checkEquals("a ray starting inside a block hits it immediately",
                Vec3i.of(3, 0, 0), inside.getPosition());
        Checks.checkEquals("with no face crossed", null, inside.getFace());
        Checks.checkEquals("at zero distance", 0f, (float) inside.getDistance());

        Checks.checkEquals("a southward ray enters through the north face", Direction.NORTH,
                VoxelRay.trace(Vec3.of(0.5d, 0.5d, 0.5d), Vec3.of(0d, 0d, 1d), 10d,
                        cell -> cell.equals(Vec3i.of(0, 0, 4))).getFace());
        Checks.checkEquals("a falling ray enters through the top", Direction.UP,
                VoxelRay.trace(Vec3.of(0.5d, 5.5d, 0.5d), Vec3.of(0d, -1d, 0d), 10d,
                        cell -> cell.equals(Vec3i.of(0, 0, 0))).getFace());

        // Traversal order and completeness: every cell entered exactly once, in
        // increasing distance. Sampling along the ray instead would do neither.
        List<Vec3i> visited = new ArrayList<>();
        double[] previous = { -1d };
        boolean[] ordered = { true };
        VoxelRay.forEach(eye, Vec3.of(1d, 0d, 0d), 5d, (position, face, distance) -> {
            visited.add(position);
            ordered[0] &= distance >= previous[0];
            previous[0] = distance;
            return true;
        });
        Checks.checkEquals("a straight ray crosses one cell per block", 6, visited.size());
        Checks.checkEquals("no cell is visited twice", visited.size(), new HashSet<>(visited).size());
        Checks.check("and they arrive in order of distance", ordered[0]);
        Checks.checkEquals("starting with the cell the ray begins in", Vec3i.of(0, 0, 0), visited.get(0));

        // A diagonal ray must not skip the cells it clips through.
        List<Vec3i> diagonal = new ArrayList<>();
        VoxelRay.forEach(eye, Vec3.of(1d, 0d, 1d), 4d, (position, face, distance) -> {
            diagonal.add(position);
            return true;
        });
        boolean stepwise = true;
        for (int i = 1; i < diagonal.size(); i++) {
            stepwise &= diagonal.get(i - 1).isAdjacentTo(diagonal.get(i));
        }
        Checks.check("a diagonal ray steps one face at a time, skipping nothing", stepwise);
        Checks.check("and covers both axes", diagonal.size() > 4);

        // The rotation overload has to agree with the vector one, or aiming and
        // raycasting disagree about where the player is looking.
        Vec2 south = Vec2.rotation(0f, 0f);
        Checks.checkEquals("tracing along a rotation matches tracing along its vector",
                Vec3i.of(0, 0, 4),
                VoxelRay.trace(eye, south, 10d, cell -> cell.equals(Vec3i.of(0, 0, 4))).getPosition());

        // A visitor can stop the walk early.
        int[] count = { 0 };
        VoxelRay.forEach(eye, Vec3.of(1d, 0d, 0d), 100d, (position, face, distance) -> ++count[0] < 3);
        Checks.checkEquals("a visitor returning false stops the traversal", 3, count[0]);
    }

    // ------------------------------------------------------------- flooding

    private static void flooding() {
        Checks.section("Flood fill");

        // A sealed 3x3 room, open air everywhere outside it.
        Set<Vec3i> walls = new HashSet<>();
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 3; z++) {
                walls.add(Vec3i.of(x, -1, z));
                walls.add(Vec3i.of(x, 1, z));
                if (x == -1 || x == 3 || z == -1 || z == 3) {
                    walls.add(Vec3i.of(x, 0, z));
                }
            }
        }
        FloodFill.Region room = FloodFill.from(Vec3i.of(1, 0, 1), cell -> !walls.contains(cell), 512);
        Checks.check("a sealed room is fully explored", room.isComplete());
        Checks.checkEquals("and every cell of it is found", 9, room.size());
        Checks.check("including the corners", room.contains(Vec3i.of(0, 0, 0)));
        Checks.check("but nothing outside the walls", !room.contains(Vec3i.of(4, 0, 1)));
        Checks.check("which is what enclosure means",
                FloodFill.isEnclosed(Vec3i.of(1, 0, 1), cell -> !walls.contains(cell), 512));

        // Knock one block out and the same fill escapes into open air.
        walls.remove(Vec3i.of(3, 0, 1));
        FloodFill.Region leaked = FloodFill.from(Vec3i.of(1, 0, 1), cell -> !walls.contains(cell), 300);
        Checks.check("a room with a hole in it is not enclosed", !leaked.isComplete());
        Checks.check("the fill stops at its budget rather than running away",
                leaked.size() <= 300);
        // Size alone cannot answer the question: this region is small and open.
        Checks.check("so enclosure is judged by completeness, not size",
                !FloodFill.isEnclosed(Vec3i.of(1, 0, 1), cell -> !walls.contains(cell), 300));

        Checks.checkEquals("a fill starting inside a wall finds nothing", 0,
                FloodFill.from(Vec3i.of(-1, 0, -1), cell -> !walls.contains(cell), 64).size());

        // Two cells touching only at a corner are connected for one rule and not
        // the other, which is the entire reason connectivity is a parameter.
        Set<Vec3i> pair = new HashSet<>();
        pair.add(Vec3i.ZERO);
        pair.add(Vec3i.of(1, 1, 0));
        FloodFill.Region faces = FloodFill.from(Vec3i.ZERO, pair::contains, 64, FloodFill.Connectivity.FACES);
        FloodFill.Region all = FloodFill.from(Vec3i.ZERO, pair::contains, 64, FloodFill.Connectivity.ALL);
        Checks.checkEquals("cells touching at an edge are not face-connected", 1, faces.size());
        Checks.checkEquals("but are when diagonals count", 2, all.size());

        Checks.checkThrows("a fill without a budget is rejected", IllegalArgumentException.class,
                () -> FloodFill.from(Vec3i.ZERO, cell -> true, 0));
    }

    // ---------------------------------------------------------- pathfinding

    private static void pathfinding() {
        Checks.section("Pathfinding");

        PathSpace open = position -> position.getY() == 0;

        Path direct = AStar.in(open).diagonal(false).find(Vec3i.ZERO, Vec3i.of(5, 0, 0));
        Checks.check("an open path is found", direct.isComplete());
        Checks.checkEquals("and is the straight one", 6, direct.size());
        Checks.checkEquals("starting where asked", Vec3i.ZERO, direct.first());
        Checks.checkEquals("and ending there", Vec3i.of(5, 0, 0), direct.last());
        Checks.checkEquals("its length is the distance walked", 5f, (float) direct.length());
        Checks.checkEquals("standing on the goal is a path of one", 1,
                AStar.in(open).find(Vec3i.ZERO, Vec3i.ZERO).size());

        // Diagonals should genuinely shorten a corner-to-corner route.
        Path square = AStar.in(open).diagonal(false).find(Vec3i.ZERO, Vec3i.of(6, 0, 6));
        Path angled = AStar.in(open).diagonal(true).find(Vec3i.ZERO, Vec3i.of(6, 0, 6));
        Checks.checkEquals("without diagonals a corner takes both legs", 13, square.size());
        Checks.checkEquals("with them it cuts across", 7, angled.size());
        Checks.check("which is a shorter walk", angled.length() < square.length());

        // ---- a maze -----------------------------------------------------------
        // Rows are Z, columns are X. The only way through is the gap at the top.
        Maze maze = Maze.of(
                ".........",
                "....#....",
                "S...#...G",
                "....#....",
                "....#....");
        Path through = AStar.in(maze).diagonal(false).maxNodes(2000).find(maze.start, maze.goal);
        Checks.check("a path is found around a wall", through.isComplete());
        Checks.check("it goes the long way, through the gap",
                through.length() > maze.start.manhattanDistanceTo(maze.goal));
        boolean avoidsWalls = true;
        for (Vec3i step : through) {
            avoidsWalls &= maze.isPassable(step);
        }
        Checks.check("and never passes through one", avoidsWalls);
        boolean contiguous = true;
        for (int i = 1; i < through.size(); i++) {
            contiguous &= through.get(i - 1).isAdjacentTo(through.get(i));
        }
        Checks.check("every step is a real step", contiguous);

        // ---- unreachable ------------------------------------------------------
        Maze sealed = Maze.of(
                "#########",
                "#S..#..G#",
                "#...#...#",
                "#########");
        Path blocked = AStar.in(sealed).find(sealed.start, sealed.goal);
        Checks.check("an unreachable goal is not claimed as reached", !blocked.isComplete());
        Checks.check("but the best effort towards it is still returned", !blocked.isEmpty());
        Checks.checkEquals("starting from where the agent is", sealed.start, blocked.first());

        // ---- budgets ----------------------------------------------------------
        Path starved = AStar.in(open).maxNodes(2).find(Vec3i.ZERO, Vec3i.of(200, 0, 200));
        Checks.check("a search out of budget gives up", !starved.isComplete());
        Checks.check("rather than freezing the game", starved.getNodesVisited() <= 2);
        Checks.check("and still points the right way", starved.size() > 1);

        // A greedier heuristic should do less work. It may return a longer path;
        // that is the trade, and it is why the knob exists.
        Path exact = AStar.in(open).diagonal(true).find(Vec3i.ZERO, Vec3i.of(25, 0, 25));
        Path greedy = AStar.in(open).diagonal(true).heuristicWeight(1.6d)
                .find(Vec3i.ZERO, Vec3i.of(25, 0, 25));
        Checks.check("both reach the goal", exact.isComplete() && greedy.isComplete());
        Checks.check("the weighted search expands fewer nodes",
                greedy.getNodesVisited() <= exact.getNodesVisited());

        // ---- vertical rules ---------------------------------------------------
        // A step up of one is a jump; two is a wall.
        PathSpace ledge = position -> {
            if (position.getX() <= 2) {
                return position.getY() == 0;
            }
            return position.getY() == 1;
        };
        Checks.check("a one-block rise is stepped up",
                AStar.in(ledge).stepUp(1).find(Vec3i.ZERO, Vec3i.of(5, 1, 0)).isComplete());
        Checks.check("but not when the agent cannot step",
                !AStar.in(ledge).stepUp(0).find(Vec3i.ZERO, Vec3i.of(5, 1, 0)).isComplete());

        PathSpace cliff = position -> position.getX() <= 2 ? position.getY() == 5 : position.getY() == 0;
        Checks.check("a drop within the limit is taken",
                AStar.in(cliff).maxFall(5).find(Vec3i.of(0, 5, 0), Vec3i.of(5, 0, 0)).isComplete());
        Checks.check("a drop beyond it is refused",
                !AStar.in(cliff).maxFall(2).find(Vec3i.of(0, 5, 0), Vec3i.of(5, 0, 0)).isComplete());

        // ---- no corner cutting -------------------------------------------------
        // A diagonal between two blocked orthogonals would clip the join of two blocks.
        PathSpace pinch = position -> position.getY() == 0
                && !(position.getX() == 1 && position.getZ() == 0)
                && !(position.getX() == 0 && position.getZ() == 1);
        Path around = AStar.in(pinch).diagonal(true).maxNodes(500).find(Vec3i.ZERO, Vec3i.of(1, 0, 1));
        Checks.check("the goal is still reachable the long way", around.isComplete());
        Checks.check("but a diagonal never squeezes between two blocked corners",
                around.length() > 2d);
        boolean legalSteps = true;
        for (int i = 1; i < around.size(); i++) {
            Vec3i from = around.get(i - 1);
            Vec3i to = around.get(i);
            if (from.getX() != to.getX() && from.getZ() != to.getZ()) {
                legalSteps &= pinch.isPassable(Vec3i.of(to.getX(), to.getY(), from.getZ()))
                        && pinch.isPassable(Vec3i.of(from.getX(), to.getY(), to.getZ()));
            }
        }
        Checks.check("every diagonal it does take has both corners open", legalSteps);

        // ---- weighted terrain ---------------------------------------------------
        // A corridor that is passable but expensive should be walked around.
        PathSpace hazard = new PathSpace() {
            @Override
            public boolean isPassable(Vec3i position) {
                return position.getY() == 0 && position.getZ() >= 0 && position.getZ() <= 2;
            }

            @Override
            public double costOf(Vec3i position) {
                return position.getZ() == 0 ? 20d : 1d;
            }
        };
        Path avoided = AStar.in(hazard).diagonal(false).maxNodes(2000)
                .find(Vec3i.ZERO, Vec3i.of(6, 0, 0));
        boolean detours = false;
        for (Vec3i step : avoided) {
            detours |= step.getZ() > 0;
        }
        Checks.check("a costly route is walked around, not through", detours);
        Checks.check("while still reaching the goal", avoided.isComplete());
    }

    /**
     * A {@link PathSpace} drawn as text, which is the point: the search reaches
     * the world through two methods, so a few string literals can stand in for one.
     */
    private static final class Maze implements PathSpace {

        private final Set<Vec3i> passable = new HashSet<>();

        private Vec3i start = Vec3i.ZERO;
        private Vec3i goal = Vec3i.ZERO;

        private static Maze of(String... rows) {
            Maze maze = new Maze();
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z];
                for (int x = 0; x < row.length(); x++) {
                    char cell = row.charAt(x);
                    if (cell == '#') {
                        continue;
                    }
                    Vec3i position = Vec3i.of(x, 0, z);
                    maze.passable.add(position);
                    if (cell == 'S') {
                        maze.start = position;
                    } else if (cell == 'G') {
                        maze.goal = position;
                    }
                }
            }
            return maze;
        }

        @Override
        public boolean isPassable(Vec3i position) {
            return passable.contains(position);
        }
    }
}
