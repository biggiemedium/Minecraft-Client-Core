# Example: a click GUI

A small but complete click GUI built on this module: a window per category, a
row per module, settings in a popup, search, favourites by drag and drop, and
tooltips. It is written the way a client would write it — the look, the screen
and the host are all the client's — and each important piece of the library is
used once, marked with a comment.

It is three files: **the look** (what everything looks like), **the screen**
(what it is made of and what it does), and **the host** (the game screen that
shows it). The adapter is yours and not shown: `Keys` turning your game's key
codes into Core's is the same translation Core's binds already need.

---

## 1. The look

One registry, one renderer per widget type. This is the only place colours,
padding and fonts appear.

```java
public final class MyLook {

    static final Color PANEL = Color.of(18, 20, 26, 230);
    static final Color ROW = Color.of(32, 36, 46, 230);
    static final Color HOVER = Color.of(60, 90, 140, 240);
    static final Color ACCENT = Color.of(110, 160, 230);
    static final Color TEXT = Color.of(230, 234, 245);
    static final Color MUTED = Color.of(130, 136, 150);

    static WidgetRendererRegistry build(CoreLogger logger) {
        WidgetRendererRegistry look = new WidgetRendererRegistry(logger);

        // A window: a draggable title bar, and its children in the body.
        // The "title" part is what drags it; Content.slot() is where the children go.
        look.register(WidgetRenderer.of(Window.class, (window, c) -> {
            c.background(PANEL, 5f).min(150f, 0f).align(Align.STRETCH);
            c.row(bar -> bar.name("title").padding(6f).text(window.getTitle(), TEXT)
                    .fill().text(window.isCollapsed() ? "+" : "-", MUTED));
            c.column(body -> body.padding(4f).slot());
        }));

        // Our own widget type (§2): lit when on, outlined while something droppable is over it.
        look.register(WidgetRenderer.of(ModuleRow.class, (row, c) -> {
            Color fill = row.getModule().isEnabled() ? ACCENT : row.isHovered() ? HOVER : ROW;
            c.background(fill.withAlpha(row.isDragged() ? 90 : 230), 3f).padding(6f, 4f);
            c.text(row.getModule().getName(), TEXT);
        }));

        look.register(WidgetRenderer.of(Button.class, (button, c) -> c
                .background(button.isHovered() ? HOVER : ROW, 3f).padding(6f, 4f).align(Align.CENTER)
                .text(button.getLabel(), button.isEnabled() ? TEXT : MUTED)));

        // Paragraphs wrap to the width the widget is given.
        look.register(WidgetRenderer.of(Label.class, (label, c) -> c.paragraph(label.getText(), MUTED)));
        look.register(WidgetRenderer.of(Tooltip.class, (tip, c) -> c
                .background(Color.of(8, 9, 12, 240), 3f).padding(5f, 4f).paragraph(tip.getText(), TEXT)));

        look.register(WidgetRenderer.of(Checkbox.class, (box, c) -> c.row(r -> r.gap(6f).align(Align.CENTER)
                .roundRect(10f, 10f, 2f, box.isChecked() ? ACCENT : ROW)
                .text(box.getLabel(), TEXT))));

        // A slider: the library reads the "track" part to know where a press lands along it.
        look.register(WidgetRenderer.of(Slider.class, (slider, c) -> c.align(Align.STRETCH)
                .custom("track", 0f, 4f, (x, y, w, h) -> {
                    Render.rect(x, y, w, h, ROW);
                    Render.rect(x, y, w * slider.getProgress(), h, ACCENT);
                })));

        // A text field draws its text, selection and caret in its "text" part.
        look.register(WidgetRenderer.of(TextField.class, (field, c) -> {
            c.align(Align.STRETCH).background(field.isFocused() ? HOVER : ROW, 3f).padding(5f, 4f);
            c.column(text -> text.name("text").min(0f, Render.textHeight()).backdrop((x, y, w, h) -> {
                Render.pushClip(x, y, w, h);
                boolean empty = field.getText().isEmpty();
                Render.text(empty ? field.getPlaceholder() : field.getText(), x - field.getScrollX(), y,
                        empty ? MUTED : TEXT);
                if (field.isFocused()) {
                    Render.rect(x + field.offsetOf(field.getCaret()), y, 1f, h, TEXT);
                }
                Render.popClip();
            }));
        }));

        look.register(WidgetRenderer.of(Dropdown.class, (dropdown, c) -> c.align(Align.STRETCH).row(r -> r
                .background(ROW, 3f).padding(6f, 4f).text(dropdown.getLabel(), TEXT).fill().text("v", MUTED))));
        look.register(WidgetRenderer.of(DropdownMenu.class, (menu, c) -> c.background(PANEL, 3f).padding(2f).slot()));
        look.register(WidgetRenderer.of(DropdownItem.class, (item, c) -> c
                .background(item.isHovered() ? HOVER : PANEL, 3f).padding(5f, 3f).text(item.getLabel(), TEXT)));

        // A scrolling list with a thin scroll bar beside its viewport.
        look.register(WidgetRenderer.of(Scroll.class, (scroll, c) -> c.row(r -> r.gap(2f).align(Align.STRETCH)
                .slot()
                .custom("track", 3f, 0f, (x, y, w, h) -> {
                    Bounds thumb = scroll.thumbIn(Bounds.of(x, y, w, h), 12f);
                    if (thumb != null) {
                        Render.rect(thumb.getX(), thumb.getY(), thumb.getWidth(), thumb.getHeight(), MUTED);
                    }
                }))));

        // A settings popup and the favourites list get a panel of their own.
        look.register(WidgetRenderer.of(SettingsPanel.class, (panel, c) -> c.background(PANEL, 4f).padding(6f).slot()));
        look.register(WidgetRenderer.of(Favourites.class, (fav, c) ->
                c.background(fav.isDropTarget() ? HOVER : ROW, 3f).padding(4f).min(0f, 24f).slot()));
        return look;
    }
}
```

