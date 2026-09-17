package dev.px.core.test.suite;

import dev.px.core.hud.Anchor;
import dev.px.core.layout.Bounds;
import dev.px.core.hud.HudEditor;
import dev.px.core.hud.HudEditorView;
import dev.px.core.hud.HudLayout;
import dev.px.core.hud.HudService;
import dev.px.core.hud.Placement;
import dev.px.core.hud.SnapGuide;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

/**
 * Edit mode: selection, dragging, snapping, locking, and the view it hands back.
 *
 * <p>Driven entirely through the editor's own API. There is no event posting
 * here and no frame being rendered, because the editor no longer listens to
 * either &mdash; a drag is {@code press}, {@code update}, {@code release}, which
 * is exactly what a client's screen calls. That this suite needs no window and
 * no render backend is the point of the redesign.
 */
public final class HudEditorTests {

    private HudEditorTests() {
    }

    public static void run(TestClient client) {
        Checks.section("HUD editor");
        client.reset();

        HudService hud = client.getCore().getHudService();
        HudEditor editor = hud.getEditor();
        HudLayout watermark = client.layout("watermark");
        HudLayout dial = client.layout("dial");

        // Park the other elements out of the way so hit tests are unambiguous.
        place(client.layout("clock"), Anchor.TOP_RIGHT, -4f, 4f);
        place(client.layout("radar"), Anchor.BOTTOM_RIGHT, -4f, -4f);
        place(client.layout("broken"), Anchor.BOTTOM_LEFT, 4f, -4f);

        // ---- opening ------------------------------------------------------------
        Checks.check("the editor starts closed", !hud.isEditing());
        hud.openEditor();
        Checks.check("opening sets the editing flag", hud.isEditing());

        hud.resolve(true);
        Checks.check("elements see the editing flag and can freeze their values",
                client.getClock().isSawEditing());

        // ---- the editor draws nothing -------------------------------------------
        // The whole point of the redesign: everything below is geometry, and the
        // view is the only thing an editor UI needs in order to draw itself.
        HudEditorView opened = editor.view();
        Checks.check("an open editor yields a live view", opened.isActive());
        Checks.check("the view carries every element, hidden ones included",
                opened.getPlacements().size() >= 5);
        Checks.check("with nothing selected there are no handles",
                opened.getHandles().isEmpty());

        // ---- selection is shape-aware ------------------------------------------------
        place(watermark, Anchor.TOP_LEFT, 300f, 200f);
        place(dial, Anchor.TOP_LEFT, 500f, 300f);
        editor.update(0f, 0f);

        Bounds mark = HudLayoutTests.boundsOf(hud, "watermark");
        editor.press(mark.getCenterX(), mark.getCenterY());
        Checks.checkEquals("clicking an element selects it", "watermark", editor.getSelectedId());
        editor.release();

        // The dial is a circle at (500,300)-(540,340): its box corner is empty space.
        editor.press(501f, 301f);
        Checks.check("clicking the empty corner of a circular element misses it",
                !"dial".equals(editor.getSelectedId()));
        editor.release();

        editor.press(520f, 320f);
        Checks.checkEquals("clicking inside the circle selects it", "dial", editor.getSelectedId());
        editor.release();

        editor.press(830f, 60f);
        Checks.check("clicking empty space clears the selection", editor.getSelectedId() == null);
        editor.release();

        // ---- the view reports selection and hover ---------------------------------
        editor.update(mark.getCenterX(), mark.getCenterY());
        editor.press(mark.getCenterX(), mark.getCenterY());
        HudEditorView selectedView = editor.view();
        Checks.checkEquals("the view names the selection",
                "watermark", selectedView.getSelected().getId());
        Checks.checkEquals("and what the cursor is over",
                "watermark", selectedView.getHovered().getId());
        Checks.checkEquals("an unlocked selection offers four resize handles",
                4, selectedView.getHandles().size());
        Checks.check("and it reports the drag in progress", selectedView.isDragging());

        // ---- dragging ---------------------------------------------------------------------
        // No frame is rendered: update() is the whole mechanism.
        editor.setSnappingSuspended(true);
        editor.update(mark.getCenterX() + 50f, mark.getCenterY() + 30f);
        Checks.check("dragging moves the element by the cursor delta",
                Checks.eq(HudLayoutTests.boundsOf(hud, "watermark").getX(), 350f)
                        && Checks.eq(HudLayoutTests.boundsOf(hud, "watermark").getY(), 230f));

        editor.release();
        Checks.check("releasing ends the drag", !editor.isDragging());
        editor.update(700f, 400f);
        Checks.checkEquals("and the element stops following the cursor",
                350f, HudLayoutTests.boundsOf(hud, "watermark").getX());

        // ---- snapping ----------------------------------------------------------------------
        editor.setSnappingSuspended(false);
        place(watermark, Anchor.TOP_LEFT, 100f, 100f);
        editor.update(0f, 0f);
        Bounds atHundred = HudLayoutTests.boundsOf(hud, "watermark");
        editor.press(atHundred.getCenterX(), atHundred.getCenterY());
        // Aim for x = 3, close enough to the screen edge for the snap to take it.
        editor.update(3f + atHundred.getWidth() / 2f, atHundred.getCenterY());
        Checks.checkEquals("dragging near an edge snaps flush to it",
                0f, HudLayoutTests.boundsOf(hud, "watermark").getX());
        Checks.check("and an alignment guide is recorded", editor.isSnapped());

        SnapGuide guide = editor.view().getGuides().get(0);
        Checks.check("the guide is a vertical line at the edge it snapped to",
                guide.isVertical() && Checks.eq(guide.getPosition(), 0f));
        editor.release();

        // Same drag with snapping suspended: the element lands exactly where aimed.
        editor.setSnappingSuspended(true);
        place(watermark, Anchor.TOP_LEFT, 100f, 100f);
        editor.update(0f, 0f);
        Bounds again = HudLayoutTests.boundsOf(hud, "watermark");
        editor.press(again.getCenterX(), again.getCenterY());
        editor.update(3f + again.getWidth() / 2f, again.getCenterY());
        Checks.checkEquals("suspending snapping lands it exactly where aimed",
                3f, HudLayoutTests.boundsOf(hud, "watermark").getX());
        Checks.check("and records no guide", editor.view().getGuides().isEmpty());
        editor.release();

        // ---- locking ------------------------------------------------------------------------
        place(watermark, Anchor.TOP_LEFT, 300f, 200f);
        watermark.setLocked(true);
        editor.update(0f, 0f);
        Bounds locked = HudLayoutTests.boundsOf(hud, "watermark");
        editor.press(locked.getCenterX(), locked.getCenterY());
        Checks.checkEquals("a locked element can still be selected, so it can be unlocked",
                "watermark", editor.getSelectedId());
        Checks.check("but no drag starts", !editor.isDragging());
        Checks.check("and the view offers no handles for it",
                editor.view().getHandles().isEmpty());

        editor.update(700f, 400f);
        Checks.checkEquals("and it does not move", 300f,
                HudLayoutTests.boundsOf(hud, "watermark").getX());

        editor.nudgeSelected(10f, 10f);
        Checks.checkEquals("nudging a locked element does nothing too", 300f,
                HudLayoutTests.boundsOf(hud, "watermark").getX());

        editor.toggleLockSelected();
        Checks.check("the editor can unlock it again", !watermark.isLocked());
        editor.release();

        // ---- hiding ---------------------------------------------------------------------------
        editor.select("watermark");
        editor.toggleHiddenSelected();
        Checks.check("hiding is reversible from the editor", watermark.isHidden());
        Checks.check("a hidden element stays selectable while editing",
                HudLayoutTests.findIn(hud.resolve(true), "watermark") != null);
        Checks.check("and the view says which elements are hidden, without dimming them itself",
                hiddenIn(editor.view()) == 1);
        editor.toggleHiddenSelected();
        Checks.check("and can be unhidden", !watermark.isHidden());

        // ---- nudging and ordering -------------------------------------------------------------
        // These were keybinds baked into Core. They are plain methods now, and the
        // client decides which key, if any, calls them.
        place(watermark, Anchor.TOP_LEFT, 300f, 200f);
        editor.update(0f, 0f);
        editor.select("watermark");

        editor.nudgeSelected(1f, 0f);
        Checks.checkEquals("nudging moves by exactly what it is given", 301f,
                HudLayoutTests.boundsOf(hud, "watermark").getX());

        editor.nudgeSelected(0f, 10f);
        Checks.checkEquals("on either axis", 210f,
                HudLayoutTests.boundsOf(hud, "watermark").getY());

        editor.toggleLockSelected();
        Checks.check("locking is a method, not a key", watermark.isLocked());
        editor.toggleLockSelected();

        editor.bringSelectedToFront();
        Checks.check("so is raising z-order", watermark.getZOrder() > dial.getZOrder());

        editor.resetSelected();
        Checks.check("and resetting to the declared default",
                watermark.getAnchor() == Anchor.TOP_LEFT && Checks.eq(watermark.getOffsetX(), 4f));

        // ---- scaling ------------------------------------------------------------------------------
        editor.select("dial");
        float beforeScale = dial.getScale();
        editor.scaleSelected(0.05f);
        Checks.check("scaling the selection works off a delta", dial.getScale() > beforeScale);
        editor.scaleSelected(-0.05f);
        Checks.checkEquals("and back the other way", beforeScale, dial.getScale());

        // ---- resize handles ------------------------------------------------------------------------
        // Regression: handles were placed strictly outside the element, so an
        // element anchored into a screen corner had that handle off-screen and
        // permanently unclickable. Corner anchoring is the common case.
        place(dial, Anchor.TOP_LEFT, 0f, 0f);
        editor.update(0f, 0f);
        editor.select("dial");
        editor.press(20f, 20f);
        Checks.check("an element in the screen corner is still selectable",
                "dial".equals(editor.getSelectedId()));
        editor.release();
        editor.press(3f, 3f);
        Checks.check("its top-left handle flips inside and is grabbable",
                editor.getResizing() != null);
        editor.release();

        place(dial, Anchor.TOP_LEFT, 300f, 300f);
        editor.update(0f, 0f);
        editor.select("dial");
        Bounds dialBounds = HudLayoutTests.boundsOf(hud, "dial");

        // The rectangles the view publishes are the ones press() actually hits.
        Bounds published = editor.view().getHandles().get(HudEditor.Handle.BOTTOM_RIGHT);
        editor.press(published.getCenterX(), published.getCenterY());
        Checks.check("the handle the view publishes is the handle that grabs",
                editor.getResizing() == HudEditor.Handle.BOTTOM_RIGHT);
        Checks.check("with room to spare it sits outside the element",
                published.getX() > dialBounds.getRight());

        editor.update(dialBounds.getX() + 80f, dialBounds.getY() + 80f);
        Checks.check("dragging the handle scales the element", dial.getScale() > 1f);
        Checks.check("and the opposite corner stays put",
                Checks.eq(HudLayoutTests.boundsOf(hud, "dial").getX(), dialBounds.getX()));
        editor.release();
        dial.setScale(1f);

        // ---- closing ---------------------------------------------------------------------------------------
        editor.select("dial");
        editor.deselect();
        Checks.check("deselecting clears the selection", editor.getSelectedId() == null);
        Checks.check("and leaves the editor open", hud.isEditing());

        hud.closeEditor();
        Checks.check("closing clears the editing flag", !hud.isEditing());
        Checks.check("and the selection", editor.getSelectedId() == null);
        Checks.check("a closed editor yields an inactive, empty view",
                !editor.view().isActive() && editor.view().getPlacements().isEmpty());

        editor.press(400f, 300f);
        Checks.check("and a press on a closed editor selects nothing",
                editor.getSelectedId() == null);

        hud.resolve(false);
        Checks.check("elements stop seeing the editing flag",
                !client.getClock().isSawEditing());
    }

    // ------------------------------------------------------------------- helpers

    private static int hiddenIn(HudEditorView view) {
        int hidden = 0;
        for (Placement placement : view.getPlacements()) {
            if (placement.getLayout().isHidden()) {
                hidden++;
            }
        }
        return hidden;
    }

    private static void place(HudLayout layout, Anchor anchor, float offsetX, float offsetY) {
        layout.setAnchor(anchor);
        layout.setOffsetX(offsetX);
        layout.setOffsetY(offsetY);
        layout.setScale(1f);
    }
}
