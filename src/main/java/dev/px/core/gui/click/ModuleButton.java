package dev.px.core.gui.click;

import dev.px.core.gui.Component;
import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.Panel;
import dev.px.core.gui.SettingRendererRegistry;
import dev.px.core.input.MouseButton;
import dev.px.core.module.Module;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.animation.Animation;
import dev.px.core.render.animation.Easing;
import dev.px.core.setting.Setting;
import lombok.Getter;

/**
 * One module in a category window: left-click toggles it, right-click opens its
 * settings.
 *
 * <p>Setting rows are built once, from the module's top-level settings. Group
 * children are not walked here &mdash; a {@code GroupSetting} renders its own
 * children, which is why {@link dev.px.core.setting.SettingHolder#getSettings()}
 * rather than {@code getAllSettings()} is the right list: the latter would
 * render every nested setting twice, once inside its group and once beside it.
 */
@Getter
public final class ModuleButton extends Panel {

    private final Module module;

    private final Animation enabled = Animation.fade(140, Easing.QUAD_OUT);

    private boolean expanded;

    public ModuleButton(Module module, SettingRendererRegistry renderers) {
        this.module = module;
        setPadding(GuiStyle.SPACING);
        this.enabled.snapTo(module.isEnabled() ? 1f : 0f);

        for (Setting<?> setting : module.getSettings()) {
            Component row = renderers.create(setting);
            if (row != null) {
                add(row);
            }
        }
    }

    /** @return whether this module has any setting the GUI can show. */
    public boolean hasSettings() {
        return !getChildren().isEmpty();
    }

    /**
     * Opens or closes the settings without a click.
     *
     * <p>For a client that wants a module's options showing the moment its
     * window is opened, and for anything driving the GUI from outside the mouse.
     */
    public void setExpanded(boolean shouldExpand) {
        this.expanded = shouldExpand && hasSettings();
    }

    @Override
    protected float headerHeight() {
        return GuiStyle.ROW_HEIGHT;
    }

    @Override
    protected boolean showsChildren() {
        return expanded;
    }

    @Override
    public String getTooltip() {
        return module.getDescription();
    }

    @Override
    public void render(float x, float y, float w, float h) {
        enabled.target(module.isEnabled());
        float progress = enabled.get();

        float rowHeight = GuiStyle.ROW_HEIGHT;
        float radius = GuiStyle.radius(w, rowHeight);

        Render.roundRect(x, y, w, rowHeight, radius, GuiStyle.surface());
        if (progress > 0f) {
            // The accent gradient spans the row, so a column of enabled modules
            // reads as one ramp rather than a stack of identical bars.
            Render.roundGradient(x, y, w, rowHeight, radius,
                    GuiStyle.accent(0f).withAlpha((int) (progress * 255f)),
                    GuiStyle.accent(1f).withAlpha((int) (progress * 255f)),
                    GuiStyle.accent(1f).withAlpha((int) (progress * 255f)),
                    GuiStyle.accent(0f).withAlpha((int) (progress * 255f)));
        }

        Color label = GuiStyle.textMuted().lerp(GuiStyle.text(), progress);
        Render.text(module.getDisplayName(), x + GuiStyle.PADDING,
                y + (rowHeight - Render.textHeight()) / 2f, label);

        if (hasSettings()) {
            float dot = 2f;
            Render.circle(x + w - GuiStyle.PADDING - dot, y + rowHeight / 2f, dot,
                    expanded ? GuiStyle.accent() : GuiStyle.outline());
        }
    }

    @Override
    protected boolean onClick(float x, float y, MouseButton button) {
        // Only the header acts. A press that landed on a setting row was already
        // offered to that row; it reaches here only if the row declined it, and
        // toggling the module because a slider ignored a right-click would be
        // the wrong answer.
        if (y > getBounds().getY() + GuiStyle.ROW_HEIGHT) {
            return false;
        }
        if (button == MouseButton.LEFT) {
            module.toggle();
            return true;
        }
        if (button == MouseButton.RIGHT && hasSettings()) {
            expanded = !expanded;
            return true;
        }
        return false;
    }
}
