package dev.px.core.test.suite;

import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudElement;
import dev.px.core.hud.HudService;
import dev.px.core.hud.Placement;
import dev.px.core.layout.Shape;
import dev.px.core.layout.Size;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.test.example.ExampleArrayList;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.FixedFont;
import dev.px.core.test.harness.RecordingRender2D;
import dev.px.core.test.harness.TestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The content system: describe an element once, and its size falls out.
 *
 * <p>Every size asserted here is one a person can work out on paper from
 * {@link FixedFont} &mdash; six pixels a character, nine tall &mdash; which is
 * the point. If measurement needed a render backend to be checkable, it would
 * not be checkable at all.
 *
 * <p>Probes are registered against the live {@link HudService} rather than
 * measured in isolation, so what is tested is the path a real element takes:
 * described during {@code resolve}, measured, anchored, clamped.
 */
public final class HudContentTests {

    private static final Color ANY = Color.WHITE;

    /**
     * Probes registered by this suite, so they can be taken out again.
     *
     * <p>They would otherwise stack up at the default top-left corner and sit in
     * front of the real elements, which is exactly how this suite broke the
     * editor's corner hit tests the first time it ran.
     */
    private static final List<HudElement> probes = new ArrayList<>();

    private HudContentTests() {
    }

    public static void run(TestClient client) {
        Checks.section("HUD content");

        HudService hud = client.getCore().getHudService();

        // ---- leaves ---------------------------------------------------------------
        Bounds text = register(hud, "probe-text", c -> c.text("abc", ANY));
        Checks.checkEquals("text measures through the font: 3 chars at 6px", 18f, text.getWidth());
        Checks.checkEquals("and one line tall", 9f, text.getHeight());

        Bounds custom = register(hud, "probe-custom", c -> c.custom(30f, 40f, (x, y, w, h) -> { }));
        Checks.checkEquals("a custom part is exactly the size it declares", 30f, custom.getWidth());
        Checks.checkEquals("on both axes", 40f, custom.getHeight());

        // ---- padding --------------------------------------------------------------
        Bounds padded = register(hud, "probe-padded", c -> {
            c.padding(4f);
            c.text("abc", ANY);
        });
        Checks.checkEquals("padding widens the box on both sides", 26f, padded.getWidth());
        Checks.checkEquals("and both ends", 17f, padded.getHeight());

        Bounds asymmetric = register(hud, "probe-asymmetric", c -> {
            c.padding(5f, 2f);
            c.text("abc", ANY);
        });
        Checks.checkEquals("horizontal and vertical padding are independent",
                28f, asymmetric.getWidth());
        Checks.checkEquals("independently", 13f, asymmetric.getHeight());

        // ---- stacking -------------------------------------------------------------
        // The root box is a column, so these stack.
        Bounds column = register(hud, "probe-column", c -> {
            c.text("ab", ANY);
            c.text("cdef", ANY);
        });
        Checks.checkEquals("a column is as wide as its widest child", 24f, column.getWidth());
        Checks.checkEquals("and as tall as their total", 18f, column.getHeight());

        Bounds row = register(hud, "probe-row", c -> c.row(r -> {
            r.text("ab", ANY);
            r.text("cdef", ANY);
        }));
        Checks.checkEquals("a row is as wide as its children together", 36f, row.getWidth());
        Checks.checkEquals("and as tall as its tallest", 9f, row.getHeight());

        // ---- gaps -----------------------------------------------------------------
        Bounds gapped = register(hud, "probe-gap", c -> c.row(r -> {
            r.gap(5f);
            r.text("ab", ANY);
            r.text("cd", ANY);
        }));
        Checks.checkEquals("a gap sits between children, not around them", 29f, gapped.getWidth());

        Bounds threeGaps = register(hud, "probe-gap3", c -> c.row(r -> {
            r.gap(5f);
            r.text("a", ANY);
            r.text("b", ANY);
            r.text("c", ANY);
        }));
        Checks.checkEquals("so three children have two gaps", 28f, threeGaps.getWidth());

        // ---- nesting ---------------------------------------------------------------
        Bounds nested = register(hud, "probe-nested", c -> {
            c.padding(3f);
            c.row(r -> {
                r.gap(2f);
                r.text("ab", ANY);
                r.rect(10f, 4f, ANY);
            });
            c.text("cd", ANY);
        });
        // row: 12 + 2 + 10 = 24 wide, 9 tall. column: max(24, 12) = 24, 9 + 9 = 18. padding 3.
        Checks.checkEquals("a nested box measures as one child of its parent",
                30f, nested.getWidth());
        Checks.checkEquals("stacking below what follows it", 24f, nested.getHeight());

        // ---- min --------------------------------------------------------------------
        Bounds floored = register(hud, "probe-min", c -> {
            c.min(50f, 20f);
            c.text("ab", ANY);
        });
        Checks.checkEquals("min floors a box that would be smaller", 50f, floored.getWidth());
        Checks.checkEquals("on both axes", 20f, floored.getHeight());

        Bounds ignored = register(hud, "probe-min-ignored", c -> {
            c.min(10f, 5f);
            c.text("abcdef", ANY);
        });
        Checks.checkEquals("and never shrinks one that is already bigger", 36f, ignored.getWidth());

        // ---- alignment ----------------------------------------------------------------
        // Alignment moves children inside the box; it must not change its size.
        Bounds aligned = register(hud, "probe-align", c -> {
            c.align(Align.CENTER);
            c.text("ab", ANY);
            c.text("cdef", ANY);
        });
        Checks.checkEquals("alignment does not change what a box measures",
                24f, aligned.getWidth());

        // ---- fill, grow and stretch -------------------------------------------------------
        // The three a GUI component needs and a HUD element does not, because a
        // component is handed a width rather than sizing to its content.
        Bounds filled = register(hud, "probe-fill", c -> {
            // STRETCH is what hands the row the box's full width. Without it a
            // child is only as wide as its own content, so there is no slack to
            // fill -- which is the right default, and why a GUI row asks for it.
            c.width(100f).align(Align.STRETCH);
            c.row(r -> {
                r.name("bar");
                r.text("ab", ANY);      // 12
                r.fill();
                r.custom("right", 10f, 4f, (x, y, w, h) -> { });
            });
        });
        Checks.checkEquals("an imposed width is what the box measures", 100f, filled.getWidth());

        Placement fillProbe = HudLayoutTests.placementOf(hud, "probe-fill");
        Bounds right = fillProbe.silhouetteOf("right");
        Checks.check("fill pushes the trailing part to the far edge",
                right != null && Checks.eq(right.getRight(), filled.getRight()));

        // ---- named parts ------------------------------------------------------------------
        // The point of naming: the rectangle drawn and the rectangle hit are one.
        Bounds track = fillProbe.silhouetteOf("right");
        Checks.check("a named part reports the rectangle it was laid out in",
                track != null && Checks.eq(track.getWidth(), 10f));
        Checks.check("and an unknown name reports nothing",
                fillProbe.silhouetteOf("nothing-by-this-name") == null);

        // Listing them, for a view that does not know the names in advance.
        Content described = Content.column();
        described.width(100f).align(Align.STRETCH).name("root");
        described.row(r -> {
            r.name("bar");
            r.text("ab", ANY);
            r.fill();
            r.custom("knob", 10f, 4f, (x, y, w, h) -> { });
        });
        described.custom(20f, 5f, (x, y, w, h) -> { });
        described.custom("knob", 30f, 6f, (x, y, w, h) -> { });
        described.row(r -> r.name("bar").text("cd", ANY));
        described.measure();
        described.layout(5f, 7f, 100f, described.size().getHeight());
        Map<String, Bounds> named = described.namedParts();
        Checks.checkEquals("every named box and part is listed, in description order, unnamed ones left out",
                Arrays.asList("root", "bar", "knob"), new ArrayList<>(named.keySet()));
        Checks.check("each with the rectangle it was laid out in (bar " + named.get("bar") + ")",
                named.get("bar") != null && Checks.eq(named.get("bar").getX(), 5f)
                        && Checks.eq(named.get("bar").getY(), 7f) && Checks.eq(named.get("bar").getWidth(), 100f));
        Checks.check("a repeated name, box or part, keeps the rectangle find() returns (knob " + named.get("knob") + ")",
                named.get("knob") != null && named.get("knob") == described.find("knob")
                        && Checks.eq(named.get("knob").getWidth(), 10f));
        Checks.check("a box with nothing named lists nothing",
                Content.column().text("x", ANY).namedParts().isEmpty());

        slots();
        paragraphs();

        // ---- measure once ----------------------------------------------------------------
        // The whole reason the content system exists: one description per frame,
        // used for both the size and the drawing.
        CountingProbe counter = new CountingProbe();
        hud.register(counter);
        probes.add(counter);

        counter.described = 0;
        hud.drawAll(hud.resolve(true));
        Checks.checkEquals("an element describes itself once per frame", 1, counter.described);

        counter.described = 0;
        hud.resolve(true);
        Checks.checkEquals("resolving alone describes it once", 1, counter.described);

        // An empty element still measures, rather than throwing or vanishing.
        Bounds empty = register(hud, "probe-empty", c -> { });
        Checks.checkEquals("an element that describes nothing measures zero", 0f, empty.getWidth());

        // ---- scenario: a rounded panel ------------------------------------------------
        // An FPS counter drawn as a rounded rect. Nothing declares a shape, so the
        // region comes from the background it described -- radius and all.
        Bounds fps = register(hud, "probe-fps", c -> {
            c.background(Color.of(0, 0, 0, 120), 6f).padding(4f);
            c.text("120 fps", ANY);
        });
        Shape fpsShape = HudLayoutTests.placementOf(hud, "probe-fps").shape();
        Checks.check("a rounded background gives a rounded region, undeclared",
                fpsShape instanceof Shape && !fpsShape.contains(fps.getX() + 0.5f, fps.getY() + 0.5f));
        Checks.check("its middle is still inside",
                fpsShape.contains(fps.getCenterX(), fps.getCenterY()));
        Checks.check("and a corner well inside the radius is too",
                fpsShape.contains(fps.getX() + 7f, fps.getY() + 7f));

        // A square background gets a square region, from the same rule.
        register(hud, "probe-square", c -> {
            c.background(Color.of(0, 0, 0, 120)).padding(4f);
            c.text("120 fps", ANY);
        });
        Bounds square = HudLayoutTests.boundsOf(hud, "probe-square");
        Checks.check("a square background keeps its corners",
                HudLayoutTests.placementOf(hud, "probe-square").shape()
                        .contains(square.getX() + 0.5f, square.getY() + 0.5f));

        // ---- scenario: an ArrayList ----------------------------------------------------
        // Three right-aligned rows of different widths. The element is the rows,
        // not the rectangle around them.
        ExampleArrayList list = new ExampleArrayList();
        probes.add(hud.register(list));
        hud.layoutOf("arraylist").setAnchor(dev.px.core.hud.Anchor.TOP_LEFT);
        hud.layoutOf("arraylist").setOffsetX(200f);
        hud.layoutOf("arraylist").setOffsetY(100f);

        Placement listed = HudLayoutTests.placementOf(hud, "arraylist");
        Bounds box = listed.getBounds();
        Shape region = listed.shape();

        // "Crystal Aura" is 12 chars, "ESP" is 3: the bottom row is much narrower,
        // and right alignment leaves the gap on the left.
        Checks.check("every row is part of the element",
                region.contains(box.getRight() - 4f, box.getY() + 3f)
                        && region.contains(box.getRight() - 4f, box.getBottom() - 3f));
        Checks.check("but the empty space beside the short row is not",
                !region.contains(box.getX() + 2f, box.getBottom() - 3f));
        Checks.check("though the bounding box would have claimed it",
                box.contains(box.getX() + 2f, box.getBottom() - 3f));
        Checks.checkEquals("the region still spans the whole element",
                box.getWidth(), region.bounds().getWidth());

        // ---- the outline itself ----------------------------------------------------------
        // The question is which lines are absent, so this is the one place the
        // suite installs a backend: a recorder that draws nothing and takes notes.
        List<Shape> rows = listed.silhouette();
        Checks.checkEquals("the list resolves to one region per row", 3, rows.size());

        Bounds top = rows.get(0).bounds();
        Bounds bottom = rows.get(2).bounds();
        Checks.check("rows are right-aligned, so each is narrower than the one above",
                bottom.getWidth() < top.getWidth() && bottom.getX() > top.getX());

        RecordingRender2D recorder = new RecordingRender2D();
        Render.install(recorder);
        try {
            region.stroke(1f, Color.WHITE);
            // These rows have a gap between them, so none of their edges are shared
            // and every row is outlined in full.
            Checks.check("the outline draws the top of the list",
                    recorder.hasHorizontal(top.getY(), top.getX(), top.getRight()));
            Checks.check("and the bottom of the last row, at that row's width",
                    recorder.hasHorizontal(bottom.getBottom(), bottom.getX(), bottom.getRight()));
        } finally {
            Render.install((dev.px.core.render.Render2D) null);
        }

        // Touching rows, with no gap: the shared line must vanish from both sides,
        // which is the case a per-part outline gets visibly wrong.
        register(hud, "probe-stacked", c -> {
            c.align(Align.END);
            c.row(r -> { r.background(Color.WHITE).padding(2f); r.text("wide text", ANY); });
            c.row(r -> { r.background(Color.WHITE).padding(2f); r.text("narrow", ANY); });
        });
        Placement stack = HudLayoutTests.placementOf(hud, "probe-stacked");
        List<Shape> stackRows = stack.silhouette();
        Bounds wide = stackRows.get(0).bounds();
        Bounds narrow = stackRows.get(1).bounds();
        Checks.checkEquals("the two rows touch along one line",
                wide.getBottom(), narrow.getY());

        RecordingRender2D touching = new RecordingRender2D();
        Render.install(touching);
        try {
            stack.shape().stroke(1f, Color.WHITE);
            float seam = wide.getBottom();
            Checks.checkEquals("the shared edge is drawn once, not twice", 1, countAt(touching, seam));
            Checks.check("and only across the step the narrow row leaves exposed",
                    touching.hasHorizontal(seam, wide.getX(), narrow.getX()));
            Checks.check("the outer top edge survives",
                    touching.hasHorizontal(wide.getY(), wide.getX(), wide.getRight()));
            Checks.check("and the outer bottom edge",
                    touching.hasHorizontal(narrow.getBottom(), narrow.getX(), narrow.getRight()));
        } finally {
            Render.install((dev.px.core.render.Render2D) null);
        }

        // Leave the HUD as it was found, so the suites after this one see the
        // elements they registered and nothing else.
        for (HudElement probe : probes) {
            hud.getElements().unregister(probe);
        }
        probes.clear();
        Checks.check("the suite leaves no probes behind",
                hud.resolve(true).size() == 5);
    }

