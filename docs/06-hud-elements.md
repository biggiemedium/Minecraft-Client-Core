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

`slot()` is for the `gui` module: it marks where a container's children go. In
a HUD element, which has no children, it takes no room and does nothing.

`paragraph(text, colour)` wraps between words to the width its column was given,
and at every `\n`. A HUD element sizes to its content, so give it a width
(`c.width(120f)`) for its paragraphs to wrap to; without one, each line stays
whole.

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

The box model lives in `dev.px.core.layout` and is shared with the
[`gui`](../gui/README.md) module, which uses the same `Content`, `Bounds` and
`Shape`. It depends on neither, so the HUD and the GUI stay decoupled from each
other.

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
./gradlew :core:visual
```

`E` toggles edit mode. Drag to move, drag a corner to scale, scroll to scale,
`ALT` suspends snapping, right-click hides, `L` locks, `R` resets,
`PageUp`/`PageDown` reorder, arrows nudge, `Escape` backs out.

| File | What it is |
|---|---|
| `NanoVGRender2D` | the `Render2D` backend — 28 methods of "draw this shape" |
| `NanoVGFont` | measurement through the real font, which is what the layout is built on |
| `WindowPlatform` | the `Platform` seam, on a GLFW window |
| `VisualWindow` | the window, the frame loop, font loading, screenshots and the offscreen options |
| `GlfwKeys` | GLFW's key codes turned into Core's `Key`, `Modifier` and `MouseButton` |
| `VisualElements` | five elements, each one class, none mentioning a coordinate |
| `VisualTest` | the HUD, and an edit mode written entirely by the "client" |

The first five are in core's test fixtures (`dev.px.core.test.visual`), so the
`gui` module's harness opens the same window; the last two are core's own tests.
It is test scope only. Core itself still has no dependencies, and `compileJava`
still proves it.

`--frames=N --screenshot=out.png` renders offscreen and writes a PNG, so the
harness can be checked without a display. `--mouse=x,y` reports that point as
the cursor, since an offscreen window has none.

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
