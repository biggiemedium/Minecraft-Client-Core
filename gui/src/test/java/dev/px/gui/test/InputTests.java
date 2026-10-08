package dev.px.gui.test;

import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.render.Color;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.gui.Popup;
import dev.px.gui.Screen;
import dev.px.gui.Widget;
import dev.px.gui.container.Column;
import dev.px.gui.container.Scroll;
import dev.px.gui.container.Stack;
import dev.px.gui.render.WidgetRenderer;
import dev.px.gui.render.WidgetRendererRegistry;
import dev.px.gui.widget.Button;
import dev.px.gui.widget.Checkbox;
import dev.px.gui.widget.Dropdown;
import dev.px.gui.widget.DropdownItem;
import dev.px.gui.widget.Label;
import dev.px.gui.widget.Slider;
import dev.px.gui.widget.TextField;
import dev.px.gui.widget.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Input routed by the screen, and the essential widgets driven through it: the
 * main behaviour of each, the way a client would use it.
 *
 * <p>Sizes come from {@code FixedFont}: six pixels a character, nine tall, on a
 * 200 by 100 screen.
 */
public final class InputTests {

    private static final Color ANY = Color.WHITE;
    private static final Set<Modifier> NONE = Collections.emptySet();

    private InputTests() {
    }

    public static void run(GuiTestClient client) {
        Checks.section("Input");
        client.reset();
        routing();
        focus();
        dragging();
        popups();

        Checks.section("Essential widgets");
        checkboxes();
        sliders();
        textFields();
        dropdowns();
        windows();

        Checks.section("Drag and drop, and wrapping");
        dragAndDrop();
        wrapping();
    }

    // --------------------------------------------------------------- routing

    private static void routing() {
        int[] presses = {0};
        boolean[] enabled = {true};
        Column root = new Column();
        Button go = root.add(new Button("go").onPress(() -> presses[0]++));
        Catcher catcher = root.add(new Catcher());
        Label inside = catcher.add(new Label("inside"));
        Button off = root.add(new Button("off").enabledWhen(() -> enabled[0] && false));
        Label plain = root.add(new Label("plain"));
        Screen screen = screen(root);

        Checks.check("a press on a button presses it", screen.press(centreX(go), centreY(go), MouseButton.LEFT)
                && presses[0] == 1);
        Checks.check("and holds it until the button comes up",
                go.isPressed() && screen.release(0f, 0f, MouseButton.LEFT) && !go.isPressed());
        Checks.check("a press the widget under it doesn't take goes up to its container",
                screen.press(centreX(inside), centreY(inside), MouseButton.LEFT) && catcher.caught == 1);
        Checks.check("a disabled widget is passed over",
                !screen.press(centreX(off), centreY(off), MouseButton.LEFT));
        Checks.check("and a press nothing takes says so", !screen.press(centreX(plain), centreY(plain), MouseButton.LEFT));
        Checks.check("a release with nothing held says so", !screen.release(0f, 0f, MouseButton.LEFT));

        Column scrolling = new Column();
        Scroll list = scrolling.add(new Scroll()).grow(1f);
        for (int i = 0; i < 30; i++) {
            list.add(new Label("row " + i));
        }
        Screen scrolled = screen(scrolling);
        Checks.check("the wheel scrolls the list under the cursor by its step",
                scrolled.scroll(10f, 10f, -1f) && Checks.eq(list.getScrollY(), 20f));
        Checks.check("and goes unclaimed where nothing scrolls", !screen.scroll(5f, 5f, -1f));
    }