`Column`, `Row`, `Stack` and `Grid` have no renderer here. A container nothing
renders is a bare slot: a layout with no look.

---

## 2. The screen

The tree, built once, and the behaviour in its listeners.

```java
public final class ClickGui {

    static Screen build(Core core, WidgetRendererRegistry look) {
        Stack desktop = new Stack();                               // free-floating windows
        Screen screen = new Screen(look, desktop)
                .tooltipMaxWidth(160f)                             // long descriptions wrap
                .clipboard(core.getPlatform()::getClipboard, core.getPlatform()::setClipboard);

        // A search bar across the top: a Row with the field taking the spare width.
        Row bar = desktop.add(new Row().gap(6f).align(Align.CENTER), Align.STRETCH, Align.START);
        TextField search = bar.add(new TextField("").placeholder("Search modules")).grow(1f);
        bar.add(new Button("Clear").onPress(() -> search.setText("")));

        // A window per category, each a scrolling list of module rows.
        float x = 10f;
        for (Category category : core.getCategories().all()) {
            Window window = desktop.add(new Window(category.getName()), x, 40f);
            Scroll modules = window.add(new Scroll().gap(2f).maxHeight(220f));
            for (Module module : core.getModuleRegistry().inCategory(category)) {
                ModuleRow row = modules.add(new ModuleRow(module));
                row.visibleWhen(() -> matches(module, search.getText()));   // search, read live
                row.tooltip(module::getDescription);                         // shown after a rest
                row.draggable(() -> module, () -> new ModuleRow(module));    // drag a copy about
            }
            x += 160f;
        }

        // Favourites: drop a module row here to keep it.
        Window favouritesWindow = desktop.add(new Window("Favourites"), x, 40f);
        Favourites favourites = favouritesWindow.add(new Favourites());
        favourites.add(new Label("Drag modules here"));
        favourites.acceptsDrops(Module.class, module -> !favourites.has(module), favourites::keep);

        // Presets in a grid, and a confirmation in a centred popup.
        Window presets = desktop.add(new Window("Presets"), x, 200f);
        Grid grid = presets.add(new Grid(2).gap(3f));
        for (String preset : new String[] { "Legit", "Rage", "Quiet", "Off" }) {
            grid.add(new Button(preset).onPress(() ->
                    confirm(screen, "Load " + preset + "?", () -> Presets.load(preset))));   // Presets: yours
        }
        return screen;
    }

    private static boolean matches(Module module, String search) {
        return module.getName().toLowerCase().contains(search.toLowerCase());
    }

    /** A centred popup with a question and two buttons. */
    private static void confirm(Screen screen, String question, Runnable yes) {
        SettingsPanel dialog = new SettingsPanel();
        dialog.add(new Label(question));
        Row answers = dialog.add(new Row().gap(4f));
        answers.add(new Button("Yes").onPress(() -> { yes.run(); screen.close(dialog); })).grow(1f);
        answers.add(new Button("No").onPress(() -> screen.close(dialog))).grow(1f);
        screen.popup(dialog, Popup.centred());
    }
}
```

**A widget of our own.** A module row toggles on a left press and opens its
settings beside itself on a right press. It is the whole of what a client writes
to add a widget type: state, input, and a renderer in the look.

```java
public final class ModuleRow extends Widget {

    @Getter
    private final Module module;

    public ModuleRow(Module module) {
        this.module = module;
    }

    @Override
    protected boolean mousePressed(float x, float y, MouseButton button) {
        if (button == MouseButton.LEFT) {
            module.toggle();
            return true;
        }
        if (button == MouseButton.RIGHT && !module.getSettings().isEmpty()) {
            getScreen().popup(SettingsPanel.of(module), Popup.rightOf(this).gap(4f));   // a popup beside the row
            return true;
        }
        return false;
    }
}
```

