package dev.px.gui.test;

import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.Render2D;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.RecordingRender2D;
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
import dev.px.gui.widget.Label;

import java.util.ArrayList;
import java.util.List;

/**
 * The containers: a column's alignment and growing children, rows, grids,
 * stacks and scrolling lists.
 *
 * <p>Labels are plain text and buttons are padded by two; containers have no
 * renderer, so each is a bare slot. Every figure is worked out on paper from
 * {@code FixedFont}: six pixels a character, nine tall, on a 200 by 100 screen.
 */
public final class ContainerTests {

    private static final Color ANY = Color.WHITE;

    private ContainerTests() {
    }

    public static void run(GuiTestClient client) {
        Checks.section("Containers");
        client.reset();

        columns();
        growing();
        rows();
        grids();
        stacks();
        scrolling();
        scrollGeometry();
    }

    // --------------------------------------------------------------- columns

    private static void columns() {
        Column root = new Column().align(Align.CENTER);
        Label centred = root.add(new Label("ab"));
        update(root);
        Checks.check("a column aligned to the centre sets a child at its own width, centred ("
                + centred.getBounds() + ")", Checks.eq(centred.getBounds().getX(), 94f)
                && Checks.eq(centred.getBounds().getWidth(), 12f));

        root.align(Align.END);
        update(screenOf(root));
        Checks.check("and aligned to the end, at the far edge (" + centred.getBounds() + ")",
                Checks.eq(centred.getBounds().getX(), 188f));

        Column spanning = new Column();
        Label wide = spanning.add(new Label("ab"));
        update(spanning);
        Checks.check("by default children span the column", Checks.eq(wide.getBounds().getWidth(), 200f));
    }

    private static void growing() {
        Column root = new Column();
        Label top = root.add(new Label("a"));
        Label filler = root.add(new Label("b")).grow(1f);
        Label bottom = root.add(new Label("c"));
        update(root);
        Checks.check("a growing child takes the room left over: 9 + (100 - 27) (" + filler.getBounds() + ")",
                Checks.eq(filler.getBounds().getHeight(), 82f));
        Checks.check("and the rest keep their size, after it (" + bottom.getBounds() + ")",
                Checks.eq(top.getBounds().getHeight(), 9f) && Checks.eq(bottom.getBounds().getY(), 91f));

        Column weighted = new Column();
        Label one = weighted.add(new Label("a")).grow(1f);
        Label three = weighted.add(new Label("b")).grow(3f);
        update(weighted);
        Checks.check("weights share the room: 82 split one to three (" + one.getBounds().getHeight() + ", "
                + three.getBounds().getHeight() + ")", Checks.eq(one.getBounds().getHeight(), 29.5f)
                && Checks.eq(three.getBounds().getHeight(), 70.5f));

        Column tight = new Column();
        Label first = tight.add(new Label("a"));
        Label giving = tight.add(new Label("b")).grow(1f);
        tight.add(new Label("c"));
        Label last = tight.add(new Label("d"));
        Screen screen = screenOf(tight);
        screen.resize(200f, 30f);
        screen.update(0f, 0f);
        Checks.check("given less room than it measured, a growing child gives up the difference ("
                + giving.getBounds() + ")", Checks.eq(giving.getBounds().getHeight(), 3f)
                && Checks.eq(first.getBounds().getHeight(), 9f) && Checks.eq(last.getBounds().getY(), 21f));

        Column cramped = new Column();
        Label squeezed = cramped.add(new Label("b")).grow(1f);
        Label after = cramped.add(new Label("x"));
        cramped.add(new Label("x"));
        cramped.add(new Label("x"));
        Screen tiny = screenOf(cramped);
        tiny.resize(200f, 10f);
        tiny.update(0f, 0f);
        Checks.check("but never below nothing, however little room there is, so what follows it doesn't move up ("
                + squeezed.getBounds() + ", " + after.getBounds() + ")",
                Checks.eq(squeezed.getBounds().getHeight(), 0f) && Checks.eq(after.getBounds().getY(), 0f));

        Checks.checkThrows("a grow weight cannot be negative", IllegalArgumentException.class,
                () -> new Label("x").grow(-1f));
    }

    // ------------------------------------------------------------------ rows

