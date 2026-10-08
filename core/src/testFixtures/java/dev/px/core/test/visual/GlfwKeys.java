package dev.px.core.test.visual;

import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;

import java.util.EnumSet;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW's key codes, translated into Core's own {@link Key}, {@link Modifier} and
 * {@link MouseButton}.
 *
 * <pre>{@code
 * glfwSetKeyCallback(window, (handle, key, scancode, action, mods) ->
 *         gui.keyPressed(GlfwKeys.key(key), GlfwKeys.modifiers(mods)));
 * }</pre>
 *
 * <p>The translation every adapter on GLFW (1.13 and newer) writes once at its
 * boundary, written here for the visual harnesses. Core never stores a GLFW code,
 * so a harness that hands Core GLFW's numbers would be testing something no
 * client does.
 */
public final class GlfwKeys {

    private GlfwKeys() {
    }

    /** @return Core's key for a GLFW key code, or {@link Key#UNKNOWN}. */
    public static Key key(int glfw) {
        if (glfw >= GLFW_KEY_A && glfw <= GLFW_KEY_Z) {
            return Key.values()[Key.A.ordinal() + (glfw - GLFW_KEY_A)];
        }
        if (glfw >= GLFW_KEY_0 && glfw <= GLFW_KEY_9) {
            return Key.values()[Key.NUM_0.ordinal() + (glfw - GLFW_KEY_0)];
        }
        if (glfw >= GLFW_KEY_F1 && glfw <= GLFW_KEY_F12) {
            return Key.values()[Key.F1.ordinal() + (glfw - GLFW_KEY_F1)];
        }
        if (glfw >= GLFW_KEY_KP_0 && glfw <= GLFW_KEY_KP_9) {
            return Key.values()[Key.NUMPAD_0.ordinal() + (glfw - GLFW_KEY_KP_0)];
        }
        switch (glfw) {
            case GLFW_KEY_ESCAPE: return Key.ESCAPE;
            case GLFW_KEY_TAB: return Key.TAB;
            case GLFW_KEY_CAPS_LOCK: return Key.CAPS_LOCK;
            case GLFW_KEY_SPACE: return Key.SPACE;
            case GLFW_KEY_ENTER: return Key.ENTER;
            case GLFW_KEY_BACKSPACE: return Key.BACKSPACE;
            case GLFW_KEY_DELETE: return Key.DELETE;
            case GLFW_KEY_INSERT: return Key.INSERT;
            case GLFW_KEY_HOME: return Key.HOME;
            case GLFW_KEY_END: return Key.END;
            case GLFW_KEY_PAGE_UP: return Key.PAGE_UP;
            case GLFW_KEY_PAGE_DOWN: return Key.PAGE_DOWN;
            case GLFW_KEY_LEFT_SHIFT: return Key.LEFT_SHIFT;
            case GLFW_KEY_RIGHT_SHIFT: return Key.RIGHT_SHIFT;
            case GLFW_KEY_LEFT_CONTROL: return Key.LEFT_CONTROL;
            case GLFW_KEY_RIGHT_CONTROL: return Key.RIGHT_CONTROL;
            case GLFW_KEY_LEFT_ALT: return Key.LEFT_ALT;
            case GLFW_KEY_RIGHT_ALT: return Key.RIGHT_ALT;
            case GLFW_KEY_LEFT_SUPER: return Key.LEFT_SUPER;
            case GLFW_KEY_RIGHT_SUPER: return Key.RIGHT_SUPER;
            case GLFW_KEY_UP: return Key.UP;
            case GLFW_KEY_DOWN: return Key.DOWN;
            case GLFW_KEY_LEFT: return Key.LEFT;
            case GLFW_KEY_RIGHT: return Key.RIGHT;
            case GLFW_KEY_MINUS: return Key.MINUS;
            case GLFW_KEY_EQUAL: return Key.EQUALS;
            case GLFW_KEY_LEFT_BRACKET: return Key.LEFT_BRACKET;
            case GLFW_KEY_RIGHT_BRACKET: return Key.RIGHT_BRACKET;
            case GLFW_KEY_BACKSLASH: return Key.BACKSLASH;
            case GLFW_KEY_SEMICOLON: return Key.SEMICOLON;
            case GLFW_KEY_APOSTROPHE: return Key.QUOTE;
            case GLFW_KEY_GRAVE_ACCENT: return Key.GRAVE;
            case GLFW_KEY_COMMA: return Key.COMMA;
            case GLFW_KEY_PERIOD: return Key.PERIOD;
            case GLFW_KEY_SLASH: return Key.SLASH;
            case GLFW_KEY_KP_ADD: return Key.NUMPAD_ADD;
            case GLFW_KEY_KP_SUBTRACT: return Key.NUMPAD_SUBTRACT;
            case GLFW_KEY_KP_MULTIPLY: return Key.NUMPAD_MULTIPLY;
            case GLFW_KEY_KP_DIVIDE: return Key.NUMPAD_DIVIDE;
            case GLFW_KEY_KP_DECIMAL: return Key.NUMPAD_DECIMAL;
            case GLFW_KEY_KP_ENTER: return Key.NUMPAD_ENTER;
            case GLFW_KEY_PRINT_SCREEN: return Key.PRINT_SCREEN;
            case GLFW_KEY_SCROLL_LOCK: return Key.SCROLL_LOCK;
            case GLFW_KEY_PAUSE: return Key.PAUSE;
            case GLFW_KEY_MENU: return Key.MENU;
            default: return Key.UNKNOWN;
        }
    }

    /** @return the modifiers held, from a GLFW {@code mods} bit field. */
    public static Set<Modifier> modifiers(int mods) {
        Set<Modifier> held = EnumSet.noneOf(Modifier.class);
        if ((mods & GLFW_MOD_CONTROL) != 0) {
            held.add(Modifier.CTRL);
        }
        if ((mods & GLFW_MOD_SHIFT) != 0) {
            held.add(Modifier.SHIFT);
        }
        if ((mods & GLFW_MOD_ALT) != 0) {
            held.add(Modifier.ALT);
        }
        if ((mods & GLFW_MOD_SUPER) != 0) {
            held.add(Modifier.SUPER);
        }
        return held;
    }

    /** GLFW numbers the first five buttons as Core does. */
    public static MouseButton button(int glfw) {
        return MouseButton.byIndex(glfw);
    }
}
