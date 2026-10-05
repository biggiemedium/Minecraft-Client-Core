# Core

The version-independent half of a Minecraft utility client: event bus, modules,
settings, config, commands, input, services, a pluggable render facade, a HUD
layout engine with an edit-mode model, a click GUI, a shader pipeline that
leaves OpenGL to you, and rotation arbitration so two modules cannot silently
fight over the player's head.

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
core.getEntityService().registerAll(new LivingTracker(), new CrystalTracker());   // §12

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

### Config: where everything is saved

`start()` loads the saved config and `stop()` saves it. Each **section** —
modules, HUD, GUI windows, theme, friends, accounts, and any of yours — is its
own JSON file, in every profile or shared by all of them:

```
configs/                          setDirectory(...), under Platform.getDataDirectory()
  profiles/                       setProfilesFolder(...)
    default/
      modules.json
      hud.json
      myclient/waypoints.json     a section of yours, in a folder of its own
    hypixel/...
  shared/                         setSharedFolder(...): the same for every profile
    accounts.enc                  encrypted, because you asked
    friends.json
    profile.json                  which profile was active last
```

The layout is yours. Name the folders before `start()`, put your own sections
anywhere, and move Core's:

```java
ConfigService config = core.getConfigService();
config.setDirectory("leapfrog");                       // instead of configs/
config.register(new SettingsSection("waypoints", waypoints), ConfigLocation.profile("myclient/waypoints"));
config.register(new MySection(), ConfigLocation.shared("stats"));
config.place("hud", ConfigLocation.profile("visual/hud"));        // move one of Core's
config.place("accounts", ConfigLocation.shared("private/accounts"));
```

A path is folder and file names separated by `/` — letters, digits, `.`, `_`,
`-` — so it can make folders but never leave the config folder. `place` wins
over a location given at registration, and two sections can never share a file.
Friends and accounts are shared by default: switching profile changes how you
play, not who your alts are.

Profiles: `load(name)` switches profile and loads its sections, `saveAs(name)`
copies the current state into a new one, `save()` writes everything,
`listProfiles()` and `delete(name)` do what they say. The last active profile is
remembered across restarts. `getProfileDirectory(name)` and
`getSharedDirectory()` are there for files of your own.

**Encryption is optional, and yours.** Nothing is encrypted unless you give a
section a `ConfigCipher`; it is then written as `.enc`, and the `.json` it
replaces is deleted once the encrypted file is written:

```java
byte[] salt = loadOrCreateSalt();                                   // yours; a salt need not be secret
SecretKey key = AesGcmCipher.deriveKey(passphrase, salt);           // PBKDF2-HMAC-SHA256
config.encrypt("accounts", AesGcmCipher.of(key));                   // before start(), to read it back
```

`AesGcmCipher` is AES-GCM from the JDK, with nothing to add to your build. Each
write gets a fresh nonce, and the file's location is bound in, so an altered file
— or one copied into another section's place — fails to decrypt instead of
loading. Implement `ConfigCipher` yourself for anything else, such as the
operating system's credential store.

**Where the key comes from decides what the encryption is worth**, which is why
Core does not choose. A passphrase the player types protects the file from
anyone without it. A key from the OS credential store protects it from other
users of the machine. A key file beside the configs only stops it being read by
accident — pasted into a support channel, synced somewhere — since anything that
can read one can read the other.

**Nothing is lost quietly.**

- Every file is written to a temporary file and renamed over the old one, so a
  crash mid-save leaves the last good file, never half of one.
- A file that cannot be read — broken by a hand edit, or encrypted with another
  key — is copied aside as `<name>.unreadable-<time>` before anything can
  overwrite it. Its section keeps its defaults, and every other section loads.
- An encrypted file found with no cipher installed is left alone, with an error
  saying which `encrypt(...)` call is missing.
- Nothing is saved on shutdown unless the config was loaded first.

Profiles saved by the old single-file format are converted on the first load,
and the originals are moved to `configs/legacy/`.

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

    // Who counts, in the game's own types. LivingTracker is yours: see §12.
    private final TargetSelector<EntityLivingBase> enemies = TargetSelector.from(LivingTracker.class)
            .range(reach::getDouble)
            .where(e -> targets.has(e instanceof EntityPlayer ? Target.PLAYERS : Target.MOBS))
            .build();
    private final TargetLock<EntityLivingBase> lock = Core.targets().lock(enemies);

    private final Stopwatch attackTimer = Stopwatch.expired();
    private Vec2 aim = Vec2.rotation(0f, 0f);

    @Subscribe(stage = Stage.PRE, priority = Priority.HIGH)
    private void onTick(TickEvent event) {
        Tracked<EntityLivingBase> target = lock.update();       // keeps one target, no flicking
        if (target == null) return;

        Vec3 eye = Core.entities().getSelf().getEyePosition();
        aim = RotationMath.step(aim, eye.rotationTo(target.aimPoint(eye, 0.05)), 30f);
        applyRotation(aim);                                      // yours

        if (attackTimer.tryConsume(1000 / cps.randomInt())) {    // randomised delay
            attack(target.get());                                // yours: the game's own entity
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (lock.hasTarget()) {
            Render.boxOutline(lock.get().getBox(), 1.5f, hitbox.resolve());
        }
    }

    @Override protected void onDisable() { lock.release(); }

    /** Extra text shown after the name in the ArrayList. */
    @Override public String getDisplayInfo() { return mode.displayValue(); }

    public enum Mode { VANILLA, WATCHDOG, NCP }
    public enum Target { PLAYERS, MOBS, ANIMALS, INVISIBLES }
}
```

Everything above is Core's API except the two lines marked `// yours` — writing
the rotation and swinging need the game — and the game's own entity types, which
reach Core only as the type parameter of a tracker (§12). The selector picks the
target in your terms, the lock holds it across ticks, `aimPoint` aims just
inside its box rather than at its edge, `RotationMath.step` traces the turn out
over several ticks instead of snapping, and the box comes back ready to outline.
One module owning its own aim like this is fine; the moment a second one wants
the head they fight, which is what `Core.rotations()` in §10 exists to settle.
`ExampleKillAura` in the test suite is the aiming half of this module, running
headless with its positions handed in.

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
`ScreenEvent`, `ClientLifecycleEvent`, and `PacketEvent` / `MotionUpdateEvent`,
which carry the game's objects opaquely (see §10 and §11). Game-specific ones
(entities, attacks) belong in your adapter.

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

Shaders are a separate seam again, for the same reason: `Render2D` is a drawing
vocabulary and a shader program is not one. See §9.

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
| Keybinds and commands | `Core.hooks().key / mouse / chatSend` | unchanged — these are not the GUI's |

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
**if your adapter calls the tick hooks, this already works.** If it does not, call
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

## 9. Shaders

Core does not link OpenGL and never will. **One class does** — the
`ShaderBackend` you write — and it is about a hundred lines of ordinary GL.
Everything around it is the half that is the same in every client and tedious in
all of them.

| Core | You |
|---|---|
| Reads the source, inlines `#include`, hoists `#version`, injects `#define` | Write the GLSL |
| Compiles once, on first draw, and keeps the handle | `glCreateProgram` / `glCompileShader` / `glLinkProgram` |
| Records uniform values by name and type | `glUniform*`, and cache the locations |
| Binds, unbinds, and restores the outer pass even when a draw throws | `glUseProgram` |
| Disposes every program at shutdown and on reload | `glDeleteProgram` |
| Turns a GLSL error into one log line with the source numbered | — |

### Wiring it up

```java
ShaderService shaders = Core.shaders();
shaders.setBackend(new GlShaderBackend());                       // yours, below
shaders.setLoader(ShaderLoader.classpath("assets/leapfrog/shaders"));

shaders.register(ShaderSource.named("glow")
        .vertex("glow.vsh")
        .fragment("glow.fsh")
        .define("SAMPLES", 12)
        .build());
```

Registration is pure data — nothing is read and no GL call is made — so it
belongs in the same block as modules and HUD elements, before a window exists.
The first draw reads, assembles and compiles it, on the thread that is rendering,
which is the only thread that may. A shader you register and never draw with
costs a map entry.

