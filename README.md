# Core

The version-independent half of a Minecraft utility client: event bus, modules,
settings, config, commands, input, services, a pluggable render facade, a HUD
layout engine with an edit-mode model, and a click GUI.

Core has **no Minecraft on its classpath** — this project compiles standalone,
which proves there is no `net.minecraft` import hiding in it. Copy
`src/main/java/dev/px/core` into a project for any version and start writing
modules, HUD elements and screens.

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
Core.gui().toggleClickGui();
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

    // Core has no entity type, so the two positions come from your adapter.
    private Vec3 eye;
    private Vec3 target;
    private Vec2 aim = Vec2.rotation(0f, 0f);

    @Subscribe(stage = Stage.PRE, priority = Priority.HIGH)
    private void onTick(TickEvent event) {
        if (target == null || eye.distanceTo(target) > reach.getFloat()) return;

        aim = RotationMath.step(aim, eye.rotationTo(target), 30f);
        applyRotation(aim);                                      // yours

        if (attackTimer.tryConsume(1000 / cps.randomInt())) {    // randomised delay
            attack();                                            // yours
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (target != null) {
            Render.boxOutline(Box.around(target, 0.6d, 1.8d), 1.5f, hitbox.resolve());
        }
    }

    @Override protected void onDisable() { target = null; }

    /** Extra text shown after the name in the ArrayList. */
    @Override public String getDisplayInfo() { return mode.displayValue(); }

    public enum Mode { VANILLA, WATCHDOG, NCP }
    public enum Target { PLAYERS, MOBS, ANIMALS, INVISIBLES }
}
```

Everything above compiles against Core except the two lines marked `// yours`:
finding a target and swinging at one need the game, and Core has no entity type
— see §10. Everything else is real API: `Vec3.rotationTo` solves the rotation,
`RotationMath.step` traces the turn out over several ticks instead of snapping,
and `Box.around` builds the hitbox to outline. `ExampleKillAura` in the test
suite is this module, running headless.

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

An element answers two questions — **who am I** and **what am I made of**. Core
measures what you describe, anchors it, clamps it, and draws it. Anchoring,
scaling, clamping, z-order, hit testing, dragging and persistence are all handled
above the element.

```java
public final class WatermarkElement implements HudElement {

    @Override public String getId() { return "watermark"; }

    @Override public void content(Content c) {
        c.background(Color.of(0, 0, 0, 120), 3f).padding(4f, 3f);
        c.text("Core", Color.WHITE);
    }
}

Core.hud().register(new WatermarkElement());   // that is the whole element
```

That is a complete, working, draggable, scalable, persisted HUD element. Once
you have installed a `Render2D` backend, it draws.

### Describe once, not twice

The oldest annoyance in HUD code is working out your size in one method and
working the same thing out again in another to draw it:

```java
// the old way — two methods that must agree, and eventually won't
Size getPreferredSize()              { return Size.of(textWidth(s) + 8f, 17f); }
void render(float x, float y, ...)   { Render.text(s, x + 4f, y + 4f, WHITE); }
```

Change the padding in one and not the other and your background no longer matches
your text. `content()` replaces both. You describe the box once; **its size is
what the box measures, and its appearance is that same box drawn.** They cannot
disagree, because they are the same description.

Core measures it once per frame during `resolve()` and reuses that measurement
when drawing. Describing something different every frame is the normal case — a
clock is wider at 12:00 than 1:00, an ArrayList grows as modules toggle.

### Boxes and parts

The root box is a **column**. `row()` and `column()` nest another.

```java
c.background(PANEL, 3f).padding(5f).gap(2f);

c.row(r -> {
    r.gap(4f).align(Align.CENTER);
    r.texture(icon, 8f, 8f, Color.WHITE);
    r.text(name, Color.WHITE);
});

c.bar(60f, 3f, 1.5f, health / 20f, Color.of(0,0,0,120), Color.RED);
```

Methods that take content **add a child**; methods that describe the box —
`padding`, `gap`, `background`, `align`, `min` — **configure the box you call
them on**. Everything returns `this`, so both chain.

| Parts | Box |
|---|---|
| `text` `textShadowed` | `padding(all)` / `(x, y)` / `(l, t, r, b)` |
| `rect` `roundRect` | `gap(n)` — between children, not around them |
| `bar` `texture` | `background(color)` / `(color, radius)` |
| `space` | `align(Align.START / CENTER / END)` — cross axis |
| `custom` | `min(w, h)` — a floor on the total size |
| `row` `column` | |

`min()` is what stops an element collapsing to nothing when its content happens
to be empty — a module list with nothing enabled, a text element before a font
has loaded — which would otherwise leave it unclickable in the editor.

### When boxes aren't enough

`custom()` takes a size and a drawing callback and does nothing else. Anything
the layout rules can't express drops to here with **no loss of control** — and
still takes part in measurement, anchoring, clamping, scaling and hit testing:

```java
@Override public void content(Content c) {
    float d = diameter.getFloat();
    c.custom(d, d, (x, y, w, h) -> {
        float r = w / 2f;
        Render.circle(x + r, y + r, r, PANEL);
        Render.line(x + r, y + r, x + w, y + r, 1.5f, Color.RED);
    });
}
```

You can mix freely: a `custom` gauge inside a padded box with a text label under
it is an ordinary column.

### With settings

Extend `AbstractHudElement` and settings register by declaration, exactly as on a
module — persisted alongside the layout:

```java
public final class ClockElement extends AbstractHudElement {

    private final BooleanSetting seconds = bool("Show Seconds", true);
    private final ColorSetting   colour  = color("Colour", Color.WHITE);

    public ClockElement() {
        super("clock", "Clock", HudLayout.at(Anchor.TOP_RIGHT, -4f, 4f));
    }

    @Override public void content(Content c) {
        c.background(PANEL, 3f).padding(4f);
        c.text(text(), colour.resolve());
    }

    @Override public Shape getShape(float x, float y, float w, float h) {
        return Shape.roundRect(x, y, w, h, 3f);   // the corner should miss
    }

    private String text() {
        // A frozen sample while positioning, so the box does not resize under
        // the cursor as the real clock advances.
        if (isEditing()) return seconds.isOn() ? "12:00:00" : "12:00";
        return LocalTime.now().format(seconds.isOn() ? LONG : SHORT);
    }
}
```

`isEditing()` is the edit-mode hook — branch at the point the data comes from and
everything else stays identical.

### Restyling an element you didn't write

Most elements need nothing more than `content()`. A `HudRenderer` is the other
path: it **replaces an element's look**, for when you want something to look
different from how its author drew it.

```java
Core.hud().getRenderers().register(
        HudRenderer.of(SomeoneElsesElement.class, (element, placement) -> {
            Bounds at = placement.getBounds();
            Size size = placement.getNatural();
            Render.roundRect(at.getX(), at.getY(), size.getWidth(), size.getHeight(), 2f, mine);
        }));
```

A renderer replaces **appearance and nothing else**. The element still describes
its content, and that description is still what Core measures — so size,
anchoring, clamping and hit testing are unaffected by whatever the renderer draws.

### Drawing a frame

Core subscribes to no render event. You drive the frame:

```java
List<Placement> placements = Core.hud().resolve(false);   // false: skip hidden
Core.hud().drawAll(placements);                           // or just drawAll()
```

`drawAll` is a convenience, not a requirement. What it buys over your own loop is
the scale transform and containing a throwing element, so one broken element
can't take the rest of the HUD with it.

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

The box model lives in `dev.px.core.layout` and is shared with the GUI, which
uses the same `Content`, `Bounds` and `Shape`. It depends on neither package, so
the HUD and the GUI stay decoupled from each other.

Everything you describe is in **natural, unscaled** units. Scale is a transform
applied around the whole element, so an element that never mentions scale is
automatically scalable.

### Three geometries, and where each comes from

| Geometry | What it is | Where it comes from |
|---|---|---|
| Layout | position and occupied space | measured from your content |
| Visual | what actually gets drawn | your content, drawn |
| Interaction | what responds to clicks, and what the editor outlines | **derived from your content** |

All three come from the one description. You don't declare your shape — Core
works it out from what you said you fill.

**Every box you give a `background` is a region the element occupies.** That one
rule covers the two cases that used to need hand-written shapes:

```java
// An FPS counter on a rounded panel.
c.background(PANEL, 6f).padding(4f);
c.text(fps + " fps", Color.WHITE);
// -> rounded interaction region, radius 6. Corners correctly miss.
//    The editor outlines a rounded rect. You wrote the radius once.

// An ArrayList: right-aligned rows of different widths.
c.align(Align.END).gap(1f);
for (String module : enabled) {
    c.row(r -> { r.background(PANEL).padding(3f, 1f); r.text(module, Color.WHITE); });
}
// -> one region per row, not the box around them. The empty space to the left
//    of "ESP" is not part of the element and does not answer clicks.
```

For the ArrayList the editor outlines the **silhouette** — the outside of the
combined region, with the shared edges between touching rows left out. Outlining
each row separately would draw a line between every pair, which is the opposite
of what an outline is for:

```
 ┌──────────────┐          ┌──────────────┐
 │ Crystal Aura │          │ Crystal Aura │
 ├──────────┬───┘          └──────────┐   │     <- the shared edge is gone,
 │ Kill Aura│      becomes  │ Kill Aura│   ¦        the step survives
 ├─────┬────┘               └─────┐    ¦   ¦
 │ ESP │                     │ ESP│    ¦   ¦
 └─────┘                     └────┘    ¦   ¦
```

An edge is hidden wherever another part has material on the far side of it. A
row's bottom edge disappears where the row below starts; the bottom edge of the
*last* row survives, because nothing is below it.

