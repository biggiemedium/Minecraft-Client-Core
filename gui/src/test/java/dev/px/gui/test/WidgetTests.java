package dev.px.gui.test;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Shape;
import dev.px.core.layout.Size;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.Render2D;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.RecordingRender2D;
import dev.px.gui.Container;
import dev.px.gui.Screen;
import dev.px.gui.Widget;
import dev.px.gui.container.Column;
import dev.px.gui.render.WidgetRenderer;
import dev.px.gui.render.WidgetRendererRegistry;
import dev.px.gui.widget.Button;
import dev.px.gui.widget.Label;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The widget foundation: renderers found by type, the container slot, layout,
 * visibility, hover, parts and drawing.
 *
 * <p>Every renderer here is the test's own, as a client's would be, and every
 * size is worked out on paper from {@code FixedFont}: six pixels a character,
 * nine tall. Nothing here needs a window.
 */
public final class WidgetTests {

    private static final Color ANY = Color.WHITE;

    private WidgetTests() {
    }

    public static void run(GuiTestClient client) {
        Checks.section("Widgets");
        client.reset();

        registry();
        missingAndBroken();
        describedOnce();
        slots();
        natural();
        visibility();
        hover();
        partsAndShapes();
        drawing();
        listeners();
        tree();
    }

    // ------------------------------------------------------------- registry

    private static void registry() {
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        WidgetRenderer<Widget> fallback = look.register(WidgetRenderer.of(Widget.class, (w, c) -> { }));
        WidgetRenderer<Button> buttons = look.register(WidgetRenderer.of(Button.class, (b, c) -> { }));

        Checks.check("the most specific renderer claims a widget",
                look.rendererFor(new Button("a")) == buttons && look.rendererFor(new Label("a")) == fallback);
        Checks.check("a subclass with no renderer of its own takes its parent type's",
                look.rendererFor(new FancyButton()) == buttons);
        WidgetRenderer<FancyButton> fancy = look.register(WidgetRenderer.of(FancyButton.class, (b, c) -> { }));
        Checks.check("and one registered for it takes over for it alone",
                look.rendererFor(new FancyButton()) == fancy && look.rendererFor(new Button("a")) == buttons);

        WidgetRenderer<Button> replacement = look.replace(WidgetRenderer.of(Button.class, (b, c) -> { }));
        Checks.check("replace swaps a type's renderer deliberately",
                look.rendererFor(new Button("a")) == replacement && look.size() == 3);
    }

    private static void missingAndBroken() {
        RecordingLogger logger = new RecordingLogger();
        WidgetRendererRegistry look = new WidgetRendererRegistry(logger);
        look.register(WidgetRenderer.of(Button.class, (b, c) -> {
            throw new IllegalStateException("broken on purpose");
        }));

        Column root = new Column();
        Label first = root.add(new Label("one"));
        Label second = root.add(new Label("two"));
        Button broken = root.add(new Button("x"));
        Label after = root.add(new Label("after"));
        Screen screen = sized(look, root);
        screen.update(0f, 0f);
        screen.update(0f, 0f);
        screen.update(0f, 0f);

        Checks.checkEquals("a leaf nothing claims is warned about once per type, however many and however often",
                1f, logger.warningCount());
        Checks.check("and lays out empty (" + first.getBounds() + ")",
                Checks.eq(first.getBounds().getHeight(), 0f) && Checks.eq(second.getBounds().getHeight(), 0f));
        Checks.checkEquals("a renderer that throws is logged once", 1f, logger.errorCount());
        Checks.check("and its widget lays out empty while the rest still lays out (" + after.getBounds() + ")",
                Checks.eq(broken.getBounds().getHeight(), 0f) && Checks.eq(after.getBounds().getWidth(), 200f));

        RecordingLogger quiet = new RecordingLogger();
        WidgetRendererRegistry labelsOnly = new WidgetRendererRegistry(quiet);
        labelsOnly.register(WidgetRenderer.of(Label.class, (l, c) -> c.text(l.getText(), ANY)));
        Column plain = new Column();
        Label top = plain.add(new Label("top"));
        Label below = plain.add(new Label("below"));
        sized(labelsOnly, plain).update(0f, 0f);
        Checks.checkEquals("a container nothing claims is not warned about", 0f, quiet.warningCount());
        Checks.check("and lays its children out as a bare slot (" + below.getBounds() + ")",
                Checks.eq(top.getBounds().getY(), 0f) && Checks.eq(below.getBounds().getY(), 9f)
                        && Checks.eq(below.getBounds().getWidth(), 200f));
    }

