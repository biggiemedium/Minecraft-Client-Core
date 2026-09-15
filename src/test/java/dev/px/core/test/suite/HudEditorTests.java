package dev.px.core.test.suite;

import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.MouseEvent;
import dev.px.core.event.impl.ScrollEvent;
import dev.px.core.hud.Anchor;
import dev.px.core.hud.Bounds;
import dev.px.core.hud.HudEditor;
import dev.px.core.hud.HudLayout;
import dev.px.core.hud.HudService;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

import java.util.EnumSet;
import java.util.Set;

/**
 * Edit mode: selection, dragging, snapping, locking and the input gate.
 *
 * <p>Dragging is driven by moving the fake cursor and rendering a frame, which
 * is exactly what happens in a real client. A drag that works here works there,
 * because nothing in between involves a window.
 */
public final class HudEditorTests {

    private static final Set<Modifier> NONE = EnumSet.noneOf(Modifier.class);

    /** ALT suspends snapping, so drag assertions test the drag and not the snap. */
    private static final Set<Modifier> ALT = EnumSet.of(Modifier.ALT);

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

        hud.renderFrame();
        Checks.check("elements see the editing flag and can freeze their values",
                client.getClock().isSawEditing());

        // ---- input is swallowed ---------------------------------------------------
        // Aimed at empty space and released, so the swallow check does not leave a
        // drag in flight for the assertions below.
        MouseEvent stray = post(client, new MouseEvent(MouseButton.LEFT, NONE, true, 700f, 150f));
        Checks.check("mouse input is swallowed while editing", stray.isCancelled());
        post(client, new MouseEvent(MouseButton.LEFT, NONE, false, 700f, 150f));

        KeyEvent key = post(client, new KeyEvent(Key.Q, NONE, true));
        Checks.check("key input is swallowed too", key.isCancelled());

        ScrollEvent scroll = post(client, new ScrollEvent(1f, 5f, 5f));
        Checks.check("and scrolling", scroll.isCancelled());

        // ---- selection is shape-aware ------------------------------------------------
        place(watermark, Anchor.TOP_LEFT, 300f, 200f);
        place(dial, Anchor.TOP_LEFT, 500f, 300f);
        hud.renderFrame();

        Bounds mark = HudLayoutTests.boundsOf(hud, "watermark");
        post(client, press(mark.getCenterX(), mark.getCenterY()));
        Checks.checkEquals("clicking an element selects it", "watermark", editor.getSelectedId());

        // The dial is a circle at (500,300)-(540,340): its box corner is empty space.
        post(client, press(501f, 301f));
        Checks.check("clicking the empty corner of a circular element misses it",
                !"dial".equals(editor.getSelectedId()));
        post(client, release(501f, 301f));

        post(client, press(520f, 320f));
        Checks.checkEquals("clicking inside the circle selects it", "dial", editor.getSelectedId());
        post(client, release(520f, 320f));

        post(client, press(830f, 60f));
        Checks.check("clicking empty space clears the selection", editor.getSelectedId() == null);

        // ---- dragging ---------------------------------------------------------------------
        hud.renderFrame();
        post(client, press(mark.getCenterX(), mark.getCenterY()));
        Checks.checkEquals("the drag begins on the element clicked", "watermark", editor.getSelectedId());
        Checks.check("and it is dragging", editor.isDragging());

        client.getPlatform().setMouse(mark.getCenterX() + 50f, mark.getCenterY() + 30f);
        hud.renderFrame();
        Checks.check("dragging moves the element by the cursor delta",
                Checks.eq(HudLayoutTests.boundsOf(hud, "watermark").getX(), 350f)
                        && Checks.eq(HudLayoutTests.boundsOf(hud, "watermark").getY(), 230f));

        post(client, release(0f, 0f));
        Checks.check("releasing ends the drag", !editor.isDragging());
        client.getPlatform().setMouse(700f, 400f);
        hud.renderFrame();
        Checks.checkEquals("and the element stops following the cursor",
                350f, HudLayoutTests.boundsOf(hud, "watermark").getX());

