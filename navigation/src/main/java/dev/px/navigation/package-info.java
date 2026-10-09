/**
 * Navigation built on Core: getting the player to a goal through any pathfinder,
 * and a precise local planner that plans the exact keys for every tick by
 * simulating each move with Core's movement rules.
 *
 * <p>Like Core, this knows no game. The rules come from the client's physics
 * profile, the world from its collision space, and what is safe or dangerous
 * from rules it supplies, so a new version is a change in the client, never here.
 */
package dev.px.navigation;