    // ------------------------------------------------------------------- helpers

    /** @return how many separate line calls landed on one horizontal. */
    private static int countAt(RecordingRender2D recorder, float y) {
        int found = 0;
        for (float[] line : recorder.getLines()) {
            if (Math.abs(line[1] - y) < 0.01f && Math.abs(line[3] - y) < 0.01f) {
                found++;
            }
        }
        return found;
    }

    /** Registers a probe and returns the bounds it resolved to. */
    /**
     * Text wrapped to the width it is given. Six pixels a character, nine tall,
     * eleven from one line to the next.
     */
    private static void paragraphs() {
        Content narrow = Content.column();
        narrow.width(40f).paragraph("aa bb cc dd", ANY);
        Size wrapped = narrow.measure();
        Checks.check("a paragraph wraps between words to the width it is given: two lines of 30 (" + wrapped + ")",
                Checks.eq(wrapped.getHeight(), 20f));

        Content padded = Content.column();
        padded.width(40f).padding(6f).paragraph("aa bb cc dd", ANY);
        Checks.checkEquals("inside the padding: 28 wide fits one word a line, four lines: 3 x 11 + 9 + 12",
                54f, padded.measure().getHeight());

        Content unbounded = Content.column().paragraph("aa bb cc dd", ANY);
        Size whole = unbounded.measure();
        Checks.check("with no width given it keeps the line whole (" + whole + ")",
                Checks.eq(whole.getWidth(), 66f) && Checks.eq(whole.getHeight(), 9f));

        Content lines = Content.column().paragraph("one\ntwo", ANY);
        Size split = lines.measure();
        Checks.check("and breaks at every newline (" + split + ")",
                Checks.eq(split.getWidth(), 18f) && Checks.eq(split.getHeight(), 20f));

        Content row = Content.row();
        row.width(40f).paragraph("aa bb cc dd", ANY);
        Checks.checkEquals("along a row it stays on one line", 9f, row.measure().getHeight());
    }