A stage is either a path the loader reads or literal text given inline
(`fragmentSource(...)`), and there is no requirement to have a vertex stage —
fragment-only programs are how most legacy post-processing is written.

### Drawing with one

```java
Core.shaders().use("glow",
        u -> u.set("uRadius", 6f).set("uColour", Core.themes().getPrimary()),
        () -> Render.rect(x, y, width, height, Color.WHITE));
```

The body is passed in for the same reason `Render.clipped` takes one: an
unbalanced bind corrupts every later draw in the frame, and a body that throws
would leave it unbalanced. Here that is impossible. Nesting works too — an inner
pass restores the outer program on the way out rather than dropping to the fixed
pipeline.

A client that owns its own draw loop and cannot express it as a body uses the
long form instead:

```java
if (shaders.bind("glow", myUniforms)) {
    try { drawMyQuad(); } finally { shaders.unbind(); }
}
```

`bind` applies exactly what you hand it; call `shaders.applyGlobals(myUniforms)`
first if you want the built-ins as well.

### The backend

The one class that sees GL. It extends `UniformSink`, because uniforms in GL go
to whichever program is bound and the calls need exactly the state a backend
already holds — the program, its cached locations, its texture units.

```java
public final class GlShaderBackend implements ShaderBackend {

    public Shader compile(ShaderSource source, Map<ShaderStage, String> glsl) {
        int program = glCreateProgram();
        for (Map.Entry<ShaderStage, String> stage : glsl.entrySet()) {
            int id = glCreateShader(typeOf(stage.getKey()));
            glShaderSource(id, stage.getValue());               // already assembled
            glCompileShader(id);
            if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
                throw new ShaderException(stage.getKey() + ": " + glGetShaderInfoLog(id));
            }
            glAttachShader(program, id);
            glDeleteShader(id);
        }
        glLinkProgram(program);
        return new GlShader(source.getName(), program);         // caches locations
    }

    public void bind(Shader shader) { glUseProgram(((GlShader) shader).id); }
    public void unbind()            { glUseProgram(0); }

    public void floats(String name, float[] v, int count) {
        switch (count) {
            case 1: glUniform1f(location(name), v[0]); break;
            case 2: glUniform2f(location(name), v[0], v[1]); break;
            // ...
        }
    }
    public void ints(String name, int[] v, int count)           { /* glUniform1i ... */ }
    public void matrix(String name, float[] v, int order)       { /* glUniformMatrix4fv */ }
    public void sampler(String name, Texture texture, int unit) { /* activate, bind, 1i */ }
}
```

Four uniform methods rather than a dozen, grouped by component count, so `vec2`
and `vec4` arrive through the same call and the backend is written once. Throwing
from `compile` is the correct way to report a driver error: the message ends up
in the log with the shader named.

Location lookup belongs to the backend, because only the backend knows what a
location is. Looking one up by name every frame is a driver round trip per
uniform — cache them on your `Shader`.

### Uniforms

`Uniforms` records what to send; the backend decides how.

```java
u.set("uStrength", 0.5f)                 // float
 .set("uCentre", x, y)                   // vec2       (also Vec2, Vec3)
 .set("uColour", theme.getPrimary())     // vec4, already normalised to 0..1
 .set("uSamples", 12)                    // int        (also two ints, boolean)
 .matrix("uProjection", projection)      // mat2/3/4, column-major
 .sampler("uScene", sceneTexture, 0);    // binds the unit and points the uniform at it
```

A `Color` is divided by 255 on the way in, because forgetting that divide is the
single most common way a shader comes out white. A `Texture` is the same handle
`Render2D` draws with, so an image loaded for the HUD can be sampled by a shader
without a second copy.

The instance is mutable and reused on purpose: a fresh immutable one per frame
would allocate a map, a boxed float and an array per uniform, sixty times a
second, forever. `use(...)` hands you one per shader, already cleared, so a
steady-state frame allocates nothing.

Three values are set for you on every pass, from `Platform`:

| Name | Value |
|---|---|
| `uTime` | seconds since startup, wrapped hourly so a `float` keeps its precision |
| `uResolution` | scaled screen width and height |
| `uMouse` | cursor, in the same space |

`setBuiltinUniforms(false)` turns them off for a client with its own naming, and
`addGlobalUniforms(u -> ...)` adds values every pass receives — a projection
matrix, a palette, partial ticks.

### `#include`, and the two other things GLSL makes awkward

GLSL has no `#include`, so a helper shared by four shaders normally lives in four
copies. Core inlines them, resolved relative to the including file and applied
**once each** — a header pulled in twice would redeclare everything in it.

`#version` has to be the first line, which means an included header can never
carry one. The first version directive found anywhere is hoisted to the top and
later ones dropped, so a header can declare the version it was written against.

`#define`s come from the `ShaderSource`, injected below the version line, so
sample counts and feature switches are configured in Java instead of by string
concatenation at the call site. `define("NAME")` with no value is a bare symbol
for `#ifdef`, which is not the same as defining it to 0.

None of this needs a GL context, which is why it is Core's and not yours.

### When a shader is broken

A missing file, a bad include or a GLSL error is logged **once**, with the
assembled source listed by line — the line a driver means is a line of text
nobody has on disk, so printing it is the difference between a fixable message
and `0(46) : error C1503`. The shader is then marked failed and not retried every
frame.

`use(...)` still runs its body, unshaded. A missing effect should cost the
effect, not the thing it was decorating — the same reasoning as `Render` dropping
draw calls before a backend is installed. `isReady(name)` lets you branch instead,
and `isFailed(name)` says whether it has already been tried.

The same applies with no backend installed at all, and to a handle whose context
went away under it: an invalid program is rebuilt rather than bound.

### Editing GLSL with the game running

```java
shaders.setLoader(ShaderLoader.directory(devFolder)
        .orElse(ShaderLoader.classpath("assets/leapfrog/shaders")));
```

Save the file, call `Core.shaders().reload()`, see it. `reload()` disposes every
program and clears every failure, so the next draw builds them again; it is also
what to call after a resource reload on versions where that destroys the GL
objects Core is holding.

### Not using any of this

Nothing outside `dev.px.core.shader` references the package. Core registers the
service because every service is registered in one place, and with no backend it
stays silent — no warning, no allocation beyond an empty registry, no GL. It is
the one service that says nothing when it is unconfigured, because a client with
no fonts is misconfigured while a client with no shaders is just a client.

---

## 10. Movement

Three things that all come down to "where is the player going", in one package
with the game-shaped parts behind two small interfaces.

| `dev.px.core.movement` | | Needs |
|---|---|---|
| `.rotation` | who gets the head, and how it turns | `RotationSink`, two methods |
| `MovementCorrection` | keeping the keys honest once it has turned | nothing |
| `.simulation` | where things have been and where they are going | `CollisionSpace`, one method |

Neither interface lives on `Platform`, which stays 13 methods with no player,
world or packets in it.

### Rotations

One module owning where the player looks is fine. Two is a silent bug: whichever
handler ran last that tick wins, the outcome depends on subscription order, and
there is nowhere to look to find out who took the head.

So modules stop writing the rotation and start **asking** for it.

```java
// in a module's tick handler -- ask every tick, for as long as you want it
Core.rotations().request(this, eye.rotationTo(target), RotationPriority.HIGH, 30f);

// in the adapter, once, wherever rotations are written
Core.rotations().apply();
```

`request` files a claim; `apply()` resolves every live claim into one rotation
and hands it to your `RotationSink`. Nothing else changes about how a module is
written.

### Priorities and ties

Highest wins. `RotationPriority` has the usual five anchors (`LOWEST` 0 through
`HIGHEST` 100) and any int in between works, same as `event.Priority`.

Equal priorities go to **whoever acquired the rotation first**, and stay there
for as long as that module keeps asking. The obvious alternative — most recent
request wins — makes two same-priority modules trade the head back and forth once
a tick, which looks exactly like a bug and is very hard to find. Give them
different priorities if the other one should win.

### Claims expire, so nothing has to remember to let go

A claim lasts one tick unless renewed. A module keeps the rotation by continuing
to want it, and loses it by going quiet — which is what happens when it is
switched off, returns early, or throws.