        // ---- snapping ----------------------------------------------------------------------
        // Drag without ALT, near the left edge: it should snap flush to zero.
        place(watermark, Anchor.TOP_LEFT, 100f, 100f);
        hud.renderFrame();
        Bounds atHundred = HudLayoutTests.boundsOf(hud, "watermark");
        post(client, new MouseEvent(MouseButton.LEFT, NONE, true,
                atHundred.getCenterX(), atHundred.getCenterY()));
        // Aim for x = 3, close enough to the screen edge for the snap to take it.
        client.getPlatform().setMouse(3f + atHundred.getWidth() / 2f, atHundred.getCenterY());
        hud.renderFrame();
        Checks.checkEquals("dragging near an edge snaps flush to it",
                0f, HudLayoutTests.boundsOf(hud, "watermark").getX());
        Checks.check("and an alignment guide is recorded", editor.isSnapped());
        post(client, release(0f, 0f));

        // Same drag with ALT held: no snap, the element lands exactly where aimed.
        place(watermark, Anchor.TOP_LEFT, 100f, 100f);
        hud.renderFrame();
        Bounds again = HudLayoutTests.boundsOf(hud, "watermark");
        post(client, press(again.getCenterX(), again.getCenterY()));
        client.getPlatform().setMouse(3f + again.getWidth() / 2f, again.getCenterY());
        hud.renderFrame();
        Checks.checkEquals("holding ALT suspends snapping",
                3f, HudLayoutTests.boundsOf(hud, "watermark").getX());
        post(client, release(0f, 0f));

        // Releasing ALT must clear the flag even if the adapter reports modifiers oddly.
        post(client, new KeyEvent(Key.LEFT_ALT, ALT, false));
        Checks.check("releasing ALT re-enables snapping", !editor.isSuspendSnapping());

        // ---- locking ------------------------------------------------------------------------
        place(watermark, Anchor.TOP_LEFT, 300f, 200f);
        watermark.setLocked(true);
        hud.renderFrame();
        Bounds locked = HudLayoutTests.boundsOf(hud, "watermark");
        post(client, press(locked.getCenterX(), locked.getCenterY()));
        Checks.checkEquals("a locked element can still be selected, so it can be unlocked",
                "watermark", editor.getSelectedId());
        Checks.check("but no drag starts", !editor.isDragging());

        client.getPlatform().setMouse(700f, 400f);
        hud.renderFrame();
        Checks.checkEquals("and it does not move", 300f,
                HudLayoutTests.boundsOf(hud, "watermark").getX());

        editor.nudgeSelected(10f, 10f);
        Checks.checkEquals("nudging a locked element does nothing too", 300f,
                HudLayoutTests.boundsOf(hud, "watermark").getX());

        editor.toggleLockSelected();
        Checks.check("the editor can unlock it again", !watermark.isLocked());
        post(client, release(0f, 0f));

        // ---- hiding ---------------------------------------------------------------------------
        editor.select("watermark");
        editor.toggleHiddenSelected();
        Checks.check("hiding is reversible from the editor", watermark.isHidden());
        Checks.check("a hidden element stays selectable while editing",
                HudLayoutTests.findIn(hud.resolve(true), "watermark") != null);
        editor.toggleHiddenSelected();
        Checks.check("and can be unhidden", !watermark.isHidden());

        // ---- keyboard -----------------------------------------------------------------------------
        place(watermark, Anchor.TOP_LEFT, 300f, 200f);
        hud.renderFrame();
        editor.select("watermark");

        post(client, new KeyEvent(Key.RIGHT, NONE, true));
        Checks.checkEquals("an arrow key nudges by one pixel", 301f,
                HudLayoutTests.boundsOf(hud, "watermark").getX());

        post(client, new KeyEvent(Key.DOWN, EnumSet.of(Modifier.SHIFT), true));
        Checks.checkEquals("shift nudges by ten", 210f,
                HudLayoutTests.boundsOf(hud, "watermark").getY());

        post(client, new KeyEvent(Key.L, NONE, true));
        Checks.check("L locks the selection", watermark.isLocked());
        post(client, new KeyEvent(Key.L, NONE, true));

