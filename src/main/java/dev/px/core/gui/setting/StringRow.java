package dev.px.core.gui.setting;

import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.setting.impl.StringSetting;

import java.util.Set;

/**
 * A text field.
 *
 * <p>Edits are written straight to the setting rather than into a buffer that is
 * committed later. The setting already enforces its own maximum length and
 * validator in {@code coerce}, so writing through means a rejected keystroke is
 * rejected as it is typed instead of silently discarding a whole line at the
 * end; and anything watching the setting sees the value the user is looking at.
 *
 * <p>The value at the moment focus was taken is kept so {@code Escape} can put
 * it back, which is the only thing a buffer would have been for.
 */
public final class StringRow extends SettingComponent<StringSetting> {

    /** Cursor blink period. */
    private static final long BLINK_MILLIS = 1060L;

    private String beforeEditing;

    public StringRow(StringSetting setting) {
        super(setting);
    }

    @Override
    public void render(float x, float y, float w, float h) {
        boolean editing = isFocused();
        renderRow(x, y, w, editing);
        renderLabel(x, y);

        String value = getSetting().get();
        if (editing && System.currentTimeMillis() % BLINK_MILLIS < BLINK_MILLIS / 2L) {
            value = value + "_";
        }
        renderValue(value, x, y, w, editing ? GuiStyle.text() : GuiStyle.textMuted());
    }

    @Override
    protected boolean onClick(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        beforeEditing = getSetting().get();
        focus();
        return true;
    }

    @Override
    protected boolean onChar(char character) {
        // Control characters are not text; the keyboard reports them separately
        // and letting them through would put a literal backspace in the value.
        if (character < ' ' || character == 127) {
            return false;
        }
        getSetting().set(getSetting().get() + character);
        return true;
    }

    @Override
    protected boolean onKey(Key key, Set<Modifier> modifiers) {
        switch (key) {
            case BACKSPACE:
                String current = getSetting().get();
                if (!current.isEmpty()) {
                    getSetting().set(current.substring(0, current.length() - 1));
                }
                return true;
            case ESCAPE:
                if (beforeEditing != null) {
                    getSetting().set(beforeEditing);
                }
                blur();
                return true;
            case ENTER:
            case NUMPAD_ENTER:
                blur();
                return true;
            default:
                // Swallow everything else while focused, so typing "r" into a text
                // field cannot also fire whatever "r" is bound to.
                return true;
        }
    }

    @Override
    protected void onFocusLost() {
        beforeEditing = null;
    }

    private void blur() {
        Screen screen = getScreen();
        if (screen != null) {
            screen.clearFocus();
        }
    }
}
