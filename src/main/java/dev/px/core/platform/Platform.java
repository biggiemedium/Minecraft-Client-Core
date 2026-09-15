package dev.px.core.platform;

import java.io.File;

/**
 * The narrow seam between Core and the game.
 *
 * <p>Deliberately small. This is not the adapter layer &mdash; there is no
 * player, world, entity or packet here. It covers only what Core itself needs
 * to function at all: where to write config files, how big the screen is, and
 * how to say something to the user. Everything richer belongs to the version
 * adapter that sits above Core.
 *
 * <p>Keeping it this thin is what lets Core compile with no game on the
 * classpath, which is the property that makes it portable.
 */
public interface Platform {

    /** A display name for the client host, e.g. "Minecraft 1.8.9". */
    String getGameVersion();

    /**
     * The directory Core writes configs and data into.
     *
     * <p>Usually a named folder inside the game directory. Core creates it if it
     * does not exist.
     */
    File getDataDirectory();

    /** @return the account name currently in use, for display and per-account configs. */
    String getUsername();

    /** @return whether the player is in a world, as opposed to a menu. */
    boolean isInGame();

    // ------------------------------------------------------------- screen

    /** Screen width in scaled units, the space {@link dev.px.core.render.Render2D} draws in. */
    float getScreenWidth();

    float getScreenHeight();

    /** @return pixels per scaled unit. */
    float getScreenScale();

    /** Mouse position in the same scaled space as {@link #getScreenWidth()}. */
    float getMouseX();

    float getMouseY();

    // --------------------------------------------------------------- chat

    /** Prints a message only this client sees. Used for command output. */
    void printMessage(String message);

    /** Sends a message or command to the server as if the player typed it. */
    void sendChatMessage(String message);

    /**
     * Copies text to the system clipboard.
     *
     * <p>On the game host rather than through AWT, because AWT clipboard access
     * is unreliable in a game process on some platforms.
     */
    void setClipboard(String text);

    String getClipboard();
}
