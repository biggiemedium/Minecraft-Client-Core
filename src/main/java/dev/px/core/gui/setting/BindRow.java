package dev.px.core.gui.setting;

import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingComponent;
import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.setting.impl.BindSetting;

import java.util.Set;

/**
 * A keybind button: click it, then press the key you want.
 *
 * <p>The capture state lives here rather than going through
 * {@link dev.px.core.input.InputService#capture}, and that is forced by the way
 * the GUI takes input. The GUI cancels key events at {@link
 * dev.px.core.event.Priority#HIGHEST} so no bind fires behind an open screen,
 * and {@code InputService} listens at {@code LOW} &mdash; it would never see the
 * key that was meant to be captured. Owning the state here is what makes the
 * capture work at all, and it keeps the two rules of capture in one place:
 *
 * <ul>
 *   <li>{@code Escape} clears the bind rather than binding Escape, which nobody
 *       wants.</li>
 *   <li>Modifiers held at the time qualify the bind, so Ctrl+R binds Ctrl+R.</li>
 * </ul>
 *
 * <p>Both match {@code InputService}'s own behaviour, so a bind set here and a
 * bind set by any other path are the same kind of thing.
 */
public final class BindRow extends SettingComponent<BindSetting> {

    private boolean capturing;

    public BindRow(BindSetting setting) {
        super(setting);
    }

    public boolean isCapturing() {
        return capturing;
    }

    @Override
    public void render(float x, float y, float w, float h) {
        renderRow(x, y, w, capturing);
        renderLabel(x, y);
        renderValue(capturing ? "..." : getSetting().get().getDisplay(), x, y, w);
    }

    @Override
    protected boolean onClick(float x, float y, MouseButton button) {
        if (button == MouseButton.RIGHT) {
            // Right-click unbinds, so clearing a bind does not need the keyboard.
            getSetting().clear();
            return true;
        }
        if (button != MouseButton.LEFT) {
            return false;
        }
        capturing = true;
        focus();
        return true;
    }

    @Override
    protected boolean onKey(Key key, Set<Modifier> modifiers) {
        if (!capturing) {
            return false;
        }
        getSetting().set(key == Key.ESCAPE
                ? Bind.NONE
                : Bind.of(key, modifiers.toArray(new Modifier[0])));
        capturing = false;

        Screen screen = getScreen();
        if (screen != null) {
            screen.clearFocus();
        }
        return true;
    }

    /** Clicking away abandons the capture rather than leaving the row armed. */
    @Override
    protected void onFocusLost() {
        capturing = false;
    }
}
