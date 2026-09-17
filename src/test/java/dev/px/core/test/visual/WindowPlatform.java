package dev.px.core.test.visual;

import dev.px.core.platform.Platform;
import lombok.Setter;

import java.io.File;

import static org.lwjgl.glfw.GLFW.*;

/**
 * {@link Platform} on a GLFW window.
 *
 * <p>Thirteen methods, and the only ones the HUD actually leans on are the
 * screen size and the cursor. That a real windowing system satisfies the same
 * interface a temp directory and four floats satisfied in the headless suite is
 * the whole argument for keeping this seam narrow.
 */
public final class WindowPlatform implements Platform {

    private final long window;
    private final File dataDirectory;

    @Setter
    private boolean inGame = true;

    private String clipboard = "";

    public WindowPlatform(long window, File dataDirectory) {
        this.window = window;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public String getGameVersion() {
        return "NanoVG harness";
    }

    @Override
    public File getDataDirectory() {
        return dataDirectory;
    }

    @Override
    public String getUsername() {
        return "Dev";
    }

    @Override
    public boolean isInGame() {
        return inGame;
    }

    // ------------------------------------------------------------------ screen

    /**
     * Window size in points, not pixels.
     *
     * <p>The HUD lays out in the same space the cursor is reported in, so this
     * has to be the logical size. On a retina display the framebuffer is twice
     * this, and that ratio goes to NanoVG as the pixel ratio instead.
     */
    @Override
    public float getScreenWidth() {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetWindowSize(window, width, height);
        return width[0];
    }

    @Override
    public float getScreenHeight() {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetWindowSize(window, width, height);
        return height[0];
    }

    @Override
    public float getScreenScale() {
        int[] windowWidth = new int[1];
        int[] windowHeight = new int[1];
        int[] bufferWidth = new int[1];
        int[] bufferHeight = new int[1];
        glfwGetWindowSize(window, windowWidth, windowHeight);
        glfwGetFramebufferSize(window, bufferWidth, bufferHeight);
        return windowWidth[0] == 0 ? 1f : (float) bufferWidth[0] / windowWidth[0];
    }

    @Override
    public float getMouseX() {
        double[] x = new double[1];
        double[] y = new double[1];
        glfwGetCursorPos(window, x, y);
        return (float) x[0];
    }

    @Override
    public float getMouseY() {
        double[] x = new double[1];
        double[] y = new double[1];
        glfwGetCursorPos(window, x, y);
        return (float) y[0];
    }

    // -------------------------------------------------------------------- chat

    @Override
    public void printMessage(String message) {
        System.out.println("[chat] " + message);
    }

    @Override
    public void sendChatMessage(String message) {
        System.out.println("[send] " + message);
    }

    @Override
    public void setClipboard(String text) {
        this.clipboard = text;
        glfwSetClipboardString(window, text);
    }

    @Override
    public String getClipboard() {
        String system = glfwGetClipboardString(window);
        return system == null ? clipboard : system;
    }
}
