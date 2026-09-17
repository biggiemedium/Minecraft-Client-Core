package dev.px.core.test.suite;

import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudElement;
import dev.px.core.hud.HudService;
import dev.px.core.hud.Placement;
import dev.px.core.layout.Shape;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.test.example.ExampleArrayList;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.FixedFont;
import dev.px.core.test.harness.RecordingRender2D;
import dev.px.core.test.harness.TestClient;

import java.util.ArrayList;
import java.util.List;

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