That is the whole reason it works this way. Acquire-and-release has one failure
mode that matters: a module disabled while holding the rotation never releases,
and the player's head stays locked with nothing to blame. There is a `release()`
for letting go early, but **nothing depends on it being called**.

```java
rotations.request(this, RotationRequest.at(target)
        .priority(RotationPriority.HIGHEST)
        .step(30f)                      // degrees per tick; default is snap
        .hold(3)                        // survive two skipped ticks
        .mode(RotationMode.SILENT));
```

### There is no call ordering to get wrong

The obvious design resolves on the tick event, which means it has to run after
every module has filed. "After every module" is not something a priority can
express, because a module is free to use any priority too — and getting it wrong
makes every rotation one tick stale, which is subtle to diagnose and very visible
in game.

So resolution is lazy. `beginTick()` only opens a window; the rotation is computed
on the first **read** after that, which is by definition after whoever wrote.
Filing a claim invalidates the result again, and the turn always steps from the
rotation held when the tick opened — so reading twice, or reading before a module
has asked, cannot advance the turn twice or land on a different answer.

**Read and request in any order you like.** The only call an adapter places
deliberately is `apply()`, and placing it badly costs a tick of latency on that
one call and nothing else. `beginTick()` is wired to `TickEvent` automatically; an
adapter that does not post ticks calls it from its game loop, the same way it
would `runPendingSync()`.

### Letting go

When the last claim expires the rotation eases back to where the player is
actually looking, at `setReleaseStep(...)` degrees a tick, rather than snapping.

In `CLIENT` mode that costs nothing — the camera is already where the service left
it, so there is nothing to walk back and the service goes idle immediately. In
`SILENT` mode it is what stops the server-side head teleporting when a module
stops aiming.

### The sink

Two methods, and the only part of any of this that needs the game:

```java
public final class PlayerRotationSink implements RotationSink {

    public Vec2 getRotation() {
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? Vec2.ZERO : Vec2.rotation(player.rotationYaw, player.rotationPitch);
    }

    public void apply(Vec2 rotation, RotationMode mode) {
        if (mode == RotationMode.CLIENT) {
            mc.thePlayer.rotationYaw = rotation.getYaw();
            mc.thePlayer.rotationPitch = rotation.getPitch();
        } else {
            pendingPacketRotation = rotation;         // written into the movement packet
        }
    }
}
```

Core decides *what* the rotation is — arbitration, stepping, clamping, easing —
and the sink decides *how* it is written. `CLIENT` and `SILENT` are a tag Core
attaches and the sink honours; Core does not know the difference.

It lives in `dev.px.core.movement.rotation`, keeping its seam local the same way
`PathSpace` and `ShaderBackend` do.

Optionally, `setSensitivity(slider)` rounds resolved rotations to the mouse grid
that sensitivity can actually produce (`RotationMath.snapToSensitivity`). Off
until the adapter supplies it, because Core cannot read the game's options.

### Movement correction

Turning the head is only half the problem. Minecraft derives motion from the key
input rotated by the player's yaw, so the moment a rotation reaches the movement
code the player walks off at an angle — into the wall, or off the edge.

```java
float applied = Core.rotations().getRotation().getYaw();
MovementCorrection.Input fixed =
        MovementCorrection.correct(cameraYaw, moveForward, moveStrafing, applied);
```

It solves for the keys that, at the new yaw, produce the motion the old yaw and
the player's real keys would have.

**Whether you need it depends on your sink.** A `SILENT` implementation that only
writes the outgoing packet leaves the game's own yaw alone, so motion was never
wrong. One that sets the yaw field around the movement update does need it. Core
supplies the arithmetic and stays out of the decision.

There are two modes because vanilla input is quantised — forward and strafe are
each −1, 0 or +1, which is eight directions and no more:

| | Accuracy | Looks like |
|---|---|---|
| `STRICT` (default) | up to 22.5° off | values a keyboard actually produces |
| `EXACT` | exact | fractional input no vanilla client sends |

No right answer, so it is an argument. `STRICT` is the default because a small
drift is a better failure than a property that cannot be explained.

### Simulation

`MovementMath.predict` says of itself that it is *"a trajectory, not a
simulation"* — it carries motion forward and walks straight through the floor.
`Simulation` is the version that collides.

```java
Core.simulation().setCollisionSpace(new WorldCollisionSpace());   // one method

MotionState now = MotionState.of(position, velocity, onGround);
MotionState landing = Core.simulation().landing(now, held, 40);
List<MotionState> path = Core.simulation().trace(now, held, 20);  // to draw it
```

The order of operations is reproduced from the game rather than invented, because
a plausible reordering gives answers that are *almost* right:

1. friction from the block underfoot — decided **before** moving, applied
   **after**, which is why stepping off ice slides one more tick
2. jump, plus the horizontal kick a sprint jump gets
3. input becomes acceleration, scaled against the cube of friction on the ground
   and flat in the air
4. move, sweeping **Y, then X, then Z**, zeroing the velocity on each blocked axis
5. step up, if the horizontal move was blocked from the ground
6. gravity and drag on the vertical, friction on the horizontal

The axis order is the part everyone gets wrong. All three at once lets a player
slide along walls they should have stopped against; X and Z before Y lets a
falling one clip the lip of a block they should have landed on.

**Modelled:** walking, sprinting, sneaking, jumping and sprint-jumping, gravity,
air drag, ground friction with per-block slipperiness, axis-separated collision,
stepping up ledges.

**Not modelled:** water and lava, ladders, cobwebs, slime, elytra, riding,
knockback, levitation, and the sneak clamp at an edge. Potion effects are not
modelled but they compose — hand in a `PhysicsProfile` already adjusted for them.

This is a faithful reproduction of the common case, not a bit-exact
reimplementation. Treat a twenty-tick prediction as a good estimate, read the
exclusion list before trusting it somewhere unusual, and gate on `isReliable()`
so the model tells you itself when it has stopped applying.

#### The oracle

One method, and it returns boxes rather than a yes or no — a sweep needs to know
*how far* it can travel, and that is a distance, not a flag:

```java
CollisionSpace world = region -> {
    List<Box> boxes = new ArrayList<>();
    forEachBlockIn(region, pos -> { if (isSolid(pos)) boxes.add(collisionBoxOf(pos)); });
    return boxes;
};
```

Called **once per simulated tick**, over the whole region the hitbox could reach,
so one call serves all three sweeps and the step retry. `slipperinessAt` is an
optional second method with a sensible default, for clients that care about ice.

#### How accurate it is

Measured by the test suite against the figures Minecraft is measured at, not
against our own constants:

| | Model | Minecraft | |
|---|---|---|---|
| Walking | 4.405 b/s | 4.317 | +2.05% |
| Sprinting | 5.727 b/s | 5.612 | +2.05% |
| Sneaking | 1.322 b/s | 1.295 | +2.05% |
| Jump height | 1.2522 blocks | 1.2522 | exact |
| First tick of a fall | −0.0784 | −0.0784 | exact |

**Vertical is exact** — those numbers fall straight out of gravity, drag and the
jump constant applied in the right order, and nothing was tuned to produce them.

**Horizontal is uniformly 2% fast.** Uniform across all three, so it is one
systematic offset rather than noise. Core reproduces the game's formula with the
game's documented constants and that is where it lands; closing the last two
percent is what calibration below is for, because the honest way to get it is to
measure your version rather than have us guess harder.

> One trap worth knowing, because it cost this code a real bug. `walkSpeed`
> (0.216) is a **top speed** — what a readout shows and what `MovementMath`
> returns. `moveSpeedAttribute` (0.1) is the game's movement-speed attribute,
> which is what the acceleration formula takes. The game calls both of them "walk
> speed" in different places. Feeding the first into the formula that wants the
> second makes a simulated player travel two and a half times too fast, and a test
> that asserts against `walkSpeed` will not notice.

#### The biggest lever is yours, not ours

**Collision shape fidelity matters more for accuracy than any constant we ship.**

The oracle hands back boxes, so non-cube blocks are fully supported — but only if
you bother to return their real shapes. An adapter that answers `Box.block(x,y,z)`
for everything models a slab as a full cube, a fence as waist-high instead of
1.5 blocks, a carpet as a block, and every stair as a solid step. A prediction
walks the player up things they would bump into and through things they would
stand on, and no amount of tuning `gravity` will fix it.