    private static void describedOnce() {
        Map<Widget, Integer> counts = new HashMap<>();
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        look.register(WidgetRenderer.of(Column.class, (column, c) -> {
            counts.merge(column, 1, Integer::sum);
            c.padding(2f).slot();
        }));
        look.register(WidgetRenderer.of(Label.class, (label, c) -> {
            counts.merge(label, 1, Integer::sum);
            c.text(label.getText(), ANY);
        }));

        Column root = new Column();
        Column middle = root.add(new Column());
        Column inner = middle.add(new Column());
        inner.add(new Label("deep"));
        middle.add(new Label("middle"));
        sized(look, root).update(0f, 0f);

        boolean once = counts.size() == 5;
        for (int count : counts.values()) {
            once &= count == 1;
        }
        Checks.check("every widget is described once an update, however deep and however often measured ("
                + counts.values() + ")", once);
    }

    // ----------------------------------------------------------------- slots

    private static void slots() {
        WidgetRendererRegistry look = look();
        look.register(WidgetRenderer.of(Titled.class, (titled, c) -> c.text("Title", ANY)));
        look.register(WidgetRenderer.of(Window.class, (window, c) -> {
            c.row(title -> title.padding(2f).text("Win", ANY));
            c.column(body -> body.padding(4f).slot());
        }));

        Column root = new Column().gap(4f);
        Label first = root.add(new Label("first"));
        Label second = root.add(new Label("second"));
        Titled titled = root.add(new Titled());
        Label underTitle = titled.add(new Label("child"));
        Window window = root.add(new Window());
        Label inWindow = window.add(new Label("inside"));
        Screen screen = sized(look, root);
        screen.update(0f, 0f);

        Checks.check("the root fills the screen (" + root.getBounds() + ")",
                Checks.eq(root.getBounds().getWidth(), 200f) && Checks.eq(root.getBounds().getHeight(), 100f));
        Checks.check("children go in the slot, inside the renderer's padding (" + first.getBounds() + ")",
                Checks.eq(first.getBounds().getX(), 6f) && Checks.eq(first.getBounds().getY(), 6f)
                        && Checks.eq(first.getBounds().getWidth(), 188f));
        Checks.check("each below the last, the column's gap apart (" + second.getBounds() + ")",
                Checks.eq(second.getBounds().getY(), 19f));

        Bounds titledAt = titled.getBounds();
        Checks.check("a description without a slot has its children after what it described ("
                + underTitle.getBounds() + ")", Checks.eq(underTitle.getBounds().getY(), titledAt.getY() + 9f)
                && Checks.eq(titledAt.getHeight(), 18f));

        Bounds windowAt = window.getBounds();
        Bounds inside = inWindow.getBounds();
        Checks.check("a slot in a nested box gets that box's padding too (" + inside + " in " + windowAt + ")",
                Checks.eq(inside.getX(), windowAt.getX() + 4f) && Checks.eq(inside.getY(), windowAt.getY() + 17f)
                        && Checks.eq(inside.getWidth(), windowAt.getWidth() - 8f));
        Checks.checkEquals("and the container is as tall as its look plus its children: 13 + 4 + 9 + 4",
                30f, windowAt.getHeight());
    }

    private static void natural() {
        WidgetRendererRegistry look = look();
        Column root = new Column();
        Shelf shelf = root.add(new Shelf());
        Label narrow = shelf.add(new Label("ab"));
        Column stack = shelf.add(new Column());
        stack.add(new Label("abc"));
        stack.add(new Label("a"));
        sized(look, root).update(0f, 0f);

        Bounds stackAt = stack.getBounds();
        Checks.check("a container asked for its natural size is as wide as its widest child plus its look ("
                + stackAt + ")", Checks.eq(stackAt.getWidth(), 30f) && Checks.eq(stackAt.getHeight(), 30f));
        Checks.check("and is placed where its container's arrangement says (" + narrow.getBounds() + ", " + stackAt + ")",
                Checks.eq(narrow.getBounds().getWidth(), 12f) && Checks.eq(stackAt.getX(), narrow.getBounds().getRight()));
    }

    // ------------------------------------------------------------ visibility