**Settings as widgets.** A popup panel built from a module's settings — the
library ships no mapping, so the client chooses which widget edits which type:

```java
public final class SettingsPanel extends Column {

    static SettingsPanel of(Module module) {
        SettingsPanel panel = new SettingsPanel();
        panel.gap(4f);
        for (Setting<?> setting : module.getSettings()) {
            if (setting instanceof BooleanSetting) {
                BooleanSetting on = (BooleanSetting) setting;
                panel.add(new Checkbox(on.getName(), on.isOn()).onChange(on::set));
            } else if (setting instanceof NumberSetting) {
                NumberSetting<?> number = (NumberSetting<?>) setting;
                panel.add(new Label(() -> number.getName() + ": " + number.displayValue()));
                panel.add(new Slider(0, 1, number.progress()).onChange(p -> number.setProgress((float) p)));
            } else if (setting instanceof EnumSetting) {
                panel.add(dropdownFor((EnumSetting<?>) setting));
            } else if (setting instanceof StringSetting) {
                StringSetting text = (StringSetting) setting;
                panel.add(new TextField(text.get()).onChange(text::set));
            }
        }
        return panel;
    }

    private static <E extends Enum<E>> Dropdown<E> dropdownFor(EnumSetting<E> choice) {
        return new Dropdown<>(choice.getOptions(), choice.get()).labels(choice::labelOf).onChange(choice::set);
    }
}

/** A column that remembers the modules dropped on it. */
public final class Favourites extends Column {

    private final Set<Module> kept = new HashSet<>();

    boolean has(Module module) {
        return kept.contains(module);
    }

    void keep(Module module) {
        kept.add(module);
        add(new ModuleRow(module));
    }
}
```

---

## 3. The host

The game screen that shows it: three calls a frame, and the game's input passed
in. Which key does what, beyond a widget's own, is decided here.

```java
public final class ClickGuiHost extends net.minecraft.client.gui.screen.Screen {

    private final dev.px.gui.Screen gui = ClickGui.build(Core.get(), MyLook.build(Core.get().getLogger()));

    @Override protected void init() {
        gui.resize(width, height);
    }

    @Override public void render(/* your version's arguments */ int mouseX, int mouseY, float delta) {
        gui.update(mouseX, mouseY);
        gui.draw();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return gui.press((float) x, (float) y, Keys.button(button));
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        return gui.release((float) x, (float) y, Keys.button(button));
    }

    @Override public boolean mouseScrolled(double x, double y, double amount) {
        return gui.scroll((float) x, (float) y, (float) amount);
    }

    @Override public boolean charTyped(char typed, int modifiers) {
        return gui.typed(typed);
    }

    @Override public boolean keyPressed(int code, int scancode, int mods) {
        Key key = Keys.fromGlfw(code);
        Set<Modifier> modifiers = Keys.modifiers(mods);
        if (gui.key(key, modifiers)) {
            return true;                                       // a text field, a slider's arrows
        }
        switch (key) {
            case TAB:    if (modifiers.contains(Modifier.SHIFT)) gui.focusPrevious(); else gui.focusNext(); return true;
            case ENTER:  return gui.activate();
            case ESCAPE: if (!gui.cancel()) onClose(); return true;   // a drag, then a popup, then focus, then the screen
            default:     return false;
        }
    }
}
```

---

## What each piece is doing here

| Piece | Used for |
|---|---|
| `WidgetRendererRegistry`, `WidgetRenderer` | the whole look, in one place (§1) |
| `Content.slot()` | where a window's, a panel's and the favourites' children go |
| named parts (`"title"`, `"track"`, `"text"`) | the window's drag handle, the slider's and scroll bar's track, the text field's text |
| `Content.paragraph` | descriptions and tooltips wrapping to their width |
| `Screen` | the root, the input, the clipboard, the tooltip width |
| `Stack` | windows floating where they are dragged; the search bar across the top |
| `Row` with `grow` | the search field taking the bar's spare width; two equal answer buttons |
| `Column` | the settings panel and the favourites list |
| `Grid` | the presets |
| `Scroll` | a long category kept to 220 high, with a scroll bar |
| `Window` | a draggable, collapsible panel per category |
| `Button`, `Checkbox`, `Slider`, `TextField`, `Dropdown`, `Label` | settings, search, presets and text |
| a `Widget` subclass | `ModuleRow`: its own state, input and look |
| `visibleWhen` | search filtering, read live |
| `tooltip` | module descriptions |
| `Popup.rightOf`, `Popup.centred` | the settings beside a row; a confirmation in the middle |
| `draggable`, `acceptsDrops` | dragging modules into Favourites |
| `focusNext`, `activate`, `cancel` | Tab, Enter and Escape, bound by the host |
