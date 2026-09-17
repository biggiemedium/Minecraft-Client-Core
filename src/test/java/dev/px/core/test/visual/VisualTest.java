package dev.px.core.test.visual;

import dev.px.core.Core;
import dev.px.core.gui.GuiStyle;
import dev.px.core.layout.Bounds;
import dev.px.core.hud.HudEditor;
import dev.px.core.hud.HudEditorView;
import dev.px.core.hud.HudService;
import dev.px.core.hud.Placement;
import dev.px.core.layout.Shape;
import dev.px.core.hud.SnapGuide;
import dev.px.core.input.Key;
import dev.px.core.input.MouseButton;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.test.example.ExampleCategories;
import dev.px.core.test.example.ExampleKillAura;
import dev.px.core.test.example.ExampleSprint;

import java.util.EnumSet;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.*;
import static org.lwjgl.opengl.GL11.*;

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

    private static final String[] FONT_CANDIDATES = {
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/Library/Fonts/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "C:\\Windows\\Fonts\\arial.ttf",
    };

    private VisualTest() {
    }

    public static void main(String[] args) throws Exception {
        int frameLimit = frameLimit(args);
        String screenshot = option(args, "--screenshot=");
        String select = option(args, "--select=");
        boolean showGui = has(args, "--gui");
        boolean startEditing = has(args, "--edit") || select != null;

        if (!glfwInit()) {
            throw new IllegalStateException("GLFW would not start");
        }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, frameLimit > 0 ? GLFW_FALSE : GLFW_TRUE);

        long window = glfwCreateWindow(1100, 680, "Core HUD - NanoVG harness", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("No window could be created");
        }
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        long vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
        if (vg == MemoryUtil.NULL) {
            throw new IllegalStateException("NanoVG would not start");
        }

        // ---- boot Core, exactly as an adapter would -----------------------------
        File dataDirectory = Files.createTempDirectory("core-visual").toFile();
        dataDirectory.deleteOnExit();

        WindowPlatform platform = new WindowPlatform(window, dataDirectory);
        Core core = Core.builder("CoreVisual", "1.0").platform(platform).build();

        // The GUI grid, resized for this harness's 15px font. Minecraft's own font
        // is about 9px, which is what the defaults are shaped for. One call, and
        // every row, window and indent Core ships follows it -- which is the whole
        // point of the metrics being replaceable rather than constant.
        GuiStyle.metrics(GuiStyle.Metrics.builder()
                .rowHeight(20f)
                .padding(6f)
                .spacing(2f)
                .windowWidth(190f)
                .titleHeight(24f)
                .indent(10f)
                .build());

        // Modules, so the click GUI has something to show.
        core.getCategories().registerAll(ExampleCategories.class);
        core.getModuleRegistry().registerAll(new ExampleKillAura(), new ExampleSprint());

        NanoVGRender2D backend = new NanoVGRender2D(vg);
        Render.install(backend);

        VisualElements.FpsCounter fps = new VisualElements.FpsCounter();
        VisualElements.Coordinates coords = new VisualElements.Coordinates();
        VisualElements.Dial dial = new VisualElements.Dial();

        HudService hud = core.getHudService();
        hud.registerAll(new VisualElements.Watermark(), fps,
                new VisualElements.ModuleList(), coords, dial);

        core.start();

        Render.setDefaultFont(loadFont(vg));

        HudEditor editor = hud.getEditor();
        installInput(window, hud, core, editor);

        if (showGui) {
            core.getGuiService().openClickGui();
            core.getGuiService().getClickGui().getWindows()
                    .forEach(w -> w.getChildren().forEach(child -> {
                        if (child instanceof dev.px.core.gui.click.ModuleButton) {
                            ((dev.px.core.gui.click.ModuleButton) child).setExpanded(true);
                        }
                    }));
        }

        if (startEditing) {
            hud.openEditor();
            editor.select(select == null ? "fps" : select);
        }

        // ---- the frame ------------------------------------------------------------
        long started = System.nanoTime();
        int frames = 0;
        int measuredFps = 0;
        long lastSecond = started;
        int sinceSecond = 0;

        while (!glfwWindowShouldClose(window) && (frameLimit <= 0 || frames < frameLimit)) {
            glfwPollEvents();

            long now = System.nanoTime();
            sinceSecond++;
            if (now - lastSecond >= 1_000_000_000L) {
                measuredFps = sinceSecond;
                sinceSecond = 0;
                lastSecond = now;
            }
            float seconds = (now - started) / 1_000_000_000f;

            // Live data, so the elements genuinely resize as they update.
            fps.setFps(measuredFps > 0 ? measuredFps
                    : Math.round(frames / Math.max(0.001f, (now - started) / 1_000_000_000f)));
            coords.setX(Math.sin(seconds * 0.4) * 1200.0);
            coords.setY(64.0 + Math.sin(seconds) * 12.0);
            coords.setZ(Math.cos(seconds * 0.3) * 980.0);
            dial.setProgress((float) (0.5 + 0.5 * Math.sin(seconds * 0.8)));

            int[] bufferWidth = new int[1];
            int[] bufferHeight = new int[1];
            glfwGetFramebufferSize(window, bufferWidth, bufferHeight);
            glViewport(0, 0, bufferWidth[0], bufferHeight[0]);
            glClearColor(0.10f, 0.11f, 0.13f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            Render.begin2D(platform.getScreenWidth(), platform.getScreenHeight(),
                    platform.getScreenScale());

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

            // The GUI is drawn by the host, exactly as a Minecraft screen would.
            core.getGuiService().renderFrame();

            drawHelp(platform.getScreenWidth(), platform.getScreenHeight(), editor.isActive());

            Render.end2D();

            if (screenshot != null && frames == frameLimit - 1) {
                capture(screenshot, bufferWidth[0], bufferHeight[0]);
            }
            glfwSwapBuffers(window);
            frames++;
        }

        core.stop();
        nvgDelete(vg);
        glfwDestroyWindow(window);
        glfwTerminate();

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

    /** Centred along the bottom, clear of the windows the GUI opens at the top. */
    private static void drawHelp(float width, float height, boolean editing) {
        String text = editing
                ? "EDIT MODE    drag to move    corner to scale    scroll to scale    ALT no-snap    "
                        + "right-click hide    L lock    R reset    ESC exit"
                : "E to edit the HUD    G for the click GUI";
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
    private static void installInput(long window, HudService hud, Core core, HudEditor editor) {
        glfwSetMouseButtonCallback(window, (handle, button, action, mods) -> {
            double[] x = new double[1];
            double[] y = new double[1];
            glfwGetCursorPos(handle, x, y);

            // The GUI gets first refusal, the way the game's own screen would.
            if (core.getGuiService().isOpen()) {
                if (action == GLFW_PRESS) {
                    core.getGuiService().mousePressed((float) x[0], (float) y[0],
                            button == GLFW_MOUSE_BUTTON_RIGHT ? MouseButton.RIGHT : MouseButton.LEFT);
                } else {
                    core.getGuiService().mouseReleased((float) x[0], (float) y[0]);
                }
                return;
            }
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
            if (core.getGuiService().isOpen()) {
                if (key == GLFW_KEY_ESCAPE
                        && !core.getGuiService().keyPressed(Key.ESCAPE, EnumSet.noneOf(dev.px.core.input.Modifier.class))) {
                    core.getGuiService().close();
                }
                return;
            }
            if (key == GLFW_KEY_G) {
                core.getGuiService().toggleClickGui();
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

    // ----------------------------------------------------------------- internals

    private static NanoVGFont loadFont(long vg) {
        for (String candidate : FONT_CANDIDATES) {
            if (!new File(candidate).isFile()) {
                continue;
            }
            int handle = nvgCreateFont(vg, "ui", candidate);
            if (handle != -1) {
                System.out.println("Font: " + candidate);
                return new NanoVGFont(vg, "ui", handle, 15f);
            }
        }
        throw new IllegalStateException("No usable font found; add one to FONT_CANDIDATES");
    }

    /**
     * Reads the framebuffer back and writes a PNG.
     *
     * <p>So that "it rendered without crashing" can be upgraded to "it rendered
     * the right thing", which is not the same claim.
     */
    private static void capture(String path, int width, int height) throws Exception {
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = (x + width * y) * 4;
                    int r = pixels.get(i) & 0xFF;
                    int g = pixels.get(i + 1) & 0xFF;
                    int b = pixels.get(i + 2) & 0xFF;
                    // OpenGL's origin is bottom-left; an image's is top-left.
                    image.setRGB(x, height - 1 - y, (r << 16) | (g << 8) | b);
                }
            }
            ImageIO.write(image, "PNG", new File(path));
            System.out.println("Screenshot: " + path);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static String option(String[] args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static boolean has(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    /** {@code --frames=N} renders offscreen and exits, so the harness can be checked in CI. */
    private static int frameLimit(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--frames=")) {
                return Integer.parseInt(arg.substring("--frames=".length()));
            }
        }
        return 0;
    }
}