        post(client, new KeyEvent(Key.PAGE_UP, NONE, true));
        Checks.check("page up raises z-order", watermark.getZOrder() > dial.getZOrder());

        post(client, new KeyEvent(Key.R, NONE, true));
        Checks.check("R resets the selection to its default layout",
                watermark.getAnchor() == Anchor.TOP_LEFT && Checks.eq(watermark.getOffsetX(), 4f));

        // ---- scaling ------------------------------------------------------------------------------
        editor.select("dial");
        float beforeScale = dial.getScale();
        post(client, new ScrollEvent(1f, 0f, 0f));
        Checks.check("scrolling scales the selection", dial.getScale() > beforeScale);
        post(client, new ScrollEvent(-1f, 0f, 0f));
        Checks.checkEquals("and back the other way", beforeScale, dial.getScale());

        // ---- resize handles ------------------------------------------------------------------------
        // Regression: handles were placed strictly outside the element, so an
        // element anchored into a screen corner had that handle off-screen and
        // permanently unclickable. Corner anchoring is the common case.
        place(dial, Anchor.TOP_LEFT, 0f, 0f);
        hud.renderFrame();
        editor.select("dial");
        post(client, press(20f, 20f));
        Checks.check("an element in the screen corner is still selectable",
                "dial".equals(editor.getSelectedId()));
        post(client, press(3f, 3f));
        Checks.check("its top-left handle flips inside and is grabbable",
                editor.getResizing() != null);
        post(client, release(0f, 0f));

        place(dial, Anchor.TOP_LEFT, 300f, 300f);
        hud.renderFrame();
        editor.select("dial");
        Bounds dialBounds = HudLayoutTests.boundsOf(hud, "dial");
        // With room available, the handle sits outside the element proper.
        post(client, press(dialBounds.getRight() + 5f, dialBounds.getBottom() + 5f));
        Checks.check("with room to spare the handle sits outside the element",
                editor.getResizing() == HudEditor.Handle.BOTTOM_RIGHT);

        client.getPlatform().setMouse(dialBounds.getX() + 80f, dialBounds.getY() + 80f);
        hud.renderFrame();
        Checks.check("dragging the handle scales the element", dial.getScale() > 1f);
        Checks.check("and the opposite corner stays put",
                Checks.eq(HudLayoutTests.boundsOf(hud, "dial").getX(), dialBounds.getX()));
        post(client, release(0f, 0f));
        dial.setScale(1f);

        // ---- escaping -----------------------------------------------------------------------------------
        editor.select("dial");
        post(client, new KeyEvent(Key.ESCAPE, NONE, true));
        Checks.check("escape clears the selection first", editor.getSelectedId() == null);
        Checks.check("leaving the editor open", hud.isEditing());

        post(client, new KeyEvent(Key.ESCAPE, NONE, true));
        Checks.check("a second escape closes the editor", !hud.isEditing());

        // ---- closing ---------------------------------------------------------------------------------------
        Checks.check("closing clears the editing flag", !hud.isEditing());
        Checks.check("and the selection", editor.getSelectedId() == null);

        MouseEvent afterClose = post(client, press(400f, 300f));
        Checks.check("input passes through once closed", !afterClose.isCancelled());

        hud.renderFrame();
        Checks.check("elements stop seeing the editing flag",
                !client.getClock().isSawEditing());
    }

    // ------------------------------------------------------------------- helpers

    private static <T extends dev.px.core.event.Event> T post(TestClient client, T event) {
        return client.getCore().getBus().post(event);
    }

    private static MouseEvent press(float x, float y) {
        return new MouseEvent(MouseButton.LEFT, ALT, true, x, y);
    }

    private static MouseEvent release(float x, float y) {
        return new MouseEvent(MouseButton.LEFT, ALT, false, x, y);
    }

    private static void place(HudLayout layout, Anchor anchor, float offsetX, float offsetY) {
        layout.setAnchor(anchor);
        layout.setOffsetX(offsetX);
        layout.setOffsetY(offsetY);
        layout.setScale(1f);
    }
}
