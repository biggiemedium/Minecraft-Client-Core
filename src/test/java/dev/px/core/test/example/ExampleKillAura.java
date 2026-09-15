package dev.px.core.test.example;

import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.math.Stopwatch;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleInfo;
import dev.px.core.render.Color;
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
 * A realistic module, exercising every setting type and every handler feature.
 *
 * <p>Written as a reference: this is what a real combat module looks like once
 * the boilerplate is gone. Everything below the annotation is either a setting
 * declaration or actual behaviour.
 *
 * <p>The counters exist only so the test suite can assert dispatch order and the
 * listening gate; a real module would not have them.
 */
@Getter
@ModuleInfo(
        name = "Kill Aura",
        description = "Attacks nearby targets",
        category = "Combat")
public final class ExampleKillAura extends Module {

    // ---- simple values ---------------------------------------------------
    private final NumberSetting<Float> reach = number("Reach", 4f, 3f, 6f)
            .step(0.1f)
            .describe("Maximum attack distance");

    private final RangeSetting cps = range("CPS", 8, 14, 1, 20)
            .step(1d)
            .describe("Clicks per second, randomised between the two bounds");

    private final BooleanSetting rotations = bool("Rotations", true);

    // ---- a setting that only appears when another is on -------------------
    private final EnumSetting<RotationMode> rotationMode = enumOf("Rotation Mode", RotationMode.SMOOTH)
            .visibleWhen(rotations)
            .describe("How the aim is moved onto the target");

    // ---- one setting instead of four adjacent booleans --------------------
    private final MultiEnumSetting<Target> targets =
            multi("Targets", Target.class, Target.PLAYERS, Target.MOBS);

    private final ColorSetting hitboxColour = color("Hitbox", Color.RED);

    private final StringSetting label = text("Label", "aura").maxLength(16);

    // ---- related options folded into a collapsible group ------------------
    private final BooleanSetting autoBlock = bool("Auto Block", true);
    private final NumberSetting<Integer> blockDelay = integer("Block Delay", 2, 0, 10)
            .visibleWhen(autoBlock);
    private final GroupSetting blocking = group("Blocking", false)
            .containing(autoBlock, blockDelay)
            .describe("Shield and sword blocking behaviour");

    private final Stopwatch attackTimer = Stopwatch.expired();

    // ---- observable state, for the suite ----------------------------------
    private int preTicks;
    private int postTicks;
    private int everyTick;
    private int enableCount;
    private int disableCount;
    private boolean highPriorityRanFirst;

    private boolean highPriorityRan;

    @Override
    protected void onEnable() {
        enableCount++;
        attackTimer.reset();
    }

    @Override
    protected void onDisable() {
        disableCount++;
    }

    /**
     * Runs before the game processes the tick, at raised priority so it can
     * settle rotations before anything reading them.
     */
    @Subscribe(stage = Stage.PRE, priority = Priority.HIGH)
    private void onPreTick(TickEvent event) {
        highPriorityRan = true;
        preTicks++;
        if (attackTimer.tryConsume(1000L / Math.max(1, cps.randomInt()))) {
            // attack(target) in a real client
            attackTimer.reset();
        }
    }

    @Subscribe(stage = Stage.POST)
    private void onPostTick(TickEvent event) {
        postTicks++;
    }

    /**
     * Runs regardless of whether the module is enabled, and last.
     *
     * <p>{@code ignoreListening} is for handlers that must observe the game even
     * while the module is off &mdash; a statistic counter, a bind watcher.
     */
    @Subscribe(priority = Priority.LOW, ignoreListening = true)
    private void onAnyTick(TickEvent event) {
        if (highPriorityRan) {
            highPriorityRanFirst = true;
        }
        everyTick++;
    }

    /** Shown after the module name in the ArrayList. */
    @Override
    public String getDisplayInfo() {
        return rotations.isOn() ? rotationMode.displayValue() : "";
    }

    public void resetCounters() {
        preTicks = 0;
        postTicks = 0;
        everyTick = 0;
        highPriorityRan = false;
        highPriorityRanFirst = false;
    }

    public enum RotationMode { INSTANT, SMOOTH, LOCKED }

    public enum Target { PLAYERS, MOBS, ANIMALS, INVISIBLES }
}
