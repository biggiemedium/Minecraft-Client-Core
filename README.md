# Core

The version-independent half of a Minecraft utility client: event bus, modules,
settings, config, commands, input, services, a pluggable render facade, and a
HUD layout engine with an edit mode.

Core has **no Minecraft on its classpath** — this project compiles standalone,
which proves there is no `net.minecraft` import hiding in it. Copy
`src/main/java/dev/px/core` into a project for any version and start writing
modules and HUD elements.

**Requires:** Java 8, Lombok (compile-time only), Gson (already ships with
Minecraft). On ForgeGradle 2.x (1.8.9), which predates the `annotationProcessor`
configuration, put Lombok on `compile` — the processor is found on the classpath.

---

## 1. Bootstrap

```java
Core core = Core.builder("LeapFrog", "2.0")
        .platform(new ForgePlatform())      // the only required piece
        .logger(new Log4jLogger(LOGGER))    // optional, defaults to console
        .build();

core.getCategories().registerAll(Categories.class);
core.getThemeService().register(Theme.of("Froggy", Color.rgb(0xADF773), Color.rgb(0x80F393)));
core.getModuleRegistry().registerAll(new KillAura(), new Sprint(), new Fullbright());
core.getCommandRegistry().registerAll(new ToggleCommand());
core.getHudService().registerAll(new WatermarkElement(), new ClockElement());

Render.install(new NanoVGRender2D());       // your 2D library
Render.install(new LegacyRender3D());       // world-space drawing

core.start();
core.installShutdownHook();                 // saves config on exit
```

Register **between** `build()` and `start()` — categories must exist before
modules resolve against them. `start()` orders service startup itself, applies
module defaults, then loads the saved config over the top.

Afterwards everything is reachable statically:

```java
Core.modules().get(KillAura.class);
Core.notifications().success("Config", "Saved");
Core.themes().getPrimary();
Core.bus().post(new PlayerMoveEvent(x, y, z));
```

Categories are an interface so Core does not dictate yours:

```java
public enum Categories implements Category {
    COMBAT("Combat"), MOVEMENT("Movement"), RENDER("Render");

    private final String name;
    Categories(String name) { this.name = name; }
    public String getName() { return name; }
}
```

---

## 2. A module

```java
@ModuleInfo(name = "Kill Aura", description = "Attacks nearby targets", category = "Combat")
public final class KillAura extends Module {

    // Declaring the field registers the setting. There is no create(...) wrapper.
    private final NumberSetting<Float> reach  = number("Reach", 4f, 3f, 6f).step(0.1f);
    private final RangeSetting         cps    = range("CPS", 8, 14, 1, 20);
    private final BooleanSetting       block  = bool("Auto Block", true);
    private final EnumSetting<Mode>    mode   = enumOf("Block Mode", Mode.VANILLA)
                                                    .visibleWhen(block);        // hides when off
    private final MultiEnumSetting<Target> targets =
            multi("Targets", Target.class, Target.PLAYERS, Target.MOBS);        // checkbox list
    private final ColorSetting         hitbox = color("Hitbox", Color.RED);

    private final Stopwatch attackTimer = Stopwatch.expired();
    private Entity target;

    @Subscribe(stage = Stage.PRE, priority = Priority.HIGH)
    private void onMotion(PlayerMotionEvent event) {
        if (target == null) return;
        event.setYaw(rotationTo(target).getYaw());

        if (attackTimer.tryConsume(1000 / cps.randomInt())) {    // randomised delay
            attack(target);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (target != null) {
            Render.boxOutline(boxOf(target), 1.5f, hitbox.resolve());
        }
    }

    @Override protected void onDisable() { target = null; }

    /** Extra text shown after the name in the ArrayList. */
    @Override public String getDisplayInfo() { return mode.displayValue(); }

    public enum Mode { VANILLA, WATCHDOG, NCP }
    public enum Target { PLAYERS, MOBS, ANIMALS, INVISIBLES }
}
```

Reading settings at the use site:

```java
if (block.isOn() && mode.is(Mode.WATCHDOG)) { ... }
if (targets.has(Target.PLAYERS)) { ... }
float r = reach.getFloat();
```

Three things are automatic:

- **Settings register by being declared** — discovered after construction, so
  nothing is missed.
- **Handlers are annotated methods** — the parameter type *is* the event, no
  type token to keep in sync.
