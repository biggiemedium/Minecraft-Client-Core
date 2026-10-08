package dev.px.gui.test.visual;

import dev.px.core.input.MouseButton;
import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.font.Font;
import dev.px.core.util.CoreLogger;
import dev.px.gui.Popup;
import dev.px.gui.Screen;
import dev.px.gui.Widget;
import dev.px.gui.container.Column;
import dev.px.gui.container.Grid;
import dev.px.gui.container.Row;
import dev.px.gui.container.Scroll;
import dev.px.gui.container.Stack;
import dev.px.gui.render.WidgetRenderer;
import dev.px.gui.render.WidgetRendererRegistry;
import dev.px.gui.widget.Button;
import dev.px.gui.widget.Checkbox;
import dev.px.gui.widget.Dropdown;
import dev.px.gui.widget.DropdownItem;
import dev.px.gui.widget.DropdownMenu;
import dev.px.gui.widget.Label;
import dev.px.gui.widget.Slider;
import dev.px.gui.widget.TextField;
import dev.px.gui.widget.Tooltip;
import dev.px.gui.widget.Window;
import lombok.Getter;

import java.util.Arrays;

/**
 * A small title-screen-like tree, built the way a client builds one: its own
 * look, a tree of widgets, and listeners.
 *
 * <p>Every colour, padding and font is this file's, written as a client would
 * write them; the library holds none. It shows each container &mdash; a stack
 * with a corner label, a row of panels, a scrolling list with a scroll bar its
 * renderer draws, a grid &mdash; plus a dropdown popup and tooltips. One widget
 * type, {@link Gauge}, has no renderer on purpose, so the wireframe's fallback
 * shows what an unrendered widget looks like.
 *
 * <p>Input reaches it through the screen's own routing: the harness passes the
 * window's presses, releases, wheel, keys and characters to {@link Screen}, and
 * binds Tab, Enter and Escape to its actions, as a client's host screen would.
 */
public final class DemoScreen {

    private static final Color BASE = Color.of(38, 42, 52, 230);
    private static final Color HOVER = Color.of(70, 110, 170, 240);
    private static final Color PANEL = Color.of(18, 20, 26, 220);
    private static final Color MENU = Color.of(28, 31, 40, 245);
    private static final Color TRACK = Color.of(255, 255, 255, 18);
    private static final Color THUMB = Color.of(255, 255, 255, 90);
    private static final Color TEXT = Color.of(230, 234, 245);
    private static final Color MUTED = Color.of(130, 136, 150);
    private static final Color ACCENT = Color.of(110, 160, 230);
    private static final Color FIELD = Color.of(12, 13, 17, 240);
    private static final Color SELECTION = Color.of(110, 160, 230, 110);
    private static final Color FOCUS = Color.of(110, 160, 230, 200);

    @Getter
    private final Screen screen;

    private final Button options;

    private Column optionsMenu;

    private int presses;

