package dev.px.core.gui;

import com.google.gson.JsonObject;
import dev.px.core.hud.Bounds;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import lombok.Getter;

import java.util.Set;

/**
 * The root of a GUI tree, and the only component {@link GuiService} talks to.
 *
 * <p>A screen owns the two pieces of state that cannot live on an individual
 * component because only one component may hold them at a time: keyboard
 * {@linkplain #getFocused() focus} and the in-progress {@linkplain #beginDrag
 * drag}. Everything else is an ordinary component.
 *
 * <p>Drags are driven from the frame loop rather than from an event. Core has no
 * mouse-move event and does not need one &mdash; the HUD editor already works
 * this way, and the adapter's screen therefore has nothing extra to bridge
 * beyond the press, release, key, character and scroll events it already posts.
 *
 * <p>Screens are full-screen by construction. There is no window manager and no
 * screen stack: {@link GuiService} shows one screen or none.
 */
@Getter
public abstract class Screen extends Component {

    private final String title;

    /** Receives key and character events until something else takes focus. */
    private Component focused;

    private Component dragging;

    private float screenWidth;
    private float screenHeight;

    protected Screen(String title) {
        this.title = title == null ? "" : title;
    }

    // ------------------------------------------------------------- lifecycle

    /** Called each time the screen is opened, before the first frame. */
    public void onOpen() {
    }

    /**
     * Called when the screen is dismissed.
     *
     * <p>Transient state belongs here: a half-typed text field or a keybind
     * waiting for a key must not still be waiting the next time the screen opens.
     */
    public void onClose() {
        clearFocus();
        dragging = null;
    }

    // ----------------------------------------------------------- persistence

    /**
     * @return whatever this screen wants remembered between sessions, such as
     *         window positions. Empty by default.
     *
     * <p>Persisted by {@link GuiService} through the ordinary config mechanism,
     * so a screen does not write its own file.
     */
    public JsonObject save() {
        return new JsonObject();
    }

    /** Applies saved state. Must tolerate missing and unexpected keys. */
    public void load(JsonObject json) {
    }

    // ---------------------------------------------------------------- layout

    /** Told the current screen metrics by {@link GuiService} before every layout. */
    public final void resize(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    @Override
    public float getPreferredHeight(float width) {
        return screenHeight;
    }

    /**
     * Fills the screen, then places the children.
     *
     * <p>A screen is not a {@link Panel}: its children are free-floating windows
     * at their own coordinates rather than a stack. A screen that does want a
     * column of rows adds one {@code Panel} child and lets that do the stacking.
     */
    @Override
    public void layout(float x, float y, float width) {
        setBounds(Bounds.of(0f, 0f, screenWidth, screenHeight));
        layoutChildren();
    }

    /** Places this screen's children. Called once per frame, after {@link #resize}. */
    protected void layoutChildren() {
    }

    @Override
    public void render(float x, float y, float w, float h) {
    }

    // ----------------------------------------------------------------- focus

    /**
     * Moves keyboard focus, telling whatever held it that it is losing it.
     *
     * <p>That notification is what lets a text field commit its buffer when the
     * user clicks away rather than only on Enter.
     */
    public final void focus(Component component) {
        if (focused == component) {
            return;
        }
        Component previous = focused;
        focused = component;
        if (previous != null) {
            previous.onFocusLost();
        }
    }

    public final void clearFocus() {
        focus(null);
    }

    // ------------------------------------------------------------------ drag

    /** Starts following the cursor on behalf of a component. */
    public final void beginDrag(Component component) {
        this.dragging = component;
    }

    public final boolean isDragging() {
        return dragging != null;
    }

    /** Feeds the current cursor position to whatever is dragging. Called each frame. */
    public final void updateDrag(float x, float y) {
        if (dragging != null) {
            dragging.onDrag(x, y);
        }
    }

    public final void endDrag(float x, float y) {
        if (dragging != null) {
            Component finished = dragging;
            dragging = null;
            finished.onRelease(x, y);
        }
    }

    // --------------------------------------------------------------- routing

    /**
     * Routes a press.
     *
     * <p>Focus is dropped first unless the press landed on the focused component
     * itself, so clicking anywhere else closes a text field. The handler is then
     * free to take focus straight back, which is how clicking a different field
     * moves focus in one step.
     *
     * @return whether anything consumed it
     */
    public final boolean mousePressed(float x, float y, MouseButton button) {
        if (componentAt(x, y) != focused) {
            clearFocus();
        }
        return dispatchClick(x, y, button) != null;
    }

    public final void mouseReleased(float x, float y) {
        endDrag(x, y);
    }

    /** Key events go only to the focused component; nothing else is listening. */
    public final boolean keyPressed(Key key, Set<Modifier> modifiers) {
        return focused != null && focused.onKey(key, modifiers);
    }

    public final boolean charTyped(char character) {
        return focused != null && focused.onChar(character);
    }

    public final boolean scrolled(float amount, float x, float y) {
        return dispatchScroll(amount, x, y) != null;
    }
}