    private static void visibility() {
        boolean[] shown = {true};
        boolean[] enabled = {true};
        int[] presses = {0};
        Column root = new Column();
        Label hidden = root.add(new Label("hidden")).visibleWhen(() -> shown[0]);
        Label next = root.add(new Label("next"));
        Column group = root.add(new Column()).enabledWhen(() -> enabled[0]);
        Button button = group.add(new Button("go").onPress(() -> presses[0]++));
        Screen screen = sized(look(), root);
        screen.update(0f, 0f);
        float nextY = next.getBounds().getY();

        shown[0] = false;
        screen.update(0f, 0f);
        Checks.check("a hidden widget takes no room (" + next.getBounds() + ")",
                Checks.eq(next.getBounds().getY(), nextY - 9f) && !root.getPlaced().contains(hidden));
        Checks.check("and is not hit where it was", screen.widgetAt(10f, 7f) != hidden);
        shown[0] = true;
        screen.update(0f, 0f);

        enabled[0] = false;
        Checks.check("a disabled container disables what is inside it", !button.isEnabled());
        Checks.check("whose actions then refuse", !button.press() && presses[0] == 0);
        Checks.check("and it still lays out and draws (" + button.getBounds() + ")",
                button.getBounds().getHeight() > 0f && group.getPlaced().contains(button));
        enabled[0] = true;
        Checks.check("enabled again, it acts", button.press() && presses[0] == 1);
    }

    // ----------------------------------------------------------------- hover

    private static void hover() {
        Column root = new Column();
        Label sibling = root.add(new Label("sibling"));
        Column group = root.add(new Column());
        Button button = group.add(new Button("go"));
        Screen screen = sized(look(), root);
        screen.update(0f, 0f);

        Bounds at = button.getBounds();
        screen.update(at.getCenterX(), at.getCenterY());
        Checks.check("the widget under the cursor is the screen's hovered one", screen.getHovered() == button);
        Checks.check("it and every container around it read as hovered",
                button.isHovered() && group.isHovered() && root.isHovered());
        Checks.check("a widget beside it does not", !sibling.isHovered());

        screen.update(-5f, -5f);
        Checks.check("off everything, nothing is hovered",
                screen.getHovered() == null && !button.isHovered() && !group.isHovered() && !root.isHovered());

        screen.update(at.getCenterX(), at.getCenterY());
        group.visibleWhen(() -> false);
        Checks.check("between updates, hit testing reads the last layout",
                screen.widgetAt(at.getCenterX(), at.getCenterY()) == button);
        screen.update(at.getCenterX(), at.getCenterY());
        Checks.check("and the next update forgets what was hidden",
                screen.widgetAt(at.getCenterX(), at.getCenterY()) == root && !button.isHovered());
    }

    // --------------------------------------------------------- parts, shapes

    private static void partsAndShapes() {
        WidgetRendererRegistry look = look();
        look.replace(WidgetRenderer.of(Button.class, (button, c) -> {
            c.padding(2f);
            c.row(r -> {
                r.text(button.getLabel(), ANY);
                r.custom("knob", 4f, 4f, (x, y, w, h) -> { });
            });
        }));
        look.register(WidgetRenderer.of(RoundButton.class, (button, c) -> c.min(20f, 20f),
                (button, bounds) -> Shape.circle(bounds.getX() + 10f, bounds.getY() + 10f, 10f)));

        Column root = new Column();
        Button button = root.add(new Button("go"));
        RoundButton round = root.add(new RoundButton());
        Screen screen = sized(look, root);
        screen.update(0f, 0f);

        Bounds knob = button.part("knob");
        Bounds at = button.getBounds();
        Checks.check("a widget keeps the parts its renderer named, where they were laid out (" + knob + ")",
                knob != null && Checks.eq(knob.getX(), at.getX() + 2f + 12f) && Checks.eq(knob.getWidth(), 4f)
                        && button.parts().keySet().equals(new java.util.HashSet<>(Arrays.asList("knob"))));
        Checks.check("an unknown part is null", button.part("nothing") == null);

        Bounds roundAt = round.getBounds();
        Checks.check("a renderer's own shape is what is hit: the centre of a round button",
                screen.widgetAt(roundAt.getX() + 10f, roundAt.getY() + 10f) == round);
        Checks.check("and not its corner, which falls through to its container",
                screen.widgetAt(roundAt.getX() + 7f + 0.5f, roundAt.getY() + 7f) == round
                        && screen.widgetAt(roundAt.getX() + 1f, roundAt.getY() + 1f) == root);
    }

    // --------------------------------------------------------------- drawing

    private static void drawing() {
        WidgetRendererRegistry look = look();
        look.register(WidgetRenderer.of(Titled.class, (titled, c) -> c.text("Title", ANY)));
        Column root = new Column();
        Titled titled = root.add(new Titled());
        titled.add(new Label("child"));
        root.add(new Label("last"));
        Screen screen = sized(look, root);

        RecordingRender2D recorder = new RecordingRender2D();
        Render2D previous = Render.backend2D();
        Render.install(recorder);
        try {
            screen.draw();
            Checks.check("nothing is drawn before the first update", recorder.getTexts().isEmpty());

            screen.update(0f, 0f);
            screen.draw();
            Checks.checkEquals("each widget draws before its children, in order",
                    Arrays.asList("Title", "child", "last"), recorder.getTexts());

            recorder.clear();
            titled.visibleWhen(() -> false);
            screen.draw();
            Checks.check("drawing goes by the last update, so a widget hidden since is drawn until the next",
                    recorder.getTexts().contains("child"));
            titled.visibleWhen(() -> true);

            recorder.clear();
            root.visibleWhen(() -> false);
            screen.update(0f, 0f);
            screen.draw();
            Checks.check("a hidden root draws nothing", recorder.getTexts().isEmpty());
        } finally {
            Render.install(previous);
        }
    }