Returning the game's own collision shape is usually one call:

```java
CollisionSpace world = region -> {
    List<Box> boxes = new ArrayList<>();
    forEachBlockIn(region, pos -> {
        AxisAlignedBB shape = world.getBlockState(pos).getBlock()
                .getCollisionBoundingBox(world, pos, state);       // the real one
        if (shape != null) {
            boxes.add(Box.of(shape.minX, shape.minY, shape.minZ,
                             shape.maxX, shape.maxY, shape.maxZ));
        }
    });
    return boxes;
};
```

If you do one thing to make prediction accurate on your version, do this one.

#### Knowing when to stop trusting it

The exclusion list above is not the danger — a caller cannot *see* the exclusion
list at runtime, so a prediction goes from good to confidently wrong with nothing
marking the transition. `DriftMonitor` marks it.

```java
// once a tick, with the input that was held over the tick that just elapsed
Core.simulation().observe(currentState, heldInput);

if (Core.simulation().isReliable()) {
    MotionState landing = Core.simulation().landing(currentState, heldInput, 40);
}
```

It simulates one step from the state it was last given and compares that to where
the player actually ended up. While the answer is close the model is describing
the game; the moment someone steps into water or deploys an elytra the error jumps
and `isReliable()` goes false. That turns every unmodelled branch from a silent
wrong answer into a detectable one — on any version, without us having to
enumerate what changed.

Errors are tracked per axis, because the two fail for different reasons and
knowing which moved is most of the diagnosis: vertical drift points at gravity,
drag or a collision shape; horizontal drift points at friction, the speed
attribute, or a branch that is not modelled. Teleports are recognised and dropped
rather than folded into the average.

#### Calibrating against your version

`PhysicsCalibration` is the offline half — a development tool, not something a
shipped client runs. The adapter records what actually happened over a few hundred
ticks, it replays each transition, and the difference is a number:

```java
calibration.record(before, heldInput, after);          // one per tick

System.out.println(calibration.check(PhysicsProfile.vanilla(), world).describe());
//  120 samples: mean 0.004182 b/t (h 0.004182, v 0.000000), worst 0.004212

PhysicsProfile base = PhysicsProfile.vanilla();
System.out.println(calibration.tune(base::withMoveSpeedAttribute, 0.08, 0.12, 41, world));
//  best 0.098000 -> 120 samples: mean 0.000003 b/t ...
```

`tune` is a plain linear sweep over one field — one dimension, dominated by
replaying the samples, and it cannot converge on a local minimum that is not
there. Narrow the range and call it again to refine. The suite proves it works by
planting an answer: it generates samples under a profile it never reveals and
checks the scan finds its way back to that constant.

**Sample quality is everything.** Record from flat ground with no water, no
ladders and no lag spikes, because a sample taken during an unmodelled branch is
noise the scan will happily fit a constant to. `DriftMonitor` is how you find a
clean stretch to record.

#### Tracking, which is a different question

"Where will *I* be" and "where will *they* be" look like one problem and are not,
so they are answered by different mechanisms:

```java
// every tick, for anything worth knowing about -- the key is opaque, so Core
// still never learns what an entity is
Core.simulation().record(other.getEntityId(), position, other.onGround);

Vec3 soon = Core.simulation().predict(id, 2);
```

Your own movement is deterministic: the input is known, so simulate it. Another
player's is a guess — nobody tells the client their intentions, so all there is to
go on is where they have been. `predict` extends their **measured** velocity in a
straight line, which is worth trusting for a tick or two of lag compensation and
not much further. `coast` sits between the two: it falls and collides, but still
assumes they do nothing of their own accord.

Velocity is measured from position deltas, not read from a motion field, because
for anything but the local player there is no motion field to read. Which also
means: **record on the tick, not on the frame.** Positions read during rendering
are interpolated — smoothed and slightly behind — and differencing them gives a
velocity that is both damped and late.

Every track is a bounded ring, so memory is `tracked × historyTicks` and does not
grow over a session, and tracks nobody updates are dropped once a tick.

### Where the physics numbers live

Related change: gravity, drag, friction, walk speed and the effect multipliers
used to be `public static final` on `MovementMath`. They are not universal truths
— they are one version's values — so a client on 1.21 silently got 1.8's answers
with no way to say otherwise short of forking the class. They are a
`PhysicsProfile` now:

```java
double speed = MovementMath.walkSpeed(2, 0);                   // the default profile
double ice   = MovementMath.friction(slippery, motion, 1d);    // an explicit one

MovementMath.setProfile(PhysicsProfile.vanilla().withWalkSpeed(0.2806d));   // client-wide
```

`walkSpeed` and `moveSpeedAttribute` are the pair to be careful with: the first is
a speed in blocks per tick, the second is the attribute the acceleration formula
multiplies. See the accuracy notes above.

Every method that needs them has both forms. `TICKS_PER_SECOND` stays a constant,
because it is not physics — it is how the protocol defines a tick, and a server
running slower is lag rather than a different rule.

Sprinting, sneaking, water, cobwebs and per-block slipperiness are deliberately
**not** in the profile. They are branches in the game's movement code, not
multipliers, and the honest place for them is a simulation that models the
branches — which is why `friction` takes slipperiness as an argument.

### Timeline recording

`Core.timeline()` records what the client and server said to each other, and
what the client was doing at the time, into one exactly-ordered timeline
between two points you choose.

```java
Core.network().setDescriber(new MyPacketDescriber());    // once, from the adapter; see §11
Core.timeline().putMetadata("gameVersion", "1.8.9");

Core.timeline().begin("velocity test");
// ... play ...
Core.timeline().mark("hit");
// ... play ...
Timeline timeline = Core.timeline().end();

try (Writer out = Files.newBufferedWriter(path)) {
    TimelineJson.write(timeline, out);                    // JSON Lines, one entry per line
}
```

Every entry carries a **seq** (global, gapless, the one true order), a monotonic
**nanos** since `begin`, the client **tick** it happened in, and its
**`EntryType`**: tick start and end, packet, motion before and after the client
reports it, correction, world change, mark. Packets add direction, phase, the
describer's type name and the name of its `PacketKind`, and whatever position, velocity,
rotation, ground state and extra fields the describer pulled out. Motion
entries add position, velocity, ground, the held `MovementInput`, sprint and
sneak state, and rotation with last tick's rotation.

It is built from events rather than hooks of its own, and adapters post them
for their modules anyway:

| `Core.hooks()` call | When | Gives the timeline |
|---|---|---|
| `tickStart()` / `tickEnd()` | already called | tick boundaries |
| `packetSent(p)` | before an outbound packet is written | outbound traffic |
| `packetReceived(p)` | network thread, as an inbound packet is decoded | true arrival order |
| `packetApplied(p)` | game thread, as that packet's handler runs | the tick it took effect |
| `motionPre(...)` / `motionPost(...)` | around the client's movement packet | what the client believed |
| `worldLoaded` / `worldUnloaded` | already called | world load and unload |

Post `received` and `applied` with the **same packet instance**: that is what
links them, so `timeline.linked(applied)` finds the arrival and the difference
in `nanos` is how long it sat in the queue. An applied packet the describer
marked `asCorrection()` — whatever your version's teleports, knockback and
pushes are — is followed immediately by a `CORRECTION` entry pointing at it,
whose `clientSeq` field is the client's last motion entry, the state the
server just overruled. Core decides nothing about which packets those are.

The queries are about how entries relate, not about single entries:
`responsesTo(sent, Packets.TELEPORT, 5)` — your own kind — finds what arrived within five ticks
of an outbound packet, going by timing; `correlated(entry)` pairs a
transaction, keep-alive or teleport confirm with its reply exactly, by the
correlation key the describer gave both; `between("jump", "landed")` slices
between two marks.

**Ordering holds across threads.** Handlers run on the posting thread, so
packets arrive from the network thread while ticks arrive from the game thread.
Seq, time and tick are all assigned under one lock in the same step that
appends the entry, so seq never ties and time never runs backwards along it.
Tick boundaries listen at the extremes of the priority range, so a packet a
module sends from its own tick handler lands inside that tick.

