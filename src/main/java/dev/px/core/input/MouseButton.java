package dev.px.core.input;

import java.util.Locale;

/** Platform-neutral mouse button, for binds and for click events. */
public enum MouseButton {

    NONE("None", -1),
    LEFT("Left", 0),
    RIGHT("Right", 1),
    MIDDLE("Middle", 2),
    BUTTON_4("Mouse4", 3),
    BUTTON_5("Mouse5", 4);

    private final String display;
    private final int index;

    MouseButton(String display, int index) {
        this.display = display;
        this.index = index;
    }

    public String getDisplay() {
        return display;
    }

    /** The conventional index used by both LWJGL and GLFW for the first five buttons. */
    public int getIndex() {
        return index;
    }

    public boolean isBound() {
        return this != NONE;
    }

    public static MouseButton byIndex(int index) {
        for (MouseButton button : values()) {
            if (button.index == index) {
                return button;
            }
        }
        return NONE;
    }

    public static MouseButton byName(String name) {
        if (name == null) {
            return NONE;
        }
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NONE;
        }
    }
}