    private static void focus() {
        Column root = new Column();
        TextField first = root.add(new TextField(""));
        Label between = root.add(new Label("between"));
        TextField second = root.add(new TextField(""));
        int[] presses = {0};
        Button button = root.add(new Button("go").onPress(() -> presses[0]++));
        Screen screen = screen(root);

        screen.press(centreX(first), centreY(first), MouseButton.LEFT);
        screen.release(0f, 0f, MouseButton.LEFT);
        Checks.check("a press on a field focuses it", screen.getFocused() == first && first.isFocused());
        Checks.check("characters go to the focused field", screen.typed('a') && first.getText().equals("a")
                && second.getText().isEmpty());
        Checks.check("keys it doesn't use are left to the client", !screen.key(Key.F5, NONE));
        screen.press(centreX(between), centreY(between), MouseButton.LEFT);
        Checks.check("a press on nothing focusable drops focus", screen.getFocused() == null && !screen.typed('b'));

        Checks.check("focus moves through the focusable widgets in order",
                screen.focusNext() && screen.getFocused() == first && screen.focusNext()
                        && screen.getFocused() == second && screen.focusNext() && screen.getFocused() == button);
        Checks.check("wrapping round, both ways", screen.focusNext() && screen.getFocused() == first
                && screen.focusPrevious() && screen.getFocused() == button);
        Checks.check("activating the focused button presses it", screen.activate() && presses[0] == 1);
        Checks.check("cancelling drops focus, then has nothing to back out of",
                screen.cancel() && screen.getFocused() == null && !screen.cancel());

        screen.focusNext();
        first.visibleWhen(() -> false);
        screen.update(0f, 0f);
        Checks.check("a widget that leaves the screen loses focus", screen.getFocused() == null);
    }

    private static void dragging() {
        Column root = new Column();
        Slider slider = root.add(new Slider(0, 100, 0));
        Screen screen = screen(root);
        Bounds track = slider.part("track");

        screen.press(track.getX(), track.getCenterY(), MouseButton.LEFT);
        screen.update(track.getX() + track.getWidth() * 0.75f, track.getCenterY());
        Checks.check("a widget that captured the mouse follows the cursor every update (" + slider.getValue() + ")",
                Math.abs(slider.getValue() - 75) < 0.01);
        screen.update(-50f, 300f);
        Checks.check("even off the widget, clamped to its range", slider.getValue() == 0);
        screen.release(0f, 0f, MouseButton.LEFT);
        screen.update(track.getRight(), track.getCenterY());
        Checks.check("and lets go when the button comes up", slider.getValue() == 0 && screen.getCaptured() == null);
    }

    private static void popups() {
        Column root = new Column();
        Button anchor = root.add(new Button("go"));
        Label beneath = root.add(new Label("beneath"));
        Screen screen = screen(root);
        Column menu = screen.popup(new Column(), Popup.below(anchor));
        Button item = menu.add(new Button("item"));
        Column other = screen.popup(new Column(), Popup.at(150f, 80f));
        other.add(new Label("other"));
        screen.update(0f, 0f);

        Checks.check("a press inside a popup goes to it",
                screen.press(centreX(item), centreY(item), MouseButton.LEFT) && screen.isPopup(menu));
        screen.release(0f, 0f, MouseButton.LEFT);
        Checks.check("a press outside every popup closes them all, and goes no further",
                screen.press(5f, 95f, MouseButton.LEFT) && screen.getPopups().isEmpty());

        screen.popup(new Column(), Popup.centred());
        screen.popup(new Column(), Popup.at(0f, 0f));
        screen.update(0f, 0f);
        Checks.check("cancelling closes the newest popup first",
                screen.cancel() && screen.getPopups().size() == 1 && screen.cancel() && screen.getPopups().isEmpty());
        Checks.check("the root beneath is untouched", beneath.getBounds().getHeight() > 0f);
    }

    // --------------------------------------------------------------- widgets

    private static void checkboxes() {
        List<Boolean> heard = new ArrayList<>();
        Column root = new Column();
        Checkbox box = root.add(new Checkbox("sprint", false).onChange(heard::add));
        Screen screen = screen(root);
        screen.press(centreX(box), centreY(box), MouseButton.LEFT);
        Checks.check("a press toggles a checkbox, and its listeners hear the new state",
                box.isChecked() && heard.equals(Arrays.asList(true)));
        box.setChecked(true);
        Checks.check("setting the state it already has tells nobody", heard.size() == 1);
        box.enabledWhen(() -> false);
        Checks.check("a disabled checkbox doesn't toggle", !box.toggle() && box.isChecked());
    }

