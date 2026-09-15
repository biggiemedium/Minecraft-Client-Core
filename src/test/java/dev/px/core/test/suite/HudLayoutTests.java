package dev.px.core.test.suite;

import dev.px.core.event.impl.Render2DEvent;
import dev.px.core.hud.Anchor;
import dev.px.core.hud.Bounds;
import dev.px.core.hud.HudLayout;
import dev.px.core.hud.HudService;
import dev.px.core.hud.Placement;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

import java.util.List;

/**
 * The layout engine: anchors, resolution independence, clamping, scale, z-order.
 *
 * <p>Almost everything asserted here falls out of one formula,
 * {@code x = ax * screenW + offsetX - ax * width}. No code anywhere
 * special-cases growth direction; the {@code - ax * width} term is the whole
 * mechanism.
 */
public final class HudLayoutTests {

    private HudLayoutTests() {
    }

    public static void run(TestClient client) {
        Checks.section("HUD layout");
        client.reset();

        HudService hud = client.getCore().getHudService();
        HudLayout watermark = client.layout("watermark");

        // Whatever the watermark reports this frame; the assertions are derived
        // from it rather than hard-coded, so changing the example cannot silently
        // invalidate them.
        float width = boundsOf(hud, "watermark").getWidth();
        float height = boundsOf(hud, "watermark").getHeight();

        // ---- every anchor ------------------------------------------------------
        at(hud, watermark, Anchor.TOP_LEFT, 4f, 4f, "top-left", 4f, 4f);
        at(hud, watermark, Anchor.TOP_RIGHT, -4f, 4f, "top-right", 854f - 4f - width, 4f);
        at(hud, watermark, Anchor.BOTTOM_LEFT, 4f, -4f, "bottom-left", 4f, 480f - 4f - height);
        at(hud, watermark, Anchor.BOTTOM_RIGHT, -4f, -4f, "bottom-right",
                854f - 4f - width, 480f - 4f - height);
        at(hud, watermark, Anchor.TOP_CENTER, 0f, 4f, "top-centre", (854f - width) / 2f, 4f);
        at(hud, watermark, Anchor.MIDDLE_LEFT, 4f, 0f, "middle-left", 4f, (480f - height) / 2f);
        at(hud, watermark, Anchor.CENTER, 0f, 0f, "centre",
                (854f - width) / 2f, (480f - height) / 2f);

        // ---- resolution independence ---------------------------------------------
        set(watermark, Anchor.BOTTOM_RIGHT, -4f, -4f);
        for (float[] screen : new float[][] { {1920f, 1080f}, {3840f, 2160f}, {640f, 360f} }) {
            client.getPlatform().setScreen(screen[0], screen[1]);
            Bounds bounds = boundsOf(hud, "watermark");
            Checks.check("a corner element stays cornered at " + (int) screen[0] + "x" + (int) screen[1],
                    Checks.eq(bounds.getRight(), screen[0] - 4f)
                            && Checks.eq(bounds.getBottom(), screen[1] - 4f));
        }
        client.getPlatform().setScreen(854f, 480f);

        // ---- growth direction -------------------------------------------------------
        // The clock's width changes with its own setting, which is exactly the
        // every-frame size change the engine is built to absorb.
        HudLayout clock = client.layout("clock");
        set(clock, Anchor.TOP_RIGHT, -4f, 4f);
        client.getClock().setLiveText("13:45");
        Bounds narrow = boundsOf(hud, "clock");
        client.getClock().setLiveText("13:45:59");
        Bounds wide = boundsOf(hud, "clock");
        Checks.check("a right-anchored element grows leftwards",
                wide.getX() < narrow.getX() && Checks.eq(wide.getRight(), narrow.getRight()));

        set(clock, Anchor.TOP_LEFT, 4f, 4f);
        client.getClock().setLiveText("13:45");
        Bounds shortLeft = boundsOf(hud, "clock");
        client.getClock().setLiveText("13:45:59");
        Bounds longLeft = boundsOf(hud, "clock");
        Checks.check("a left-anchored element grows rightwards, origin fixed",
                Checks.eq(shortLeft.getX(), longLeft.getX()));

        set(clock, Anchor.BOTTOM_CENTER, 0f, -4f);
        Bounds bottom = boundsOf(hud, "clock");
        Checks.check("a bottom-anchored element grows upwards",
                Checks.eq(bottom.getBottom(), 480f - 4f));
        client.getClock().setLiveText("13:45");

        // ---- clamping ------------------------------------------------------------------
        // An element may hang off an edge, but never entirely: something has to stay
        // grabbable. An element smaller than the sliver stays fully on screen.
        set(watermark, Anchor.TOP_LEFT, -500f, 4f);
        Checks.checkEquals("clamped so a sliver stays visible on the left",
                furthestOff(width), boundsOf(hud, "watermark").getX());
        Checks.checkEquals("the layout is not rewritten by clamping",
                -500f, watermark.getOffsetX());

        set(watermark, Anchor.TOP_LEFT, 5000f, 4f);
        Checks.checkEquals("clamped on the right too",
                854f - Math.min(HudService.MIN_VISIBLE, width), boundsOf(hud, "watermark").getX());

        set(watermark, Anchor.TOP_LEFT, 4f, -500f);
        Checks.checkEquals("and vertically", furthestOff(height),
                boundsOf(hud, "watermark").getY());
        Checks.check("an element shorter than the sliver is kept fully visible",
                height >= HudService.MIN_VISIBLE || Checks.eq(boundsOf(hud, "watermark").getY(), 0f));
        set(watermark, Anchor.TOP_LEFT, 4f, 4f);

        // ---- scale ------------------------------------------------------------------------
        watermark.setScale(2f);
        Checks.checkEquals("scale widens the layout box", width * 2f, boundsOf(hud, "watermark").getWidth());
        watermark.setScale(99f);
        Checks.checkEquals("scale is clamped high", HudLayout.MAX_SCALE, watermark.getScale());
        watermark.setScale(0.01f);
        Checks.checkEquals("and low", HudLayout.MIN_SCALE, watermark.getScale());
        watermark.setScale(1f);

        // ---- hit testing, shape-aware -----------------------------------------------------
        HudLayout dial = client.layout("dial");
        set(dial, Anchor.TOP_LEFT, 200f, 200f);
        Placement dialPlacement = placementOf(hud, "dial");
        // 40px diameter at (200,200): centre (220,220), radius 20.
        Checks.check("a circular element is hit at its centre", dialPlacement.hits(220f, 220f));
        Checks.check("and near its rim", dialPlacement.hits(238f, 220f));
        Checks.check("but not at the corner of its layout box", !dialPlacement.hits(201f, 201f));

        dial.setScale(2f);
        Placement scaled = placementOf(hud, "dial");
        Checks.check("scaling grows the hit region with it", scaled.hits(275f, 240f));
        Checks.check("and still excludes beyond it", !scaled.hits(285f, 240f));
        dial.setScale(1f);

        HudLayout radar = client.layout("radar");
        set(radar, Anchor.TOP_LEFT, 400f, 300f);
        Placement radarPlacement = placementOf(hud, "radar");
        // A 60x40 cone: apex at (430,300), base from (400,340) to (460,340).
        Checks.check("a polygonal element is hit inside the cone", radarPlacement.hits(430f, 335f));
        Checks.check("but not in the empty corner beside it", !radarPlacement.hits(403f, 303f));

        // ---- z-order -------------------------------------------------------------------------
        set(watermark, Anchor.TOP_LEFT, 200f, 205f);
        watermark.setZOrder(0);
        dial.setZOrder(5);
        List<Placement> ordered = hud.resolve(true);
        Checks.checkEquals("elements resolve in ascending z-order",
                "dial", ordered.get(ordered.size() - 1).getId());
        Checks.checkEquals("the topmost overlapping element wins a hit test",
                "dial", hud.hitTest(hud.resolve(true), 215f, 210f).getId());

        dial.setZOrder(-5);
        Checks.checkEquals("lowering z hands the hit to the one beneath",
                "watermark", hud.hitTest(hud.resolve(true), 215f, 210f).getId());

        hud.bringToFront("dial");
        Checks.check("bringToFront raises above everything",
                dial.getZOrder() > watermark.getZOrder());
        hud.sendToBack("dial");
        Checks.check("sendToBack drops below everything",
                dial.getZOrder() < watermark.getZOrder());
        dial.setZOrder(0);
        watermark.setZOrder(0);

        // ---- changing anchor in place ----------------------------------------------------------
        set(watermark, Anchor.TOP_LEFT, 200f, 150f);
        Bounds before = boundsOf(hud, "watermark");
        hud.setAnchor("watermark", Anchor.BOTTOM_RIGHT);
        Bounds after = boundsOf(hud, "watermark");
        Checks.check("changing anchor does not move the element on screen",
                Checks.eq(before.getX(), after.getX()) && Checks.eq(before.getY(), after.getY()));
        Checks.check("but the anchor really changed", watermark.getAnchor() == Anchor.BOTTOM_RIGHT);
        // The proof it took effect: it now tracks the far corner on a resize.
        client.getPlatform().setScreen(1000f, 600f);
        Checks.check("and it now tracks that corner",
                Checks.eq(boundsOf(hud, "watermark").getRight() - 1000f, after.getRight() - 854f));
        client.getPlatform().setScreen(854f, 480f);

        // ---- visibility ---------------------------------------------------------------------------
        HudLayout broken = client.layout("broken");
        broken.setHidden(true);
        Checks.check("a hidden element does not resolve in game", placementOf(hud, "broken") == null);
        Checks.check("but does while editing", findIn(hud.resolve(true), "broken") != null);
        broken.setHidden(false);

        // ---- reset --------------------------------------------------------------------------------
        set(watermark, Anchor.CENTER, 77f, 88f);
        watermark.setScale(3f);
        hud.resetLayout("watermark");
        Checks.check("reset restores the element's declared default",
                watermark.getAnchor() == Anchor.TOP_LEFT
                        && Checks.eq(watermark.getOffsetX(), 4f)
                        && Checks.eq(watermark.getScale(), 1f));

        // ---- resilience ------------------------------------------------------------------------------
        client.getBroken().setExplodeOnRender(true);
        Checks.checkSurvives("an element that throws while rendering is contained", hud::renderFrame);
        client.getBroken().setExplodeOnRender(false);

        client.getBroken().setExplodeOnMeasure(true);
        Checks.checkSurvives("an element that throws while measuring is skipped", hud::renderFrame);
        Checks.check("and the others still resolve", hud.resolve(true).size() >= 4);
        client.getBroken().setExplodeOnMeasure(false);

        Checks.checkSurvives("a frame renders with no Render2D backend installed",
                () -> client.getCore().getBus().post(new Render2DEvent(0f, 854f, 480f)));

        // ---- Anchor.nearest ----------------------------------------------------------------------------
        Checks.check("nearest picks the corner a point sits in",
                Anchor.nearest(800f, 450f, 854f, 480f) == Anchor.BOTTOM_RIGHT);
        Checks.check("and the centre for the middle",
                Anchor.nearest(427f, 240f, 854f, 480f) == Anchor.CENTER);
        Checks.check("an unknown anchor name degrades to top-left",
                Anchor.byName("NOT_AN_ANCHOR") == Anchor.TOP_LEFT);
    }