`Placement.silhouette()` gives you the regions individually, if you'd rather
highlight the row under the cursor than the whole element.

### When to override `getShape()`

Only when the region genuinely isn't what your content describes — which in
practice means when your content is a `custom()` callback, since Core can't see
inside one:

```java
// A dial drawn by a custom callback: only the disc is clickable.
@Override public Shape getShape(float x, float y, float w, float h) {
    return Shape.circle(x + w / 2f, y + h / 2f, w / 2f);
}

// A radar cone: the space either side of the triangle is not clickable.
@Override public Shape getShape(float x, float y, float w, float h) {
    return Shape.polygon(Vec2.of(x + w / 2f, y),
                         Vec2.of(x, y + h),
                         Vec2.of(x + w, y + h));
}
```

`Shape.rect`, `roundRect`, `circle`, `polygon` and `union` are built in; polygons
may be concave, since containment is a ray cast rather than a convex test. An
element that fills nothing it described — plain text, or a bare `custom()` —
falls back to its whole rectangle.

**A `Shape` can stroke itself.** Core never calls it, but `shape.stroke(1.5f,
accent)` is what lets *your* editor outline a circle, a polygon or a merged
silhouette correctly without ever switching on shape type — so a new shape works
in your editor the day it is written.

### Seeing it run

There is a working harness in the test sources: a real GLFW window, a real
NanoVG backend, five elements and an edit mode.

```
./gradlew visual
```

`E` toggles edit mode. Drag to move, drag a corner to scale, scroll to scale,
`ALT` suspends snapping, right-click hides, `L` locks, `R` resets,
`PageUp`/`PageDown` reorder, arrows nudge, `Escape` backs out.

| File | What it is |
|---|---|
| `NanoVGRender2D` | the `Render2D` backend — 28 methods of "draw this shape" |
| `NanoVGFont` | measurement through the real font, which is what the layout is built on |
| `WindowPlatform` | the `Platform` seam, on a GLFW window |
| `VisualElements` | five elements, each one class, none mentioning a coordinate |
| `VisualTest` | the window, the frame loop, and an edit mode written entirely by the "client" |

It is test scope only. Core itself still has no dependencies, and `compileJava`
still proves it.

`--frames=N --screenshot=out.png` renders offscreen and writes a PNG, so the
harness can be checked without a display.

### Edit mode

`HudEditor` is the interaction *model* — hit testing, drag offsets, uniform
resize about a fixed corner, edge and centre snapping, handle placement. **It
draws nothing and listens to nothing.** Core has no opinion on what edit mode
looks like or which key does what, so both are yours.

Three calls a frame:

```java
editor.update(mouseX, mouseY);          // apply any in-flight drag or resize
HudEditorView view = editor.view();     // the geometry to draw
Core.hud().drawAll(view.getPlacements());
```

`update()` takes the cursor rather than reading it, so a drag can be driven by a
test, a controller, or anything that is not a mouse. Everything else is a plain
method you bind however you like:

| Action | Method |
|---|---|
| Begin / end an interaction | `editor.press(x, y)` / `editor.release()` |
| Select | `editor.select(id)` / `editor.deselect()` |
| Move | `editor.nudgeSelected(dx, dy)` |
| Scale | `editor.scaleSelected(delta)` |
| Lock / hide | `editor.toggleLockSelected()` / `editor.toggleHiddenSelected()` |
| Z-order | `editor.bringSelectedToFront()` / `sendSelectedToBack()` |
| Re-anchor | `editor.setSelectedAnchor(anchor)` |
| Reset | `editor.resetSelected()` / `Core.hud().resetAll()` |
| Suspend snapping | `editor.setSnappingSuspended(true)` |
| Open / close | `Core.hud().openEditor()` / `closeEditor()` |

`HudEditorView` is an immutable snapshot with everything a UI needs and nothing
that decides how it looks:

```java
for (Placement placement : view.getPlacements()) {
    if (view.isSelected(placement))     placement.shape().stroke(1.5f, myAccent);
    else if (view.isHovered(placement)) placement.shape().stroke(1f, myHover);

    if (placement.getLayout().isHidden()) { /* your call: dim it, outline it, skip it */ }
}

for (Bounds handle : view.getHandles().values()) {                 // empty if locked
    Render.rect(handle.getX(), handle.getY(), handle.getWidth(), handle.getHeight(), myAccent);
}

for (SnapGuide guide : view.getGuides()) {
    if (guide.isVertical()) Render.line(guide.getPosition(), 0f, guide.getPosition(), h, 1f, myGuide);
    else                    Render.line(0f, guide.getPosition(), w, guide.getPosition(), 1f, myGuide);
}
```

Your screen routes its own input in:

```java
public final class HudEditorScreen extends GuiScreen {

    @Override protected void mouseClicked(int x, int y, int button) {
        if (button == 0) editor.press(x, y);
        else if (button == 1) editor.toggleHiddenSelected();
    }

    @Override protected void mouseReleased(int x, int y, int button) { editor.release(); }

    @Override protected void keyTyped(char typed, int code) {
        Key key = Keys.fromLwjgl(code);
        if (key == Key.L)      editor.toggleLockSelected();
        if (key == Key.LEFT)   editor.nudgeSelected(-1f, 0f);
        if (key == Key.ESCAPE) Core.hud().closeEditor();
        editor.setSnappingSuspended(isAltDown());
    }

    @Override public void drawScreen(int mouseX, int mouseY, float pt) {
        editor.update(mouseX, mouseY);
        HudEditorView view = editor.view();
        drawRect(0, 0, width, height, 0x6E000000);      // your backdrop
        Core.hud().drawAll(view.getPlacements());
        drawOverlay(view);                              // your outlines and handles
    }

    @Override public void onGuiClosed() { Core.hud().closeEditor(); }
}
```

Handle rectangles sit outside the element and flip inside when it is anchored
into a screen corner and there would be no room. `editor.setHandleSize(...)`
keeps your drawn grab target and Core's hit test in agreement. A locked element
is still selectable so it can be unlocked, and reports no handles; a hidden one
still appears in the view while editing so you can bring it back.
---

## 7. The GUI

A click GUI, and the pieces it is made of. Open it and you get a window per
category, a button per module, and a row per setting — every setting type
editable, and the values persisted by the ordinary config mechanism because they
are ordinary settings.

It is deliberately **not** a general widget kit. It was built downward from the
click GUI, so a component exists only because that screen needs it. There is no
layout engine beyond "a panel stacks its children", no flexbox, no constraint
solver, and no screen stack — `GuiService` shows one screen or none.

### Opening it

```java
Core.gui().openClickGui();
Core.gui().open(new MyScreen());   // any screen; replaces whatever was showing
Core.gui().close();
```

**Core draws nothing and listens to nothing.** The GUI lives inside the game's
own screen — `Screen` on modern versions, `GuiScreen` on older ones — and that
screen already owns the mouse and keyboard while it is showing. So there is no
event to intercept and nothing to cancel: your screen calls in.

```java
public final class CoreGuiScreen extends net.minecraft.client.gui.screen.Screen {

    @Override public void render(MatrixStack stack, int mx, int my, float delta) {
        Core.gui().renderFrame();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return Core.gui().mousePressed((float) x, (float) y, MouseButton.byIndex(button));
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        Core.gui().mouseReleased((float) x, (float) y);
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        return Core.gui().scrolled((float) amount, (float) x, (float) y);
    }

    @Override public boolean keyPressed(int key, int scancode, int mods) {
        Key resolved = Keys.fromGlfw(key);
        // Escape is reported, not acted on: whether it closes is your decision.
        if (!Core.gui().keyPressed(resolved, Keys.modifiers(mods)) && resolved == Key.ESCAPE) {
            onClose();
        }
        return true;
    }

    @Override public boolean charTyped(char typed, int mods) {
        return Core.gui().charTyped(typed);
    }

    @Override public void removed() { Core.gui().close(); }
}
```

Every method returns whether a component consumed the input, so you decide what a
press that landed on nothing means.

### Restyling what Core ships

The GUI's grid is replaceable, not constant. One call and every row, window and
indent follows it — no rewritten components:

```java
GuiStyle.metrics(GuiStyle.Metrics.builder()
        .rowHeight(20f)
        .padding(6f)
        .windowWidth(190f)
        .titleHeight(24f)
        .build());
```

Colours already came from the active theme. Between the two, the shipped click
GUI adapts to your look without any component being replaced — and a
`SettingRenderer` is still there for when you want a genuinely different widget.

### A component

One method, and it is the same one a `HudElement` writes: **describe what you are
made of.** Your height is what that measures and your appearance is that same
description drawn, so the two cannot drift apart. Stacking, hit routing, drag
tracking and focus are handled above you, so writing a component never means
reading the layout or input code.

```java
public final class Divider extends Component {

    @Override protected void content(Content c) {
        c.align(Align.STRETCH);                 // else the rect is zero wide
        c.rect(0f, 1f, GuiStyle.outline());
    }
}
```

A component is *handed* a width, where a HUD element sizes to its content, so
three primitives matter here that do not in §6:

| | |
|---|---|
| `fill()` | absorbs the slack — label left, value right |
| `grow()` | a whole box takes the slack, not just a gap |
| `align(Align.STRETCH)` | children get the box's full cross-axis size |

Two more cover what a flow layout cannot express: **`backdrop(draw)`** puts
arbitrary drawing *behind* a box's parts (`background()` only takes a flat
colour, and parts stack rather than overlap), and **`clip()`** cuts parts off at
the box's edge so a long value is truncated instead of spilling out of the window
it sits in.

