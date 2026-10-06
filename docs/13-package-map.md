## 13. Package map

| Package | What it is |
|---|---|
| `event` / `event.bus` / `event.impl` | Bases, `@Subscribe`, the bus, built-in events |
| `hook` | `GameHooks` (`Core.hooks()`) — every moment the adapter tells Core about, one method each; counts them, warns when one something needs never fires, and `verify()` for your development build. See §14 |
| `module` | `Module`, `@ModuleInfo`, `Category`, `ModuleRegistry`, `ThreadedModule` (a module whose work runs off the game thread) |
| `setting` / `setting.impl` | Settings, auto-discovery, the nine types |
| `layout` | Geometry and the box model, shared by the HUD and the GUI and depending on neither: `Bounds`, `Size`, `Shape`, `Content`, `Align`, `Draw` |
| `hud` | HUD placement and the edit-mode model: `HudElement`, `Anchor`, `HudLayout`, `Placement`, `HudService`, `HudRenderer`, `HudEditor`, `HudEditorView` |
| `gui` / `gui.setting` / `gui.click` | The click GUI and the pieces it is built from: `Component`, `Panel`, `Screen`, `GuiService`, `GuiStyle`, the nine `SettingRenderer`s, and `ClickGuiScreen` |
| `registry` | Generic `Registry<T>` — one class replacing four hand-written managers |
| `service` | `Service` + `ServiceContainer`: subsystems declare `dependsOn()`, Core orders startup and shuts down in reverse |
| `config` | `ConfigService`, `ConfigSection`, `ConfigLocation`, `ConfigLoadEvent` — JSON profiles, a folder each, one file per section, at the location you choose (per profile or shared, folders allowed). Atomic saves, unreadable files kept aside. See §1 |
| `config.section` | Ready-made sections: `SettingsSection` (one `SettingHolder`) and `ToggleableSection` (a registry of modules or elements, by name) |
| `config.crypto` | Optional encryption: `ConfigCipher`, the interface you implement, and `AesGcmCipher`, AES-GCM from the JDK with passphrase key derivation |
| `config.io` | `Json` — the shared Gson instance, atomic file writes, and `child(...)` for reading nested objects |
| `command` | `Command`, `@CommandInfo`, typed `CommandContext`, chat dispatch |
| `input` | `Key` / `MouseButton` / `Modifier` / `Bind` — Core's own enums, not LWJGL ints, so a bind saved on 1.8.9 loads on 1.21. `InputService` routes presses to module binds |
| `render` | `Render` facade, `Render2D` / `Render3D` SPIs, `Color`, `Texture` |
| `render.font` | `Font` measuring + `FontProvider` (your backend loads the TTF) |
| `render.theme` | `Theme` = two accent colours plus derived roles; `ThemeService` holds radius / opacity / text colour |
| `render.animation` | `Easing` (22 curves) and time-based `Animation` |
| `shader` | `ShaderService` — registers, assembles, compiles once, caches and unbinds. `ShaderSource` (where the GLSL is), `ShaderLoader` (how it is read), `ShaderPreprocessor` (`#include` / `#version` / `#define`), `Uniforms` + `UniformSink` (values, without GL), `ShaderBackend` (the one class you write). See §9 |
| `movement` | `MovementCorrection` — re-bases the movement keys onto an applied rotation so the player still walks where they meant to. Static, no seam. See §10 |
| `movement.rotation` | `RotationService` — priority arbitration for the player's rotation, with claims that expire rather than needing release. `RotationRequest`, `RotationPriority`, `RotationMode`, and the two-method `RotationSink` your adapter writes |
| `movement.simulation` | `SimulationService` — `Simulation` (the real movement rules, axis-separated collision, step-up) over a one-method `CollisionSpace`, plus `DriftMonitor` (scores the model against the game every tick and says when to stop trusting it), `PhysicsCalibration` (offline: measure the gap, sweep a constant to close it) and the `MotionState` / `MovementInput` values they pass around |
| `movement.prediction` | `PredictionService` — where somebody else will be, likely and possible: `MotionEstimate` (the keys that best explain how they moved), `EntityPhysics` (your client's word on each entity's effects, attributes and pose), `Behaviour` (how they really move, learned: speed, drops, how much the rules explain), `Scenario` (one way the future could go, with an optional prior), and `Prediction` / `Future` (each scenario played out with collision and weighed, plus `earliestPossible`). Reads `Tracked` history |
| `movement.prediction.record` | `MovementRecorder` — real movement kept per entity, with the rules and the blocks around it; `MovementJson` (JSON Lines); `MovementReplay` → `ReplayReport` (predictions scored against what really happened) |
| `movement.timeline` | `TimelineRecorder` — records packets, ticks and the player's movement between two points in time, in one exactly-ordered `Timeline` of `TimelineEntry`s, with queries for links, replies and correlation. Reads packets through the describer on `Core.network()`; `TimelineJson` reads and writes JSON Lines |
| `network` | `NetworkService` — where the adapter's packets come in: holds the one `PacketDescriber`, picks each packet's arrival, describes it once and hands it to every `PacketListener`. See §11 |
| `network.packet` | The packet vocabulary everything above shares: `PacketDescriber` (the one-method SPI your adapter writes), `PacketDescription` with the roles the services read (`withWorldAge`, `withTransaction`, `withLatency`, `withBrand`, `withChannels`, `asCorrection`), `PacketKind` (the interface your own kinds implement), `PacketFields`, and `ClassPacketDescriber` (a describer built from one rule per class) |
| `network.tps` | `TpsService` over `TpsTracker` — the server's tick rate from its world clock, measured with no rate assumed, smoothed, stall-aware, capped by a target only if you set one |
| `network.lag` | `LagService` — ping, packet rates, and `LagSpikeEvent` when the server goes silent |
| `network.server` | `ServerService` — address, `ServerBrand` (raw brand, software and proxy), the `ServerSoftware` you register, channels, `ServerChangeEvent` |
| `network.anticheat` | `AntiCheatService` — ranks `Detection`s from the `AntiCheatSignature`s you register (none ship) over `ServerEvidence`, including the `TransactionPattern` a `TransactionTracker` reads off packets given `withTransaction` |
| `entity` | `EntityService` — reads the world once a tick through the adapter's one `EntitySource` and routes each entity by class to the `EntityTracker<E>`s you register, typed by the game's own classes. Each tracker holds a `Tracked<E>` per entity (the game's object plus position, box, velocity, ticks tracked) and a `SpatialGrid` built only when queried. See §12 |
| `target` | `TargetService` — runs a `TargetSelector<E>` over one tracker (filters built once, run cheapest first, predicates on the game's own object) ranked by a `TargetSort`, from the player's eyes or any point; `TargetLock` holds a choice across ticks |
| `world` | The world as Core's geometry asks about it, answered by your adapter: `BlockView` + `BlockShape` (what a line through a cell meets, and `without(...)` for a block that is gone), `Rays` (whether a line gets through, over `VoxelRay`), `CellTest` (any yes-or-no question about a cell), `Obstructions` (whether an entity is in a region, from your trackers). Shared by every library built on Core. See §14 |
| `math` | `Vec2`, `Vec3`, `Vec3i` (the block grid), `Direction`, `Box`, `Range`, `MathUtil`, `Stopwatch` (cooldowns) |
| `util` | `Validate`, `Reflect`, `CoreLogger` / `ConsoleLogger` |
| `util.collect` | `Pair`, `Triplet`, `CircularQueue` / `CircularDeque` (bounded histories that never grow), `RollingAverage` (allocation-free smoothing for FPS / ping / CPS), `LruCache` / `ExpiringCache` (bounded by size and by age), `Trie` (prefix completion), `WeightedList` |
| `util.math` | `MovementMath` (input to motion, BPS, fall prediction), `RotationMath` (look vectors, angular distance, stepped turning, mouse-sensitivity snapping), `PhysicsProfile` (gravity, drag, friction and walk speed as a replaceable value rather than constants), `Curves` (Bézier and Catmull-Rom), `Statistics` |
| `util.time` | `TickTimer` (server ticks, not wall clock), `RateMeter` (events per second over a sliding window, allocation-free), `Profiler` (which part of the frame is slow) |
| `util.spatial` | Algorithms over 3D space that never learn what a block is: `AStar` + `PathSpace` (the two-method SPI your adapter implements) + `Path`, `VoxelRay` (Amanatides–Woo grid traversal), `FloodFill` (enclosure and connected regions), `SpatialGrid` (range queries without scanning everything) |
| `util.render` | `ColorUtil` (rainbow, pulse, HSB the other way, health colours, RGBA packing), `Gradient` (multi-stop ramp) |
| `util.text` | `TextUtil` (labels, durations, byte counts, roman numerals, "did you mean"), `ChatColor` (the section-sign codes) |
| `util.net` | `Http` — blocking one-shot GET/POST for update checks and small APIs. Run it on `ThreadService` |
| `notification` | On-screen toast queue. Core owns the lifecycle; you draw them |
| `concurrent` | `ThreadService` — three tiers (one-shot workers, timers, dedicated loop threads) plus the game-thread queue; `TaskHandle` for cancelling a loop. Task exceptions are logged, not swallowed. See §8 |
| `social` | Friends list, for your trackers' `accepts`, nametags and chat to consult |
| `account` | Alt manager. `AuthProvider` is the SPI you implement for Microsoft login |
| `integration` | Optional external hooks: **Discord Rich Presence** (`PresenceProvider`) and **now-playing / Spotify** (`MediaProvider`). Both polled off-thread; absent providers are simply inert |
| `platform` | The narrow game seam: data directory, screen size, chat, username, in-game flag. **Not** the adapter layer — no player, world, entity, or packets |

`math` and `render` hold the value types Core is built out of — `Vec3`, `Vec3i`,
`Box`, `Color`. `util.math` and `util.render` hold helpers built *on* those types,
which is why they are one level down: nothing in `util` is part of Core's
vocabulary, and deleting any of it would not stop Core from booting.

`util.spatial` is where that separation earns its keep. A pathfinder, a raycast
and a flood fill all need to ask the world questions, and none of them needs to
know the answers come from a world: `PathSpace` is two methods, `VoxelRay` and
`FloodFill` take a predicate. Your adapter supplies those, the algorithms stay
here, and the whole package is tested against mazes written as string literals.