    private static void rows() {
        Column root = new Column();
        Row row = root.add(new Row().gap(4f));
        Label first = row.add(new Label("ab"));
        Label second = row.add(new Label("abcd"));
        update(root);
        Checks.check("a row sets children side by side at their own width, the gap apart (" + second.getBounds() + ")",
                Checks.eq(first.getBounds().getWidth(), 12f) && Checks.eq(second.getBounds().getX(), 16f)
                        && Checks.eq(second.getBounds().getWidth(), 24f));
        Checks.checkEquals("and is as tall as its tallest child", 9f, row.getBounds().getHeight());

        Column filled = new Column();
        Row bar = filled.add(new Row());
        bar.add(new Label("ab"));
        Label middle = bar.add(new Label("x")).grow(1f);
        Label end = bar.add(new Label("abc"));
        update(filled);
        Checks.check("a growing child takes the width left over, pushing the rest to the far edge ("
                + middle.getBounds() + ", " + end.getBounds() + ")", Checks.eq(middle.getBounds().getWidth(), 170f)
                && Checks.eq(end.getBounds().getX(), 182f));

        Column aligned = new Column();
        Row centred = aligned.add(new Row().align(Align.CENTER));
        Button tall = centred.add(new Button("go"));
        Label shorter = centred.add(new Label("hi"));
        update(aligned);
        Checks.check("aligned to the centre, a shorter child sits midway down the row (" + shorter.getBounds() + ")",
                Checks.eq(shorter.getBounds().getY(), 2f) && Checks.eq(shorter.getBounds().getHeight(), 9f)
                        && Checks.eq(tall.getBounds().getHeight(), 13f));

        Column stretched = new Column();
        Row spans = stretched.add(new Row());
        spans.add(new Button("go"));
        Label spanned = spans.add(new Label("hi"));
        update(stretched);
        Checks.checkEquals("by default children span the row's height", 13f, spanned.getBounds().getHeight());

        Stack loose = new Stack();
        Row natural = loose.add(new Row().gap(4f), Align.START, Align.START);
        natural.add(new Label("ab"));
        natural.add(new Label("abcd"));
        update(loose);
        Checks.checkEquals("a row asked for its own width is its children side by side: 12 + 4 + 24",
                40f, natural.getBounds().getWidth());
    }

    // ----------------------------------------------------------------- grids

    private static void grids() {
        Column root = new Column();
        Grid grid = root.add(new Grid(3).gap(2f));
        List<Widget> cells = new ArrayList<>();
        cells.add(grid.add(new Label("a")));
        cells.add(grid.add(new Button("b")));
        cells.add(grid.add(new Label("c")));
        cells.add(grid.add(new Label("d")));
        cells.add(grid.add(new Label("e")));
        update(root);

        float cell = (200f - 4f) / 3f;
        Bounds third = cells.get(2).getBounds();
        Bounds fourth = cells.get(3).getBounds();
        Checks.check("a grid's cells share its width equally, the gap apart (" + third + ")",
                Checks.eq(third.getX(), 2f * (cell + 2f)) && Checks.eq(third.getWidth(), cell));
        Checks.check("filling left to right, then down a row (" + fourth + ")",
                Checks.eq(fourth.getX(), 0f) && Checks.eq(fourth.getY(), 15f));
        Checks.check("every cell in a row as tall as its tallest child (" + cells.get(0).getBounds() + ")",
                Checks.eq(cells.get(0).getBounds().getHeight(), 13f));
        Checks.checkEquals("and the grid as tall as its rows and the gap between them: 13 + 2 + 9",
                24f, grid.getBounds().getHeight());

        Stack loose = new Stack();
        Grid natural = loose.add(new Grid(3).gap(2f), Align.START, Align.START);
        natural.add(new Label("ab"));
        natural.add(new Label("abcd"));
        update(loose);
        Checks.checkEquals("asked for its own width, every column is as wide as the widest child: 3 x 24 + 4",
                76f, natural.getBounds().getWidth());

        Checks.checkThrows("a grid needs a column", IllegalArgumentException.class, () -> new Grid(0));
    }

    // ---------------------------------------------------------------- stacks

