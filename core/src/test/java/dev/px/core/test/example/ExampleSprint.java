package dev.px.core.test.example;

import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleInfo;
import dev.px.core.setting.impl.BooleanSetting;
import lombok.Getter;

/**
 * The smallest useful module: an annotation, one setting, one handler.
 *
 * <p>Note what is absent. No settings list, no registration call, no
 * subscribe/unsubscribe in the lifecycle hooks, no constructor. A module that
 * needs none of those writes none of them.
 */
@Getter
@ModuleInfo(
        name = "Sprint",
        description = "Sprints without holding the key",
        category = "Movement",
        enabled = true)
public final class ExampleSprint extends Module {

    private final BooleanSetting omnidirectional = bool("Omnidirectional", false)
            .describe("Sprint sideways and backwards too");

    /** Counts ticks seen, so a test can prove the listening gate works. */
    private int ticks;

    @Subscribe
    private void onTick(TickEvent event) {
        ticks++;
    }

    public void resetTicks() {
        ticks = 0;
    }
}