    /**
     * @param title the font the title is drawn in; everything else uses the default
     * @param quit run by the Quit button
     */
    public DemoScreen(CoreLogger logger, Font title, Wireframe wireframe, Runnable quit) {
        WidgetRendererRegistry look = new WidgetRendererRegistry(logger);
        look.register(wireframe.renderer());
        look.register(WidgetRenderer.of(Backdrop.class, (backdrop, c) -> c.padding(32f, 24f).slot()));
        look.register(WidgetRenderer.of(Panel.class, (panel, c) -> c.background(PANEL, 6f).padding(8f).slot()));
        look.register(WidgetRenderer.of(Menu.class, (menu, c) -> c.background(MENU, 4f).padding(4f).slot()));
        look.register(WidgetRenderer.of(Label.class, (label, c) -> c.text(label.getText(), MUTED)));
        look.register(WidgetRenderer.of(Title.class, (label, c) -> c.text(title, label.getText(), TEXT)));
        look.register(WidgetRenderer.of(Tooltip.class, (tip, c) -> {
            c.background(Color.of(8, 9, 12, 240), 3f).padding(5f, 4f);
            c.text(tip.getText(), TEXT);
        }));
        look.register(WidgetRenderer.of(Button.class, (button, c) -> {
            boolean lit = button.isHovered() && button.isEnabled();
            c.background(lit ? HOVER : BASE, 4f).padding(8f, 6f).align(Align.CENTER);
            c.text(button.getLabel(), button.isEnabled() ? TEXT : MUTED);
        }));
        look.register(WidgetRenderer.of(Window.class, (window, c) -> {
            c.background(PANEL, 6f).min(190f, 0f).align(Align.STRETCH);
            c.row(bar -> {
                bar.name("title").padding(8f, 6f).align(Align.CENTER);
                bar.text(window.getTitle(), TEXT).fill().text(window.isCollapsed() ? "+" : "-", MUTED);
            });
            c.column(body -> body.padding(8f, 0f, 8f, 8f).slot());
        }));
        look.register(WidgetRenderer.of(Checkbox.class, (box, c) -> c.row(r -> {
            r.gap(6f).align(Align.CENTER);
            r.custom(12f, 12f, (x, y, w, h) -> {
                Render.roundRect(x, y, w, h, 3f, box.isChecked() ? ACCENT : BASE);
                if (box.isFocused()) {
                    Render.roundRectOutline(x, y, w, h, 3f, 1f, FOCUS);
                }
            });
            r.text(box.getLabel(), TEXT);
        })));
        look.register(WidgetRenderer.of(Slider.class, (slider, c) -> {
            c.align(Align.STRETCH).gap(4f);
            c.row(r -> r.text("Reach", TEXT).fill().text(String.format("%.1f", slider.getValue()), MUTED));
            c.custom("track", 0f, 6f, (x, y, w, h) -> {
                Render.roundRect(x, y, w, h, 3f, BASE);
                Render.roundRect(x, y, Math.max(h, w * slider.getProgress()), h, 3f,
                        slider.isFocused() || slider.isPressed() ? ACCENT : FOCUS);
            });
        }));
        look.register(WidgetRenderer.of(TextField.class, (field, c) -> {
            c.align(Align.STRETCH).background(FIELD, 3f).padding(6f, 5f);
            c.column(text -> text.name("text").min(0f, Render.textHeight()).backdrop((x, y, w, h) -> {
                Render.pushClip(x, y - 2f, w, h + 4f);
                if (field.hasSelection()) {
                    float from = x + field.offsetOf(field.getSelectionStart());
                    Render.rect(from, y - 1f, x + field.offsetOf(field.getSelectionEnd()) - from, h + 2f, SELECTION);
                }
                boolean empty = field.getText().isEmpty();
                Render.text(empty ? field.getPlaceholder() : field.getText(), x - field.getScrollX(), y,
                        empty ? MUTED : TEXT);
                if (field.isFocused() && caretShown()) {
                    Render.rect(x + field.offsetOf(field.getCaret()), y - 1f, 1f, h + 2f, TEXT);
                }
                Render.popClip();
            }));
        }));
        look.register(WidgetRenderer.of(Dropdown.class, (dropdown, c) -> c.align(Align.STRETCH).row(r -> {
            r.background(BASE, 4f).padding(8f, 6f).align(Align.CENTER);
            r.text(dropdown.getLabel(), TEXT).fill().text(dropdown.isOpen() ? "^" : "v", MUTED);
        })));
        look.register(WidgetRenderer.of(DropdownMenu.class, (menu, c) -> c.background(MENU, 4f).padding(3f).slot()));
        look.register(WidgetRenderer.of(DropdownItem.class, (item, c) -> c
                .background(item.isHovered() ? HOVER : item.isSelected() ? BASE : MENU, 3f)
                .padding(6f, 4f)
                .text(item.getLabel(), TEXT)));
        look.register(WidgetRenderer.of(Scroll.class, (scroll, c) -> c.row(r -> {
            r.gap(4f).align(Align.STRETCH);
            r.slot();
            r.custom("track", 4f, 0f, (x, y, w, h) -> {
                Render.roundRect(x, y, w, h, 2f, TRACK);
                Bounds thumb = scroll.thumbIn(Bounds.of(x, y, w, h), 16f);
                if (thumb != null) {
                    Render.roundRect(thumb.getX(), thumb.getY(), thumb.getWidth(), thumb.getHeight(), 2f, THUMB);
                }
            });
        })));

        Stack root = new Stack();
        this.screen = new Screen(look, root).tooltipDelay(350L);
        root.add(new Label("dev.px.gui, step 3"), Align.END, Align.END);

        Backdrop page = root.add(new Backdrop());
        page.gap(10f);
        page.add(new Title("Core GUI"));
        page.add(new Label(() -> "Containers, popups, tooltips, input and the essential widgets. Focused: "
                + (screen.getFocused() == null ? "nothing" : Wireframe.nameOf(screen.getFocused()))));

        Row panels = page.add(new Row().gap(10f)).grow(1f);

        Panel menu = panels.add(new Panel());
        menu.gap(6f);
        menu.add(new Button("Singleplayer").onPress(() -> presses++)).tooltip("Play on your own");
        menu.add(new Button("Multiplayer").onPress(() -> presses++)).tooltip("Join a server");
        this.options = menu.add(new Button("Options  v")).tooltip("Opens a popup below");
        options.onPress(this::toggleOptions);
        menu.add(new Button("Quit").onPress(quit));
        menu.add(new Label(() -> "Pressed " + presses + (presses == 1 ? " time" : " times")));

        Panel modules = panels.add(new Panel());
        modules.gap(6f).grow(1f);
        modules.add(new Label("A scrolling list: only the rows in view are laid out"));
        Scroll list = modules.add(new Scroll().gap(3f)).grow(1f);
        for (int i = 1; i <= 40; i++) {
            int number = i;
            list.add(new Button("Module " + i).onPress(() -> presses++)).tooltip(() -> "Module number " + number);
        }

        Panel keys = panels.add(new Panel());
        keys.gap(6f);
        keys.add(new Label("A grid"));
        Grid grid = keys.add(new Grid(3).gap(4f));
        for (int i = 1; i <= 9; i++) {
            grid.add(new Button(String.valueOf(i)).onPress(() -> presses++));
        }
        keys.add(new Gauge());

        Window settings = root.add(new Window("Settings"), 620f, 330f);
        settings.gap(8f);
        settings.add(new Checkbox("Auto sprint", true)).tooltip("A checkbox: press it, or Tab to it and press Enter");
        settings.add(new Slider(3, 6, 4.5).step(0.1)).tooltip("A slider: drag it, or use the arrows with it focused");
        settings.add(new TextField("").placeholder("Type a name...").maxLength(24));
        settings.add(new Dropdown<>(Arrays.asList("Instant", "Smooth", "Locked"), "Smooth"));
        settings.add(new Label("Drag the title to move; right-click it to collapse"));
    }