    /**
     * Where a GUI container's children go. Every figure is worked out on paper
     * from {@link FixedFont}: six pixels a character, nine tall.
     */
    private static void slots() {
        Content plain = Content.column().text("ab", ANY);
        Checks.check("a description without a slot has none to fill",
                !plain.hasSlot() && !plain.fillSlot(10f, 10f));

        Content unfilled = Content.column();
        unfilled.text("ab", ANY);
        unfilled.slot();
        Size empty = unfilled.measure();
        Checks.check("an unfilled slot takes no room, so a HUD element can ignore it (" + empty + ")",
                unfilled.hasSlot() && Checks.eq(empty.getWidth(), 12f) && Checks.eq(empty.getHeight(), 9f));
        unfilled.layout(0f, 0f, 12f, 9f);
        Checks.check("and reports nowhere to put children", unfilled.slotBounds() == null);

        // A window: a title, and a padded body the children go in. Nothing in it
        // asks to stretch; the slot makes the body do so on its own.
        Content window = Content.column();
        window.width(100f).padding(2f);
        window.text("Title", ANY);
        window.column(body -> body.padding(4f).slot());
        window.fillSlot(0f, 0f);
        Checks.checkEquals("an empty slot leaves the chrome's height: 2 + 9 + 4 + 4 + 2",
                21f, window.measure().getHeight());
        window.layout(0f, 0f, 100f, 21f);
        Bounds first = window.slotBounds();
        Checks.check("the box around a slot spans its parent, so the slot gets the width inside both paddings ("
                + first + ")", first != null && Checks.eq(first.getX(), 6f) && Checks.eq(first.getY(), 15f)
                && Checks.eq(first.getWidth(), 88f));

        window.fillSlot(88f, 40f);
        Checks.checkEquals("a filled slot adds the children's height", 61f, window.measure().getHeight());
        window.layout(0f, 0f, 100f, 61f);
        Bounds filled = window.slotBounds();
        Checks.check("and is laid out at that size (" + filled + ")", filled != null
                && Checks.eq(filled.getY(), 15f) && Checks.eq(filled.getWidth(), 88f)
                && Checks.eq(filled.getHeight(), 40f));

        window.layout(0f, 0f, 100f, 100f);
        Bounds taller = window.slotBounds();
        Checks.check("given more height than it measured, the slot takes the extra (" + taller + ")",
                taller != null && Checks.eq(taller.getHeight(), 79f));

        Content centred = Content.column();
        centred.width(100f).align(Align.CENTER);
        centred.text("ab", ANY);
        centred.slot();
        centred.fillSlot(20f, 5f);
        centred.measure();
        centred.layout(0f, 0f, 100f, 14f);
        Bounds spanning = centred.slotBounds();
        Checks.check("a slot spans its box whatever the box's alignment (" + spanning + ")",
                spanning != null && Checks.eq(spanning.getX(), 0f) && Checks.eq(spanning.getWidth(), 100f));

        Content row = Content.row();
        row.width(100f).gap(2f);
        row.text("ab", ANY);
        row.slot();
        row.fillSlot(10f, 9f);
        row.measure();
        row.layout(0f, 0f, 100f, 9f);
        Bounds side = row.slotBounds();
        Checks.check("in a row, the slot takes the width left after the other parts (" + side + ")",
                side != null && Checks.eq(side.getX(), 14f) && Checks.eq(side.getWidth(), 86f));

        Content twice = Content.column();
        twice.slot();
        twice.custom("after", 5f, 5f, (x, y, w, h) -> { });
        twice.slot();
        twice.fillSlot(10f, 10f);
        Checks.checkEquals("only the first slot is filled; a later one measures nothing",
                15f, twice.measure().getHeight());
        twice.layout(0f, 0f, 10f, 15f);
        Bounds after = twice.find("after");
        Checks.check("and the parts after the first slot sit below it (" + after + ")",
                after != null && Checks.eq(after.getY(), 10f) && Checks.eq(twice.slotBounds().getY(), 0f));
    }

    private static Bounds register(HudService hud, String id, Described described) {
        probes.add(hud.register(new Probe(id, described)));
        return HudLayoutTests.boundsOf(hud, id);
    }

    @FunctionalInterface
    private interface Described {
        void describe(Content content);
    }

    /** A HUD element that is nothing but the content it was handed. */
    private static final class Probe implements HudElement {

        private final String id;
        private final Described described;

        Probe(String id, Described described) {
            this.id = id;
            this.described = described;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void content(Content content) {
            described.describe(content);
        }
    }

    /** Counts how many times Core asks it what it is made of. */
    private static final class CountingProbe implements HudElement {

        int described;

        @Override
        public String getId() {
            return "probe-counting";
        }

        @Override
        public void content(Content content) {
            described++;
            content.text("x", ANY);
        }
    }
}