**The describer** is the one piece of version knowledge it needs: one method
from packet to `PacketDescription`, installed on `Core.network()` and shared
with the network services in §11. Without one, packets are recorded under
their class name as `OTHER`, which in an obfuscated build means `a`. A describer
that throws costs that packet its description, not its entry, and is logged
once.

**Idle is free.** The recorder subscribes to nothing and allocates nothing until
`begin`, and `end` gives both back. It reads nothing from `RotationService` or
`SimulationService`, and neither knows it exists. A recording keeps at most
`setCapacity(n)` entries (262,144 by default), dropping the oldest and counting
them; `setFilter` narrows which packets are kept.

### Not using any of this

Install no `RotationSink` and that service is inert: claims are still accepted
and arbitrated, nothing is ever applied, `isActive()` stays false and `apply()`
returns false. A client that keeps writing rotations itself loses nothing, and a
module that files a claim into a client with no sink is harmless rather than
broken.

Install no `CollisionSpace` and the tracker still works in full — it needs no
world at all — while simulation falls back to `CollisionSpace.empty()` and
answers "where would this go if nothing were in the way". That is a useful
question, so nothing warns about it.

`MovementCorrection` is static and needs nothing installed, and nothing outside
`dev.px.core.movement` references any of it.

Never call `Core.timeline().begin(...)` and the recorder never subscribes to
anything. Install no `PacketDescriber` and a recording still works, with
packets named by class.

---

## 11. Network

