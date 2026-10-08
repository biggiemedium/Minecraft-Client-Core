package dev.px.core.test.visual;

import dev.px.core.Core;
import dev.px.core.layout.Bounds;
import dev.px.core.hud.HudEditor;
import dev.px.core.hud.HudEditorView;
import dev.px.core.hud.HudService;
import dev.px.core.hud.Placement;
import dev.px.core.layout.Shape;
import dev.px.core.hud.SnapGuide;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.test.example.ExampleCategories;
import dev.px.core.test.example.ExampleKillAura;
import dev.px.core.test.example.ExampleSprint;



import static org.lwjgl.glfw.GLFW.*;

/**
 * The HUD in a real window, drawn by a real renderer.
 *
 * <pre>
 *   ./gradlew visual
 * </pre>
 *
 * <p>Five elements, a NanoVG backend, and an edit mode written entirely in this
 * file. What it demonstrates is the division of labour: Core positions and
 * measures, and every pixel below &mdash; the backdrop, the selection colour, the
 * handle style, the key bindings &mdash; is a decision made here, by the client,
 * exactly as it would be in a real one.
 *
 * <p><b>Controls.</b> {@code E} toggles edit mode. Drag to move, drag a corner to
 * scale, scroll to scale, {@code ALT} suspends snapping, right-click hides,
 * {@code L} locks, {@code R} resets, {@code PageUp}/{@code PageDown} reorder,
 * arrows nudge, {@code Escape} backs out.
 */
public final class VisualTest {

    // The editor's look. None of this is Core's business, which is the point.
    private static final Color BACKDROP = Color.of(0, 0, 0, 120);
    private static final Color SELECTED = Color.of(120, 200, 255);
    private static final Color HOVERED = Color.of(255, 255, 255, 110);
    private static final Color LOCKED = Color.of(255, 150, 60, 210);
    private static final Color GUIDE = Color.of(120, 220, 255, 170);

    private VisualTest() {
    }

    public static void main(String[] args) throws Exception {
        VisualWindow window = VisualWindow.open("Core HUD - NanoVG harness", args);
        String select = window.option("--select=");
        boolean startEditing = window.has("--edit") || select != null;

        // ---- boot Core, exactly as an adapter would -----------------------------
        WindowPlatform platform = window.getPlatform();
        Core core = Core.builder("CoreVisual", "1.0").platform(platform).build();

        // Modules, so the module list has something to show.
        core.getCategories().registerAll(ExampleCategories.class);
        core.getModuleRegistry().registerAll(new ExampleKillAura(), new ExampleSprint());

        Render.install(window.getBackend());

        VisualElements.FpsCounter fps = new VisualElements.FpsCounter();
        VisualElements.Coordinates coords = new VisualElements.Coordinates();
        VisualElements.Dial dial = new VisualElements.Dial();

        HudService hud = core.getHudService();
        hud.registerAll(new VisualElements.Watermark(), fps,
                new VisualElements.ModuleList(), coords, dial);

        core.start();

        Render.setDefaultFont(window.loadFont(15f));

        HudEditor editor = hud.getEditor();
        installInput(window.getHandle(), hud, editor);

        if (startEditing) {
            hud.openEditor();
            editor.select(select == null ? "fps" : select);
        }

        // ---- the frame ------------------------------------------------------------
        int[] measuredFps = new int[1];
        int[] sinceSecond = new int[1];
        float[] lastSecond = new float[1];

        int frames = window.run((frame, seconds) -> {
            sinceSecond[0]++;
            if (seconds - lastSecond[0] >= 1f) {
                measuredFps[0] = sinceSecond[0];
                sinceSecond[0] = 0;
                lastSecond[0] = seconds;
            }

            // Live data, so the elements genuinely resize as they update.
            fps.setFps(measuredFps[0] > 0 ? measuredFps[0] : Math.round(frame / Math.max(0.001f, seconds)));
            coords.setX(Math.sin(seconds * 0.4) * 1200.0);
            coords.setY(64.0 + Math.sin(seconds) * 12.0);
            coords.setZ(Math.cos(seconds * 0.3) * 980.0);
            dial.setProgress((float) (0.5 + 0.5 * Math.sin(seconds * 0.8)));

            drawBackdropGrid(platform.getScreenWidth(), platform.getScreenHeight());

            if (editor.isActive()) {
                // Three calls, then the look is ours.
                editor.update(platform.getMouseX(), platform.getMouseY());
                HudEditorView view = editor.view();

                Render.rect(0f, 0f, platform.getScreenWidth(), platform.getScreenHeight(), BACKDROP);
                drawElements(hud, view);
                drawOverlay(view, platform.getScreenWidth(), platform.getScreenHeight());
            } else {
                hud.drawAll(hud.resolve(false));
            }

            drawHelp(platform.getScreenWidth(), platform.getScreenHeight(), editor.isActive());
        });

        core.stop();
        window.close();

        System.out.println("Rendered " + frames + " frames with a real NanoVG backend.");
    }

    // ------------------------------------------------------------------- drawing

    /** Hidden elements are faint here because this client decided so; Core has no view. */
    private static void drawElements(HudService hud, HudEditorView view) {
        for (Placement placement : view.getPlacements()) {
            boolean hidden = placement.getLayout().isHidden();
            if (hidden) {
                Render.pushAlpha(0.3f);
            }
            hud.draw(placement);
            if (hidden) {
                Render.popAlpha();
            }
        }
    }

