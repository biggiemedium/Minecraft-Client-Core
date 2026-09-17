package dev.px.core.gui.click;

import dev.px.core.layout.Content;

import com.google.gson.JsonObject;
import dev.px.core.config.Json;
import dev.px.core.gui.Component;
import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingRendererRegistry;
import dev.px.core.math.MathUtil;
import dev.px.core.module.Category;
import dev.px.core.module.CategoryRegistry;
import dev.px.core.module.ModuleRegistry;
import dev.px.core.render.Render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The click GUI: a window per category, a button per module, a row per setting.
 *
 * <p>This screen is what the rest of the package was built for, and nothing in
 * {@link Component}, {@link dev.px.core.gui.Panel} or
 * {@link SettingRendererRegistry} exists that is not needed to draw it. A widget
 * kit written the other way round accumulates components nothing uses.
 *
 * <p>Windows are keyed by category name and reused across a {@link #rebuild()},
 * so registering a module later does not throw away the positions a user chose.
 */
public final class ClickGuiScreen extends Screen {

    /** How much of a window must stay on screen, matching the HUD's own rule. */
    private static final float MIN_VISIBLE = 16f;

    private static final float MARGIN = 4f;

    private final CategoryRegistry categories;
    private final ModuleRegistry modules;
    private final SettingRendererRegistry renderers;

    private final Map<String, CategoryWindow> windows = new LinkedHashMap<>();

    public ClickGuiScreen(CategoryRegistry categories, ModuleRegistry modules,
                          SettingRendererRegistry renderers) {
        super("Click GUI");
        this.categories = categories;
        this.modules = modules;
        this.renderers = renderers;
    }

    /**
     * Builds a window per registered category and fills it with that category's
     * modules.
     *
     * <p>Called once at startup and again whenever the module or category
     * registries change. Existing windows are kept, so a rebuild costs the user
     * nothing.
     */
    public void rebuild() {
        List<Category> ordered = new ArrayList<>(categories.all());
        // Stable, so equal order falls back to the order categories were registered.
        ordered.sort(Comparator.comparingInt(Category::getOrder));

        clearChildren();
        int index = 0;
        for (Category category : ordered) {
            CategoryWindow window = windows.get(category.getName());
            if (window == null) {
                window = new CategoryWindow(category, defaultX(index), MARGIN);
                windows.put(category.getName(), window);
            }
            window.populate(modules.inCategory(category), renderers);
            add(window);
            index++;
        }
        // A category that was unregistered should not keep a saved position alive.
        windows.keySet().removeIf(name -> !categories.contains(name));
    }

    public Optional<CategoryWindow> windowFor(String categoryName) {
        return Optional.ofNullable(windows.get(categoryName));
    }

    public List<CategoryWindow> getWindows() {
        return new ArrayList<>(windows.values());
    }

    private static float defaultX(int index) {
        return MARGIN + index * (GuiStyle.windowWidth() + MARGIN);
    }

    // ---------------------------------------------------------------- layout

    /**
     * Places each window at its own position, clamped onto the screen.
     *
     * <p>The clamp is applied to the resolved position and never written back, so
     * opening the GUI at a small window size does not permanently shove someone's
     * layout into the corner &mdash; the same rule the HUD follows.
     */
    @Override
    protected void layoutChildren() {
        for (Component child : getChildren()) {
            if (!(child instanceof CategoryWindow)) {
                continue;
            }
            CategoryWindow window = (CategoryWindow) child;
            window.layout(clamp(window.getX(), getScreenWidth()),
                    clamp(window.getY(), getScreenHeight()),
                    GuiStyle.windowWidth());
        }
    }

    private static float clamp(float position, float screenSize) {
        return screenSize <= 0f ? position : MathUtil.clamp(position, 0f, Math.max(0f, screenSize - MIN_VISIBLE));
    }

    /** The scrim over the game. Everything else on this screen is a window. */
    @Override
    protected void content(Content c) {
        c.custom("scrim", getScreenWidth(), getScreenHeight(),
                (x, y, w, h) -> Render.rect(x, y, w, h, GuiStyle.backdrop()));
    }

    // ----------------------------------------------------------- persistence

    @Override
    public JsonObject save() {
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, CategoryWindow> entry : windows.entrySet()) {
            entries.add(entry.getKey(), entry.getValue().save());
        }
        JsonObject json = new JsonObject();
        json.add("windows", entries);
        return json;
    }

    @Override
    public void load(JsonObject json) {
        JsonObject entries = Json.child(json, "windows");
        for (Map.Entry<String, CategoryWindow> entry : windows.entrySet()) {
            if (entries.has(entry.getKey()) && entries.get(entry.getKey()).isJsonObject()) {
                entry.getValue().load(entries.getAsJsonObject(entry.getKey()));
            }
        }
    }
}
