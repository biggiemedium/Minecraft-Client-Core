# GUI

Screens for Minecraft clients, built on [Core](../README.md) and just as
version-independent: the building blocks for any screen a client wants, from a
click GUI to a HUD editor to a custom title screen. The library does the part
that is the same in every client — layout, scrolling, clipping, popups,
tooltips, hit testing, hover — and **every pixel is yours**.

Like Core, it has **no Minecraft on its classpath**, and it has **no look in its
code**. Widgets hold state and geometry and draw nothing. For each widget type
your client registers a renderer that describes it with Core's `Content`, and
the library measures, lays out, hit tests and draws that one description, just
as Core does for a HUD element. No default skin ships, so no two clients built
on it have to look alike.

**Status.** Widgets, screens, the renderer registry, the container slot, rows,
columns, grids, stacks, scrolling lists, popups, tooltips, wrapped text, input
routing with focus and dragging, drag and drop, and the essential widgets —
button, checkbox, slider, text field, dropdown and window — are done. Anything
else a client needs, it builds the same way. [EXAMPLE.md](EXAMPLE.md) is a small
click GUI using each of them; see also [Not yet included](#not-yet-included).
Core's old click GUI lives on in `dev.px.gui.legacy` until its replacements
exist (§11).

**Requires:** Core, Java 8. Inside this repository:

```groovy
dependencies {
    implementation project(':gui')     // brings Core with it
}
```

Without Gradle, copy `gui/src/main/java/dev/px/gui` alongside Core's sources.

---

## Contents

1. [A screen](#1-a-screen)
2. [Your look](#2-your-look)
3. [Widgets](#3-widgets)
4. [Containers](#4-containers)
5. [Scrolling](#5-scrolling)
6. [Popups](#6-popups)
7. [Tooltips](#7-tooltips)
8. [Input](#8-input)
9. [The essential widgets](#9-the-essential-widgets)
10. [Hosting it in your game](#10-hosting-it-in-your-game)
11. [The legacy click GUI](#11-the-legacy-click-gui)
12. [Across versions](#12-across-versions)
13. [Package map](#13-package-map)
14. [Verifying](#14-verifying)

---

## The one idea

The library owns **geometry and state**; your client owns **the look**.

| GUI ships | Your client supplies |
|---|---|
| a tree of widgets with their state: hovered, enabled, visible, a button's label, a list's scroll | a renderer per widget type: colours, padding, fonts, shapes |
| measuring, layout, clipping, hit testing, drawing in order | a `Render2D` backend, once, for Core |
| rows, columns, grids, stacks and scrolling lists | what each screen is made of |
| popups placed beside a widget and kept on screen, tooltips after a delay | which key does what beyond a widget's own: Tab, Enter, Escape |
| routing presses, drags, the wheel, keys and characters; focus; the clipboard's use | the game's input, passed in, in Core's `Key` and `MouseButton` |
| a warning, once, for a widget type nothing renders | the game screen that hosts it |

A widget never calls `Render`, never picks a colour and never listens to the
event bus. The game screen hosting it calls in, as it does with Core's
`HudEditor`.

---

## 1. A screen

A screen is a tree of widgets and the look that draws them:

```java
WidgetRendererRegistry look = new WidgetRendererRegistry(core.getLogger());
// ... a renderer per widget type: §2

Column menu = new Column().gap(4f);
menu.add(new Label("My Client"));
menu.add(new Button("Singleplayer").onPress(adapter::singleplayer));
menu.add(new Button("Quit").onPress(adapter::quit));

Screen title = new Screen(look, menu);
```

and the game screen hosting it makes three calls:

```java
title.resize(width, height);        // when it opens, and whenever the window resizes
title.update(mouseX, mouseY);       // every frame: describe, lay out, work out what is hovered
title.draw();                       // every frame: paint what update laid out
```

The root fills the screen. **`update` does all the measuring and `draw` only
paints**, so anything that asks where a widget is between frames — hit testing,
a click — reads the last update's geometry and never depends on something
having been drawn.

A screen is built, not subclassed: its behaviour lives in its widgets and their
listeners. Build trees in your own factory methods and keep the `Screen`.

Game thread only, as is everything in this module.

---

## 2. Your look

A renderer **describes** one widget type with Core's `Content`
([HUD elements, §6](../docs/06-hud-elements.md)), reading the widget's state:

```java
look.register(WidgetRenderer.of(Button.class, (button, c) -> {
    boolean lit = button.isHovered() && button.isEnabled();
    c.background(lit ? HOVER : BASE, 4f).padding(8f, 6f).align(Align.CENTER);
    c.text(button.getLabel(), button.isEnabled() ? TEXT : MUTED);
}));

look.register(WidgetRenderer.of(Label.class, (label, c) -> c.text(label.getText(), MUTED)));

look.register(WidgetRenderer.of(Column.class, (column, c) -> {
    c.background(PANEL, 6f).padding(8f);
    c.slot();                                   // the children go here
}));
```

The widget is as big as what you describe, and looks like it, so its size and
its look cannot disagree. Every widget is described once per update, however
many times its container measures it.

- **The most specific renderer wins.** A renderer for your `class Danger extends
  Button` takes over for that subclass alone, and `look.replace(...)` swaps one
  on purpose. One registry can serve every screen, or each screen its own look.
- **Named parts are a widget's contract with its renderer.** Describe one with
  `c.custom("track", w, h, draw)` and `widget.part("track")` is the rectangle it
  landed in, so what is drawn and what is clicked are the same rectangle.
- **The hit shape** is the widget's bounds, unless the renderer gives one:
  `WidgetRenderer.of(type, describe, (widget, bounds) -> Shape.circle(...))`.
- **The library sets the width** of the box it hands you; use `c.min(w, h)` for
  a floor rather than `c.width(...)`.
- **Text that wraps.** `c.paragraph(text, colour)` breaks between words to the
  width the widget is given, and at every `\n`; `c.text` keeps one line. A
  label described as a paragraph wraps to its column, and a tooltip once the
  screen caps its width: `screen.tooltipMaxWidth(160f)`. Along a row, or with
  no width given, a paragraph keeps each line whole.

**When something is missing.** A leaf widget nothing renders is warned about
once per type and lays out empty. A renderer that throws is logged once per type,
and its widget lays out empty while it does. Neither takes the screen down. A
container with no renderer is silent, and lays out as a bare slot: a plain
layout column has no look to miss.

### The slot

A container's renderer draws its own look — a background, a title bar, a
border — and says with `Content.slot()` where the children go:

```java
// Window is yours: a Column subclass with a title, so it gets a look of its own
look.register(WidgetRenderer.of(Window.class, (window, c) -> {
    c.background(PANEL, 4f);
    c.row(title -> title.padding(4f).text(window.getTitle(), TEXT));
    c.column(body -> body.padding(6f).slot());          // the children, padded, under the title
}));
```

The library sizes the slot from the children and places them in the rectangle it
lands in. A slot takes all the room it can, and so does every box around it,
which is why `body` above needs no alignment of its own. Only the first slot in
a description is used. A description with no slot gets one at its end, so the
children follow what you described.

---

## 3. Widgets

A widget holds state and geometry and draws nothing.

```java
Button play = menu.add(new Button("Singleplayer").onPress(adapter::singleplayer));
play.enabledWhen(adapter::canPlay);                     // read live, as settings are
play.tooltip("Play on your own");                       // §7
```

| On every widget | What it does |
|---|---|
| `visibleWhen(condition)` | a hidden widget takes no part in layout, drawing or hit testing, nor does anything inside it |
| `enabledWhen(condition)` | a disabled widget is still drawn — its renderer reads `isEnabled()` — but its actions refuse, and so do those of everything inside it |
| `grow(weight)` | its share of the room left over in a row or column (§4) |
| `tooltip(text)` | text shown beside the cursor once it rests here (§7) |
| `isHovered()` | true for the widget under the cursor and every container around it, so a row reads as hovered while the cursor is on its label |
| `getBounds()`, `part(name)`, `parts()`, `getShape()` | where the last update put it |

`Label` is fixed text, or a `Supplier` read live; `Button`, `Checkbox`,
`Slider`, `TextField`, `Dropdown` and `Window` are in §9. Changes are reported to
listeners on the widget, and its state stays public, so a client can poll
instead.

Leaves have no children. A button's icon and label are parts its renderer
describes, not widgets of their own; only a container holds children.

---

## 4. Containers

| Container | Arranges its children |
|---|---|
| `Column` | top to bottom, spanning its width unless aligned |
| `Row` | left to right, at their own widths, spanning its height unless aligned |
| `Grid(columns)` | in cells of equal width, left to right then down, each row as tall as its tallest child |
| `Stack` | on top of one another: filling it, aligned in it, or at an offset |
| `Scroll` | a column seen through a viewport (§5) |

```java
Row bar = new Row().gap(4f).align(Align.CENTER);
bar.add(new Label("Search"));
bar.add(field).grow(1f);                               // the rest of the width
bar.add(new Button("Go"));

Stack desktop = new Stack();
desktop.add(background);                               // fills the stack
desktop.add(menu, Align.CENTER, Align.CENTER);         // its own size, centred
desktop.add(window, 40f, 30f);                         // its own size, at (40, 30)
desktop.move(window, 120f, 30f);                       // dragged somewhere else
```

**Growing.** A child with `grow(weight)` gets its measured size plus its share
of whatever room is left over, and gives up its share when there is less room
than everything measured, never going below zero. A child that doesn't grow
keeps its size either way. That is how a list fills the rest of a window.

**Spacing is the container's**, set where the screen is built: `gap` on a column,
row or grid, zero unless set. Padding and backgrounds are the renderer's, around
its slot.

**A stack** caps an aligned child to its own size, and `Align.STRETCH` fills one
axis alone. A child at an offset keeps its size wherever it is moved, even past
the edge, as a dragged window should. Later children are drawn over earlier ones
and hit before them.

**Your own container** extends `Container` and writes three methods:
`childrenHeight(visible, width)`, `childrenNatural(visible)` and
`arrange(visible, slot)`, using `heightOf`, `naturalSizeOf` and `place`. A child
is drawn and hit only if `arrange` placed it, in the order placed. Override
`clipChildren()` to clip them.

---

## 5. Scrolling

A `Scroll` is a column seen through a viewport, and only the rows in view are
laid out and drawn:

```java
Scroll modules = window.add(new Scroll().gap(1f)).grow(1f);   // the rest of the window
for (Module module : core.getModuleRegistry().all()) {
    modules.add(new Button(module.getName()));
}

look.register(WidgetRenderer.of(Scroll.class, (scroll, c) -> c.row(r -> {
    r.gap(4f).align(Align.STRETCH);
    r.slot();                                                  // the viewport
    r.custom("track", 4f, 0f, (x, y, w, h) -> {
        Bounds thumb = scroll.thumbIn(Bounds.of(x, y, w, h), 16f);
        if (thumb != null) {
            Render.roundRect(thumb.getX(), thumb.getY(), thumb.getWidth(), thumb.getHeight(), 2f, THUMB);
        }
    });
})));
```

- **The viewport** is the renderer's slot: as tall as the content, up to
  `maxHeight(...)`, and shorter when the scroll grows into less room.
- **Clipping.** Rows are clipped to the viewport for drawing *and* hit testing,
  so a row half out of view is cut off at the edge and can't be clicked where
  it isn't shown.
- **Long lists.** Rows out of view are measured, to know where everything is,
  but never laid out or drawn.
- **Moving it.** `scrollBy`, `scrollTo` and `scrollIntoView(widget)`, clamped to
  the content. A scroll asked for before the first update is kept until there is
  content to clamp it to. The wheel scrolls the list under the cursor by its
  `wheelStep` (twenty unless set), and a press on the renderer's `"track"` part
  drags along it.
- **The scroll bar** is yours to draw. `thumbIn(track, minLength)` says where the
  thumb goes in your track — its length the share of content in view, at least
  `minLength` — or null when there is nothing to scroll.

Vertical only, for now.

---

## 6. Popups

A popup opens above the screen, placed by a `Popup`:

```java
screen.popup(choices, Popup.below(dropdown).gap(4f).matchWidth());    // a dropdown
screen.popup(menu, Popup.at(mouseX, mouseY));                         // a context menu
screen.popup(submenu, Popup.rightOf(item));                           // a submenu
screen.popup(dialog, Popup.fill().onClose(this::cancelled));          // a modal
```

| Placement | Where |
|---|---|
| `below`, `above`, `rightOf`, `leftOf` (a widget) | beside it, flipping to the other side when that has more room, cut to the room it opens into |
| `at(x, y)` | its corner at the point, flipping up and left to stay on screen |
| `centred()` | in the middle of the screen |
| `fill()` | over the whole screen: a modal, since nothing beneath it can be hit |

- **Kept on screen.** A popup is laid out at its own size, or its anchor's width
  with `matchWidth()`. A growing `Scroll` inside one that is cut to the room it
  has scrolls rather than running off the edge.
- **Above everything.** Popups are drawn after the root, in the order opened,
  never clipped by what opened them, and hit before anything beneath them.
- **Closing.** `screen.close(popup)` or `closePopups()` (newest first) runs the
  placement's `onClose` listener. A popup also closes by itself once its anchor
  is no longer laid out — hidden, removed, or inside a popup that closed — which
  is how a submenu goes with its menu.
- **A modal's dimming** is its own renderer's: the screen draws nothing of its own.

A press outside every open popup closes them all and goes no further, and
`screen.cancel()` — the client's Escape — closes the newest.

---

## 7. Tooltips

```java
button.tooltip("Join a server");
row.tooltip(() -> "Ping " + ping.get() + "ms");             // read each time it shows

look.register(WidgetRenderer.of(Tooltip.class, (tip, c) -> {
    c.background(TOOLTIP, 3f).padding(5f, 4f);
    c.text(tip.getText(), TEXT);
}));
```

Once the cursor has rested on a widget for the screen's `tooltipDelay` — half a
second unless set, read from the screen's clock — the screen shows a `Tooltip`
beside the cursor, `tooltipOffset` away (twelve each way unless set) and flipped
to stay on screen. Hovering inside a container that has a tooltip shows the
container's. Moving to another widget starts the delay again.

The tooltip is a `Label`, so a look with no `Tooltip` renderer draws it with its
`Label` one. It is drawn last, over everything, and is never hit.

`new Screen(...).clock(supplier)` replaces the clock, so a test controls time.

---

## 8. Input

The host passes the game's input in, in Core's own `Key`, `Modifier` and
`MouseButton`:

```java
screen.press(x, y, button);        // a button went down
screen.release(x, y, button);      // and came up
screen.scroll(x, y, notches);      // the wheel; positive is up
screen.key(key, modifiers);        // a key went down or repeated
screen.typed(character);           // a character was typed
```

Each returns whether anything took it, so the host can pass on what the GUI
didn't want.

- **Presses** go to the widget under the cursor — popups first — and then up
  through each container around it until one takes it. Disabled widgets are
  passed over. A press outside every open popup closes them and goes no further.
- **Focus.** A press gives keyboard focus to the nearest focusable widget under
  it, and drops focus when there is none. Keys go to the focused widget, then up
  through its containers; characters go to the focused widget.
- **Dragging.** A widget that takes a press can capture the mouse. It is then
  told where the cursor is every `update` — before layout, so what it drags is
  drawn under the cursor that same frame — and when the button comes up,
  wherever the cursor is. Sliders, windows, scroll bars and text selection all
  drag this way. `isPressed()` is true while a widget holds the mouse.

**Which key means what is yours.** A widget handles the keys that are its own —
a text field's editing keys, a slider's arrows — and leaves the rest. Bind them
to the screen's actions:

```java
if (!screen.key(key, modifiers)) {
    switch (key) {
        case TAB:    if (modifiers.contains(Modifier.SHIFT)) screen.focusPrevious(); else screen.focusNext(); break;
        case ENTER:  screen.activate(); break;                  // presses a button, toggles a checkbox, opens a dropdown
        case ESCAPE: if (!screen.cancel()) closeGameScreen(); break;   // a drag, then the newest popup, then focus, then you
    }
}
```

`focusNext` and `focusPrevious` visit focusable widgets in the order they are
laid out, wrapping round, and only the newest popup's while one is open.

**Your own widget** overrides what it needs, all defaulting to "not taken":
`mousePressed`, `mouseDragged`, `mouseReleased`, `mouseScrolled`, `keyPressed`,
`charTyped`, `activated`, `focusGained`, `focusLost`, and `isFocusable()`. From
`mousePressed` it calls `capture()` to drag, or `focus()`.

**The clipboard** a text field copies to is the screen's own unless you hand
it the game's: `screen.clipboard(platform::getClipboard, platform::setClipboard)`.

### Drag and drop

Any widget can be dragged onto another, carrying whatever you like:

```java
row.draggable(() -> module, () -> new ModuleRow(module));          // carries the module, a copy under the cursor
favourites.acceptsDrops(Module.class, m -> !kept.contains(m), favourites::keep);
```

- **Starting.** A drag starts once the cursor moves the screen's `dragThreshold`
  (four unless set) from a left press on a draggable widget, so a plain click is
  still a click. A payload of null cancels it.
- **The ghost**, if given, is a widget your look renders like any other, drawn
  above the popups where the dragged widget was held, and never hit.
- **The target** is the nearest widget under the cursor that accepts the
  payload — by type, and by your test if given. `isDropTarget()` is true on it,
  for its renderer to light up; `isDragged()` on the widget being dragged.
- **Letting go** over a target hands it the payload; over nothing, nothing
  happens. `screen.cancel()` drops the drag without dropping it.
- **Subclasses** override `isDraggable`, `dragPayload`, `dragGhost`,
  `acceptsDrop`, `dropped` and `dragEnded` instead.

---

## 9. The essential widgets

Six widgets cover most screens, and any other is built the same way: a
`Widget` subclass with its state, its input, and a renderer the client writes.

```java
Window settings = desktop.add(new Window("Settings"), 20f, 20f);
settings.add(new Checkbox("Auto sprint", true).onChange(on -> config.sprint = on));
settings.add(new Slider(3, 6, 4.5).step(0.1).onChange(value -> config.reach = value));
settings.add(new TextField("").placeholder("Name").maxLength(16).onSubmit(this::rename));
settings.add(new Dropdown<>(Arrays.asList(Mode.values()), Mode.SMOOTH).onChange(mode -> config.mode = mode));
settings.add(new Button("Save").onPress(config::save));
```

| Widget | State | Input | Its renderer reads |
|---|---|---|---|
| `Button` | label | a left press, or `activate`, presses it | `getLabel()`, `isHovered()`, `isPressed()`, `isEnabled()` |
| `Checkbox` | label, checked | a left press, or `activate`, toggles it | `isChecked()`, `getLabel()` |
| `Slider` | min, max, value, step | a press on its `"track"` part sets the value, dragging follows; arrows step it while focused | `getValue()`, `getProgress()` — 0 to 1, for the fill |
| `TextField` | text, caret, selection | a press puts the caret, dragging selects, typing inserts; the editing keys below | `getText()`, `getPlaceholder()`, `offsetOf(index)`, `getScrollX()`, the caret and selection, in its `"text"` part |
| `Dropdown<T>` | choices, selected | a press, or `activate`, opens a `DropdownMenu` of `DropdownItem`s below it | `getLabel()`, `isOpen()`; an item's `getLabel()`, `isSelected()` |
| `Window` | title, collapsed | pressing brings it to the front; its `"title"` part drags it within its `Stack`; the right button there (or a `"collapse"` part) collapses it | `getTitle()`, `isCollapsed()`, and its children in the slot |

Each reports changes to listeners — `onPress`, `onChange`, `onSubmit` — and has
its actions public too (`toggle()`, `setValue`, `insert`, `open()`,
`toggleCollapsed()`), for a client driving it from anywhere else.

**The text field** takes the usual editing keys itself, since every text field
needs them: Left and Right (with Shift to select, Ctrl or Alt for a word at a
time), Home and End, Backspace and Delete (Ctrl or Alt for a word), Ctrl or Cmd
with A, C, X and V, and Enter to submit. Each is a public method as well, so a
subclass that wants other keys overrides `keyPressed` and calls them. It
measures text with its `font(...)`, or Core's default; the renderer draws with
the same, so the caret lands where the text is:

```java
look.register(WidgetRenderer.of(TextField.class, (field, c) -> {
    c.align(Align.STRETCH).background(field.isFocused() ? FOCUSED : BASE, 3f).padding(4f);
    c.column(text -> text.name("text").min(0f, Render.textHeight()).backdrop((x, y, w, h) -> {
        Render.pushClip(x, y, w, h);
        Render.text(field.getText(), x - field.getScrollX(), y, TEXT);
        if (field.isFocused()) {
            Render.rect(x + field.offsetOf(field.getCaret()), y, 1f, h, TEXT);
        }
        Render.popClip();
    }));
}));
```

**The dropdown** opens its list as a popup the width of the box, with the items
inside a `Scroll`, so a long list is cut to the room on screen — or to
`listHeight(...)` — and scrolls. Picking an item chooses it and closes the list;
so does a press outside, or `cancel`.

**The window** needs to sit in a `Stack` to be dragged or brought to the front,
and is kept inside it while dragged. Its width is its children's, or the floor
its renderer sets with `min`.

---

## 10. Hosting it in your game

The game screen your adapter opens passes its frame through:

```java
public final class TitleScreenHost extends net.minecraft.client.gui.screen.Screen {

    private final dev.px.gui.Screen title = Menus.title(look);   // yours: builds the tree

    @Override protected void init() {
        title.resize(width, height);
    }

    @Override public void render(/* your version's arguments */ int mouseX, int mouseY, float delta) {
        title.update(mouseX, mouseY);
        title.draw();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return title.press((float) x, (float) y, Keys.button(button));    // yours: the game's codes to Core's
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        return title.release((float) x, (float) y, Keys.button(button));
    }

    @Override public boolean keyPressed(int key, int scancode, int mods) {
        Key resolved = Keys.fromGlfw(key);
        if (!title.key(resolved, Keys.modifiers(mods))) {
            // §8: Tab, Enter, Escape
        }
        return true;
    }

    @Override public boolean charTyped(char typed, int mods) {
        return title.typed(typed);
    }
}
```

Drawing goes through Core's `Render` facade to the `Render2D` you installed;
fonts are Core's `FontService`. The GUI never sees your rendering library.

Turning the game's key and button codes into Core's `Key`, `Modifier` and
`MouseButton` is the adapter's, written once per version — the same translation
Core's binds already need.

---

## 11. The legacy click GUI

Core's old click GUI moved here as a starting point, into `dev.px.gui.legacy`.
**It breaks the headless rule** — it draws itself, with colours from
`GuiStyle` — and it is removed once the new building blocks replace it. A client
that wants it meanwhile:

```java
Core core = Core.builder("MyClient", "1.0").platform(platform).build();
GuiService gui = dev.px.gui.legacy.GuiService.install(core);    // between build() and start()
core.start();

gui.openClickGui();
gui.renderFrame();                                              // from your game screen
gui.mousePressed(x, y, button);
```

New screens should not be built on it.

---

## 12. Across versions

Nothing in this module changes when Minecraft does. What changes is yours:

| If your version changes… | Change… |
|---|---|
| how it draws (1.8.9 immediate mode, 1.20 `DrawContext`, a blur pass) | your `Render2D` backend, once, for Core |
| its screen class, its input callbacks or its key codes | your host screen (§10) |
| its fonts | what you register with Core's `FontService` |

Your look, your screens and their behaviour carry over unchanged.

---

## 13. Package map

| Package | Classes | What it is |
|---|---|---|
| `gui` | `Widget`, `Container`, `Screen`, `Popup` | the tree, the screen that shows it with its popup and tooltip layers, and where a popup opens |
| `gui.render` | `WidgetRenderer`, `WidgetRendererRegistry` | your look: a renderer per widget type, found most specific first |
| `gui.container` | `Column`, `Row`, `Grid`, `Stack`, `Scroll` | the ways children are arranged |
| `gui.widget` | `Label`, `Button`, `Checkbox`, `Slider`, `TextField`, `Dropdown`, `DropdownMenu`, `DropdownItem`, `Window`, `Tooltip` | the essential widgets, each a model a renderer draws |
| `gui.legacy` (+ `.click`, `.setting`) | `GuiService`, `Component`, `Panel`, `Screen`, `GuiStyle`, the nine setting rows, `ClickGuiScreen`, ... | Core's old click GUI, until it is replaced (§11) |

From Core it uses `layout` (`Content` and its `slot`, `Bounds`, `Shape`, `Size`,
`Align`), `render` (`Render`, for clipping and through renderers' content;
`Font`, for a text field's measuring), `input` (`Key`, `Modifier`,
`MouseButton`), `registry` and `util` (`Validate`, `CoreLogger`).

---

## 14. Verifying

`dev.px.gui.test.GuiSmokeTest` runs **328 checks** in a plain JVM — no
Minecraft, no window, no render backend. Every renderer in the suites is the
test's own, as a client's would be, and every size is worked out on paper from
`FixedFont`: six pixels a character, nine tall.

```
sh gradlew :gui:compileTestJava
java -cp gui/build/classes/java/main:gui/build/classes/java/test:\
core/build/classes/java/main:core/build/classes/java/testFixtures:<gson.jar> \
     dev.px.gui.test.GuiSmokeTest
```

The suites share Core's test fixtures — `Checks`, `FakePlatform`,
`RecordingLogger`, `RecordingRender2D`, `FixedFont` and `ExampleCategories` —
through `testFixtures(project(':core'))`. `Content`'s slot is Core's, and checked
by Core's `HudContentTests`, as is `Content.paragraph`.

| Suite | Covers |
|---|---|
| `GuiTests` | the legacy click GUI: renderer lookup and replacement, the tree, visibility gating, hit routing, every setting row's edits, the input gate, persistence |
| `WireframeTests` | the harness's inspector over both trees: every component and widget outlined at its bounds, class names and named parts, the hovered path and hit shape, focus and drag, popups above the root; the fallback renderer naming unrendered leaves and leaving containers bare; labels and parts switched off; nothing for a hidden root |
| `WidgetTests` | the most specific renderer winning, subclasses, `replace`; a leaf nothing renders warned once and empty, a container silent and a bare slot, a throwing renderer logged once and contained; every widget described once an update at any depth; the root filling the screen; children in the slot inside padding, the gap, a description without a slot, a slot in a nested box; natural sizes through a container of the test's own; hidden widgets taking no room and not hit; disabled containers disabling what is inside and refusing presses; hover up the chain and cleared; hit testing reading the last layout; parts and renderer shapes; draw order, drawing by the last update; listeners in order; roots, cycles, moves and removal |
| `ContainerTests` | a column aligned to the centre and the end, spanning by default; growing children taking the room left, by weight, giving it up when short and never below zero; rows side by side, a growing child pushing the rest to the edge, aligned and stretched heights, natural width with gaps; grids of equal cells, rows as tall as their tallest, natural width; stacks filling, aligned, stretched on one axis, capped, at an offset and moved past the edge, hit last-first, positions forgotten on removal; scrolling — a growing scroll's viewport, content height and maximum, only rows in view placed, scrolling and clamping, into view both ways, a request before the first update kept and clamped, a maximum height, a short list not scrolling, rows clipped for drawing and hits, thumbs at both ends and their minimum |
| `InputTests` | presses pressing a button and holding it until release, going up to a container, passing over disabled widgets, unclaimed; the wheel scrolling the list under it; focus by press, characters to the focused field, unused keys left to the client, focus dropped on a press elsewhere, moved in order and wrapping both ways, activate, cancel, lost when its widget leaves; a captured slider following the cursor every update, clamped, and let go; presses inside popups, outside ones closing them all, cancel closing the newest; checkboxes toggling and telling listeners only of changes; sliders snapping, clamping and stepping by arrows; typing, word selection and deletion, select all, copy and paste through the screen's clipboard, submit, caret placed by a press and selection by a drag, filters and maximum length; dropdowns opening their items as wide as the box, picking one, closing on a press outside; windows brought to the front, dragged in the same update, kept inside the stack, collapsed from the title; drags starting past the threshold, carrying their payload, a ghost where the widget was held and never hit, targets by type and up the tree, dropping and telling the source, cancelling; labels and tooltips wrapping to their width |
| `LayerTests` | popups below, above, beside, at a point, centred and filling; gaps and matching width; flipping to the side with more room, cut to the room, kept on screen; hit before the root, hovered up to the popup, drawn after it; a modal blocking everything; a submenu opening beside another popup's item and closing with it; refusing widgets already in a tree; closing with one `onClose`, by a vanished anchor, newest first; tooltips after the delay and not before, beside the cursor, never hit, drawn last by the `Label` renderer, a container's shown inside it, the delay restarting on a new widget, suppliers read live, no delay, flipping at the edge |

The layout, layers and wireframe were mutation-tested: 75 deliberate breaks in
this module and the Core pieces it added (`Content.slot` and `namedParts`), every
one caught except two that change no behaviour — the per-frame measurement
cache, and `Stack.remove` forgetting a position that every `add` overwrites
anyway. Input and the essential widgets are covered by their main behaviours
only, and were not mutation-tested.

### Seeing it run

```
sh gradlew :gui:visual
```

opens a real window, drawn by a NanoVG `Render2D` from Core's test fixtures: a
demo screen built from the widgets above — a settings window with every
essential widget included — with a look written in the harness, its input routed
through the screen as a host would, and a wireframe inspector over it showing
every widget's bounds, named parts and the hit shape under the cursor. Tab moves
focus, Enter or Space activates, and Escape backs out of a popup or focus before
it quits. `F1` cycles the view (look and wireframe, faded
look, wireframe only, look only), `F2` class-name labels, `F3` named parts.
`--screen=clickgui` opens the legacy click GUI instead. Test scope only: the
library never sees NanoVG.

Without a display: `--frames=N --screenshot=out.png`, with `--mouse=x,y` for
the cursor, `--click=x,y`, `--scroll=n` and `--tooltip-delay=0`.

---

## Not yet included

- **Editing beyond one line:** a multi-line text field, a double press
  selecting a word, undo. Wrapped text is drawn, not edited. Baseline alignment
  across font sizes.
- **More widgets**, which a client can write on the same model meanwhile: a
  range slider, a list, tabs, a colour picker, a keybind button, a resizable
  window, and a setting turned into its widget for any `SettingHolder`.
- **Animation progress** on widgets, read from the screen's clock. Core's
  `Animation` reads system time and will need a clock of its own first.
- **Horizontal scrolling.**
- **A screen stack and a GUI scale.** Each `Screen` stands alone, drawn at whatever scale its host draws it.
- **In-game container screens** — a slot grid that reports gestures, planned for
  v2. It will never re-create Minecraft's click rules.
