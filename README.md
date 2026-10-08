# Core

The version-independent half of a Minecraft utility client: event bus, modules,
settings, config, commands, input, services, a pluggable render facade, a HUD
layout engine with an edit-mode model, a shader pipeline that
leaves OpenGL to you, and rotation arbitration so two modules cannot silently
fight over the player's head.

Core has **no Minecraft on its classpath** — this project compiles standalone,
which proves there is no `net.minecraft` import hiding in it. Copy
`core/src/main/java/dev/px/core` into a project for any version and start writing
modules, HUD elements and screens.

The repository holds Core and the optional libraries built on it, each its own
Gradle module and its own jar:

| Module | What it is |
|---|---|
| `core/` | everything in the contents below |
| [`combat/`](combat/README.md) | PvP built on Core: crystal, bed and respawn anchor searches, explosion rules, holes, auto-fill, traps, block placement, a damage monitor, test vectors |
| [`projectile/`](projectile/README.md) | Projectiles built on Core: where an arrow, pearl or potion goes and what it hits, following one already in the air, and aiming at a point or at someone moving — for Trajectories and BowAim modules |
| [`gui/`](gui/README.md) | Screens built on Core's layout and render facade, being designed as headless building blocks for any screen — module GUIs, the HUD editor, title and in-game screens. So far: screens with input, focus, dragging and drag and drop; rows, grids, stacks, scrolling lists, popups, tooltips and wrapped text; buttons, checkboxes, sliders, text fields, dropdowns and windows — all drawn by renderers the client registers, plus the legacy click GUI moved out of Core. See [the example](gui/EXAMPLE.md) |
| [`navigation/`](navigation/PLAN.md) | In design, nothing built yet: getting the player somewhere through any pathfinder (Baritone included) or a precise local planner on Core's movement simulation, plus the flow engine Core will gain for writing automation. See [the plan](navigation/PLAN.md) |

**Requires:** Java 8, Lombok (compile-time only), Gson (already ships with
Minecraft). On ForgeGradle 2.x (1.8.9), which predates the `annotationProcessor`
configuration, put Lombok on `compile` — the processor is found on the classpath.

---

## Contents

1. [Bootstrap](docs/01-bootstrap.md)
2. [A module](docs/02-a-module.md)
3. [An event](docs/03-an-event.md)
4. [A command](docs/04-a-command.md)
5. [Rendering](docs/05-rendering.md)
6. [HUD elements](docs/06-hud-elements.md)
7. [The GUI](docs/07-the-gui.md) (moved to the `gui` module)
8. [Threading](docs/08-threading.md)
9. [Shaders](docs/09-shaders.md)
10. [Movement](docs/10-movement.md)
11. [Network](docs/11-network.md)
12. [Entities and targeting](docs/12-entities-and-targeting.md)
13. [Package map](docs/13-package-map.md)
14. [What your adapter must supply](docs/14-what-your-adapter-must-supply.md)
15. [Verifying](docs/15-verifying.md)

---

## Not yet included

- **Adapter layer** — `Player`, `World`, packet wrappers. (Packets reach Core only as opaque objects in `PacketEvent`, read through the adapter's `PacketDescriber`; entities only as the type parameter of a client's `EntityTracker`, located through the adapter's `EntitySource`.)