    private static void sliders() {
        Slider slider = new Slider(3, 6, 4).step(0.5);
        slider.setValue(4.26);
        Checks.check("a value snaps to the step from the minimum (" + slider.getValue() + ")", slider.getValue() == 4.5);
        slider.setValue(99);
        Checks.check("and is kept between the bounds", slider.getValue() == 6 && slider.getProgress() == 1f);

        Column root = new Column();
        Slider keyed = root.add(new Slider(0, 10, 5).step(1));
        Screen screen = screen(root);
        screen.focusNext();
        Checks.check("with focus, arrows step it", screen.key(Key.RIGHT, NONE) && keyed.getValue() == 6
                && screen.key(Key.LEFT, NONE) && screen.key(Key.LEFT, NONE) && keyed.getValue() == 4);
        Checks.checkThrows("its bounds must be in order", IllegalArgumentException.class, () -> new Slider(5, 5, 5));
    }

    private static void textFields() {
        Column root = new Column();
        List<String> submitted = new ArrayList<>();
        TextField field = root.add(new TextField("").onSubmit(submitted::add));
        Screen screen = screen(root);
        screen.press(centreX(field), centreY(field), MouseButton.LEFT);
        screen.release(0f, 0f, MouseButton.LEFT);
        type(screen, "hello world");
        Checks.check("typing inserts at the caret", field.getText().equals("hello world") && field.getCaret() == 11);

        screen.key(Key.LEFT, EnumSet.of(Modifier.SHIFT, Modifier.CTRL));
        Checks.check("shift selects, a word at a time with ctrl", field.getSelection().equals("world"));
        screen.key(Key.BACKSPACE, NONE);
        Checks.check("backspace deletes the selection", field.getText().equals("hello "));
        screen.key(Key.BACKSPACE, EnumSet.of(Modifier.CTRL));
        Checks.check("and a word with ctrl", field.getText().isEmpty());

        type(screen, "copy me");
        screen.key(Key.A, EnumSet.of(Modifier.CTRL));
        screen.key(Key.C, EnumSet.of(Modifier.CTRL));
        screen.key(Key.END, NONE);
        screen.key(Key.V, EnumSet.of(Modifier.SUPER));
        Checks.check("select all, copy and paste go through the screen's clipboard (" + field.getText() + ")",
                field.getText().equals("copy mecopy me") && screen.getClipboard().equals("copy me"));
        screen.key(Key.ENTER, NONE);
        Checks.check("enter tells the submit listeners", submitted.equals(Arrays.asList("copy mecopy me")));

        field.setText("abcdef");
        screen.update(0f, 0f);
        Bounds text = field.part("text");
        screen.press(text.getX() + 12f, text.getCenterY(), MouseButton.LEFT);
        screen.update(text.getX() + 30f, text.getCenterY());
        screen.release(0f, 0f, MouseButton.LEFT);
        Checks.check("a press puts the caret where it lands, and dragging selects (" + field.getSelection() + ")",
                field.getSelectionStart() == 2 && field.getSelectionEnd() == 5);

        TextField digits = new TextField("").maxLength(4).filter(Character::isDigit);
        digits.insert("1a2b3c45");
        Checks.check("a filter and a maximum length cut what is typed or pasted (" + digits.getText() + ")",
                digits.getText().equals("1234"));
    }

    private static void dropdowns() {
        List<String> heard = new ArrayList<>();
        Column root = new Column();
        Dropdown<String> mode = root.add(new Dropdown<>(Arrays.asList("instant", "smooth", "locked"), "smooth"))
                .onChange(heard::add);
        Screen screen = screen(root);

        screen.press(centreX(mode), centreY(mode), MouseButton.LEFT);
        screen.update(0f, 0f);
        Checks.check("a press opens the choices below it, one item each", mode.isOpen()
                && screen.getPopups().size() == 1 && items(screen.getPopups().get(0)).size() == 3);
        DropdownItem locked = items(screen.getPopups().get(0)).get(2);
        Checks.check("as wide as the dropdown, the current one marked",
                Checks.eq(screen.getPopups().get(0).getBounds().getWidth(), mode.getBounds().getWidth())
                        && items(screen.getPopups().get(0)).get(1).isSelected());

        screen.press(centreX(locked), centreY(locked), MouseButton.LEFT);
        Checks.check("pressing an item chooses it and closes the list",
                mode.getSelected().equals("locked") && heard.equals(Arrays.asList("locked")) && !mode.isOpen());

        screen.update(0f, 0f);
        screen.press(centreX(mode), centreY(mode), MouseButton.LEFT);
        screen.update(0f, 0f);
        screen.press(5f, 95f, MouseButton.LEFT);
        Checks.check("a press outside closes it without choosing", !mode.isOpen() && heard.size() == 1);
        Checks.checkThrows("only one of its choices can be selected", IllegalArgumentException.class,
                () -> mode.select("unknown"));
    }

