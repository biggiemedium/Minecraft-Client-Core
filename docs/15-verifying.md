## 15. Verifying

`dev.px.core.test.CoreSmokeTest` runs **1647 checks** in a plain JVM — no
Minecraft, no window, no GL context, no render backend, no font. If a check ever
needs a game to pass, the abstraction has leaked.

```
src/test/java/dev/px/core/test/
├── CoreSmokeTest.java     runs every suite
├── harness/               Checks, FakePlatform, TestClient (also the bootstrap example)
├── example/               reference modules, commands and HUD elements
└── suite/                 one file per subsystem
```

| Suite | Covers |
|---|---|
| `RegistryTests` | lookup, duplicate rejection, hooks, `clear()` |
| `ShapeTests` | containment and scaling for rect / round-rect / circle / concave polygon |
| `MathTests` | grid positions and packing, directions, curves, statistics |
| `UtilTests` | ring buffers and eviction, LRU and TTL caches, prefix completion, weighted draws, movement and rotation maths, tick timers, profiling, colour conversion, gradients, text and chat codes |
| `SpatialTests` | range queries against a brute-force scan, voxel traversal order and faces, enclosure detection, A* through hand-drawn mazes |
| `ServiceTests` | dependency ordering, cycles, missing deps, failed startup |
| `EventTests` | priority, stage, cancellation, supertype dispatch, listening gate |
| `SettingTests` | every type: coercion, visibility, change events, JSON round-trip |
| `ModuleTests` | annotation identity, category resolution, toggle lifecycle, keybinds |
| `CommandTests` | dispatch, aliases, typed args, error messages, completion, prefix |
| `HudLayoutTests` | all nine anchors, four resolutions, growth, clamping, z-order |
| `HudEditorTests` | shape-aware selection, drag, snapping, lock, handles, input gate |
| `ShaderTests` | include inlining and include-once, version hoisting, defines, uniform recording for every type, compile-on-first-use, bind/unbind pairing and nesting, a throwing draw, a broken shader contained and logged once, reload, a lost context |
| `MovementTests` | that corrected input travels where the player asked, swept over every facing, key pair and applied rotation: strict rounding never off by more than half a key step and never emitting a value a keyboard could not, exact rounding not off at all |
| `SimulationTests` | walk, sprint, sneak and jump against the figures Minecraft is measured at (not against our own constants); landing on a floor rather than through it, a forty-block-a-tick fall not tunnelling, walls stopping the blocked axis only, half-height ledges stepped onto and full blocks not, ice sliding further, one world query per tick; drift going to zero on a matching world, spiking on a mismatched one and naming the axis, teleports excluded; calibration recovering a planted constant it was never told; and for the tracker: ring wrapping, a missed tick averaged not doubled, eviction |
| `TimelineTests` | exact entry order including packets sent from inside tick handlers, gapless seq and monotonic time with four threads posting packets against ticks, received and applied halves linked by instance and not equality, corrections following whichever applied packets the describer marked as corrections, kinds stored and matched by name, reply finding by time window and by correlation key, marks, filters and capacity, a throwing describer contained and logged once, JSON Lines round-tripping equal, and no subscription at all while idle |
| `NetworkTests` | the test's own packet kinds, server software and signatures, since Core ships none; roles on descriptions and class rules (exact, parent, interface, fallback); TPS measured with nothing assumed — NaN before a measurement, a 60-tick game read as 60 unconfigured, bunched clocks adding up and capped only by a target you set, pulled down by an update overdue against the server's own pace, re-baselined on a world-age leap, an update without an age ignored; each packet heard once whether posted RECEIVED, APPLIED, both, or mixed per packet, cancelled sends dropped and cancelled receives kept, throwing describers and listeners contained and logged once; packet rates, ping and jitter, lag spikes starting, growing, ending with their duration, and ending on leave; addresses with no port assumed and IPv6, domain matching without lookalikes; brands matched by whole word against registered software, proxy and server found independently behind BungeeCord, Waterfall and Velocity, priority by registration, nothing recognised with nothing registered and re-read when software is registered later; a connecting-phase brand held until join, one change event per tick; transaction countdowns through a wrap, no detection without signatures, your rates deciding, anticheat ranking, signatures that throw, the evaluation interval, the declared dependency on the server service; and the timeline reading through the network's describer |
| `TargetingTests` | trackers typed by the test's own entity classes: routing by class, interface and subclass, overlapping trackers reading each entity once and never reading one nobody wants, the local player in none; the game's object back with no cast, and zero rather than any game's numbers when the source leaves something out; one object per entity per tracker with velocity and ticks tracked, untracked on leaving or on no longer being accepted and never recycled, with hooks firing once each way; `track` and `forget` between ticks, including `track` from a hook in the middle of a refresh; throwing sources, `accepts` and hooks contained and logged once; range to the box not the position, and 450 random grid queries against a brute-force scan at two cell sizes; selectors by class built before their tracker exists, filters on the game's object and on measurements, range read live, field of view turning with the player, every sort with missing values last both ways, limits, origins, a query nested in a filter, sticky and greedy locks refusing another tracker's entity, box distance, inset and aim points |
| `HookTests` | every hook posting its event in the right stage and reporting cancellation, chat handing back a rewritten message; counting however an event was posted, cancelled packets included, inbound and outbound apart; listeners named after the class that owns them or registered them, disabled modules and catch-all handlers left out; the self-check silent out of a world, within the grace period, for hooks that fire, hooks nothing needs and hooks that fire only on player input, then warning once with what is idle and the call to make, a late need getting its own grace period, a throwing platform contained; `verify()` listing needed hooks that never fired and `verify(hooks)` exactly the ones named; and the booted client naming Core's own services as what idles without ticks, keys, chat and packets |
| `RotationTests` | priority arbitration, stable tie-breaking across renewals, claims expiring without release, stepped and snapped turns, the short way round 180, that reads and requests commute in any order, easing back on release, both modes, the inert no-sink path |
| `GuiTests` | renderer lookup and replacement, tree structure, visibility gating, hit routing, every setting type edited through the GUI, the input gate, window persistence |
| `ConfigStorageTests` | default and renamed layouts, sections in folders of their own, invalid paths and nested folders refused; `place` winning over registration and moving Core's sections, no two sections sharing a file even by case; profiles switching only their own sections, the last active one remembered across a restart and a deleted one falling back, profile names never leaving the folder; late registration loaded at once; no temporary files left, an unreadable file costing only its own section and copied aside before the save on exit, a never-loaded config never saved over; encryption off unless asked, plaintext gone once encrypted, the right key loading, a wrong one or none leaving the file safe, a file moved to another section's place refused; key derivation, fresh nonces, tamper detection; and the old single-file profiles converted once, shared sections from the active one, encrypted where asked, never overwriting converted files |
| `ConfigTests` | every built-in section round-tripping through a profile folder, friends and accounts shared, a full load restoring shared sections, second-load regression, path sanitising |

The `example/` package is written to be read: it is what a real client's modules,
commands and HUD elements look like, and `TestClient.boot()` is the canonical
bootstrap minus the render backends.
