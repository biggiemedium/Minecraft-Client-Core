package dev.px.core.test.visual;

import dev.px.core.render.Render;
import lombok.Getter;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * A real window with a real 2D backend, for any module's visual harness.
 *
 * <pre>{@code
 * VisualWindow window = VisualWindow.open("My harness", args);
 * Render.install(window.getBackend());
 * Core core = Core.builder("Visual", "1.0").platform(window.getPlatform()).build();
 * core.start();
 * Render.setDefaultFont(window.loadFont(15f));
 *
 * window.run(frame -> drawEverything());
 * window.close();
 * }</pre>
 *
 * <p>Test tooling only. The libraries draw through Core's {@code Render2D} and
 * never see NanoVG; this is the one implementation that puts their drawing on a
 * screen while they are being developed. Nothing here knows what a HUD element
 * or a widget is &mdash; that is each harness's business.
 *
 * <p>Every harness gets the same three options, which is what lets a change be
 * checked without a display:
 *
 * <ul>
 *   <li>{@code --frames=N} renders N frames to a hidden window and exits</li>
 *   <li>{@code --screenshot=path.png} writes the last of those frames to a PNG</li>
 *   <li>{@code --mouse=x,y} reports that point as the cursor, since a hidden
 *       window has none</li>
 * </ul>
 *
 * <p>On macOS the JVM needs {@code -XstartOnFirstThread}, or GLFW aborts when the
 * window is created. Each module's {@code visual} Gradle task passes it.
 */
public final class VisualWindow {

    private static final String[] FONT_CANDIDATES = {
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/Library/Fonts/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "C:\\Windows\\Fonts\\arial.ttf",
    };

    /** The window background: dark, so outlines and light text read against it. */
    private static final float CLEAR_RED = 0.10f;
    private static final float CLEAR_GREEN = 0.11f;
    private static final float CLEAR_BLUE = 0.13f;

    /** The GLFW window. */
    @Getter
    private final long handle;

    /** The NanoVG context. */
    @Getter
    private final long vg;

    @Getter
    private final NanoVGRender2D backend;
    @Getter
    private final WindowPlatform platform;

    /** How many frames to render before exiting; zero runs until the window is closed. */
    private final int frameLimit;

    /** Where to write the last frame, or null. */
    private final String screenshot;

    private final String[] args;

    private boolean loadedFont;

    private VisualWindow(long handle, long vg, WindowPlatform platform, String[] args) {
        this.handle = handle;
        this.vg = vg;
        this.backend = new NanoVGRender2D(vg);
        this.platform = platform;
        this.args = args;
        this.frameLimit = frameLimit(args);
        this.screenshot = option(args, "--screenshot=");
    }

    /**
     * Opens a window, hidden when {@code --frames} is given.
     *
     * <p>Installs nothing: the caller decides when {@code Render.install} runs
     * relative to building its Core, exactly as an adapter would.
     */
    public static VisualWindow open(String title, String[] args) throws IOException {
        return open(title, 1100, 680, args);
    }

    public static VisualWindow open(String title, int width, int height, String[] args) throws IOException {
        if (!glfwInit()) {
            throw new IllegalStateException("GLFW would not start");
        }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, frameLimit(args) > 0 ? GLFW_FALSE : GLFW_TRUE);

        long window = glfwCreateWindow(width, height, title, MemoryUtil.NULL, MemoryUtil.NULL);
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

        File dataDirectory = Files.createTempDirectory("core-visual").toFile();
        dataDirectory.deleteOnExit();
        WindowPlatform platform = new WindowPlatform(window, dataDirectory);

        String mouse = option(args, "--mouse=");
        if (mouse != null) {
            String[] at = mouse.split(",");
            platform.overrideCursor(Float.parseFloat(at[0].trim()), Float.parseFloat(at[1].trim()));
        }
        return new VisualWindow(window, vg, platform, args);
    }

    /** @return whether this run renders a fixed number of frames to a hidden window. */
    public boolean isHeadless() {
        return frameLimit > 0;
    }

    /**
     * Loads the first system font found, at a size.
     *
     * <p>Each call registers the face again under its own name; NanoVG keeps
     * them all until the window closes.
     */
    public NanoVGFont loadFont(float size) {
        for (String candidate : FONT_CANDIDATES) {
            if (!new File(candidate).isFile()) {
                continue;
            }
            String name = "ui-" + size;
            int font = nvgCreateFont(vg, name, candidate);
            if (font != -1) {
                if (!loadedFont) {
                    System.out.println("Font: " + candidate);
                    loadedFont = true;
                }
                return new NanoVGFont(vg, name, font, size);
            }
        }
        throw new IllegalStateException("No usable font found; add one to FONT_CANDIDATES");
    }

    // ------------------------------------------------------------------ frames

    /** One frame's drawing, between {@code Render.begin2D} and {@code Render.end2D}. */
    public interface Frame {

        /**
         * @param index how many frames came before this one
         * @param seconds time since {@link #run} began
         */
        void draw(int index, float seconds) throws Exception;
    }

    /**
     * Renders until the window is closed, or for {@code --frames} frames.
     *
     * <p>Each frame polls input (so GLFW callbacks the caller installed fire
     * here), clears, opens a 2D frame at the window's logical size and pixel
     * ratio, and hands over. The last frame of a {@code --frames} run is written
     * to {@code --screenshot} if one was given.
     *
     * @return the number of frames rendered
     */
    public int run(Frame frame) throws Exception {
        long started = System.nanoTime();
        int frames = 0;
        while (!glfwWindowShouldClose(handle) && (frameLimit <= 0 || frames < frameLimit)) {
            glfwPollEvents();

            int[] bufferWidth = new int[1];
            int[] bufferHeight = new int[1];
            glfwGetFramebufferSize(handle, bufferWidth, bufferHeight);
            glViewport(0, 0, bufferWidth[0], bufferHeight[0]);
            glClearColor(CLEAR_RED, CLEAR_GREEN, CLEAR_BLUE, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            Render.begin2D(platform.getScreenWidth(), platform.getScreenHeight(), platform.getScreenScale());
            frame.draw(frames, (System.nanoTime() - started) / 1_000_000_000f);
            Render.end2D();

            if (screenshot != null && frames == frameLimit - 1) {
                capture(screenshot, bufferWidth[0], bufferHeight[0]);
            }
            glfwSwapBuffers(handle);
            frames++;
        }
        return frames;
    }

    /** Frees NanoVG and the window. Call after the caller's Core has stopped. */
    public void close() {
        nvgDelete(vg);
        glfwDestroyWindow(handle);
        glfwTerminate();
    }

    /**
     * Reads the framebuffer back and writes a PNG.
     *
     * <p>So that "it rendered without crashing" can be upgraded to "it rendered
     * the right thing", which is not the same claim.
     */
    private static void capture(String path, int width, int height) throws IOException {
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

    // --------------------------------------------------------------- arguments

    /** @return the value after {@code prefix} in this window's arguments, or null. */
    public String option(String prefix) {
        return option(args, prefix);
    }

    /** @return whether this window's arguments include {@code flag} exactly. */
    public boolean has(String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String option(String[] args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static int frameLimit(String[] args) {
        String frames = option(args, "--frames=");
        return frames == null ? 0 : Integer.parseInt(frames);
    }
}
