package dev.px.gui.test;

import dev.px.core.layout.Bounds;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.Render2D;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.RecordingRender2D;
import dev.px.gui.Popup;
import dev.px.gui.Screen;
import dev.px.gui.Widget;
import dev.px.gui.container.Column;
import dev.px.gui.container.Scroll;
import dev.px.gui.container.Stack;
import dev.px.gui.render.WidgetRenderer;
import dev.px.gui.render.WidgetRendererRegistry;
import dev.px.gui.widget.Button;
import dev.px.gui.widget.Label;
import dev.px.gui.widget.Tooltip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The layers above a screen's root: popups, placed beside an anchor or on the
 * screen and kept on it, and the tooltip.
 *
 * <p>The anchor is a button {@code "go"}, 16 by 13, set where each check needs
 * it in a 200 by 100 screen; a popup is two labels, {@code "first"} and
 * {@code "second"}, 36 by 18. Figures are from {@code FixedFont}: six pixels a
 * character, nine tall.
 */
public final class LayerTests {

    private static final Color ANY = Color.WHITE;

    private LayerTests() {
    }

    public static void run(GuiTestClient client) {
        Checks.section("Layers");
        client.reset();

        beside();
        elsewhere();
        hitsAndDrawing();
        closing();
        tooltips();
    }

    // ------------------------------------------------------------ placement

    private static void beside() {
        Bounds below = place(20f, 20f, anchor -> Popup.below(anchor), false);
        Checks.check("below its anchor, at its own size (" + below + ")",
                same(below, 20f, 33f, 36f, 18f));
        Checks.check("a gap apart when asked (" + place(20f, 20f, anchor -> Popup.below(anchor).gap(2f), false) + ")",
                Checks.eq(place(20f, 20f, anchor -> Popup.below(anchor).gap(2f), false).getY(), 35f));
        Checks.check("as wide as its anchor when matching it",
                Checks.eq(place(20f, 20f, anchor -> Popup.below(anchor).matchWidth(), false).getWidth(), 16f));

        Bounds flipped = place(20f, 85f, anchor -> Popup.below(anchor), false);
        Checks.check("with no room below and more above, it flips above (" + flipped + ")",
                same(flipped, 20f, 67f, 36f, 18f));
        Bounds up = place(20f, 20f, anchor -> Popup.above(anchor), false);
        Checks.check("above its anchor when it fits there (" + up + ")", Checks.eq(up.getY(), 2f));
        Bounds asked = place(20f, 10f, anchor -> Popup.above(anchor), false);
        Checks.check("asked for above with too little room there, it opens below (" + asked + ")",
                Checks.eq(asked.getY(), 23f));

        Bounds cut = place(20f, 20f, anchor -> Popup.below(anchor), true);
        Checks.check("too tall for the room it opens into, it is cut to that room (" + cut + ")",
                Checks.eq(cut.getY(), 33f) && Checks.eq(cut.getHeight(), 67f));

        Bounds kept = place(190f, 20f, anchor -> Popup.below(anchor), false);
        Checks.check("near the screen's edge it is kept on it (" + kept + ")", Checks.eq(kept.getX(), 164f));

        Bounds right = place(20f, 20f, anchor -> Popup.rightOf(anchor), false);
        Checks.check("beside its anchor, level with its top (" + right + ")", same(right, 36f, 20f, 36f, 18f));
        Bounds left = place(180f, 20f, anchor -> Popup.rightOf(anchor), false);
        Checks.check("flipping to the left with no room on the right (" + left + ")", Checks.eq(left.getX(), 144f));

        Checks.checkThrows("only a popup beside a widget can match its width", IllegalArgumentException.class,
                () -> Popup.centred().matchWidth());
    }

    private static void elsewhere() {
        Checks.check("at a point, its corner is the point",
                same(place(0f, 0f, anchor -> Popup.at(50f, 40f), false), 50f, 40f, 36f, 18f));
        Checks.check("flipping up and left near the far corner",
                same(place(0f, 0f, anchor -> Popup.at(190f, 95f), false), 154f, 77f, 36f, 18f));
        Checks.check("centred, in the middle of the screen",
                same(place(0f, 0f, anchor -> Popup.centred(), false), 82f, 41f, 36f, 18f));
        Checks.check("filling, over the whole screen",
                same(place(0f, 0f, anchor -> Popup.fill(), false), 0f, 0f, 200f, 100f));
    }

    // -------------------------------------------------- hits, drawing, hover