- **Subscription is permanent** — a module subscribes once at registration and
  its handlers go quiet while disabled. Overriding `onEnable` without calling
  `super` can no longer silently break event delivery.

Leave `category` off `@ModuleInfo` and it is inferred from the package name.

> On Java 9+ (1.17 and newer), import `dev.px.core.module.Module` explicitly —
> a wildcard import collides with `java.lang.Module`. Not an issue on Java 8.

### Setting types

| Factory | Type | Renders as |
|---|---|---|
| `bool(name, def)` | `BooleanSetting` | checkbox |
| `number/integer/decimal(name, def, min, max)` | `NumberSetting<N>` | slider |
| `range(name, lo, hi, min, max)` | `RangeSetting` | two-handled slider |
| `enumOf(name, def)` | `EnumSetting<E>` | dropdown |
| `multi(name, Type.class, defaults...)` | `MultiEnumSetting<E>` | checkbox list |
| `text(name, def)` | `StringSetting` | text field |
| `color(name, def)` | `ColorSetting` | picker (+ rainbow / theme-sync) |
| `bind(name, Key.X)` | `BindSetting` | keybind button |
| `group(name)` | `GroupSetting` | collapsible section |

All chain `.describe(...)`, `.visibleWhen(...)`, `.onChange(...)`.

---

## 3. An event

```java
@Getter @AllArgsConstructor
public final class PlayerMoveEvent extends CancellableEvent {
    private double x, y, z;
}
```

| Base | Use when |
|---|---|
| `Event` | pure notification |
| `CancellableEvent` | handlers may suppress the action |
| `StagedEvent` | the same call site fires before *and* after the action |

Posting from a mixin — `post` returns the event, so you can check it inline:

```java
@Inject(method = "jump", at = @At("HEAD"), cancellable = true)
private void onJump(CallbackInfo ci) {
    if (Core.bus().post(new PlayerJumpEvent(motionY)).isCancelled()) {
        ci.cancel();
    }
}
```

A handler may declare a supertype and receive every subclass, so one
`@Subscribe(PacketEvent)` covers both send and receive.

Core already ships version-agnostic events in `event.impl`: `TickEvent`,
`Render2DEvent`, `Render3DEvent`, `KeyEvent`, `MouseEvent`, `ScrollEvent`,
`CharTypedEvent`, `ChatSendEvent`, `ChatReceiveEvent`, `WorldEvent`,
`ScreenEvent`, `ClientLifecycleEvent`. Game-specific ones (packets, entities)
belong in your adapter.

---

## 4. A command

```java
@CommandInfo(name = "toggle", aliases = {"t"}, usage = "toggle <module>")
public final class ToggleCommand extends Command {

    @Override
    public void execute(CommandContext ctx) {
        Module module = Core.modules().find(ctx.rest(0))
                .orElseThrow(() -> new CommandException("No such module"));
        module.toggle();
        ctx.reply(module.getName() + (module.isEnabled() ? " enabled" : " disabled"));
    }
}
```

`ctx.get(i)` / `getInt(i)` / `getBoolean(i)` / `rest(i)` validate for you and
throw `CommandException`, which the registry turns into a chat message — so the
happy path needs no error plumbing. The prefix (default `.`) is a persisted
setting; matching chat lines are cancelled before reaching the server.

---

## 5. Rendering

One facade, two swappable backends. `Render2D` is the pluggable one (NanoVG,
Skija, or legacy GL). `Render3D` is separate because a 2D vector library has no
camera or world space — it comes from your version adapter.

```java
Render.roundRect(x, y, w, h, 4, Core.themes().getSurface());
Render.text("Kill Aura", x + 4, y + 3, Color.WHITE);
Render.progressBar(x, y, w, 2, 1, health, track, fill);

Render.boxOutline(hitbox, 1.5f, Color.RED);
Render.sphere(center, 3f, Color.CYAN.withAlpha(80));
Vec2 screen = Render.worldToScreen(pos);        // null when behind the camera

// Paired push/pop also come in body-taking forms that cannot be unbalanced:
Render.clipped(x, y, w, h, () -> drawScrollingContent());
```