Anything interactive adds one more method. `onClick` returns whether it consumed
the press; returning false offers it to the parent, which is how a setting row
that ignores a right-click lets the module button behind it decide instead:

```java
public final class Button extends Component {

    private final String label;
    private final Runnable action;
    private final Animation hover = Animation.fade(120, Easing.QUAD_OUT);

    public Button(String label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    @Override protected void content(Content c) {
        hover.target(getBounds().contains(Core.platform().getMouseX(),
                                          Core.platform().getMouseY()));

        float height = GuiStyle.rowHeight();
        c.height(height).align(Align.STRETCH)
         .background(GuiStyle.surface().lerp(GuiStyle.accent(), hover.get()),
                     Math.min(GuiStyle.radius(), height / 2f));

        // A row, not a bare text part: align(CENTER) on a column would centre the
        // label horizontally, and what you want is left, centred in the row.
        c.row(r -> {
            r.grow().padding(GuiStyle.padding(), 0f).align(Align.CENTER);
            r.text(label, GuiStyle.text());
        });
    }

    @Override protected boolean onClick(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        action.run();
        return true;
    }

    @Override public String getTooltip() { return "Runs " + label; }
}
```

Children are drawn by the engine, not by you — your content draws first and the
visible children follow. A fill that must span the children too, such as a window
background, goes in `renderBackdrop()` instead, because it needs a height that is
only known once they are placed. Everything else is opt-in:

| Override | For |
|---|---|
| `onClick(x, y, button)` | a press that landed on you; return whether you consumed it |
| `onDrag` / `onRelease` | after `getScreen().beginDrag(this)` |
| `onKey` / `onChar` | delivered only while you hold focus; take it with `focus()` |
| `onScroll(amount, x, y)` | the wheel, over you |
| `part(name)` / `hitsPart` | the rectangle a named part of your content was drawn in |
| `renderBackdrop(x, y, w, h)` | a fill spanning your whole subtree |
| `shape(x, y, w, h)` | a non-rectangular clickable region |
| `isVisible()` | whether you take part in layout, drawing and hit testing at all |
| `showsChildren()` | whether your children do |
| `getTooltip()` | text shown when the cursor rests on you |

`Panel` is the stack, and the only layout there is for *children*:
`headerHeight(width)` is measured from your own content rather than declared —
so a header can never be a different height from the thing drawn in it —
`getPadding()` insets the children, and `childIndent()` shifts them right. A panel
whose visible children are all gone collapses to its header.

> **Children stack vertically and nothing else.** The box model above applies to a
> component's own content, not to its child components: there is no horizontal or
> grid container for children, and no scrolling. A list longer than the screen has
> no answer yet.

### Three things share one answer

Layout, drawing and hit testing all walk `visibleChildren()`, so they cannot
disagree about whether a row exists. That is what makes everything collapsible
work the same way and stay correct: a collapsed group, a closed dropdown and a
minimised window all return false from `showsChildren()`, and a row hidden by
`visibleWhen` returns false from `isVisible()`. In every case the row leaves
layout, drawing and hit testing together — it can never be clicked through the
gap it left behind.

Hit testing is a `Shape`, the same one the HUD uses, so a round or triangular
component gets the right clickable region by overriding one method, and
containment is hierarchical: a point outside a parent never reaches its children.

### A screen of your own

A screen is a component that fills the display. It owns the two things only one
component can hold at a time — keyboard focus and the in-flight drag — and
otherwise gets out of the way. Place the children in `layoutChildren()`; that is
the only method a screen has to implement.

```java
public final class ProfileScreen extends Screen {

    private final Panel body = add(new Panel());

    public ProfileScreen() {
        super("Profiles");
        body.setBackground(true);

        for (String profile : Core.config().listProfiles()) {
            body.add(new Button(profile, () -> Core.config().load(profile)));
        }
        body.add(new Divider());
        body.add(new Button("Close", () -> Core.gui().close()));
    }

    /** Centred, a third of the way down. The panel measures its own height. */
    @Override protected void layoutChildren() {
        float width = GuiStyle.windowWidth() * 1.5f;
        body.layout((getScreenWidth() - width) / 2f, getScreenHeight() / 3f, width);
    }

    /** Dim the game behind it. Skip this and the screen draws over a live world. */
    @Override protected void content(Content c) {
        c.custom(getScreenWidth(), getScreenHeight(),
                (x, y, w, h) -> Render.rect(x, y, w, h, GuiStyle.backdrop()));
    }
}
```

`add` returns the child, so a field can be declared and attached in one line.
Override `onOpen` / `onClose` for state that must not survive a dismissal, and
`save` / `load` to have `GuiService` persist something in the ordinary config —
that is all the click GUI does to remember where its windows were dragged.

### A setting renderer

One per setting type, looked up through a `Registry`. Core ships nine, and adding
a tenth needs no change to any file in `gui`:

```java
Core.gui().getRenderers().register(
        SettingRenderer.of(WaypointSetting.class, WaypointRow::new));
```

A row extends `SettingComponent<S>`, which handles the three things every row
does identically: visibility follows `visibleWhen`, the tooltip is the setting's
`describe` text, and the header is one row high so it lines up with its
neighbours. It also measures itself, so the only method a row must write is
`content`. Replacing one of Core's is the same call with the built-in type:

```java
/** A stepper instead of a slider: click the left half to go down, the right to go up. */
public final class StepperRow extends SettingComponent<NumberSetting<?>> {

    public StepperRow(NumberSetting<?> setting) {
        super(setting);
    }

    @Override protected void content(Content c) {
        // header() is the standard row: the setting's name, then whatever you add
        // on the right. "steps" is named, so the click below measures against the
        // very rectangle that was drawn.
        header(c, false, row -> row.custom("steps", 40f, GuiStyle.rowHeight(),
                (x, y, w, h) -> Render.text(getSetting().displayValue(), x, y, GuiStyle.text())));
    }

    @Override protected boolean onClick(float pointerX, float pointerY, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        boolean left = progressIn("steps", pointerX) < 0.5f;
        getSetting().setProgress(getSetting().progress() + (left ? -0.05f : 0.05f));
        return true;
    }
}
```

```java
Core.gui().getRenderers().register(
        SettingRenderer.of(NumberSetting.class, (NumberSetting<?> s) -> new StepperRow(s)));
```

The row never clamps or steps the value itself: `setProgress` goes through the
setting, which already enforces its own bounds and increment. That is why
dragging past either end of a slider cannot produce an out-of-range number.

`GroupSetting` renders its own children, which is why a module's panel is built
from `getSettings()` rather than `getAllSettings()` — the flattened list would
draw every nested setting twice.

Two of the nine carry state beyond their value. A `BindSetting` row captures:
click it, and the next key becomes the bind, with `Escape` clearing it rather
than binding `Escape`. A `ColorSetting` row opens a picker with a
saturation/brightness square, hue and alpha strips, and checkboxes for the
rainbow and theme-sync modes the setting already supports — hiding the strips
while either mode is on, since they no longer drive anything.

### Where things register

| What | When |
|---|---|
| Setting renderers | **before** `core.start()`, alongside modules and commands |
| Screens | any time; `Core.gui().open(...)` takes one |
| Anything after startup | call `Core.gui().rebuild()` |

The click GUI is built at the end of `start()`, so a renderer registered before
then is already in the rows. Registering one for a type Core also ships wins:
the defaults fill the gaps and never overrule a claim, so replacing the slider is
an ordinary `register` at the same point in the bootstrap as everything else. A
module registered *after* startup, or a renderer swapped while the client runs,
needs `Core.gui().rebuild()` — windows keep their positions across it.

### Colours

Every colour comes from `GuiStyle`, which reads the active `ThemeService` on each
call rather than caching: a config load applies settings silently, so anything
that cached a theme colour would draw the previous theme until the client
restarted.

```java
GuiStyle.background();   GuiStyle.surface();   GuiStyle.backdrop();
GuiStyle.accent();       GuiStyle.accent(0.5f);            // along the theme gradient
GuiStyle.text();         GuiStyle.textMuted();   GuiStyle.outline();
GuiStyle.radius(w, h);   // theme rounding, capped so it cannot exceed the shape
```

The metrics beside them — `rowHeight()`, `padding()`, `spacing()`, `indent()`,
`windowWidth()`, `titleHeight()` — are the grid the whole GUI is laid out on, and
they are replaceable as a set: see *Restyling what Core ships* above. A component
that invents its own row height stops lining up with every other component in the
panel.

### How input reaches a component

**Core subscribes to nothing.** Input arrives because the game screen hosting the
GUI calls `Core.gui().mousePressed(...)`, `keyPressed(...)` and the rest — and
that screen already owns the mouse and keyboard while it is showing, so there is
nothing to intercept and nothing to cancel. The HUD editor works the same way.

From there a press goes to the deepest component under the cursor and walks back
up until one consumes it. Keys and characters go only to the focused component,
which is whatever last called `focus()`; clicking anywhere else drops focus, and
`onFocusLost()` is where a text field commits what was typed.

Dragging follows the cursor from `Platform.getMouseX()` each frame rather than
from a move event — there is no mouse-move event to bridge. The HUD editor does
the same thing, except you pass it the cursor rather than it reading one.
A component starts one with `getScreen().beginDrag(this)` and then receives
`onDrag(x, y)` every frame until the button comes up.

### Not using any of this

The GUI package is optional. **Nothing outside `gui` references `Component`,
`Panel` or `Screen`**, so a client that wants its own look from the ground up can
ignore the whole package and lose nothing else. Core stays useful because the
parts a GUI actually needs live elsewhere:

| You need | It is in | Already yours |
|---|---|---|
| Drawing | `Render` / `Render2D` | you installed the backend |
| Screen size, cursor, clipboard | `Platform` | you wrote it |
| What to show | `Core.modules()`, `Core.categories()` | §2 |
| Editing a value | `Setting<?>`, `SettingHolder.getSettings()` | §2 |
| Colours and rounding | `ThemeService` | §5 |
| Measuring text | `FontService`, `Font.widthOf` | §5 |
| Remembering your state | implement `ConfigSection`, register it | §1 |
| A box model, if you want one | `dev.px.core.layout` | §6 |

That last row is worth knowing about: `Content`, `Bounds`, `Shape` and `Align`
live in `dev.px.core.layout`, which imports neither `hud` nor `gui`. You can use
the box model on its own — measure, lay out, draw, name a part and hit test it —
without a single `Component`, or skip it and issue `Render` calls directly.

**The settings do the work, not the rows.** Core's `NumberRow` is about forty
lines because `NumberSetting` already clamps, steps, and converts to and from a
0..1 fraction; `BindSetting` already formats itself; `ColorSetting` already
carries its rainbow and theme-sync modes. Whatever you draw a slider as, the
value handling is done.

```java
// your own screen, with none of gui/ involved
for (Module module : Core.modules().inCategory(Categories.COMBAT)) {
    drawMyButton(module.getDisplayName(), module.isEnabled(), module::toggle);

    for (Setting<?> setting : module.getSettings()) {
        if (setting instanceof NumberSetting) {
            NumberSetting<?> number = (NumberSetting<?>) setting;
            drawMySlider(number.getName(), number.displayValue(),
                    number.progress(), number::setProgress);
        }
    }
}
```

### What you plug in either way

The seam does not change when you drop the GUI — it gets smaller:

| | Using Core's GUI | Your own |
|---|---|---|
| `Render2D` backend | required | required |
| `Platform` | required | required |
| A game screen to host it | calls `Core.gui().renderFrame()` and the input methods | calls your own code |
| Input | `Core.gui().mousePressed / keyPressed / charTyped / scrolled` | yours |
| Persistence | `GuiService` saves screens through `Screen.save()` | register your own `ConfigSection` |
| Keybinds and commands | post `KeyEvent` / `MouseEvent` / `ChatSendEvent` | unchanged — these are not the GUI's |

Keybinds and commands are the row that catches people out: `InputService` and
`CommandRegistry` listen on the event bus whether or not you use the GUI, so a
client with a hand-rolled interface still posts those three events or its binds
and commands silently never fire.

One rough edge to know about: `GuiService.start()` builds the click GUI
unconditionally, so a client that never opens it still constructs it and nine
setting rows once at startup. Harmless, but it is not opt-out yet.

---

## 8. Threading

Three tiers, because background work in a client comes in three shapes and one
pool cannot serve all of them. A `while (true)` loop sharing a fixed pool with a
one-second timer means the loop owns a thread forever and the timer quietly stops
firing — nothing throws, nothing is logged.

| Call | For | Runs on |
|---|---|---|
| `submit(Runnable)` / `submit(Callable<T>)` | work that finishes: a login, an update check, a file scan | a pool of `threadPoolSize()` workers that time out when idle |
| `schedule` / `repeat` | timers | their own small pool, which the tier above cannot starve |
| `loop(label, body)` | work that runs until switched off | a dedicated daemon thread per loop |

```java
Core.threads().submit(() -> {
    String latest = Http.getOrNull(VERSION_URL);          // worker thread
    Core.threads().sync(() -> {                           // game thread
        Core.notifications().info("Update", latest + " is available");
    });
});
```

`repeat` is fixed-*delay*, not fixed-rate: if one run overruns the period,
fixed-rate fires the backlog all at once, which is the wrong behaviour for
polling an API. A run that throws is logged and the schedule **continues** — a
raw `scheduleWithFixedDelay` cancels every remaining run the first time its body
throws, so one network blip would stop the poll for the session. Everywhere else
a failure is logged *and* rethrown, so a `Future` you check still reports it.

### Getting back to the game thread

Background work must not touch game state. `sync(Runnable)` queues a task and
`runPendingSync()` runs the queue, and Core wires that drain to `TickEvent` — so
**if your adapter posts ticks, this already works.** If it does not, call
`Core.threads().runPendingSync()` from your game loop yourself; `ThreadService`
warns if a backlog builds up and nobody is draining it.

Tasks run in queue order on the next drain, even when `sync` is called from the
game thread already: running inline would let a task execute in the middle of
another one's drain. A drain is bounded to the backlog present on entry, so a
task that queues another cannot spin a frame. `runPendingSync(limit)` spreads a
large backlog over several ticks, and `isGameThread()` answers whether you are
somewhere it is safe to touch the world — `false` when no thread has been
marked, since the safe answer to that question when unknown is no.

