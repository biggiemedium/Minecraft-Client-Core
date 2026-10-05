package dev.px.core.input;

import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * An immutable key or mouse binding, optionally with modifiers.
 *
 * <p>The old Bind was an int wrapper: it could not express Ctrl+R, could not
 * tell "unbound" apart from "key 0", and wrote a version-specific LWJGL code
 * straight into the config file. This holds a {@link Key} or a
 * {@link MouseButton} plus a modifier set, and serialises by name.
 */
@Getter
@EqualsAndHashCode
public final class Bind {

    /** The unbound value. Shared, since it is by far the most common bind. */
    public static final Bind NONE = new Bind(Key.NONE, MouseButton.NONE, EnumSet.noneOf(Modifier.class));

    private final Key key;
    private final MouseButton button;
    private final Set<Modifier> modifiers;

    private Bind(Key key, MouseButton button, Set<Modifier> modifiers) {
        this.key = key;
        this.button = button;
        this.modifiers = Collections.unmodifiableSet(modifiers);
    }

    public static Bind of(Key key, Modifier... modifiers) {
        return new Bind(key, MouseButton.NONE, toSet(modifiers));
    }

    public static Bind of(MouseButton button, Modifier... modifiers) {
        return new Bind(Key.NONE, button, toSet(modifiers));
    }

    public boolean isBound() {
        return key.isBound() || button.isBound();
    }

    public boolean isMouse() {
        return button.isBound();
    }

    /**
     * @param activeModifiers the modifiers currently held down
     * @return whether this bind should fire for the given key press
     */
    public boolean matches(Key pressed, Set<Modifier> activeModifiers) {
        return key.isBound() && key == pressed && activeModifiers.containsAll(modifiers);
    }

    public boolean matches(MouseButton pressed, Set<Modifier> activeModifiers) {
        return button.isBound() && button == pressed && activeModifiers.containsAll(modifiers);
    }

    /** @return the label for a keybind button, e.g. Ctrl+R, or None when unbound. */
    public String getDisplay() {
        if (!isBound()) {
            return "None";
        }
        StringBuilder text = new StringBuilder();
        for (Modifier modifier : Modifier.values()) {
            if (modifiers.contains(modifier)) {
                text.append(modifier.getDisplay()).append(PLUS);
            }
        }
        return text.append(isMouse() ? button.getDisplay() : key.getDisplay()).toString();
    }

    /** Round-trip form for configs, such as CTRL+KEY:R, or NONE when unbound. */
    public String serialize() {
        if (!isBound()) {
            return "NONE";
        }
        StringBuilder text = new StringBuilder();
        for (Modifier modifier : modifiers) {
            text.append(modifier.name()).append(PLUS);
        }
        return text.append(isMouse() ? "MOUSE:" + button.name() : "KEY:" + key.name()).toString();
    }

    public static Bind deserialize(String text) {
        if (text == null || "NONE".equalsIgnoreCase(text)) {
            return NONE;
        }
        EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        String remainder = text;
        int split;
        while ((split = remainder.indexOf(PLUS)) >= 0) {
            Modifier modifier = Modifier.byName(remainder.substring(0, split));
            if (modifier != null) {
                modifiers.add(modifier);
            }
            remainder = remainder.substring(split + 1);
        }
        if (remainder.startsWith("MOUSE:")) {
            return new Bind(Key.NONE, MouseButton.byName(remainder.substring(6)), modifiers);
        }
        if (remainder.startsWith("KEY:")) {
            return new Bind(Key.byName(remainder.substring(4)), MouseButton.NONE, modifiers);
        }
        // Tolerate a bare key name so a hand-edited config still loads.
        return new Bind(Key.byName(remainder), MouseButton.NONE, modifiers);
    }

    private static EnumSet<Modifier> toSet(Modifier... modifiers) {
        EnumSet<Modifier> set = EnumSet.noneOf(Modifier.class);
        Collections.addAll(set, modifiers);
        return set;
    }

    private static final char PLUS = '+';

    @Override
    public String toString() {
        return getDisplay();
    }
}