Before a backend is installed, every call is a silent no-op — a module or HUD
element that draws during early startup is harmless. Text is the same: with no
font installed, draws are dropped and `Render.textWidth(...)` returns 0, so an
element can size itself from text before fonts load. `Render.hasFont()` reports
it, and `FontService` warns once if no provider was installed at all.

Animations are time-based, so a fade takes the same wall-clock time at 30 and
240 FPS:

```java
private final Animation hover = Animation.fade(150, Easing.QUAD_OUT);

hover.target(isHovered);                        // call every frame; cheap
Render.rect(x, y, w, h, base.lerp(accent, hover.get()));
```

---

## 6. HUD elements

An element answers two questions — **how big am I** and **how do I draw myself**.
Anchoring, scaling, clamping, z-order, hit testing, dragging and persistence are
all handled above it. That is the whole contract, and it is deliberately the
whole contract: adding an element type must never mean reading the layout engine.

### The minimum

No settings, no shape override — implement the interface directly:

```java
public final class WatermarkElement implements HudElement {

    @Override public String getId() { return "watermark"; }

    @Override public Size getPreferredSize() {
        return Size.of(Render.textWidth("Core") + 8f, Render.textHeight() + 6f);
    }

    @Override public void render(float x, float y, float w, float h) {
        Render.roundRect(x, y, w, h, 3f, Color.of(0, 0, 0, 120));
        Render.text("Core", x + 4f, y + 3f, Color.WHITE);
    }
}
```

### With settings and a real shape

Extend `AbstractHudElement` and settings register by declaration, exactly as on a
module — persisted alongside the layout:

```java
public final class ClockElement extends AbstractHudElement {

    private static final float PADDING = 4f;
    private static final float RADIUS  = 3f;

    private final BooleanSetting seconds = bool("Show Seconds", true);
    private final ColorSetting   colour  = color("Colour", Color.WHITE);

    public ClockElement() {
        super("clock", "Clock", HudLayout.at(Anchor.TOP_RIGHT, -4f, 4f));
    }

    @Override public Size getPreferredSize() {
        return Size.of(Render.textWidth(text()) + PADDING * 2f,
                       Render.textHeight() + PADDING * 2f);
    }

    @Override public void render(float x, float y, float w, float h) {
        Render.roundRect(x, y, w, h, RADIUS, Core.themes().getSurface());
        Render.text(text(), x + PADDING, y + PADDING, colour.resolve());
    }

    /** Drawn rounded, so the hit region is rounded: the corner should miss. */
    @Override public Shape getShape(float x, float y, float w, float h) {
        return Shape.roundRect(x, y, w, h, RADIUS);
    }

    private String text() {
        // A frozen sample while positioning, so the box does not resize under
        // the cursor as the real clock advances.
        if (isEditing()) return seconds.isOn() ? "12:00:00" : "12:00";
        return LocalTime.now().format(seconds.isOn() ? LONG : SHORT);
    }
}
```

`isEditing()` is the edit-mode hook: branch at the point the data comes from, and
everything else stays identical.

```java
Core.hud().registerAll(new WatermarkElement(), new ClockElement(), new DialElement());
```

**Preferred size may change every frame** — a clock is wider with seconds on, an
ArrayList grows as modules toggle. That is the normal case, not an edge case.
Nothing caches it.

### Position is an anchor plus an offset

Never absolute coordinates. `x = ax * screenW + offsetX - ax * width`, where `ax`
is 0, 0.5 or 1. That one formula gives you, with no special-casing anywhere:

| | |
|---|---|
| Resolution changes | a bottom-right element stays bottom-right, at any size |
| Growth direction | right-anchored grows left, bottom grows up, centre grows both ways |
| Edge gaps | an offset means "distance from my anchor", which is what users mean |

```java
HudLayout layout = Core.hud().layoutOf("clock");
layout.setAnchor(Anchor.BOTTOM_RIGHT);   // or Core.hud().setAnchor(id, anchor)
layout.setOffsetX(-4f);                  // 4px in from the right edge
layout.setScale(1.5f);
```

`Core.hud().setAnchor(id, anchor)` re-anchors **without moving the element** — it
recomputes the offset, so only the resize behaviour changes.

Bounds are clamped so at least 8px stays on screen; an element smaller than that
stays fully visible. Clamping never writes back to the layout, so briefly
shrinking the window does not permanently move someone's HUD.

### Three geometries, kept separate

