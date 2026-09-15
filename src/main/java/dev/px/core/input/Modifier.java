package dev.px.core.input;

import java.util.Locale;

/** Modifier key held alongside a bind. Left and right variants are treated alike. */
public enum Modifier {

    CTRL("Ctrl"),
    SHIFT("Shift"),
    ALT("Alt"),
    SUPER("Win");

    private final String display;

    Modifier(String display) {
        this.display = display;
    }

    public String getDisplay() {
        return display;
    }

    /** @return the modifier a key represents, or {@code null} if it is not one. */
    public static Modifier of(Key key) {
        switch (key) {
            case LEFT_CONTROL: case RIGHT_CONTROL: return CTRL;
            case LEFT_SHIFT: case RIGHT_SHIFT: return SHIFT;
            case LEFT_ALT: case RIGHT_ALT: return ALT;
            case LEFT_SUPER: case RIGHT_SUPER: return SUPER;
            default: return null;
        }
    }

    public static Modifier byName(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