    // ------------------------------------------------------------- listeners

    private static void listeners() {
        List<String> heard = new ArrayList<>();
        Button button = new Button("go").onPress(() -> heard.add("first")).onPress(() -> heard.add("second"));
        button.press();
        button.press();
        Checks.checkEquals("a press runs every listener, in the order added, each time",
                Arrays.asList("first", "second", "first", "second"), heard);
    }

    // ------------------------------------------------------------------ tree

    private static void tree() {
        WidgetRendererRegistry look = look();
        Checks.checkThrows("a screen needs a look", IllegalArgumentException.class, () -> new Screen(null, new Label("a")));

        Column holder = new Column();
        Label inside = holder.add(new Label("a"));
        Checks.checkThrows("a widget inside a container cannot be a root", IllegalArgumentException.class,
                () -> new Screen(look, inside));

        Column root = new Column();
        new Screen(look, root);
        Checks.checkThrows("nor can one root serve two screens", IllegalArgumentException.class,
                () -> new Screen(look, root));
        Checks.checkThrows("and a root cannot be added to a container", IllegalArgumentException.class,
                () -> holder.add(root));

        Column outer = new Column();
        Column nested = outer.add(new Column());
        Checks.checkThrows("a container cannot be added inside itself", IllegalArgumentException.class,
                () -> nested.add(outer));

        Column other = new Column();
        other.add(inside);
        Checks.check("adding a child elsewhere moves it",
                inside.getParent() == other && !holder.getChildren().contains(inside));

        Column shown = new Column();
        Label leaving = shown.add(new Label("leaving"));
        sized(look, shown).update(0f, 0f);
        shown.remove(leaving);
        Checks.check("a removed child keeps no layout",
                leaving.getParent() == null && Checks.eq(leaving.getBounds().getWidth(), 0f));

        long[] time = {1234L};
        Screen timed = new Screen(look, new Column()).clock(() -> time[0]);
        Checks.checkEquals("a screen's time comes from the clock it was given", 1234f, (float) timed.now());
    }

    // --------------------------------------------------------------- helpers

    /** Labels as plain text, buttons padded by two, columns padded by six: the test's look. */
    private static WidgetRendererRegistry look() {
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        look.register(WidgetRenderer.of(Label.class, (label, c) -> c.text(label.getText(), ANY)));
        look.register(WidgetRenderer.of(Button.class, (button, c) -> c.padding(2f).text(button.getLabel(), ANY)));
        look.register(WidgetRenderer.of(Column.class, (column, c) -> c.padding(6f).slot()));
        return look;
    }

    private static Screen sized(WidgetRendererRegistry look, Widget root) {
        Screen screen = new Screen(look, root);
        screen.resize(200f, 100f);
        return screen;
    }

    private static final class FancyButton extends Button {
        FancyButton() {
            super("fancy");
        }
    }

    private static final class RoundButton extends Button {
        RoundButton() {
            super("round");
        }
    }

    /** A column whose renderer describes a title and no slot. */
    private static final class Titled extends Column {
    }

    /** A column whose renderer has a title bar and a padded body. */
    private static final class Window extends Column {
    }

    /**
     * Children side by side at their natural widths: the shape of a row, written
     * with only what {@link Container} gives a subclass.
     */
    private static final class Shelf extends Container {

        @Override
        protected float childrenHeight(List<Widget> visible, float width) {
            return childrenNatural(visible).getHeight();
        }

        @Override
        protected Size childrenNatural(List<Widget> visible) {
            float width = 0f;
            float height = 0f;
            for (Widget child : visible) {
                Size size = naturalSizeOf(child);
                width += size.getWidth();
                height = Math.max(height, size.getHeight());
            }
            return Size.of(width, height);
        }

        @Override
        protected void arrange(List<Widget> visible, Bounds slot) {
            float x = slot.getX();
            for (Widget child : visible) {
                Size size = naturalSizeOf(child);
                place(child, Bounds.of(x, slot.getY(), size.getWidth(), size.getHeight()));
                x += size.getWidth();
            }
        }
    }
}