| Geometry | What it is | Type |
|---|---|---|
| Layout | position and occupied space | `Bounds` |
| Visual | what actually gets drawn | the element's `render()` |
| Interaction | what responds to clicks | `Shape` |

Simple elements make all three identical — `getShape()` returns a rectangle by
default. Override it when they differ, and a circular element stops having a
rectangular hitbox:

```java
// A dial: its layout box is square, but only the disc is clickable.
@Override public Shape getShape(float x, float y, float w, float h) {
    return Shape.circle(x + w / 2f, y + h / 2f, w / 2f);
}

// A radar cone: the empty space either side of the triangle is not clickable.
@Override public Shape getShape(float x, float y, float w, float h) {
    return Shape.polygon(Vec2.of(x + w / 2f, y),
                         Vec2.of(x, y + h),
                         Vec2.of(x + w, y + h));
}
```

`Shape.rect`, `roundRect`, `circle` and `polygon` are built in; polygons may be
concave, since containment is a ray cast rather than a convex test.

**A `Shape` strokes itself.** That is what lets the editor outline a circle or a
polygon correctly without ever switching on shape type — so a new shape works in
the editor the day it is written.

Everything above is in **natural, unscaled** coordinates. An element's scale is
applied as a transform around it, so an element that never mentions scale is
automatically scalable.

### Edit mode

`HudEditor` subscribes to Core's `MouseEvent` / `KeyEvent` / `ScrollEvent` at
`Priority.HIGHEST` and cancels every one while open. That cancellation **is** how
input is swallowed: with the events consumed first, nothing behind the editor can
fire. Your adapter only has to post the events its screen already receives.

```java
// In the adapter's editor screen
public final class HudEditorScreen extends GuiScreen {

    @Override protected void mouseClicked(int x, int y, int button) {
        Core.bus().post(new MouseEvent(MouseButton.byIndex(button), mods(), true, x, y));
    }

    @Override protected void mouseReleased(int x, int y, int button) {
        Core.bus().post(new MouseEvent(MouseButton.byIndex(button), mods(), false, x, y));
    }

    @Override protected void keyTyped(char typed, int code) {
        Core.bus().post(new KeyEvent(Keys.fromLwjgl(code), mods(), true));
    }

    @Override public void onGuiClosed() { Core.hud().closeEditor(); }
}
```

Dragging follows `Platform.getMouseX()` each frame rather than a move event, so
there is no mouse-move event to bridge.

| Action | Mouse / key | Method |
|---|---|---|
| Select | click | `editor.select(id)` |
| Move | drag | `editor.nudgeSelected(dx, dy)` |
| Scale | scroll, or drag a corner handle | `editor.scaleSelected(delta)` |
| Lock | `L` | `editor.toggleLockSelected()` |
| Hide | `H`, or right-click | `editor.toggleHiddenSelected()` |
| Z-order | `PageUp` / `PageDown` | `editor.bringSelectedToFront()` |
| Reset | `R` | `editor.resetSelected()` / `Core.hud().resetAll()` |
| Deselect, then close | `Escape` | `Core.hud().closeEditor()` |

Snapping to screen edges and centre draws alignment guides and is suspended while
`ALT` is held. A locked element is still selectable, so it can be unlocked; a
hidden one still shows faintly in the editor, so it can be brought back. Corner
handles sit outside the element, and flip inside when an element is anchored into
a screen corner and there would be no room.

> The editor cancels **every** key, including whatever bind opened it. `Escape`
> is therefore the way out: it clears the selection if there is one, and closes
> the editor otherwise.

---

## 7. Package map