    private static void hitsAndDrawing() {
        Stack root = new Stack();
        Button anchor = root.add(new Button("go"), 20f, 20f);
        Label under = root.add(new Label("under"), 20f, 35f);
        Screen screen = screen(root);
        screen.update(0f, 0f);
        Column menu = screen.popup(popupContent(false), Popup.below(anchor));
        screen.update(25f, 36f);

        Checks.check("a popup is hit before the root beneath it",
                screen.widgetAt(25f, 36f).getParent() == menu && screen.isPopup(menu));
        Checks.check("and hovering inside it hovers up to the popup",
                screen.getHovered().getParent() == menu && menu.isHovered() && !under.isHovered());

        RecordingRender2D recorder = new RecordingRender2D();
        Render2D previous = Render.backend2D();
        Render.install(recorder);
        try {
            screen.draw();
        } finally {
            Render.install(previous);
        }
        Checks.checkEquals("popups are drawn after the root", Arrays.asList("go", "under", "first", "second"),
                recorder.getTexts());

        screen.close(menu);
        Column modal = screen.popup(popupContent(false), Popup.fill());
        screen.update(0f, 0f);
        Checks.check("a filling popup is hit everywhere, so nothing beneath it can be",
                screen.widgetAt(22f, 22f) != anchor && screen.widgetAt(195f, 95f) == modal);
        screen.closePopups();

        Column first = screen.popup(popupContent(false), Popup.below(anchor));
        screen.update(0f, 0f);
        Widget item = first.getChildren().get(0);
        Column submenu = screen.popup(popupContent(false), Popup.rightOf(item));
        screen.update(0f, 0f);
        Checks.check("a popup can open beside a widget in another popup",
                screen.isPopup(submenu) && Checks.eq(submenu.getBounds().getX(), item.getBounds().getRight()));
        screen.close(first);
        screen.update(0f, 0f);
        Checks.check("and closes with it, its anchor gone", !screen.isPopup(submenu));

        Checks.checkThrows("a widget inside a container cannot be a popup", IllegalArgumentException.class,
                () -> screen.popup(under, Popup.centred()));
        Column twice = screen.popup(popupContent(false), Popup.centred());
        Checks.checkThrows("nor can one widget be two popups", IllegalArgumentException.class,
                () -> screen.popup(twice, Popup.centred()));
        Checks.checkThrows("nor a screen's root", IllegalArgumentException.class,
                () -> screen.popup(root, Popup.centred()));
        screen.closePopups();
    }

    // --------------------------------------------------------------- closing

    private static void closing() {
        Stack root = new Stack();
        boolean[] shown = {true};
        Button anchor = root.add(new Button("go"), 20f, 20f).visibleWhen(() -> shown[0]);
        Screen screen = screen(root);
        screen.update(0f, 0f);

        List<String> closed = new ArrayList<>();
        Column menu = screen.popup(popupContent(false), Popup.below(anchor).onClose(() -> closed.add("menu")));
        screen.update(0f, 0f);
        Checks.check("closing a popup runs its listener once and takes it off the screen",
                screen.close(menu) && closed.equals(Arrays.asList("menu")) && !screen.isPopup(menu)
                        && Checks.eq(menu.getBounds().getWidth(), 0f));
        Checks.check("closing it again does nothing", !screen.close(menu) && closed.size() == 1);

        Column again = screen.popup(popupContent(false), Popup.below(anchor).onClose(() -> closed.add("again")));
        screen.update(0f, 0f);
        shown[0] = false;
        screen.update(0f, 0f);
        Checks.check("a popup closes once its anchor is no longer laid out",
                !screen.isPopup(again) && closed.contains("again"));
        shown[0] = true;
        screen.update(0f, 0f);

        closed.clear();
        screen.popup(popupContent(false), Popup.centred().onClose(() -> closed.add("older")));
        screen.popup(popupContent(false), Popup.at(5f, 5f).onClose(() -> closed.add("newer")));
        screen.closePopups();
        Checks.checkEquals("closing them all goes newest first", Arrays.asList("newer", "older"), closed);
        Checks.check("and leaves none", screen.getPopups().isEmpty());
    }

    // -------------------------------------------------------------- tooltips