    private static void windows() {
        Stack desktop = new Stack();
        Window back = desktop.add(new Window("back"), 10f, 10f);
        back.add(new Label("one"));
        Window front = desktop.add(new Window("front"), 100f, 50f);
        Label content = front.add(new Label("two"));
        Screen screen = screen(desktop);

        Bounds title = back.part("title");
        screen.press(title.getX() + 2f, title.getY() + 2f, MouseButton.LEFT);
        Checks.check("pressing a window brings it to the front of its stack",
                desktop.getChildren().get(desktop.getChildren().size() - 1) == back);
        screen.update(title.getX() + 42f, title.getY() + 32f);
        Checks.check("dragging its title moves it with the cursor, in the same update (" + back.getBounds() + ")",
                Checks.eq(back.getBounds().getX(), 50f) && Checks.eq(back.getBounds().getY(), 40f));
        screen.update(500f, 500f);
        screen.release(0f, 0f, MouseButton.LEFT);
        screen.update(0f, 0f);
        Checks.check("kept inside the stack (" + back.getBounds() + ")",
                Checks.eq(back.getBounds().getRight(), 200f) && Checks.eq(back.getBounds().getBottom(), 100f));

        Bounds frontTitle = front.part("title");
        screen.press(frontTitle.getX() + 2f, frontTitle.getY() + 2f, MouseButton.RIGHT);
        screen.update(0f, 0f);
        Checks.check("the right button on its title collapses it to its title",
                front.isCollapsed() && !front.getPlaced().contains(content)
                        && Checks.eq(front.getBounds().getHeight(), 9f));
    }

    // ---------------------------------------------------- drag and drop, text

    private static void dragAndDrop() {
        List<String> dropped = new ArrayList<>();
        List<Object> ended = new ArrayList<>();
        Stack root = new Stack();
        Label item = root.add(new Label("item") {
            @Override
            protected void dragEnded(Object payload, Widget target) {
                ended.add(target == null ? "nothing" : target);
            }
        }, 10f, 10f);
        item.draggable(() -> "carried", () -> new Label("ghost"));
        Column bin = root.add(new Column(), 100f, 60f);
        Label inBin = bin.add(new Label("bin"));
        bin.acceptsDrops(String.class, dropped::add);
        Column numbers = root.add(new Column(), 150f, 10f);
        numbers.add(new Label("numbers"));
        numbers.acceptsDrops(Integer.class, n -> dropped.add("number"));
        Screen screen = screen(root);

        screen.press(12f, 12f, MouseButton.LEFT);
        screen.update(14f, 13f);
        Checks.check("a press on a draggable widget doesn't drag until the cursor moves past the threshold",
                !screen.isDragging());
        screen.update(30f, 20f);
        Checks.check("then it does, carrying the payload", screen.isDragging() && item.isDragged()
                && "carried".equals(screen.getDragPayload()));
        Bounds ghost = screen.getDragGhost() == null ? null : screen.getDragGhost().getBounds();
        Checks.check("its ghost is laid out where the widget was held, under the cursor (" + ghost + ")",
                ghost != null && Checks.eq(ghost.getX(), 28f) && Checks.eq(ghost.getY(), 18f)
                        && screen.widgetAt(ghost.getX() + 1f, ghost.getY() + 1f) != screen.getDragGhost());

        Bounds onNumbers = numbers.getBounds();
        screen.update(onNumbers.getX() + 2f, onNumbers.getY() + 2f);
        Checks.check("a widget that doesn't take the payload's type is not a target", screen.getDropTarget() == null);
        screen.update(centreX(inBin), centreY(inBin));
        Checks.check("over a widget inside one that takes it, that one is the target",
                screen.getDropTarget() == bin && bin.isDropTarget());
        Checks.check("letting go drops it there, and the dragged widget hears where it went",
                screen.release(centreX(inBin), centreY(inBin), MouseButton.LEFT)
                        && dropped.equals(Arrays.asList("carried")) && ended.equals(Arrays.asList(bin))
                        && !screen.isDragging() && screen.getDragGhost() == null);

        screen.press(12f, 12f, MouseButton.LEFT);
        screen.update(40f, 40f);
        Checks.check("cancelling a drag drops nothing", screen.cancel() && !screen.isDragging()
                && dropped.size() == 1 && ended.get(1).equals("nothing"));
        screen.release(0f, 0f, MouseButton.LEFT);
    }

