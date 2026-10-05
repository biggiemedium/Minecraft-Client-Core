package dev.px.core.gui;

import dev.px.core.registry.Registry;
import dev.px.core.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * The registered {@link SettingRenderer}s, and the lookup that turns a setting
 * into a component.
 *
 * <p>A plain {@link Registry} keyed by name already gives duplicate detection,
 * ordering and iteration; the only thing it cannot do is find an entry by the
 * type it <em>handles</em> rather than the type it <em>is</em>. That is the one
 * method added here.
 */
public final class SettingRendererRegistry extends Registry<SettingRenderer<?>> {

    @Override
    protected String describe() {
        return "setting renderer";
    }

    /**
     * Replaces the renderer for a setting type, if one is registered.
     *
     * <p>Plain {@link #register} rejects a duplicate, which is right when two
     * clients both claim a type by accident and wrong when a client deliberately
     * wants its own slider instead of Core's. This is the deliberate version.
     *
     * @return the renderer, so it can be registered and kept in one expression
     */
    public <R extends SettingRenderer<?>> R replace(R renderer) {
        SettingRenderer<?> existing = get(renderer.getName());
        if (existing != null) {
            unregister(existing);
        }
        return register(renderer);
    }

    /**
     * @return the renderer that claims this setting, or null
     *
     * <p>The most specific match wins, so registering a renderer for a subclass
     * of a built-in setting overrides the built-in for that subclass alone and
     * leaves the base type alone.
     */
    public SettingRenderer<?> rendererFor(Setting<?> setting) {
        if (setting == null) {
            return null;
        }
        SettingRenderer<?> best = null;
        for (SettingRenderer<?> candidate : all()) {
            if (!candidate.getSettingType().isInstance(setting)) {
                continue;
            }
            if (best == null || best.getSettingType().isAssignableFrom(candidate.getSettingType())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * @return a component for this setting, or null if nothing claims its type
     *
     * <p>Returning null rather than throwing is deliberate: a client that copies
     * Core and adds a setting type before writing its renderer should see that
     * one row is missing, not lose the whole GUI.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Component create(Setting<?> setting) {
        SettingRenderer renderer = rendererFor(setting);
        return renderer == null ? null : renderer.create(setting, this);
    }

    /**
     * Builds rows for a list of settings, skipping any type nothing claims.
     *
     * <p>Every place that shows settings &mdash; a module's panel, a group's
     * children &mdash; wants exactly this, so it lives here rather than being
     * written out twice.
     */
    public List<Component> createAll(List<Setting<?>> settings) {
        List<Component> rows = new ArrayList<>(settings.size());
        for (Setting<?> setting : settings) {
            Component row = create(setting);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }
}