    private static void stacks() {
        Stack stack = new Stack();
        Label filling = stack.add(new Label("fill"));
        Label centred = stack.add(new Label("ab"), Align.CENTER, Align.CENTER);
        Label edge = stack.add(new Label("ab"), Align.END, Align.STRETCH);
        Label placed = stack.add(new Label("ab"), 40f, 30f);
        Screen screen = screenOf(stack);
        screen.update(0f, 0f);

        Checks.check("a child added plainly fills the stack (" + filling.getBounds() + ")",
                Checks.eq(filling.getBounds().getWidth(), 200f) && Checks.eq(filling.getBounds().getHeight(), 100f));
        Checks.check("an aligned child keeps its own size, centred (" + centred.getBounds() + ")",
                Checks.eq(centred.getBounds().getX(), 94f) && Checks.eq(centred.getBounds().getY(), 45.5f)
                        && Checks.eq(centred.getBounds().getWidth(), 12f));
        Checks.check("stretching fills one axis alone (" + edge.getBounds() + ")",
                Checks.eq(edge.getBounds().getX(), 188f) && Checks.eq(edge.getBounds().getHeight(), 100f));
        Checks.check("a child at an offset sits there at its own size (" + placed.getBounds() + ")",
                Checks.eq(placed.getBounds().getX(), 40f) && Checks.eq(placed.getBounds().getY(), 30f));

        stack.move(placed, 150f, 95f);
        screen.update(0f, 0f);
        Checks.check("moved past the edge, it keeps its size, as a dragged window should (" + placed.getBounds() + ")",
                Checks.eq(placed.getBounds().getY(), 95f) && Checks.eq(placed.getBounds().getHeight(), 9f));
        Checks.check("later children are hit before earlier ones",
                screen.widgetAt(155f, 97f) == placed && screen.widgetAt(5f, 5f) == filling);

        Stack small = new Stack();
        Column tall = small.add(new Column(), Align.START, Align.START);
        for (int i = 0; i < 20; i++) {
            tall.add(new Label("row"));
        }
        update(small);
        Checks.check("an aligned child taller than the stack is cut to it (" + tall.getBounds() + ")",
                Checks.eq(tall.getBounds().getHeight(), 100f));

        Checks.checkThrows("only a child can be moved in a stack", IllegalArgumentException.class,
                () -> stack.move(new Label("stranger"), 0f, 0f));
        stack.remove(placed);
        stack.add(placed);
        screen.update(0f, 0f);
        Checks.check("a child taken out and added again forgets where it was, and fills",
                Checks.eq(placed.getBounds().getWidth(), 200f));
    }

    // ------------------------------------------------------------- scrolling

    private static void scrolling() {
        Column root = new Column();
        root.add(new Label("above"));
        Scroll list = root.add(new Scroll()).grow(1f);
        List<Label> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rows.add(list.add(new Label("row " + i)));
        }
        Screen screen = screenOf(root);
        screen.update(0f, 0f);

        Bounds viewport = list.getViewport();
        Checks.check("a growing scroll's viewport is the room left, not its content (" + viewport + ")",
                Checks.eq(viewport.getY(), 9f) && Checks.eq(viewport.getHeight(), 91f));
        Checks.check("its content is every row: 20 x 9, so 89 is out of view (" + list.getContentHeight()
                + ", " + list.getMaxScroll() + ")", Checks.eq(list.getContentHeight(), 180f)
                && Checks.eq(list.getMaxScroll(), 89f) && list.canScroll());
        Checks.check("only the rows in view are laid out and drawn (" + list.getPlaced().size() + " of 20)",
                list.getPlaced().size() == 11 && list.getPlaced().get(0) == rows.get(0));

        list.scrollBy(20f);
        screen.update(0f, 0f);
        Checks.check("scrolled by 20, the rows move up and the first two leave the view ("
                + rows.get(2).getBounds() + ")", list.getPlaced().get(0) == rows.get(2)
                && Checks.eq(rows.get(2).getBounds().getY(), 7f));

        list.scrollTo(1000f);
        Checks.checkEquals("scrolling past the end stops at it", 89f, list.getScrollY());
        list.scrollTo(-5f);
        Checks.checkEquals("and past the start, at the start", 0f, list.getScrollY());

        list.scrollIntoView(rows.get(15));
        screen.update(0f, 0f);
        Checks.checkEquals("scrolling a row into view moves the least distance that shows it: 144 - 91",
                53f, list.getScrollY());
        list.scrollIntoView(rows.get(1));
        screen.update(0f, 0f);
        Checks.checkEquals("and back up, to its top", 9f, list.getScrollY());
        Checks.check("a widget outside the scroll cannot be scrolled into view",
                !list.scrollIntoView(new Label("elsewhere")));

        Scroll early = new Scroll();
        for (int i = 0; i < 20; i++) {
            early.add(new Label("row " + i));
        }
        early.scrollTo(30f);
        Column holder = new Column();
        holder.add(early).grow(1f);
        update(holder);
        Checks.checkEquals("a scroll asked for before the first update is kept until there is content to clamp it to",
                30f, early.getScrollY());
        Scroll fresh = new Scroll();
        for (int i = 0; i < 20; i++) {
            fresh.add(new Label("row " + i));
        }
        fresh.scrollTo(1000f);
        Column freshHolder = new Column();
        freshHolder.add(fresh).grow(1f);
        update(freshHolder);
        Checks.checkEquals("and clamped then, if it asked for more than there is: 180 - 100",
                80f, fresh.getScrollY());

