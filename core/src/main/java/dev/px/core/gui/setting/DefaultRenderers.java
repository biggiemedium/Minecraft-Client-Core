package dev.px.core.gui.setting;

import dev.px.core.gui.SettingRenderer;
import dev.px.core.gui.SettingRendererRegistry;
import dev.px.core.setting.impl.BindSetting;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.setting.impl.EnumSetting;
import dev.px.core.setting.impl.GroupSetting;
import dev.px.core.setting.impl.MultiEnumSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.setting.impl.RangeSetting;
import dev.px.core.setting.impl.StringSetting;

/**
 * Registers a renderer for each setting type Core ships.
 *
 * <p>One place, nine lines, and no switch anywhere else in the GUI. A client
 * adds a tenth by registering its own renderer against the same registry.
 *
 * <p><b>A type a client has already claimed is left alone.</b> These are
 * installed during {@code GuiService.start()}, which is after the client has had
 * its chance to register between {@code build()} and {@code start()}. Registering
 * over the top would throw on the duplicate name and take startup with it, and
 * skipping the client's instead would silently ignore what they asked for. So a
 * default fills a gap and never wins an argument, and replacing Core's slider is
 * one ordinary {@code register} call at the same point in the bootstrap as
 * everything else.
 *
 * <p>Every registration spells out its lambda parameter type rather than using a
 * constructor reference. The generic settings have only raw class literals
 * &mdash; {@code EnumSetting.class}, not {@code EnumSetting<?>.class} &mdash; so
 * the parameter type is what tells the compiler which setting the renderer is
 * for, and writing them all the same way keeps the list readable.
 */
public final class DefaultRenderers {

    private DefaultRenderers() {
    }

    /**
     * Installs the built-in renderers for every setting type nothing has claimed.
     *
     * <p>Called once, by {@code GuiService.start()}.
     */
    public static void installInto(SettingRendererRegistry registry) {
        install(registry, SettingRenderer.of(BooleanSetting.class,
                (BooleanSetting setting) -> new BooleanRow(setting)));

        install(registry, SettingRenderer.of(NumberSetting.class,
                (NumberSetting<?> setting) -> new NumberRow(setting)));

        install(registry, SettingRenderer.of(RangeSetting.class,
                (RangeSetting setting) -> new RangeRow(setting)));

        install(registry, SettingRenderer.of(EnumSetting.class,
                (EnumSetting<?> setting) -> new EnumRow(setting)));

        install(registry, SettingRenderer.of(MultiEnumSetting.class,
                (MultiEnumSetting<?> setting) -> new MultiEnumRow(setting)));

        install(registry, SettingRenderer.of(StringSetting.class,
                (StringSetting setting) -> new StringRow(setting)));

        install(registry, SettingRenderer.of(ColorSetting.class,
                (ColorSetting setting) -> new ColorRow(setting)));

        install(registry, SettingRenderer.of(BindSetting.class,
                (BindSetting setting) -> new BindRow(setting)));

        // The only one that needs the registry: its children are settings too.
        install(registry, SettingRenderer.of(GroupSetting.class,
                (GroupSetting setting, SettingRendererRegistry renderers) -> new GroupRow(setting, renderers)));
    }

    /** Registers a default only if the setting type is still unclaimed. */
    private static void install(SettingRendererRegistry registry, SettingRenderer<?> renderer) {
        if (!registry.contains(renderer.getName())) {
            registry.register(renderer);
        }
    }
}
