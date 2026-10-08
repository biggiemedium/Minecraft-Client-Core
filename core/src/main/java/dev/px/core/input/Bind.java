package dev.px.core.input;

import dev.px.core.util.Validate;
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
 *
 * <p>A bind also carries its {@link BindMode}: {@link #of} binds act on the
 * press, {@link #hold} binds last while the key is down.
 */
@Getter
@EqualsAndHashCode
public final class Bind {

    /** The unbound value. Shared, since it is by far the most common bind. */
    public static final Bind NONE = new Bind(Key.NONE, MouseButton.NONE, EnumSet.noneOf(Modifier.class),
            BindMode.PRESS);

    private final Key key;
    private final MouseButton button;
    private final Set<Modifier> modifiers;
    private final BindMode mode;

    private Bind(Key key, MouseButton button, Set<Modifier> modifiers, BindMode mode) {
        this.key = key;
        this.button = button;
        this.modifiers = Collections.unmodifiableSet(modifiers);
        this.mode = mode;
    }

    public static Bind of(Key key, Modifier... modifiers) {
        return new Bind(key, MouseButton.NONE, toSet(modifiers), BindMode.PRESS);
    }

    public static Bind of(MouseButton button, Modifier... modifiers) {
        return new Bind(Key.NONE, button, toSet(modifiers), BindMode.PRESS);
    }

    /** A bind that lasts while the key is down. */
    public static Bind hold(Key key, Modifier... modifiers) {
        return of(key, modifiers).withMode(BindMode.HOLD);
    }

    /** A bind that lasts while the button is down. */
    public static Bind hold(MouseButton button, Modifier... modifiers) {
        return of(button, modifiers).withMode(BindMode.HOLD);
    }

    /** @return the same key or button and modifiers, acting in {@code newMode}. */
    public Bind withMode(BindMode newMode) {
        if (Validate.notNull(newMode, "bind mode") == mode) {
            return this;
        }
        EnumSet<Modifier> copy = EnumSet.noneOf(Modifier.class);
        copy.addAll(modifiers);
        return new Bind(key, button, copy, newMode);
    }

    public boolean isHold() {
        return mode == BindMode.HOLD;
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

    /**
     * @return the label for a keybind button, e.g. Ctrl+R, or None when unbound,
     *         followed by (Hold) for a hold bind
     */
    public String getDisplay() {
        String suffix = isHold() ? " (Hold)" : "";
        if (!isBound()) {
            return "None" + suffix;
        }
        StringBuilder text = new StringBuilder();
        for (Modifier modifier : Modifier.values()) {
            if (modifiers.contains(modifier)) {
                text.append(modifier.getDisplay()).append(PLUS);
            }
        }
        return text.append(isMouse() ? button.getDisplay() : key.getDisplay()).append(suffix).toString();
    }

    /**
     * Round-trip form for configs, such as CTRL+KEY:R, or NONE when unbound, with
     * /HOLD after a hold bind. A press bind writes no suffix, so configs written
     * before modes existed read back unchanged.
     */
    public String serialize() {
        String suffix = isHold() ? MODE_SEPARATOR + BindMode.HOLD.name() : "";
        if (!isBound()) {
            return "NONE" + suffix;
        }
        StringBuilder text = new StringBuilder();
        for (Modifier modifier : modifiers) {
            text.append(modifier.name()).append(PLUS);
        }
        return text.append(isMouse() ? "MOUSE:" + button.name() : "KEY:" + key.name()).append(suffix).toString();
    }

    public static Bind deserialize(String text) {
        if (text == null) {
            return NONE;
        }
        int separator = text.lastIndexOf(MODE_SEPARATOR);
        if (separator >= 0) {
            BindMode mode = BindMode.PRESS;
            for (BindMode candidate : BindMode.values()) {
                if (candidate.name().equalsIgnoreCase(text.substring(separator + 1))) {
                    mode = candidate;
                }
            }
            return deserialize(text.substring(0, separator)).withMode(mode);
        }
        if ("NONE".equalsIgnoreCase(text)) {
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
            return new Bind(Key.NONE, MouseButton.byName(remainder.substring(6)), modifiers, BindMode.PRESS);
        }
        if (remainder.startsWith("KEY:")) {
            return new Bind(Key.byName(remainder.substring(4)), MouseButton.NONE, modifiers, BindMode.PRESS);
        }
        // Tolerate a bare key name so a hand-edited config still loads.
        return new Bind(Key.byName(remainder), MouseButton.NONE, modifiers, BindMode.PRESS);
    }

    private static EnumSet<Modifier> toSet(Modifier... modifiers) {
        EnumSet<Modifier> set = EnumSet.noneOf(Modifier.class);
        Collections.addAll(set, modifiers);
        return set;
    }

    private static final char PLUS = '+';
    private static final char MODE_SEPARATOR = '/';

    @Override
    public String toString() {
        return getDisplay();
    }
}