    private static void drawOverlay(HudEditorView view, float width, float height) {
        for (Placement placement : view.getPlacements()) {
            boolean selected = view.isSelected(placement);
            if (!selected && !view.isHovered(placement)) {
                continue;
            }
            Color outline = placement.getLayout().isLocked() ? LOCKED
                    : selected ? SELECTED : HOVERED;
            // The shape strokes itself, so the module list traces its rows and the
            // dial traces its circle without this file knowing either exists.
            placement.shape().stroke(selected ? 2f : 1f, outline);
        }

        for (Bounds handle : view.getHandles().values()) {
            Render.roundRect(handle.getX(), handle.getY(),
                    handle.getWidth(), handle.getHeight(), 1.5f, SELECTED);
        }

        for (SnapGuide guide : view.getGuides()) {
            if (guide.isVertical()) {
                Render.line(guide.getPosition(), 0f, guide.getPosition(), height, 1f, GUIDE);
            } else {
                Render.line(0f, guide.getPosition(), width, guide.getPosition(), 1f, GUIDE);
            }
        }

        Placement selected = view.getSelected();
        if (selected != null) {
            Shape shape = selected.shape();
            Bounds at = shape.bounds();
            String label = selected.getElement().getDisplayName()
                    + "   " + selected.getLayout().getAnchor().name().toLowerCase()
                    + "   x" + String.format("%.2f", selected.getLayout().getScale());

            float y = at.getBottom() + 12f;
            if (y + 14f > height) {
                y = at.getY() - 24f;
            }
            // Kept on screen: an element anchored into the right edge would
            // otherwise have its label run off the side and be unreadable.
            float x = Math.max(8f, Math.min(at.getX(), width - Render.textWidth(label) - 8f));
            Render.text(label, x, y, Color.of(230, 234, 245));
        }
    }

    /** A faint grid, so dragging an element reads as movement against something. */
    private static void drawBackdropGrid(float width, float height) {
        Color line = Color.of(255, 255, 255, 12);
        for (float x = 0f; x < width; x += 40f) {
            Render.line(x, 0f, x, height, 1f, line);
        }
        for (float y = 0f; y < height; y += 40f) {
            Render.line(0f, y, width, y, 1f, line);
        }
    }

    /** Centred along the bottom. */
    private static void drawHelp(float width, float height, boolean editing) {
        String text = editing
                ? "EDIT MODE    drag to move    corner to scale    scroll to scale    ALT no-snap    "
                        + "right-click hide    L lock    R reset    ESC exit"
                : "E to edit the HUD";
        Render.text(text, (width - Render.textWidth(text)) / 2f, height - 22f,
                Color.of(255, 255, 255, 105));
    }

    // --------------------------------------------------------------------- input

    /**
     * Every binding below is a choice made here.
     *
     * <p>Core supplies {@code press}, {@code release}, {@code update} and the
     * action methods, and has no opinion about which key or button reaches them.
     */
    private static void installInput(long window, HudService hud, HudEditor editor) {
        glfwSetMouseButtonCallback(window, (handle, button, action, mods) -> {
            double[] x = new double[1];
            double[] y = new double[1];
            glfwGetCursorPos(handle, x, y);

            if (!editor.isActive()) {
                return;
            }

            if (action == GLFW_PRESS) {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    editor.press((float) x[0], (float) y[0]);
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    editor.press((float) x[0], (float) y[0]);
                    editor.toggleHiddenSelected();
                }
            } else if (action == GLFW_RELEASE) {
                editor.release();
            }
        });

        glfwSetScrollCallback(window, (handle, xOffset, yOffset) -> {
            if (editor.isActive()) {
                editor.scaleSelected(yOffset > 0 ? 0.05f : -0.05f);
            }
        });

        glfwSetKeyCallback(window, (handle, key, scancode, action, mods) -> {
            editor.setSnappingSuspended((mods & GLFW_MOD_ALT) != 0);
            if (action != GLFW_PRESS && action != GLFW_REPEAT) {
                return;
            }
            if (key == GLFW_KEY_E && !editor.isActive()) {
                hud.openEditor();
                return;
            }
            if (!editor.isActive()) {
                return;
            }
            float step = (mods & GLFW_MOD_SHIFT) != 0 ? 10f : 1f;
            switch (key) {
                case GLFW_KEY_ESCAPE:
                    if (editor.getSelectedId() != null) {
                        editor.deselect();
                    } else {
                        hud.closeEditor();
                    }
                    break;
                case GLFW_KEY_E: hud.closeEditor(); break;
                case GLFW_KEY_LEFT: editor.nudgeSelected(-step, 0f); break;
                case GLFW_KEY_RIGHT: editor.nudgeSelected(step, 0f); break;
                case GLFW_KEY_UP: editor.nudgeSelected(0f, -step); break;
                case GLFW_KEY_DOWN: editor.nudgeSelected(0f, step); break;
                case GLFW_KEY_L: editor.toggleLockSelected(); break;
                case GLFW_KEY_H: editor.toggleHiddenSelected(); break;
                case GLFW_KEY_R: editor.resetSelected(); break;
                case GLFW_KEY_PAGE_UP: editor.bringSelectedToFront(); break;
                case GLFW_KEY_PAGE_DOWN: editor.sendSelectedToBack(); break;
                default: break;
            }
        });
    }
}