Four services read the connection: **`Core.tps()`** (the server's tick rate),
**`Core.lag()`** (ping, packet rates, the server going silent),
**`Core.server()`** (address, brand, software, proxy, channels) and
**`Core.anticheat()`** (which anticheat the server probably runs). They share
one way in, `Core.network()`, which is also where the timeline recorder in §10
gets its packets.

The services do the work — smoothing, spike detection, brand matching, ranking
evidence — and **know nothing about any game**. No packet names, no channel
names, no tick rate, no server software, no anticheat. All of that is plugged
in by your client, so a version that renames, splits or removes a packet costs a
line in your describer and nothing in Core.

```java
Core.tps().getTps();                               // 19.8
Core.lag().getPing();                              // 48
Core.lag().isLagging();                            // true while the server is silent
Core.server().isOn("hypixel.net");                 // true on mc.hypixel.net
Core.server().getSoftware();                       // Paper — if you registered it
Core.anticheat().getPrimary();                     // Optional[MyAC (KNOWN: ...)] — if you registered it
```

### Wiring it up

1. **Hook the traffic.** `Core.hooks().packetSent(p)` before an outbound packet
   is written, `packetReceived(p)` as an inbound one is decoded. `packetApplied`
   is optional here; a packet given only that counts as arriving then.
2. **Declare your packet kinds**, as an enum, the same way you declare module
   categories. Core ships none but `PacketKind.OTHER`. Kinds are labels for
   you — filtering, timeline queries — and no service reads them.
3. **Say what the packets are**, once, with a describer. Each description gets
   your kind, plus a **role** for each thing a service should read from it:

```java
public enum Packets implements PacketKind { TIME, TRANSACTION, KEEP_ALIVE, PAYLOAD, TELEPORT, VELOCITY }

// 1.8.9 (MCP names)
Core.network().setDescriber(PacketDescriber.byClass()
        .on(S03PacketTimeUpdate.class, p -> PacketDescription.of("S03PacketTimeUpdate", Packets.TIME)
                .withWorldAge(p.getTotalWorldTime()))                          // TPS
        .on(S32PacketConfirmTransaction.class, p -> PacketDescription.of("S32PacketConfirmTransaction", Packets.TRANSACTION)
                .withTransaction(p.getActionNumber()))                         // anticheat
        .on(S00PacketKeepAlive.class, p -> PacketDescription.of("S00PacketKeepAlive", Packets.KEEP_ALIVE)
                .withCorrelationKey("keepalive:" + p.func_149134_c()))          // no role: just recorded
        .on(S3FPacketCustomPayload.class, p -> "MC|Brand".equals(p.getChannelName())
                // read a copy: the game reads the same buffer after this returns
                ? PacketDescription.of("S3FPacketCustomPayload", Packets.PAYLOAD).withBrand(
                        new PacketBuffer(p.getBufferData().copy()).readStringFromBuffer(32767))   // server
                : PacketDescription.of("S3FPacketCustomPayload", Packets.PAYLOAD))
        .on(S08PacketPlayerPosLook.class, p -> PacketDescription.of("S08PacketPlayerPosLook", Packets.TELEPORT)
                .asCorrection())                                               // timeline
        .on(S12PacketEntityVelocity.class, p -> PacketDescription.of("S12PacketEntityVelocity", Packets.VELOCITY))
        .build());
```

| Role | Read by | Give it to |
|---|---|---|
| `withWorldAge(ticks)` | `Core.tps()` | the packet carrying the server's clock |
| `withTransaction(id)` | `Core.anticheat()` | packets the server sends for the client to answer, to time it |
| `withLatency(ms)` | `Core.lag()` | whatever reports the server's measured ping for the local player |
| `withBrand(brand)` / `withChannels(list)` | `Core.server()` | whatever carries the brand and registered channels |
| `asCorrection()` | `Core.timeline()` | packets that overrule the client's motion |

A version with no such packet never gives the role; a version where two packets
play it gives it to both. The channel names in the example are the adapter's —
Core has none. `PacketDescriber.byClass()` builds a describer from one rule per
packet class — a hash lookup per packet instead of an `instanceof` chain, with
subclasses falling back to their parent's rule and anything unmatched to the
class name. A plain lambda describer works just as well. Name packets with
string literals: in an obfuscated build the class is called `a`.

Where the version already parses something, reporting it is simpler than
describing the packet it came in:

```java
// from a tick handler, about once a second
Core.server().reportBrand(mc.thePlayer.getClientBrand());          // 1.8.9

NetworkPlayerInfo self = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
if (self != null) Core.lag().reportPing(self.getResponseTime());    // 1.8.9
```

Both are safe to call every tick: an unchanged value changes nothing.

What each service needs, beyond the `PacketEvent` posts and the `WorldEvent`
your adapter already sends:

| Service | Needs a role | Or reported | Also yours | Works without? |
|---|---|---|---|---|
| `Core.lag()` | `withLatency`, for ping | `reportPing(ms)` | the spike threshold, if not 1.5s | Yes: rates and spikes need arrivals only |
| `Core.tps()` | `withWorldAge` | — | optionally `setTargetTps` | NaN, with `hasEstimate()` false |
| `Core.server()` | `withBrand`, `withChannels` | `reportBrand`, `reportChannels` | `registerSoftware(...)` | Yes: address, singleplayer and `isOn` come from `WorldEvent` |
| `Core.anticheat()` | `withTransaction`, for timing | — | `register(signature)` — none ship | No: with no signatures, nothing is detected |

### TPS

A server that tells the client its world age every so often is saying how many
ticks passed (the age moved on by that much) and, by when the update arrives,
how long they took. `getTps()` is the ratio of the sums over the last ten
updates. Summing rather than averaging each update's rate is what keeps network
bunching out of it: an update held back 0.9s and the one after arriving 0.1s
later read as wildly different rates; summed, they are the ticks that passed in
the time that passed.

**No rate is assumed.** Not what the server should run at, nor how often it
sends its clock — both are measured. A frozen server sends no update to report
that it froze, so the estimate is also bounded by the update now overdue:
once it is later than updates have been arriving, the ticks it would have
carried are spread over the time actually waited. Measurements are forgotten on
leaving or changing server, and a world age that goes backwards, or leaps by far
more than updates have been carrying, re-baselines rather than reporting a spike.

`setTargetTps(rate)` is yours to give, and optional: it caps the estimate, so
bunching never reads as a server running fast, and it is what `getTps()` reports
before the first measurement. Without one, that is NaN. Call it again whenever
your server announces a different rate. `TpsTracker` is the whole algorithm with
no bus or service; feed it from anywhere with `update(nanos, worldAge)`.

### Lag

Two different things get called lag. A **slow** server still talks, less
often: that is TPS. A **silent** one sends nothing at all, and everything the
client does meanwhile is judged late. `Core.lag()` watches for the second: once
nothing has arrived for `getSpikeThresholdMillis()` (1.5s unless you set it —
pick something longer than the longest gap your game's server leaves on a
healthy connection), `isLagging()` turns true and a `LagSpikeEvent` is posted,
then another with the whole duration when traffic resumes. Both are posted on
the game thread, and never outside a world.

```java
@Subscribe
private void onLag(LagSpikeEvent event) {
    if (event.isStarted()) Core.notifications().warn("Lag", "Server not responding");
}
```

`getInboundRate()` and `getOutboundRate()` are packets a second over the last
second. `getPing()` is the server's own measurement: a client cannot time a
round trip the server starts. `getAveragePing()` and `getPingJitter()` are over
the last ten reports.

### Server

```java
Core.server().registerSoftware(                    // yours: Core recognises none
        ServerSoftware.of("Purpur"),               // forks before what they forked
        ServerSoftware.of("Paper"),
        ServerSoftware.of("Forge", "forge", "fml"),
        ServerSoftware.proxy("Velocity"),
        ServerSoftware.proxy("BungeeCord", "bungeecord", "waterfall"));

Core.server().getInfo();                   // ServerInfo(play.example.net:25577, Paper (Velocity))
Core.server().getBrand().getSoftware();    // Paper — the server behind any proxy
Core.server().getBrand().getProxy();       // Velocity (proxy), or null
Core.server().getBrand().getRaw();         // always: exactly what the server sent
Core.server().hasChannel("floodgate:skin");
```

Server software comes and goes faster than any library, so a built-in list would
go stale and name the wrong thing with confidence. You register what you care
about, with the words its brand contains. The whole brand is matched against
every registration by **whole word**, ignoring case, so `"Newspaper"` is not
Paper — and a proxy and the server behind it are found independently, which is
why no proxy's brand format needs knowing: `"BungeeCord (git:...) <- Paper"` and
`"Paper (Velocity)"` both name one of each. Where a brand names two, the one
registered first wins. Registering later reads a brand already received again;
with nothing registered, the software is `ServerSoftware.UNKNOWN` and the raw
brand is still there.

The address is normalised (lower case, no trailing dot, port split off, IPv6 in
brackets understood), with **no default port** — `hasPort()` says whether the
address named one — and `isOn("hypixel.net")` matches the domain and its
subdomains, never a lookalike.

A brand that arrives before the world does — some games send it while still
connecting — is held and applied when the world loads, which is why what a
server said is forgotten on leaving rather than on joining. Changes are
announced with one `ServerChangeEvent` per tick on the game thread, with
`isJoin()`, `isLeave()` and `isBrandChanged()` against what was last announced:

```java
@Subscribe
private void onServer(ServerChangeEvent event) {
    if (event.isJoin() && event.getCurrent().isOn("hypixel.net")) {
        Core.config().load("hypixel");
    }
}
```

### Anticheat

Detection is a guess, and says so. Every `Detection` carries a `Confidence`
(`POSSIBLE`, `LIKELY`, `KNOWN`) and a human-readable reason. Nothing a server
sends proves which anticheat it runs, so use this to pick sensible defaults,
not to bet an account on.

Each registered `AntiCheatSignature` looks at `ServerEvidence` — the
`ServerInfo` above, plus the `TransactionPattern` of the packets given
`withTransaction`: how fast they come, and whether their ids count up or down
and on which side of zero — and returns a `Detection` or null. They run every
`setEvaluateEveryTicks(n)` ticks (20 unless set) and whenever the server
changes; a changed result posts an `AntiCheatChangeEvent`.

**Core registers no signatures.** A signature that names an anticheat, or a
server's anticheat, is a fact about one game's ecosystem that goes stale, and a
stale one reports the wrong name with confidence. So every signature is yours,
built from these or written as a lambda:

```java
Core.anticheat().register(AntiCheatSignature.onServer("example.net", "MyAC"));
Core.anticheat().register(AntiCheatSignature.brandContains("myac", "MyAC", Confidence.KNOWN));
Core.anticheat().register(AntiCheatSignature.channel("myac:main", "MyAC", Confidence.KNOWN));
Core.anticheat().register(AntiCheatSignatures.transactionBased(2, 10));   // your rates, per second
Core.anticheat().register(evidence -> {
    TransactionPattern p = evidence.getTransactions();
    return p.getOrder() == TransactionPattern.Order.DECREMENTING && p.getHighestId() < -1000
            ? Detection.of("MyAC", Confidence.LIKELY, "counts down from -1000")
            : null;
});
```

`transactionBased(possibleRate, likelyRate)` reports a generic
`"Transaction-based anticheat"` once answer-me packets arrive faster than your
game's own traffic explains — the two rates are yours, because what is normal
depends on the game. Detections rank by confidence, and at equal confidence a
named one ranks above the generic one, so a signature you add for the same
evidence is the one `getPrimary()` reports. Give `withTransaction` only to
packets the server sends to time the client: one it sends on its own fixed
schedule, such as a keep-alive on most versions, would make every server look
protected.

### Hearing the traffic yourself

`Core.network().addListener(PacketListener)` gets each packet once, already
described: inbound on arrival (including ones a handler cancelled, since the
server still sent them), outbound only if actually sent. It is the same stream
the services read, for a module that cares about `packet.getKind().is(Packets.VELOCITY)`
and not about packet classes.

### Threading

Inbound packets arrive on the network thread, so the services record under
small locks and every getter is safe from any thread. Every event they post —
`ServerChangeEvent`, `LagSpikeEvent`, `AntiCheatChangeEvent` — is posted from
the tick, on the game thread, so a handler may touch the game.

### Not using any of this

Post no `PacketEvent` and all four services sit idle: nothing is described,
nothing is posted, and every getter returns its "unknown" value (`-1` ping,
NaN TPS with `hasEstimate()` false, `ServerInfo.DISCONNECTED`, no
detections). Install no describer and `Core.lag()` still works in full, since
it needs arrivals and not meanings. `TpsTracker`, `TransactionTracker` and
`util.time.RateMeter` are plain classes with no bus or service behind them.

---

## 12. Entities and targeting

Two layers. **`Core.entities()`** is the world: read once a tick through your
adapter and handed to **trackers** — one per kind of thing you care about, typed
by the game's own class. **`Core.targets()`** picks from a tracker: selectors
that filter and rank it, and locks that hold a choice across ticks. ESP, radar
and nametags can use the first without the second.

### A tracker per kind of thing, in the game's own types

Core has no list of entity types, no idea what "dead" or "teammate" means, and
no numbers taken from any game — no reach, no hitbox size, no eye height. So it
does not describe entities at all. You write a tracker for each kind you want
followed, typed by the game's class, and it hands back the game's own object:

```java
public final class CrystalTracker extends EntityTracker<EntityEnderCrystal> {
    public CrystalTracker() { super(EntityEnderCrystal.class); }
}

public final class LivingTracker extends EntityTracker<EntityLivingBase> {
    public LivingTracker() { super(EntityLivingBase.class); }

    @Override protected boolean accepts(EntityLivingBase e) {        // who counts, asked every tick
        return e.deathTime == 0 && !Core.social().isFriend(e.getName());
    }
}

Core.entities().registerAll(new LivingTracker(), new CrystalTracker());

Tracked<EntityEnderCrystal> crystal = Core.entities().get(CrystalTracker.class).nearest(6);
crystal.get().getEntityId();                       // the game's object, already typed
```

The class argument is there because Java forgets `E` at runtime. It can be an
interface (`IMob`), and subclasses come along: `EntityTracker<EntityLivingBase>`
holds players and monsters alike. Trackers overlap freely — Core reads the
world **once** a tick, works out per class which trackers want it (one map
lookup, cached), reads each wanted entity's geometry once, and hands it to all
of them. An entity no tracker wants is never read.

| You write | For |
|---|---|
| `extends EntityTracker<E>` | a tracker other code looks up by class, `Core.entities().get(CrystalTracker.class)` |
| `accepts(E)` | which entities of the type to keep; one that stops passing is let go |
| `onTracked` / `onUntracked` | reacting as entities arrive and leave |
| `EntityTracker.of(type, predicate)` | a tracker one module owns, with no class of its own |

### Wiring it up

The adapter's half is one class that lists the world and says where things are:

```java
public final class LegacyEntities implements EntitySource<Entity> {
    public Iterable<Entity> entities() { return mc.theWorld == null ? null : mc.theWorld.loadedEntityList; }
    public Entity self()               { return mc.thePlayer; }

    public double x(Entity e)          { return e.posX; }
    public double y(Entity e)          { return e.posY; }
    public double z(Entity e)          { return e.posZ; }
    public double width(Entity e)      { return e.width; }
    public double height(Entity e)     { return e.height; }
    public double eyeHeight(Entity e)  { return e.getEyeHeight(); }   // optional: 0 if left out
    public float yaw(Entity e)         { return e.rotationYaw; }      // optional
    public float pitch(Entity e)       { return e.rotationPitch; }    // optional
}

Core.entities().setSource(new LegacyEntities());
```

Core refreshes every tracker at the start of every tick, ahead of every module,
so all of them read the same world. **Nothing you leave out gets a default from
a game**: no eye height puts the eyes at the position, no facing is yaw 0. A
source that throws for one entity skips it for the tick and is logged once; one
that throws while listing keeps the last tick's world. `setAutoRefresh(false)`
and `refresh()` let you pick a different point in your version's loop.

The local player is in no tracker. It is `Core.entities().getSelf()`, and it is
where targeting looks from.

### Between ticks

A tick is late for anything that reacts to a packet. A crystal aura wants the
crystal the moment its spawn packet arrives, not up to 50ms later, and wants it
gone the moment it is destroyed so it is not hit twice:

```java
// in your packet handling, on the game thread
crystals.track(spawnedCrystal);      // tracked now; queries find it straight away
crystals.forget(destroyedCrystal);   // let go now
```

Tracking something already tracked returns it unchanged, so its velocity stays a
per-tick one.

### The snapshot

A `Tracked<E>` is the game's object, from `get()`, plus what Core can measure
itself: a position, a box, a facing, the eye height, `getVelocityX/Y/Z` from the
last tick's move, `getTicksTracked()`, `extrapolate(n)`.

It is **one object per entity per tracker for as long as the tracker keeps
it**, updated in place each tick and never recycled. Holding one across ticks is
safe: it keeps moving with the entity, and `isTracked()` turns false when the
tracker lets go — because the entity left the world or stopped being accepted.
Two trackers holding one entity hold two snapshots of it.

The position is the **tick's**, read before the world moves. It is what modules
should decide with. To draw smoothly between ticks, use the game's own
interpolated position from `get()`.

Reads are primitives first. `getX()`, `getMinX()` and
`squaredDistanceToBox(x, y, z)` read fields and allocate nothing;
`getPosition()`, `getEyePosition()` and `getBox()` build their value once per
tick on first ask.

### Selectors

A `TargetSelector<E>` is built once, kept in a field and run as often as needed.
**It matches exactly what you tell it to.** The default is every entity in its
tracker, any range, any angle, nearest first.

```java
private final TargetSelector<EntityLivingBase> enemies = TargetSelector.from(LivingTracker.class)
        .range(reach::getDouble)                       // your setting, read on every query
        .fov(fov::getDouble)
        .where(e -> e.hurtTime == 0)                   // the game's object, already typed
        .sort(TargetSort.by(EntityLivingBase::getHealth))
        .build();
```

`from(LivingTracker.class)` finds the tracker on each query, so a module can
build its selectors in field initialisers before any tracker is registered; until
one is, the selector finds nothing. `from(tracker)` takes one directly.

| Builder call | Keeps |
|---|---|
| `range(n)` / `range(supplier)` | boxes within reach of the origin, measured to the nearest point |
| `fov(degrees)` | boxes whose centre is inside a cone around the local player's view |
| `minTicksTracked(n)` / `maxTicksTracked(n)` | entities tracked for long enough, or recently enough |
| `where(predicate)` | entities whose game object passes; runs after the built-ins |
| `whereTracked(predicate)` | entities whose measurements pass — velocity, box, ticks tracked |

Filters run cheapest first: ticks tracked, range (the tracker's grid means far
entities are never visited), field of view (a dot product against
`cos(fov / 2)`, no inverse trigonometry), then your predicates. `toBuilder()`
makes a variant. Friends, teams and death are questions about your game, so they
are your tracker's `accepts` or your `where`.

`TargetSort` ranks by a score per candidate, lowest first, so choosing the best
is one pass and not a sort. Core ships only what means the same in any world —
`DISTANCE`, `ANGLE`, `NEWEST`, `OLDEST` — and everything else reads the game's
object: `TargetSort.by(EntityLivingBase::getHealth)`, or a lambda over the
`Tracked<E>` and the query's `TargetContext`. Any of them can be `reversed()`; a
key that returns `NaN` ranks last either way. Ties go to the nearer box.

```java
Core.targets().best(enemies);                       // or null
Core.targets().all(enemies, 3);                     // three best, in order
Core.targets().count(enemies);
Core.targets().forEach(enemies, e -> ...);          // unsorted, allocates nothing
Core.targets().best(crystals, enemy.getPosition()); // measured from another point
Core.targets().accepts(enemies, tracked);           // O(1), one entity
```

Selectors nest: a `where` predicate may run a query of its own ("players with
two or more monsters beside them"), and each query gets its own working state.

### Locks

`Core.targets().lock(selector)` returns a `TargetLock<E>`. `update()` once a
tick keeps the held target for as long as it passes the selector (an O(1)
check) and searches only when it stops, so a module does not flick between two
entities that trade places at the top of the ranking. `hasChanged()` says when
it switched, `lockOn(tracked)` holds one the player chose, `setSticky(false)`
takes the best every tick, `release()` lets go.

### Geometry

Range is measured to the **nearest point of the box**, because that is what
reaching something means in any world with boxes: an entity whose position is
four blocks away and whose box is two wide is three away. `Box` has
`closestPoint`, `distanceTo` and `inset`, and `Tracked` has `distanceToBox`,
`closestPoint` and `aimPoint(from, inset)` — the nearest point of the box shrunk
by `inset`, so a ray aimed there lands inside instead of grazing an edge. How
much reach and how much inset are both yours: Core has no default for either.

```java
Tracked<?> self = Core.entities().getSelf();
if (target.distanceToBox(self.getEyePosition()) <= reach.getDouble()) {
    Vec3 aim = target.aimPoint(self.getEyePosition(), 0.05);
    Core.rotations().request(this, self.getEyePosition().rotationTo(aim));
}
```

### Cost

With n entities in the world, n<sub>t</sub> in a tracker, k candidates a query
visits and m that pass:

| Operation | Cost |
|---|---|
| refresh, once a tick | O(n) class lookups; an entity is read once however many trackers want it, and not at all if none do |
| per tracker | O(n<sub>t</sub>), nothing allocated for entities already tracked |
| `get`, `contains`, `track`, `accepts`, a lock keeping its target | O(1) |
| range query over a small tracker (≤ `setScanLimit`, 32 unless changed) | O(n<sub>t</sub>), a plain scan |
| range query over a larger one | O(b + k) through the tracker's `SpatialGrid`, b buckets the radius covers |
| `best`, `count`, `forEach` | O(k), one pass, no allocation |
| `all` | O(k + m log m), over candidates pooled between calls |

A tracker's grid is built on the first range query of the tick that needs one,
in O(n<sub>t</sub>), and not at all on a tick with no such query. The grid
indexes positions, so the search is widened by the furthest any box reached from
its position that tick and every candidate is measured exactly. `setCellSize`
tunes it; near the radius you query most is best. The suite checks 450 random
queries against a brute-force scan, at two cell sizes.

Game thread only, like the tick it refreshes on.

### Not using any of this

Install no `EntitySource` or register no tracker and nothing is read:
`getSelf()` is null and every targeting query answers null, empty or zero.

---

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
| `movement.simulation` | `SimulationService` — `Simulation` (the real movement rules, axis-separated collision, step-up) over a one-method `CollisionSpace`, plus `MotionTracker` / `MotionTrack` (bounded history per opaque key), `DriftMonitor` (scores the model against the game every tick and says when to stop trusting it), `PhysicsCalibration` (offline: measure the gap, sweep a constant to close it) and the `MotionState` / `MovementInput` values they pass around |
| `movement.timeline` | `TimelineRecorder` — records packets, ticks and the player's movement between two points in time, in one exactly-ordered `Timeline` of `TimelineEntry`s, with queries for links, replies and correlation. Reads packets through the describer on `Core.network()`; `TimelineJson` reads and writes JSON Lines |
| `network` | `NetworkService` — where the adapter's packets come in: holds the one `PacketDescriber`, picks each packet's arrival, describes it once and hands it to every `PacketListener`. See §11 |
| `network.packet` | The packet vocabulary everything above shares: `PacketDescriber` (the one-method SPI your adapter writes), `PacketDescription` with the roles the services read (`withWorldAge`, `withTransaction`, `withLatency`, `withBrand`, `withChannels`, `asCorrection`), `PacketKind` (the interface your own kinds implement), `PacketFields`, and `ClassPacketDescriber` (a describer built from one rule per class) |
| `network.tps` | `TpsService` over `TpsTracker` — the server's tick rate from its world clock, measured with no rate assumed, smoothed, stall-aware, capped by a target only if you set one |
| `network.lag` | `LagService` — ping, packet rates, and `LagSpikeEvent` when the server goes silent |
| `network.server` | `ServerService` — address, `ServerBrand` (raw brand, software and proxy), the `ServerSoftware` you register, channels, `ServerChangeEvent` |
| `network.anticheat` | `AntiCheatService` — ranks `Detection`s from the `AntiCheatSignature`s you register (none ship) over `ServerEvidence`, including the `TransactionPattern` a `TransactionTracker` reads off packets given `withTransaction` |
| `entity` | `EntityService` — reads the world once a tick through the adapter's one `EntitySource` and routes each entity by class to the `EntityTracker<E>`s you register, typed by the game's own classes. Each tracker holds a `Tracked<E>` per entity (the game's object plus position, box, velocity, ticks tracked) and a `SpatialGrid` built only when queried. See §12 |
| `target` | `TargetService` — runs a `TargetSelector<E>` over one tracker (filters built once, run cheapest first, predicates on the game's own object) ranked by a `TargetSort`, from the player's eyes or any point; `TargetLock` holds a choice across ticks |
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

---

## 14. What your adapter must supply

1. **`Platform`** — data dir, screen metrics, cursor, chat, username, in-game flag
2. **`Render2D`** — your 2D library
3. **`Render3D`** — world drawing and projection
4. **`FontProvider`** — font loading for that backend
5. **Game hooks** — call `Core.hooks()` from your mixins. It is the whole
   list of moments Core needs to hear about; see *Game hooks* below.
6. **A per-frame `Core.hud().drawAll(...)`** from your render hook, and, if you
   want edit mode, a screen that routes input into `HudEditor` and draws
   `HudEditorView`. Elements describe themselves, so there is nothing else to
   write per element; see §6.
7. **A game screen, if you use the GUI** — one that calls `Core.gui().renderFrame()`
   and hands it mouse, key, scroll and character input, then `Core.gui().close()`
   when dismissed. Core neither draws it nor listens for it. Skip this entirely if
   you are writing your own interface; see *Not using any of this* in §7.

Nothing extra is needed for threading: Core drains the game-thread queue on
every tick, so calling the tick hooks (step 5) covers it. An adapter that does
not calls `Core.threads().runPendingSync()` from its game loop instead; see §8.

Optional: `RotationSink` if you want Core arbitrating rotations — two methods,
and modules stop fighting over the head; `CollisionSpace` if you want movement
simulation — one method, and the motion tracker works without it; `ShaderBackend` if you use `shader` —
one class of ordinary GL, and nothing else in Core notices whether it exists; `AuthProvider` (alt manager),
`PresenceProvider` / `MediaProvider`, a `PacketDescriber` on `Core.network()` with your own packet kinds plus `PacketEvent`
posts if you want TPS, ping, server and anticheat detection — and your server software and anticheat signatures, since
none ship — see §11 — an `EntitySource` plus trackers of the game's own
types if you want `Core.entities()` and targeting — see §12 — and `MotionUpdateEvent` posts on top if you want
timeline recordings — see §10, and `PathSpace` if you use `util.spatial` — one lambda saying which cells your agent
can occupy is enough to run `AStar` against your world.

Core runs headless without any of these. The test suite boots it with none
installed, which is how the seam stays honest.

### Game hooks

Core never hooks the game. Your mixins call one method on `Core.hooks()` at
each moment below, and it posts the event Core's services and your modules
listen for. If one is never called, whatever listens for it goes idle — so
Core tells you instead of failing silently.

```java
@Inject(method = "runTick", at = @At("HEAD"))   private void head(CallbackInfo ci) { Core.hooks().tickStart(); }
@Inject(method = "runTick", at = @At("RETURN")) private void tail(CallbackInfo ci) { Core.hooks().tickEnd(); }

@Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
private void send(Packet<?> packet, CallbackInfo ci) {
    if (Core.hooks().packetSent(packet)) ci.cancel();          // true when a handler cancelled it
}
```

| Call | When | Idle without it, in Core |
|---|---|---|
| `tickStart()` / `tickEnd()` | head and tail of every game tick | entities, rotations, simulation, lag spikes, server and anticheat events, timeline ticks, `threads().sync(...)` |
| `worldLoaded(address)` / `worldUnloaded()` | joining and leaving a world | server, TPS, lag, entities |
| `packetReceived(p)` / `packetSent(p)` | each inbound packet decoded / outbound packet written | TPS, lag, server, anticheat, timeline |
| `packetApplied(p)` | optional: as an inbound packet's handler runs, same instance | timeline queue times |
| `motionPre(...)` / `motionPost(...)` | around the client's movement report | timeline motion entries |
| `key(...)` / `mouse(...)` | every press and release | module keybinds |
| `chatSend(message)` | before the player's chat is sent | commands |
| `scroll`, `charTyped`, `chatReceived`, `screen`, `render2D`, `render3D` | as named | nothing in Core — your modules |

The cancellable ones return whether a handler cancelled them; the chat ones
return the event, since a handler may rewrite the message. `render2D` is for
your modules' drawing and does **not** draw the HUD — that is still
`Core.hud().drawAll()`, step 6. Posting the events yourself still works; Core
counts them however they arrive.

**It warns.** Ticks, the world, packets, motion and rendering fire the whole
time the player is in a world, so their absence can be seen: once
`Platform.isInGame()` has been true for five seconds (`setGracePeriodMillis`),
Core logs one warning per hook that something is listening for and that has
never fired, naming the call to make and everything idle without it:

```
No TickEvent after 5s in a world, so these are idle: Core, ServerService, EntityService, RotationService,
SimulationService, LagService, AntiCheatService, KillAura. Call Core.hooks().tickStart() and tickEnd(),
around every game tick from your adapter (or post TickEvent yourself).
```

"Needed" means something is listening right now — Core's services, or your
modules while enabled — so a hook nothing uses never warns, and the motion hook
only matters while a timeline is recording.

**It verifies.** Keys, mouse and chat fire only when the player acts, so their
absence proves nothing at runtime. In your development build, play for a few
seconds, press a key, click, send a chat message, then:

```java
Core.hooks().verify();                       // throws, listing every needed hook that never fired
Core.hooks().verify(Hook.KEY, Hook.MOUSE);   // or exactly these, needed or not
Core.hooks().report().forEach(System.out::println);
//  TICK         4211x    Core, ServerService, EntityService, ...
//  KEY          MISSING  InputService, KillAura
//  SCREEN       unused   -
```

Core never throws on its own: a hook can only be judged missing over time, and
a client that skips one should lose the features that need it, not crash.

---

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

---

## Not yet included

- **Adapter layer** — `Player`, `World`, packet wrappers. (Packets reach Core only as opaque objects in `PacketEvent`, read through the adapter's `PacketDescriber`; entities only as the type parameter of a client's `EntityTracker`, located through the adapter's `EntitySource`.)
