package dev.px.core.gui;

import com.google.gson.JsonObject;
import dev.px.core.config.ConfigSection;
import dev.px.core.config.Json;
import dev.px.core.gui.click.ClickGuiScreen;
import dev.px.core.gui.setting.DefaultRenderers;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.module.CategoryRegistry;
import dev.px.core.module.ModuleRegistry;
import dev.px.core.platform.Platform;
import dev.px.core.render.Render;
import dev.px.core.render.theme.ThemeService;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import lombok.Getter;

import java.util.Set;

/**
 * Owns the open screen, the input gate that sits in front of it, and the
 * renderers a screen builds its rows from.
 *
 * <p><b>One screen at a time, and no stack.</b> A stack is what turns a GUI into
 * a navigation problem: every screen has to know what it was opened from, and
 * closing one has to guess whether to go back or to leave. {@link #open} replaces
 * whatever was showing, {@link #close} leaves. A client that wants a sub-screen
 * opens it and reopens the first one.
 *
 * <p><b>Core subscribes to nothing and draws nothing on its own.</b> The GUI is
 * hosted inside the game's own screen &mdash; {@code Screen} on modern versions,
 * {@code GuiScreen} on older ones &mdash; and that screen already owns the mouse
 * and keyboard while it is showing. So there is no event to intercept and
 * nothing to cancel: the host calls in.
 *
 * <pre>{@code
 * public final class CoreGuiScreen extends net.minecraft.client.gui.screen.Screen {
 *
 *     @Override public void render(MatrixStack stack, int mx, int my, float delta) {
 *         Core.gui().renderFrame();
 *     }
 *
 *     @Override public boolean mouseClicked(double x, double y, int button) {
 *         return Core.gui().mousePressed((float) x, (float) y, MouseButton.byIndex(button));
 *     }
 *
 *     @Override public boolean mouseReleased(double x, double y, int button) {
 *         Core.gui().mouseReleased((float) x, (float) y);
 *         return true;
 *     }
 *
 *     @Override public boolean keyPressed(int key, int scancode, int mods) {
 *         Key resolved = Keys.fromGlfw(key);
 *         if (!Core.gui().keyPressed(resolved, Keys.modifiers(mods)) && resolved == Key.ESCAPE) {
 *             onClose();
 *         }
 *         return true;
 *     }
 *
 *     @Override public boolean charTyped(char typed, int mods) {
 *         return Core.gui().charTyped(typed);
 *     }
 *
 *     @Override public void removed() { Core.gui().close(); }
 * }
 * }</pre>
 *
 * <p>Drags are driven from the frame loop rather than from a move event, which
 * is why the list above has no mouse-move in it.
 */
@Getter
public final class GuiService implements Service, ConfigSection {

    private final CoreLogger logger;
    private final Platform platform;
    private final ModuleRegistry modules;
    private final CategoryRegistry categories;
    private final ThemeService themes;

    private final SettingRendererRegistry renderers = new SettingRendererRegistry();

    /** The click GUI, built at startup. Core's one concrete screen. */
    private ClickGuiScreen clickGui;

    private Screen current;

    /** Whether a screen has already been reported as broken, so it logs once. */
    private boolean warned;

    public GuiService(CoreLogger logger, Platform platform,
                      ModuleRegistry modules, CategoryRegistry categories, ThemeService themes) {
        this.logger = logger;
        this.platform = platform;
        this.modules = modules;
        this.categories = categories;
        this.themes = themes;
    }

    @Override
    public String getName() {
        return "GUI";
    }

