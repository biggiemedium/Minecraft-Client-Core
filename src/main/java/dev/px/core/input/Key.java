package dev.px.core.input;

import java.util.Locale;

/**
 * Platform-neutral keyboard key.
 *
 * <p>LWJGL 2 (1.8.x) and GLFW (1.13+) use different numeric key codes, so Core
 * refuses to store either. Binds hold a {@code Key}, adapters translate at the
 * boundary, and a config written on 1.8.9 loads unchanged on 1.21.
 */
public enum Key {

    NONE("None"),

    A, B, C, D, E, F, G, H, I, J, K, L, M,
    N, O, P, Q, R, S, T, U, V, W, X, Y, Z,

    NUM_0("0"), NUM_1("1"), NUM_2("2"), NUM_3("3"), NUM_4("4"),
    NUM_5("5"), NUM_6("6"), NUM_7("7"), NUM_8("8"), NUM_9("9"),

    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,

    ESCAPE("Esc"), TAB("Tab"), CAPS_LOCK("Caps"), SPACE("Space"),
    ENTER("Enter"), BACKSPACE("Backspace"), DELETE("Delete"), INSERT("Insert"),
    HOME("Home"), END("End"), PAGE_UP("PgUp"), PAGE_DOWN("PgDn"),

    LEFT_SHIFT("LShift"), RIGHT_SHIFT("RShift"),
    LEFT_CONTROL("LCtrl"), RIGHT_CONTROL("RCtrl"),
    LEFT_ALT("LAlt"), RIGHT_ALT("RAlt"),
    LEFT_SUPER("LWin"), RIGHT_SUPER("RWin"),

    UP("Up"), DOWN("Down"), LEFT("Left"), RIGHT("Right"),

    MINUS("-"), EQUALS("="), LEFT_BRACKET("["), RIGHT_BRACKET("]"),
    BACKSLASH("\\"), SEMICOLON(";"), QUOTE("Quote"), GRAVE("Grave"),
    COMMA(","), PERIOD("."), SLASH("/"),

    NUMPAD_0("Num0"), NUMPAD_1("Num1"), NUMPAD_2("Num2"), NUMPAD_3("Num3"), NUMPAD_4("Num4"),
    NUMPAD_5("Num5"), NUMPAD_6("Num6"), NUMPAD_7("Num7"), NUMPAD_8("Num8"), NUMPAD_9("Num9"),
    NUMPAD_ADD("Num+"), NUMPAD_SUBTRACT("Num-"), NUMPAD_MULTIPLY("Num*"),
    NUMPAD_DIVIDE("Num/"), NUMPAD_DECIMAL("Num."), NUMPAD_ENTER("NumEnter"),

    PRINT_SCREEN("PrtSc"), SCROLL_LOCK("ScrLk"), PAUSE("Pause"), MENU("Menu"),

    /** Reported by an adapter for a physical key Core has no constant for. */
    UNKNOWN("Unknown");

    private final String display;

    Key() {
        this.display = name();
    }

    Key(String display) {
        this.display = display;
    }

    /** @return the label to show on a keybind button. */
    public String getDisplay() {
        return display;
    }

    public boolean isModifier() {
        switch (this) {
            case LEFT_SHIFT: case RIGHT_SHIFT:
            case LEFT_CONTROL: case RIGHT_CONTROL:
            case LEFT_ALT: case RIGHT_ALT:
            case LEFT_SUPER: case RIGHT_SUPER:
                return true;
            default:
                return false;
        }
    }

    public boolean isBound() {
        return this != NONE && this != UNKNOWN;
    }

    /** Parses a persisted name, falling back to {@link #NONE} for anything unrecognised. */
    public static Key byName(String name) {
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