| Package | What it is |
|---|---|
| `event` / `event.bus` / `event.impl` | Bases, `@Subscribe`, the bus, built-in events |
| `module` | `Module`, `@ModuleInfo`, `Category`, `ModuleRegistry`, `ThreadedModule` (a module whose work runs off the game thread) |
| `setting` / `setting.impl` | Settings, auto-discovery, the nine types |
| `hud` | HUD layout and edit mode: `HudElement`, `Anchor`, `Shape`, `Bounds`, `HudLayout`, `HudService`, `HudEditor` |
| `registry` | Generic `Registry<T>` — one class replacing four hand-written managers |
| `service` | `Service` + `ServiceContainer`: subsystems declare `dependsOn()`, Core orders startup and shuts down in reverse |
| `config` | JSON profiles. A `ConfigSection` is one block of the file; add your own to persist anything |
| `command` | `Command`, `@CommandInfo`, typed `CommandContext`, chat dispatch |
| `input` | `Key` / `MouseButton` / `Modifier` / `Bind` — Core's own enums, not LWJGL ints, so a bind saved on 1.8.9 loads on 1.21. `InputService` routes presses to module binds |
| `render` | `Render` facade, `Render2D` / `Render3D` SPIs, `Color`, `Texture` |
| `render.font` | `Font` measuring + `FontProvider` (your backend loads the TTF) |
| `render.theme` | `Theme` = two accent colours plus derived roles; `ThemeService` holds radius / opacity / text colour |
| `render.animation` | `Easing` (22 curves) and time-based `Animation` |
| `math` | `Vec2`, `Vec3`, `Vec3i` (the block grid), `Direction`, `Box`, `Range`, `MathUtil`, `Stopwatch` (cooldowns) |
| `util` | `Validate`, `Reflect`, `CoreLogger` / `ConsoleLogger` |
| `util.collect` | `Pair`, `Triplet`, `CircularQueue` / `CircularDeque` (bounded histories that never grow), `RollingAverage` (allocation-free smoothing for FPS / ping / CPS), `LruCache` / `ExpiringCache` (bounded by size and by age), `Trie` (prefix completion), `WeightedList` |
| `util.math` | `MovementMath` (input to motion, BPS, fall prediction), `RotationMath` (look vectors, angular distance, stepped turning, mouse-sensitivity snapping), `Curves` (Bézier and Catmull-Rom), `Statistics` |
| `util.time` | `TickTimer` (server ticks, not wall clock), `Profiler` (which part of the frame is slow) |
| `util.spatial` | Algorithms over 3D space that never learn what a block is: `AStar` + `PathSpace` (the two-method SPI your adapter implements) + `Path`, `VoxelRay` (Amanatides–Woo grid traversal), `FloodFill` (enclosure and connected regions), `SpatialGrid` (range queries without scanning everything) |
| `util.render` | `ColorUtil` (rainbow, pulse, HSB the other way, health colours, RGBA packing), `Gradient` (multi-stop ramp) |
| `util.text` | `TextUtil` (labels, durations, byte counts, roman numerals, "did you mean"), `ChatColor` (the section-sign codes) |
| `util.net` | `Http` — blocking one-shot GET/POST for update checks and small APIs. Run it on `ThreadService` |
| `notification` | On-screen toast queue. Core owns the lifecycle; you draw them |
| `concurrent` | `ThreadService` — named daemon pool; task exceptions are logged, not swallowed |
| `social` | Friends list, consulted by targeting / nametags / chat |
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

---

## 8. What your adapter must supply

1. **`Platform`** — data dir, screen metrics, cursor, chat, username, in-game flag
2. **`Render2D`** — your 2D library
3. **`Render3D`** — world drawing and projection
4. **`FontProvider`** — font loading for that backend
5. **Event bridging** — post Core's events from your mixins. For the HUD editor
   that means `MouseEvent`, `KeyEvent` and `ScrollEvent` from your editor screen;
   `Platform.getMouseX()` covers dragging, so there is no move event to bridge.
6. **A screen for the editor** — a bare `GuiScreen` that posts input and calls
   `Core.hud().closeEditor()` when dismissed. Core draws the HUD and the editor
   overlay itself from `Render2DEvent`.

Optional: `AuthProvider` (alt manager), `PresenceProvider` / `MediaProvider`, and
`PathSpace` if you use `util.spatial` — one lambda saying which cells your agent
can occupy is enough to run `AStar` against your world.

Core runs headless without any of these. The test suite boots it with none
installed, which is how the seam stays honest.

---

## 9. Verifying

`dev.px.core.test.CoreSmokeTest` runs **676 checks** in a plain JVM — no
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
| `ConfigTests` | full round-trip, profiles, second-load regression, path sanitising |

The `example/` package is written to be read: it is what a real client's modules,
commands and HUD elements look like, and `TestClient.boot()` is the canonical
bootstrap minus the render backends.

---

## Not yet included

- **GUI framework** — screens, component tree, widgets. The HUD editor has its
  own input handling and does not depend on it.
- **Adapter layer** — `Player`, `World`, `Entity`, packet wrappers.