    private static void tooltips() {
        long[] time = {0L};
        Column root = new Column();
        Button plain = root.add(new Button("plain"));
        Button helpful = root.add(new Button("help")).tooltip("Shows help");
        Column group = root.add(new Column().tooltip("The group"));
        Label inside = group.add(new Label("inside"));
        String[] live = {"one"};
        Button changing = root.add(new Button("live")).tooltip(() -> live[0]);
        Screen screen = screen(root).clock(() -> time[0]);
        screen.update(0f, 0f);

        Bounds at = helpful.getBounds();
        float x = at.getX() + 2f;
        float y = at.getY() + 2f;
        screen.update(x, y);
        time[0] = 499L;
        screen.update(x, y);
        Checks.check("no tooltip shows before the cursor has rested for the delay", screen.getTooltip() == null);
        time[0] = 500L;
        screen.update(x, y);
        Tooltip tip = screen.getTooltip();
        Checks.check("after the delay, the widget's tooltip shows (" + (tip == null ? "none" : tip.getText()) + ")",
                tip != null && tip.getText().equals("Shows help"));
        Checks.check("beside the cursor (" + (tip == null ? "none" : tip.getBounds()) + ")",
                tip != null && Checks.eq(tip.getBounds().getX(), x + 12f) && Checks.eq(tip.getBounds().getY(), y + 12f));
        Checks.check("and is never hit", screen.widgetAt(x + 14f, y + 14f) != tip);

        RecordingRender2D recorder = new RecordingRender2D();
        Render2D previous = Render.backend2D();
        Render.install(recorder);
        try {
            screen.draw();
        } finally {
            Render.install(previous);
        }
        List<String> texts = recorder.getTexts();
        Checks.check("it is drawn last, over everything, by the look's Label renderer when there is no Tooltip one ("
                + texts + ")", !texts.isEmpty() && texts.get(texts.size() - 1).equals("Shows help"));

        Bounds plainAt = plain.getBounds();
        screen.update(plainAt.getX() + 2f, plainAt.getY() + 2f);
        Checks.check("a widget without a tooltip shows none", screen.getTooltip() == null);

        Bounds insideAt = inside.getBounds();
        screen.update(insideAt.getX() + 2f, insideAt.getY() + 2f);
        time[0] = 1000L;
        screen.update(insideAt.getX() + 2f, insideAt.getY() + 2f);
        Checks.check("hovering inside a container with a tooltip shows the container's",
                screen.getTooltip() != null && screen.getTooltip().getText().equals("The group"));

        Bounds liveAt = changing.getBounds();
        screen.update(liveAt.getX() + 2f, liveAt.getY() + 2f);
        Checks.check("moving to another widget starts the delay again", screen.getTooltip() == null);
        time[0] = 1500L;
        screen.update(liveAt.getX() + 2f, liveAt.getY() + 2f);
        live[0] = "two";
        screen.update(liveAt.getX() + 2f, liveAt.getY() + 2f);
        Checks.check("a tooltip read from a supplier changes as it does",
                screen.getTooltip() != null && screen.getTooltip().getText().equals("two"));

        screen.tooltipDelay(0L);
        Bounds helpAt = helpful.getBounds();
        float farX = 195f;
        screen.update(farX, helpAt.getY() + 2f);
        Tooltip edge = screen.getTooltip();
        Checks.check("with no delay it shows at once, and flips left of the cursor near the edge ("
                + (edge == null ? "none" : edge.getBounds()) + ")",
                edge != null && Checks.eq(edge.getBounds().getRight(), farX - 12f));
        Checks.checkThrows("a tooltip delay cannot be negative", IllegalArgumentException.class,
                () -> screen.tooltipDelay(-1L));
    }

    // --------------------------------------------------------------- helpers

    /**
     * Opens a popup placed as {@code placement} says, given a button laid out at
     * (x, y) to anchor it to, and says where it went.
     */
    private static Bounds place(float x, float y, Function<Button, Popup> placement, boolean tall) {
        Stack root = new Stack();
        Button anchor = root.add(new Button("go"), x, y);
        Screen screen = screen(root);
        screen.update(0f, 0f);
        Column popup = screen.popup(popupContent(tall), placement.apply(anchor));
        screen.update(0f, 0f);
        return popup.getBounds();
    }

    private static Column popupContent(boolean tall) {
        Column column = new Column();
        if (tall) {
            Scroll list = column.add(new Scroll()).grow(1f);
            for (int i = 0; i < 20; i++) {
                list.add(new Label("row " + i));
            }
        } else {
            column.add(new Label("first"));
            column.add(new Label("second"));
        }
        return column;
    }

    private static Screen screen(Widget root) {
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        look.register(WidgetRenderer.of(Label.class, (label, c) -> c.text(label.getText(), ANY)));
        look.register(WidgetRenderer.of(Button.class, (button, c) -> c.padding(2f).text(button.getLabel(), ANY)));
        Screen screen = new Screen(look, root);
        screen.resize(200f, 100f);
        return screen;
    }

    private static boolean same(Bounds at, float x, float y, float width, float height) {
        return at != null && Checks.eq(at.getX(), x) && Checks.eq(at.getY(), y)
                && Checks.eq(at.getWidth(), width) && Checks.eq(at.getHeight(), height);
    }
}
