/**
 * The test kit: a world with no game in it, for testing flows and anything else
 * built on Core in a plain JVM.
 *
 * <p>A {@link dev.px.testkit.Sandbox} holds its own Core services, a block world,
 * a player moved by Core's real movement rules with whatever keys the controls
 * resolve, and entities seen only as positions, as the game shows them. Add it as
 * {@code testImplementation}; it depends only on Core.
 */
package dev.px.testkit;