    /** Opens the options menu below its button, or closes it. */
    private void toggleOptions() {
        if (optionsMenu != null) {
            screen.close(optionsMenu);
            return;
        }
        Menu items = new Menu();
        items.gap(2f);
        for (String name : new String[] { "Video", "Sound", "Controls" }) {
            items.add(new Button(name).onPress(() -> {
                presses++;
                screen.close(optionsMenu);
            })).tooltip(name + " settings");
        }
        optionsMenu = screen.popup(items, Popup.below(options).gap(4f).matchWidth().onClose(() -> optionsMenu = null));
    }

    /** Whether a blinking caret is lit now: half a second on, half off, by the screen's clock. */
    private boolean caretShown() {
        return (screen.now() / 500L) % 2L == 0L;
    }

    /** A click at a point, through the screen's routing: what {@code --click} sends. */
    public void click(float x, float y) {
        screen.press(x, y, MouseButton.LEFT);
        screen.release(x, y, MouseButton.LEFT);
    }

    /** The whole page: a padded column. */
    public static final class Backdrop extends Column {
    }

    /** A panel on the page. */
    public static final class Panel extends Column {
    }

    /** The options popup. */
    public static final class Menu extends Column {
    }

    /** A label in the title font. */
    public static final class Title extends Label {
        Title(String text) {
            super(text);
        }
    }

    /** A widget type this look leaves out, to show the wireframe's fallback. */
    public static final class Gauge extends Widget {
    }
}
