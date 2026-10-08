# Minecraft-Client-Core

A version-independent library for Minecraft utility clients: `core/` plus optional
add-on modules (`combat/`, `projectile/`, `gui/`, ...). It has no Minecraft on its classpath;
client developers write the adapter that connects it to a game version.

## The rules that matter most

1. **No game values or closed game vocabularies in the library.** No numbers taken
   from Minecraft (reach 3.0, gravity 0.08, 20 TPS, a 0.6×1.8 box), no enums of
   game concepts (`CRYSTAL`, `HOSTILE`, `ARROW`). The developer supplies them as
   rules, parameters or their own enums implementing a library interface. Before
   adding a constant, ask: "is this a fact about Minecraft?" If so, it is the
   developer's. Tuning knobs for the library's own algorithms (a grid size, a
   default horizon) are fine, named as such and overridable.
   `PhysicsProfile.vanilla()` is the one adjustable default, every value citing the wiki.
2. **The developer keeps control; the library does the heavy lifting.** Searches
   take settings as suppliers read live (`IntSupplier`, `BooleanSupplier`), accept
   developer filters and rankings, and say why anything was left out (a stats
   object). One clear way to do a thing beats several.
3. **Outputs are positions, clicks, rotations and timing.** The library never
   manages inventory: no item switching, hotbar, offhand or armour choice. Where
   an item changes the logic, expose a setting; never pick the item.
4. **Nothing anticheat-specific.** No code written for one anticheat. Give
   developers presets they can use on strict servers (e.g. `PlacementStyle.strict`),
   built from general rules.
5. **The library owns geometry, not pixels.** It computes positions, layout, hit
   tests and state; the client chooses the look. Headless models (e.g. `HudEditor`)
   draw nothing and listen to nothing: they never call `Render`, pick colours or
   subscribe to the bus, and the host routes input in through action methods. The
   `gui` module follows the same rule — widgets are headless, the client draws
   each widget type through a renderer it registers, and no default look ships.
   The old click GUI in `gui`'s `legacy` package still draws itself; it is
   temporary and is removed once clients no longer need it.
6. **The adapter belongs to the client developer.** Core never throws on its own
   over a missing adapter piece; it warns, loses the feature, and offers
   `verify()` for a development build.

## Modules

- Every module except `core` is optional. Each depends only on core
  (`api project(':core')`, `testImplementation testFixtures(project(':core'))`),
  never on another add-on, and core never imports a module.
- Each module has its own package (`dev.px.<module>`).
- When two modules need the same abstraction, move it into core (as `core.world`
  was moved out of combat).
- All modules release together at one version.
- Core has no GUI. `gui/` is the only GUI system: read `gui/README.md` (and
  `gui/EXAMPLE.md`, a click GUI built on it) before working on it.
  New screens are plain objects (`new Screen(look, root)`, driven by the host);
  the legacy click GUI is wired with `dev.px.gui.legacy.GuiService.install(core)`.
  There is no `Core.gui()`.
- `navigation/` is in design, with no code yet: read `navigation/PLAN.md` before
  working on it or on the flow engine, controls arbitration or shared memory it
  plans for core, and keep it current as decisions are made.

## Where game facts come from

- The source is [minecraft.wiki](https://minecraft.wiki). Read a page's raw
  wikitext with `curl -sL "https://minecraft.wiki/w/<Page>?action=raw"`; the
  rendered page loses tables and detail.
- Cite the page in Javadoc and READMEs wherever a value or behaviour is described.
  Note version changes the wiki records (e.g. "changed in 1.21.2").
- Facts the wiki does not give become rules the developer supplies; say so in
  the docs instead of guessing.

## Code conventions

- Java 8 source and target: no `var`, records or newer APIs. Lombok where the
  surrounding code uses it (`@Getter` on value types).
- Match the surrounding code: naming, comment density, Javadoc voice (plain
  sentences, a `<pre>{@code ...}</pre>` usage example at the top of public classes).
- Builders: required parts checked in `build()`, which throws
  `IllegalStateException` naming every missing part (`"a TrapSearch needs: planner targets"`).
  Arguments are checked with `Validate.notNull` / `Validate.check`.
- Values are immutable; say so in the class doc. Say "Game thread only." where it applies.
- Packages are by domain, roughly 5–12 classes; add a sub-package only when one
  grows past that. No `package-info.java` except at a module root and in `core.util`.
- Plain objects the developer builds over global state; a `Service` only when it
  truly runs every tick.

## Build and test

- Build with JDK 20 (the default JDK breaks Lombok 1.18.30):
  ```
  sh gradlew compileJava compileTestJava -q \
    -Dorg.gradle.java.home=/Users/jameskemp/Library/Java/JavaVirtualMachines/temurin-20.0.2/Contents/Home
  ```
- Tests are a hand-rolled harness, not JUnit. Each module has a smoke test main
  (`CoreSmokeTest`, `CombatSmokeTest`, `ProjectileSmokeTest`, `GuiSmokeTest`) that runs suites built
  from `Checks`. Run one with Gson on the classpath by hand (it is `compileOnly`):
  ```
  GSON=$(find ~/.gradle -name 'gson-2.8.9.jar' | head -1)
  java -cp <module>/build/classes/java/main:<module>/build/classes/java/test:core/build/classes/java/main:core/build/classes/java/testFixtures:$GSON \
       dev.px.<module>.test.<Module>SmokeTest
  ```
  Core's own run uses `core/build/classes/java/test` in place of the module's.
- Every feature gets a suite (or a section in one), registered in its module's
  smoke test. Check messages are sentences describing the behaviour, with the
  values or stats that explain a failure in parentheses.
- Shared test fixtures (`Checks`, `MovementRig`, `GridCollisionSpace`, `FixedFont`,
  `ExampleCategories`, ...) live in `core/src/testFixtures`.
- Mutation-check new logic: break a line on purpose, run the suite, confirm a
  check fails, restore. A mutation nothing catches means a missing test (or an
  equivalent mutation — say which).
- Report results faithfully: the check count, failures with their output.

## Docs to keep current with every change

- Core: `docs/NN-*.md` — especially `13-package-map.md`,
  `14-what-your-adapter-must-supply.md` and `15-verifying.md` (suite table and
  check count).
- Each module's `README.md`: its section for the feature, the package map, the
  verifying table and check count, and "Not yet included".
- The root `README.md` module table.
- `gui/EXAMPLE.md` when an API it uses changes: its code must still compile.

## Working with James

- Design decisions are his. Plan first, give a recommendation, and ask when a
  choice changes the API or the rules above; otherwise pick the sensible default
  and say so.
- Don't commit unless asked.
