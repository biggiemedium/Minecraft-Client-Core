package dev.px.core.hud;

import dev.px.core.setting.SettingHolder;
import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * Base for HUD elements that want their own settings.
 *
 * <p>Extending {@link SettingHolder} means settings register by being declared,
 * exactly as they do on a module, and {@link HudService} persists them alongside
 * the layout. An element with no options can implement {@link HudElement}
 * directly and skip this entirely.
 *
 * <pre>{@code
 * public final class ClockElement extends AbstractHudElement {
 *
 *     private final BooleanSetting seconds = bool("Show Seconds", true);
 *     private final ColorSetting colour    = color("Colour", Color.WHITE);
 *
 *     public ClockElement() {
 *         super("clock", "Clock", HudLayout.at(Anchor.TOP_RIGHT, -4f, 4f));
 *     }
 *
 *     @Override
 *     public Size getPreferredSize() {
 *         return Size.of(Render.textWidth(text()), Render.textHeight());
 *     }
 *
 *     @Override
 *     public void render(float x, float y, float w, float h) {
 *         Render.text(text(), x, y, colour.resolve());
 *     }
 * }
 * }</pre>
 */
@Getter
public abstract class AbstractHudElement extends SettingHolder implements HudElement {

    private final String id;
    private final String displayName;
    private final HudLayout defaultLayout;

    protected AbstractHudElement(String id, String displayName, HudLayout defaultLayout) {
        this.id = Validate.notBlank(id, "element id");
        this.displayName = displayName == null || displayName.isEmpty() ? id : displayName;
        this.defaultLayout = defaultLayout == null ? HudLayout.at(Anchor.TOP_LEFT, 4f, 4f) : defaultLayout;
    }

    protected AbstractHudElement(String id, String displayName) {
        this(id, displayName, null);
    }

    protected AbstractHudElement(String id) {
        this(id, id, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A copy, so an element that is reset cannot corrupt its own default.
     */
    @Override
    public HudLayout defaultLayout() {
        return defaultLayout.copy();
    }

    /**
     * @return whether the HUD editor is currently open.
     *
     * <p>Branch on this at the point the data comes from, so an element that shows
     * live values displays a stable sample while the user is positioning it:
     *
     * <pre>{@code
     * int fps = isEditing() ? 120 : stats.getFps();
     * }</pre>
     */
    protected final boolean isEditing() {
        return HudService.editingGlobally();
    }

    /**
     * The registry key is the id, and {@link SettingHolder} also needs a name.
     * Both resolve to the id so a saved config keys off something stable.
     */
    @Override
    public final String getName() {
        return id;
    }
}
