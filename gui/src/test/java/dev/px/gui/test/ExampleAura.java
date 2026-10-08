package dev.px.gui.test;

import dev.px.core.module.Module;
import dev.px.core.module.ModuleInfo;
import dev.px.core.render.Color;
import dev.px.core.setting.impl.BindSetting;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.setting.impl.EnumSetting;
import dev.px.core.setting.impl.GroupSetting;
import dev.px.core.setting.impl.MultiEnumSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.setting.impl.RangeSetting;
import dev.px.core.setting.impl.StringSetting;
import lombok.Getter;

/**
 * A module with every setting type and nothing else, so the GUI has one of each
 * row to build, draw and edit. Its behaviour is irrelevant here; Core's own
 * {@code ExampleKillAura} is the one that does something.
 */
@Getter
@ModuleInfo(
        name = "Kill Aura",
        description = "Attacks nearby targets",
        category = "Combat")
public final class ExampleAura extends Module {

    private final NumberSetting<Float> reach = number("Reach", 4f, 3f, 6f)
            .step(0.1f)
            .describe("Maximum attack distance");

    private final RangeSetting cps = range("CPS", 8, 14, 1, 20)
            .step(1d)
            .describe("Clicks per second, randomised between the two bounds");

    private final BooleanSetting rotations = bool("Rotations", true);

    private final EnumSetting<RotationMode> rotationMode = enumOf("Rotation Mode", RotationMode.SMOOTH)
            .visibleWhen(rotations)
            .describe("How the aim is moved onto the target");

    private final MultiEnumSetting<Target> targets =
            multi("Targets", Target.class, Target.PLAYERS, Target.MOBS);

    private final ColorSetting hitboxColour = color("Hitbox", Color.RED);

    private final StringSetting label = text("Label", "aura").maxLength(16);

    private final BooleanSetting autoBlock = bool("Auto Block", true);
    private final NumberSetting<Integer> blockDelay = integer("Block Delay", 2, 0, 10)
            .visibleWhen(autoBlock);
    private final GroupSetting blocking = group("Blocking", false)
            .containing(autoBlock, blockDelay)
            .describe("Shield and sword blocking behaviour");

    private final BindSetting keybind = toggledBy(bind("Keybind"));

    public enum RotationMode { INSTANT, SMOOTH, LOCKED }

    public enum Target { PLAYERS, MOBS, ANIMALS, INVISIBLES }
}
