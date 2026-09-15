package dev.px.core.test.harness;

import dev.px.core.platform.Platform;

import java.io.File;

/**
 * A {@link Platform} backed by plain fields.
 *
 * <p>This class is the proof that Core's game seam is narrow enough to be
 * useful. Everything Core needs from Minecraft is thirteen methods, and all of
 * them can be answered by a temp directory and four floats.
 *
 * <p>Screen size and cursor are writable so tests can change resolution and
 * drive a drag without a window.
 */
public final class FakePlatform implements Platform {

    private final File dataDirectory;

    private float screenWidth = 854f;
    private float screenHeight = 480f;
    private float mouseX;
    private float mouseY;
    private boolean inGame = true;
    private String clipboard = "";

    /** Messages Core printed, so command output can be asserted. */
    private final java.util.List<String> messages = new java.util.ArrayList<>();

    public FakePlatform(File dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public void setScreen(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    public void setMouse(float x, float y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    public void setInGame(boolean inGame) {
        this.inGame = inGame;
    }

    public java.util.List<String> getMessages() {
        return messages;
    }

    public String lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public void clearMessages() {
        messages.clear();
    }

    @Override public String getGameVersion() { return "Headless"; }
    @Override public File getDataDirectory() { return dataDirectory; }
    @Override public String getUsername() { return "Tester"; }
    @Override public boolean isInGame() { return inGame; }
    @Override public float getScreenWidth() { return screenWidth; }
    @Override public float getScreenHeight() { return screenHeight; }
    @Override public float getScreenScale() { return 2f; }
    @Override public float getMouseX() { return mouseX; }
    @Override public float getMouseY() { return mouseY; }
    @Override public void printMessage(String message) { messages.add(message); }
    @Override public void sendChatMessage(String message) { }
    @Override public void setClipboard(String text) { this.clipboard = text; }
    @Override public String getClipboard() { return clipboard; }
}
