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