    private static void wrapping() {
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        look.register(WidgetRenderer.of(Label.class, (label, c) -> c.paragraph(label.getText(), ANY)));
        Column root = new Column();
        Label words = root.add(new Label("aa bb cc dd"));
        Button help = root.add(new Button("x")).tooltip("aa bb cc dd");
        Screen screen = new Screen(look, root).tooltipDelay(0L).tooltipMaxWidth(40f);
        screen.resize(40f, 100f);
        screen.update(0f, 0f);
        Checks.check("a label described as a paragraph wraps to the width its container gives it ("
                + words.getBounds() + ")", Checks.eq(words.getBounds().getHeight(), 20f));

        screen.resize(200f, 100f);
        screen.update(centreX(help), centreY(help));
        screen.update(centreX(help), centreY(help));
        Bounds tip = screen.getTooltip() == null ? null : screen.getTooltip().getBounds();
        Checks.check("a tooltip wraps to the screen's tooltip width (" + tip + ")",
                tip != null && Checks.eq(tip.getWidth(), 40f) && Checks.eq(tip.getHeight(), 20f));
    }

    // --------------------------------------------------------------- helpers

    /** A container that takes every press, counting them. */
    private static final class Catcher extends Column {

        int caught;

        @Override
        protected boolean mousePressed(float x, float y, MouseButton button) {
            caught++;
            return true;
        }
    }

    private static Screen screen(Widget root) {
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        look.register(WidgetRenderer.of(Label.class, (label, c) -> c.text(label.getText(), ANY)));
        look.register(WidgetRenderer.of(Button.class, (button, c) -> c.padding(2f).text(button.getLabel(), ANY)));
        look.register(WidgetRenderer.of(Checkbox.class, (box, c) -> c.text(box.getLabel(), ANY)));
        look.register(WidgetRenderer.of(Slider.class, (slider, c) ->
                c.align(Align.STRETCH).custom("track", 0f, 4f, (x, y, w, h) -> { })));
        look.register(WidgetRenderer.of(TextField.class, (field, c) ->
                c.align(Align.STRETCH).padding(2f).column(text -> text.name("text").min(0f, 9f))));
        look.register(WidgetRenderer.of(Dropdown.class, (dropdown, c) -> c.padding(2f).text(dropdown.getLabel(), ANY)));
        look.register(WidgetRenderer.of(DropdownItem.class, (item, c) -> c.text(item.getLabel(), ANY)));
        look.register(WidgetRenderer.of(Window.class, (window, c) -> {
            c.row(title -> title.name("title").text(window.getTitle(), ANY));
            c.slot();
        }));
        Screen screen = new Screen(look, root);
        screen.resize(200f, 100f);
        screen.update(0f, 0f);
        return screen;
    }

    private static void type(Screen screen, String text) {
        for (char character : text.toCharArray()) {
            screen.typed(character);
        }
    }

    private static List<DropdownItem> items(Widget menu) {
        List<DropdownItem> found = new ArrayList<>();
        collect(menu, found);
        return found;
    }

    private static void collect(Widget widget, List<DropdownItem> out) {
        if (widget instanceof DropdownItem) {
            out.add((DropdownItem) widget);
        }
        if (widget instanceof dev.px.gui.Container) {
            for (Widget child : ((dev.px.gui.Container) widget).getChildren()) {
                collect(child, out);
            }
        }
    }

    private static float centreX(Widget widget) {
        return widget.getBounds().getCenterX();
    }

    private static float centreY(Widget widget) {
        return widget.getBounds().getCenterY();
    }
}