    @Override
    public String getId() {
        return "gui";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Service>[] dependsOn() {
        // Colours are read from the theme the moment a component draws, and the
        // click GUI is built here, so the theme must already be resolvable.
        return new Class[] { ThemeService.class };
    }

    @Override
    public void start() {
        DefaultRenderers.installInto(renderers);
        GuiStyle.bind(themes);

        // Built at startup rather than at construction: categories and modules are
        // registered between build() and start(), so this is the first moment the
        // registries hold what the windows are made of.
        clickGui = new ClickGuiScreen(categories, modules, renderers);
        clickGui.rebuild();
    }

    @Override
    public void stop() {
        close();
    }

    // ------------------------------------------------------------- lifecycle

    public boolean isOpen() {
        return current != null;
    }

    /** Replaces whatever is showing. Passing null closes. */
    public void open(Screen screen) {
        if (current == screen) {
            return;
        }
        close();
        current = screen;
        if (screen != null) {
            screen.onOpen();
        }
    }

    public void openClickGui() {
        open(clickGui);
    }

    /**
     * Rebuilds the click GUI's windows and rows.
     *
     * <p>Needed only for a change made <em>after</em> startup: a module
     * registered late, or a {@link SettingRenderer} swapped while the client is
     * running. Anything registered between {@code Core.builder(...).build()} and
     * {@code Core.start()} is already accounted for, because the click GUI is
     * built at the end of that window.
     *
     * <p>Windows keep their positions across a rebuild, so this costs the user
     * nothing.
     */
    public void rebuild() {
        if (clickGui != null) {
            clickGui.rebuild();
        }
    }

    public void toggleClickGui() {
        if (current == clickGui) {
            close();
        } else {
            openClickGui();
        }
    }

    /**
     * Dismisses the open screen.
     *
     * <p>{@link Screen#onClose()} drops focus and any in-flight drag, so a
     * keybind button left waiting for a key is not still waiting the next time
     * the screen opens.
     */
    public void close() {
        if (current == null) {
            return;
        }
        Screen closing = current;
        current = null;
        closing.onClose();
    }

    // ----------------------------------------------------------------- input

    /**
     * A press.
     *
     * <p>Called by the game screen this GUI is hosted in. Core subscribes to
     * nothing: the screen the client opens already owns the mouse and keyboard
     * while it is showing, so there is no event to intercept and nothing to
     * cancel. The HUD editor works the same way.
     *
     * @return whether a component consumed it, so the host can decide what to do
     *         with a press that landed on nothing
     */
    public boolean mousePressed(float x, float y, MouseButton button) {
        Screen screen = current;
        return screen != null && screen.mousePressed(x, y, button);
    }

    public void mouseReleased(float x, float y) {
        Screen screen = current;
        if (screen != null) {
            screen.mouseReleased(x, y);
        }
    }

    public boolean scrolled(float amount, float x, float y) {
        Screen screen = current;
        return screen != null && screen.scrolled(amount, x, y);
    }

    /**
     * A key press, routed to whatever holds focus.
     *
     * <p>Escape is not handled here. A text field being edited consumes it for
     * itself, and what an otherwise idle screen does with it &mdash; close, or
     * something else &mdash; is the host's decision, not Core's. Check the return
     * value and call {@link #close()} if you want the usual behaviour:
     *
     * <pre>{@code
     * if (!Core.gui().keyPressed(key, mods) && key == Key.ESCAPE) {
     *     Core.gui().close();
     * }
     * }</pre>
     *
     * @return whether the focused component consumed it
     */
    public boolean keyPressed(Key key, Set<Modifier> modifiers) {
        Screen screen = current;
        return screen != null && screen.keyPressed(key, modifiers);
    }

    public boolean charTyped(char character) {
        Screen screen = current;
        return screen != null && screen.charTyped(character);
    }

    /**
     * Advances an in-progress drag from the current cursor position.
     *
     * <p>Called once a frame before {@link #renderFrame()}, which does it itself,
     * so a host driving the ordinary way never calls this.
     */
    public void updateDrag(float mouseX, float mouseY) {
        Screen screen = current;
        if (screen != null) {
            screen.updateDrag(mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------- rendering

    /**
     * Measures, places and draws the open screen.
     *
     * <p>Laid out every frame rather than on change, for the same reason a HUD
     * element's size is asked for every frame: a dropdown opens, a group
     * collapses and a row hides itself as another setting changes, and none of
     * those go through a place that could invalidate a cache.
     *
     * <p>A screen that throws loses the rest of its frame rather than taking the
     * client's frame down with it, and is reported once: a GUI that fails every
     * frame would otherwise fill the log faster than anyone could read it.
     */
    public void renderFrame() {
        Screen screen = current;
        if (screen == null) {
            return;
        }
        float width = platform.getScreenWidth();
        float height = platform.getScreenHeight();

        screen.resize(width, height);
        // Before layout, so a dragged window is drawn where the cursor is this
        // frame rather than one frame behind it.
        screen.updateDrag(platform.getMouseX(), platform.getMouseY());
        screen.layout(0f, 0f, width);

        try {
            screen.renderTree();
            renderTooltip(screen);
        } catch (RuntimeException failure) {
            if (!warned) {
                warned = true;
                logger.error("GUI screen " + screen.getTitle()
                        + " threw while drawing; the rest of that frame was skipped,"
                        + " and further failures will not be logged", failure);
            }
        }
    }

    /** Draws the tooltip of whatever is under the cursor, if it has one. */
    private void renderTooltip(Screen screen) {
        float mouseX = platform.getMouseX();
        float mouseY = platform.getMouseY();

        Component hovered = screen.componentAt(mouseX, mouseY);
        if (hovered == null) {
            return;
        }
        String text = hovered.getTooltip();
        if (text == null || text.isEmpty()) {
            return;
        }

        float padding = GuiStyle.padding();
        float width = Render.textWidth(text) + padding * 2f;
        float height = Render.textHeight() + padding * 2f;

        // Flip to the left of the cursor rather than running off the screen edge.
        float x = mouseX + 8f;
        if (x + width > platform.getScreenWidth()) {
            x = mouseX - 8f - width;
        }
        float y = mouseY + 8f;

        Render.roundRect(x, y, width, height, GuiStyle.radius(width, height), GuiStyle.surface());
        Render.roundRectOutline(x, y, width, height, GuiStyle.radius(width, height), 1f, GuiStyle.outline());
        Render.text(text, x + padding, y + padding, GuiStyle.text());
    }

    // ----------------------------------------------------------- persistence

    @Override
    public JsonObject save() {
        JsonObject json = new JsonObject();
        if (clickGui != null) {
            json.add("clickgui", clickGui.save());
        }
        return json;
    }

    @Override
    public void load(JsonObject json) {
        if (clickGui != null) {
            clickGui.load(Json.child(json, "clickgui"));
        }
    }
}