        Column capped = new Column();
        Scroll limited = capped.add(new Scroll().maxHeight(30f));
        for (int i = 0; i < 20; i++) {
            limited.add(new Label("row " + i));
        }
        update(capped);
        Checks.checkEquals("a maximum height caps the viewport", 30f, limited.getViewport().getHeight());

        Column roomy = new Column();
        Scroll shortList = roomy.add(new Scroll()).grow(1f);
        shortList.add(new Label("one"));
        Label stretchy = shortList.add(new Label("two")).grow(1f);
        update(roomy);
        Checks.check("content shorter than the viewport doesn't scroll, and a growing row fills it ("
                + stretchy.getBounds() + ")", !shortList.canScroll() && Checks.eq(stretchy.getBounds().getHeight(), 91f));
    }

    private static void scrollGeometry() {
        WidgetRendererRegistry look = look();
        look.register(WidgetRenderer.of(Scroll.class, (scroll, c) -> c.padding(5f).slot()));
        Column root = new Column();
        Scroll list = root.add(new Scroll()).grow(1f);
        for (int i = 0; i < 20; i++) {
            list.add(new Label("row " + i));
        }
        Screen screen = new Screen(look, root);
        screen.resize(200f, 100f);
        list.scrollTo(4f);
        screen.update(0f, 0f);

        Bounds viewport = list.getViewport();
        Widget cut = list.getPlaced().get(0);
        Checks.check("the first row in view sticks out above the viewport (" + cut.getBounds() + " vs " + viewport + ")",
                cut.getBounds().getY() < viewport.getY());
        Checks.check("but is not hit where it is clipped off: the scroll is",
                screen.widgetAt(10f, cut.getBounds().getY() + 1f) == list);
        Checks.check("and is hit where it shows", screen.widgetAt(10f, viewport.getY() + 1f) == cut);

        RecordingRender2D recorder = new RecordingRender2D();
        Render2D previous = Render.backend2D();
        Render.install(recorder);
        try {
            screen.draw();
        } finally {
            Render.install(previous);
        }
        float[] clip = recorder.getClips().isEmpty() ? null : recorder.getClips().get(0);
        Checks.check("its rows are drawn clipped to the viewport, and the clip is popped again",
                clip != null && Checks.eq(clip[1], viewport.getY()) && Checks.eq(clip[3], viewport.getHeight())
                        && recorder.getClipDepth() == 0);

        Bounds track = Bounds.of(0f, 0f, 4f, 100f);
        list.scrollTo(0f);
        Bounds thumb = list.thumbIn(track, 0f);
        float length = 100f * viewport.getHeight() / list.getContentHeight();
        Checks.check("a thumb is as long as the share of content in view, at the top when scrolled to it ("
                + thumb + ")", thumb != null && Checks.eq(thumb.getHeight(), length) && Checks.eq(thumb.getY(), 0f));
        list.scrollTo(list.getMaxScroll());
        Bounds bottom = list.thumbIn(track, 0f);
        Checks.check("and at the bottom when scrolled to the end (" + bottom + ")",
                bottom != null && Checks.eq(bottom.getBottom(), 100f));
        Checks.check("never shorter than asked", Checks.eq(list.thumbIn(track, 80f).getHeight(), 80f));

        Column roomy = new Column();
        Scroll fits = roomy.add(new Scroll()).grow(1f);
        fits.add(new Label("only"));
        update(roomy);
        Checks.check("with nothing to scroll there is no thumb", fits.thumbIn(track, 0f) == null);
    }

    // --------------------------------------------------------------- helpers

    private static WidgetRendererRegistry look() {
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        look.register(WidgetRenderer.of(Label.class, (label, c) -> c.text(label.getText(), ANY)));
        look.register(WidgetRenderer.of(Button.class, (button, c) -> c.padding(2f).text(button.getLabel(), ANY)));
        return look;
    }

    private static Screen screenOf(Widget root) {
        Screen screen = existing(root);
        if (screen != null) {
            return screen;
        }
        screen = new Screen(look(), root);
        screen.resize(200f, 100f);
        return screen;
    }

    private static Screen existing(Widget root) {
        return root.getScreen();
    }

    private static void update(Widget root) {
        update(screenOf(root));
    }

    private static void update(Screen screen) {
        screen.update(0f, 0f);
    }
}
