/**
 * Utilities: data structures, algorithms and helpers, none of which Core needs to
 * boot. Where to look:
 *
 * <table border="1">
 *   <caption>The util subpackages</caption>
 *   <tr><th>Package</th><th>For</th></tr>
 *   <tr><td>{@link dev.px.core.util}</td>
 *       <td>{@code Validate} argument checks, {@code Reflect} field walking,
 *           {@code CoreLogger} / {@code ConsoleLogger}</td></tr>
 *   <tr><td>{@link dev.px.core.util.collect}</td>
 *       <td>Containers: {@code Pair}, {@code Triplet}, {@code CircularQueue} /
 *           {@code CircularDeque}, {@code RollingAverage}, {@code LruCache} /
 *           {@code ExpiringCache}, {@code Trie}, {@code WeightedList}</td></tr>
 *   <tr><td>{@link dev.px.core.util.math}</td>
 *       <td>{@code MovementMath}, {@code RotationMath}, {@code Curves},
 *           {@code Statistics}</td></tr>
 *   <tr><td>{@link dev.px.core.util.spatial}</td>
 *       <td>Algorithms over 3D space: {@code AStar} + {@code PathSpace} +
 *           {@code Path}, {@code VoxelRay}, {@code FloodFill},
 *           {@code SpatialGrid}</td></tr>
 *   <tr><td>{@link dev.px.core.util.time}</td>
 *       <td>{@code TickTimer} for server ticks, {@code Profiler} for frame
 *           timing</td></tr>
 *   <tr><td>{@link dev.px.core.util.render}</td>
 *       <td>{@code ColorUtil}, {@code Gradient}</td></tr>
 *   <tr><td>{@link dev.px.core.util.text}</td>
 *       <td>{@code TextUtil}, {@code ChatColor}</td></tr>
 *   <tr><td>{@link dev.px.core.util.net}</td>
 *       <td>{@code Http}, blocking &mdash; run it on
 *           {@link dev.px.core.concurrent.ThreadService}</td></tr>
 * </table>
 *
 * <p>Not here: the value types these build on. {@code Vec2}, {@code Vec3},
 * {@code Vec3i}, {@code Direction}, {@code Box}, {@code Range} and
 * {@code Stopwatch} live in {@link dev.px.core.math}; {@code Color} and
 * {@code Animation} in {@link dev.px.core.render}.
 *
 * <p>Nothing in this package imports the game, and the classes reaching the world
 * do so through an interface the caller supplies &mdash; {@code PathSpace} for
 * pathfinding, a predicate for raycasting and flood filling.
 */
package dev.px.core.util;