    // ------------------------------------------------------------------ helpers

    private static void at(HudService hud, HudLayout layout, Anchor anchor,
                           float offsetX, float offsetY, String label,
                           float expectedX, float expectedY) {
        set(layout, anchor, offsetX, offsetY);
        Bounds bounds = boundsOf(hud, "watermark");
        boolean ok = Checks.eq(bounds.getX(), expectedX) && Checks.eq(bounds.getY(), expectedY);
        Checks.check(ok
                ? "anchor " + label + " resolves correctly"
                : "anchor " + label + " resolves correctly  (expected "
                        + expectedX + ", " + expectedY + " but got "
                        + bounds.getX() + ", " + bounds.getY() + ")", ok);
    }

    /**
     * The furthest an element of this size can be pushed off the near edge.
     *
     * <p>Mirrors the rule rather than restating a constant: at least
     * {@link HudService#MIN_VISIBLE} stays on screen, or the whole element when
     * it is smaller than that.
     */
    private static float furthestOff(float size) {
        return -(size - Math.min(HudService.MIN_VISIBLE, size));
    }

    private static void set(HudLayout layout, Anchor anchor, float offsetX, float offsetY) {
        layout.setAnchor(anchor);
        layout.setOffsetX(offsetX);
        layout.setOffsetY(offsetY);
    }

    static Placement findIn(List<Placement> placements, String id) {
        for (Placement placement : placements) {
            if (placement.getId().equals(id)) {
                return placement;
            }
        }
        return null;
    }

    static Placement placementOf(HudService hud, String id) {
        return findIn(hud.resolve(false), id);
    }

    static Bounds boundsOf(HudService hud, String id) {
        Placement placement = findIn(hud.resolve(true), id);
        return placement == null ? null : placement.getBounds();
    }
}
