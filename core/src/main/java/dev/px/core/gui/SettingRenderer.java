package dev.px.core.gui;

import dev.px.core.registry.Named;
import dev.px.core.setting.Setting;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Builds the component that edits one kind of {@link Setting}.
 *
 * <p>This is the extension point that keeps the GUI from having a switch over
 * setting types in it. Core ships one renderer per built-in type; a client that
 * invents a setting registers a renderer for it and its setting appears in the
 * GUI with no change to any file here.
 *
 * <pre>{@code
 * // A setting type of your own, and the row that edits it.
 * Core.gui().getRenderers().register(
 *         SettingRenderer.of(WaypointSetting.class, WaypointRow::new));
 * }</pre>
 *
 * <p>Renderers are looked up through an ordinary {@link dev.px.core.registry.Registry},
 * so duplicate registration is caught the same way it is for modules and
 * commands, and the registry is inspectable.
 *
 * @param <S> the setting type this renders
 */
public interface SettingRenderer<S extends Setting<?>> extends Named {

    /**
     * The setting class this renderer claims.
     *
     * <p>A {@code Class<?>} rather than a {@code Class<S>} because the built-in
     * generic settings have no class literal for their parameterised form:
     * {@code EnumSetting.class} is raw, and forcing every registration through a
     * cast to spell {@code Class<EnumSetting<?>>} would buy no safety. Matching
     * is by {@link Class#isInstance}, so the token only has to name the type.
     */
    Class<?> getSettingType();

    /**
     * Builds a component for one setting.
     *
     * @param setting the setting to edit
     * @param renderers the registry, for a renderer whose setting nests others.
     *                  Almost every renderer ignores it; a group does not
     * @return the component, which the GUI adds to the tree itself
     */
    Component create(S setting, SettingRendererRegistry renderers);

    /** Registry key. The setting type's name, which is unique by construction. */
    @Override
    default String getName() {
        return getSettingType().getSimpleName();
    }

    // ------------------------------------------------------------ factories

    /** The common case: a row that needs nothing but its setting. */
    static <S extends Setting<?>> SettingRenderer<S> of(Class<?> type, Function<S, Component> factory) {
        return of(type, (setting, renderers) -> factory.apply(setting));
    }

    /** For a renderer that builds rows for nested settings, such as a group. */
    static <S extends Setting<?>> SettingRenderer<S> of(Class<?> type,
                                                        BiFunction<S, SettingRendererRegistry, Component> factory) {
        return new SettingRenderer<S>() {

            @Override
            public Class<?> getSettingType() {
                return type;
            }

            @Override
            public Component create(S setting, SettingRendererRegistry renderers) {
                return factory.apply(setting, renderers);
            }
        };
    }
}