### Modules

`ThreadedModule` is the shape for a module whose work is a loop. It gets its own
thread through `loop`, so any number can be enabled at once:

```java
@ModuleInfo(name = "Scanner", description = "Scans in the background", category = "Render")
public final class Scanner extends ThreadedModule {

    private volatile List<String> found = Collections.emptyList();

    @Override
    protected void runInBackground() {
        while (!Thread.currentThread().isInterrupted()) {
            List<String> scanned = scan();
            Core.threads().sync(() -> found = scanned);
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                return;                      // disabled; stop
            }
        }
    }
}
```

Honour interruption — return from an `InterruptedException` rather than
continuing. Cancellation is not treated as a failure, so switching a module off
logs nothing even though the body unwinds with an exception.

### Lifecycle

Work requested before `start()` or after `stop()` is dropped with one warning and
an already-finished handle, never a `NullPointerException`. Shutdown is two-phase:
work already running gets 1.5s to finish on its own — a config half-written to
disk is worth waiting for — and only then are the stragglers interrupted.

Pool size comes from the builder:

```java
Core.builder("LeapFrog", "2.0").platform(...).threadPoolSize(4).build();
```

That sizes only the `submit` tier; timers have their own pool and each `loop` has
its own thread, so it is worth raising only for a client firing many concurrent
requests.

---

## 9. Package map

| Package | What it is |
|---|---|
| `event` / `event.bus` / `event.impl` | Bases, `@Subscribe`, the bus, built-in events |
| `module` | `Module`, `@ModuleInfo`, `Category`, `ModuleRegistry`, `ThreadedModule` (a module whose work runs off the game thread) |
| `setting` / `setting.impl` | Settings, auto-discovery, the nine types |
| `layout` | Geometry and the box model, shared by the HUD and the GUI and depending on neither: `Bounds`, `Size`, `Shape`, `Content`, `Align`, `Draw` |
| `hud` | HUD placement and the edit-mode model: `HudElement`, `Anchor`, `HudLayout`, `Placement`, `HudService`, `HudRenderer`, `HudEditor`, `HudEditorView` |
| `gui` / `gui.setting` / `gui.click` | The click GUI and the pieces it is built from: `Component`, `Panel`, `Screen`, `GuiService`, `GuiStyle`, the nine `SettingRenderer`s, and `ClickGuiScreen` |
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
| `concurrent` | `ThreadService` — three tiers (one-shot workers, timers, dedicated loop threads) plus the game-thread queue; `TaskHandle` for cancelling a loop. Task exceptions are logged, not swallowed. See §8 |
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

## 10. What your adapter must supply

1. **`Platform`** — data dir, screen metrics, cursor, chat, username, in-game flag
2. **`Render2D`** — your 2D library
3. **`Render3D`** — world drawing and projection
4. **`FontProvider`** — font loading for that backend
5. **Event bridging** — post Core's events from your mixins. Only two things in
   Core still listen: `InputService` wants `KeyEvent` and `MouseEvent` for module
   keybinds, and `CommandRegistry` wants `ChatSendEvent` to intercept commands.
   Post the rest for your own modules to subscribe to.
6. **A per-frame `Core.hud().drawAll(...)`** from your render hook, and, if you
   want edit mode, a screen that routes input into `HudEditor` and draws
   `HudEditorView`. Elements describe themselves, so there is nothing else to
   write per element; see §6.
7. **A game screen, if you use the GUI** — one that calls `Core.gui().renderFrame()`
   and hands it mouse, key, scroll and character input, then `Core.gui().close()`
   when dismissed. Core neither draws it nor listens for it. Skip this entirely if
   you are writing your own interface; see *Not using any of this* in §7.

Nothing extra is needed for threading: Core drains the game-thread queue from
`TickEvent`, so posting ticks (step 5) covers it. An adapter that does not post
them calls `Core.threads().runPendingSync()` from its game loop instead; see §8.

Optional: `AuthProvider` (alt manager), `PresenceProvider` / `MediaProvider`, and
`PathSpace` if you use `util.spatial` — one lambda saying which cells your agent
can occupy is enough to run `AStar` against your world.

Core runs headless without any of these. The test suite boots it with none
installed, which is how the seam stays honest.

---

## 11. Verifying

`dev.px.core.test.CoreSmokeTest` runs **893 checks** in a plain JVM — no
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
| `GuiTests` | renderer lookup and replacement, tree structure, visibility gating, hit routing, every setting type edited through the GUI, the input gate, window persistence |
| `ConfigTests` | full round-trip, profiles, second-load regression, path sanitising |

The `example/` package is written to be read: it is what a real client's modules,
commands and HUD elements look like, and `TestClient.boot()` is the canonical
bootstrap minus the render backends.

---

## Not yet included

- **Adapter layer** — `Player`, `World`, `Entity`, packet wrappers.
