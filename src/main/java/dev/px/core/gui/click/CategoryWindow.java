package dev.px.core.gui.click;

import dev.px.core.layout.Align;
import dev.px.core.layout.Content;

import com.google.gson.JsonObject;
import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.Panel;
import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingRendererRegistry;
import dev.px.core.input.MouseButton;
import dev.px.core.module.Category;
import dev.px.core.module.Module;
import dev.px.core.render.Render;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * A draggable window listing the modules of one category.
 *
 * <p>Holds its own position, because that is the piece of state a click GUI has
 * to remember and the only reason this screen persists anything at all.
 * Dragging follows the cursor from the frame loop through
 * {@link Screen#beginDrag}, exactly as a HUD element does in edit mode, so
 * nothing here needs a mouse-move event that Core does not have.
 *
 * <p>Collapsing hides the buttons through {@link #showsChildren()}, which takes
 * them out of layout, drawing and hit testing together &mdash; a collapsed
 * window cannot be clicked through.
 */
@Getter
public final class CategoryWindow extends Panel {

    private final Category category;

    @Setter
    private float x;

    @Setter
    private float y;

    private boolean collapsed;

    /** Cursor position relative to the window's corner when the drag began. */
    private float grabX;
    private float grabY;

    public CategoryWindow(Category category, float x, float y) {
        this.category = category;
        this.x = x;
        this.y = y;
        setBackground(true);
    }

    /**
     * Rebuilds the module buttons.
     *
     * <p>Separate from construction so a window survives a rebuild with its
     * position intact: the alternative is recreating windows and losing wherever
     * the user dragged them.
     */
    public void populate(List<Module> modules, SettingRendererRegistry renderers) {
        clearChildren();
        for (Module module : modules) {
            add(new ModuleButton(module, renderers));
        }
    }

    @Override
    protected boolean showsChildren() {
        return !collapsed;
    }

    /**
     * The title bar. Its measured height is what {@link dev.px.core.gui.Panel}
     * reserves above the module buttons, so the two can never disagree.
     */
    @Override
    protected void content(Content c) {
        float height = GuiStyle.titleHeight();
        c.height(height).padding(GuiStyle.padding(), 0f).align(Align.CENTER);
        // The title bar carries the accent so the window reads as belonging to the
        // theme without every row having to be tinted. A backdrop rather than a
        // part, so the name sits on it instead of under it.
        c.backdrop((x, y, w, h) -> Render.roundGradient(x, y, w, h,
                Math.min(GuiStyle.radius(), height / 2f),
                GuiStyle.accent(0f), GuiStyle.accent(1f),
                GuiStyle.accent(1f), GuiStyle.accent(0f)));
        c.text(category.getName(), GuiStyle.text());
    }

    @Override
    protected boolean onClick(float pointerX, float pointerY, MouseButton button) {
        // Only the title bar is grabbable. A press below it that got this far was
        // declined by whatever module button it landed on.
        if (pointerY > getBounds().getY() + GuiStyle.titleHeight()) {
            return false;
        }
        if (button == MouseButton.RIGHT) {
            collapsed = !collapsed;
            return true;
        }
        if (button != MouseButton.LEFT) {
            return false;
        }
        grabX = pointerX - x;
        grabY = pointerY - y;

        Screen screen = getScreen();
        if (screen != null) {
            // Last child is drawn last and hit first, so a grabbed window comes to
            // the front of the pile.
            if (getParent() != null) {
                getParent().bringToFront(this);
            }
            screen.beginDrag(this);
        }
        return true;
    }

    @Override
    protected void onDrag(float pointerX, float pointerY) {
        this.x = pointerX - grabX;
        this.y = pointerY - grabY;
    }

    // ----------------------------------------------------------- persistence

    public JsonObject save() {
        JsonObject json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("collapsed", collapsed);
        return json;
    }

    /** Tolerates missing keys: a config outlives the build that wrote it. */
    public void load(JsonObject json) {
        if (json.has("x")) {
            this.x = json.get("x").getAsFloat();
        }
        if (json.has("y")) {
            this.y = json.get("y").getAsFloat();
        }
        if (json.has("collapsed")) {
            this.collapsed = json.get("collapsed").getAsBoolean();
        }
    }
}
