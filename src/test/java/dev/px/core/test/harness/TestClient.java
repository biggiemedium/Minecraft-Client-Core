package dev.px.core.test.harness;

import dev.px.core.Core;
import dev.px.core.hud.HudLayout;
import dev.px.core.render.Color;
import dev.px.core.render.theme.Theme;
import dev.px.core.test.example.ExampleBrokenElement;
import dev.px.core.test.example.ExampleCategories;
import dev.px.core.test.example.ExampleClock;
import dev.px.core.test.example.ExampleDial;
import dev.px.core.test.example.ExampleEchoCommand;
import dev.px.core.test.example.ExampleKillAura;
import dev.px.core.test.example.ExampleRadar;
import dev.px.core.test.example.ExampleSprint;
import dev.px.core.test.example.ExampleToggleCommand;
import dev.px.core.test.example.ExampleWatermark;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A booted client, with the example modules, commands and HUD elements
 * registered.
 *
 * <p>{@link #boot()} is also the canonical bootstrap example: this is exactly
 * what a version adapter does, minus the render backends. That it runs at all,
 * in a plain JVM with no game and no graphics, is the property the whole
 * architecture exists to provide.
 */
@Getter
public final class TestClient {

    private final Core core;
    private final FakePlatform platform;

    private final ExampleKillAura killAura;
    private final ExampleSprint sprint;
    private final ExampleToggleCommand toggleCommand;
    private final ExampleEchoCommand echoCommand;

    private final ExampleWatermark watermark;
    private final ExampleClock clock;
    private final ExampleDial dial;
    private final ExampleRadar radar;
    private final ExampleBrokenElement broken;

    private TestClient(Core core, FakePlatform platform) {
        this.core = core;
        this.platform = platform;
        this.killAura = core.getModuleRegistry().get(ExampleKillAura.class);
        this.sprint = core.getModuleRegistry().get(ExampleSprint.class);
        this.toggleCommand = core.getCommandRegistry().get(ExampleToggleCommand.class);
        this.echoCommand = core.getCommandRegistry().get(ExampleEchoCommand.class);
        this.watermark = (ExampleWatermark) core.getHudService().getElements().get("watermark");
        this.clock = (ExampleClock) core.getHudService().getElements().get("clock");
        this.dial = (ExampleDial) core.getHudService().getElements().get("dial");
        this.radar = (ExampleRadar) core.getHudService().getElements().get("radar");
        this.broken = (ExampleBrokenElement) core.getHudService().getElements().get("broken");
    }

    public static TestClient boot() throws IOException {
        File dataDirectory = Files.createTempDirectory("core-test").toFile();
        dataDirectory.deleteOnExit();

        FakePlatform platform = new FakePlatform(dataDirectory);
        Core core = Core.builder("TestClient", "1.0")
                .platform(platform)
                .build();

        // Registration happens between build() and start(): categories must exist
        // before modules resolve against them, themes before a saved selection is
        // looked up, and elements before the config is loaded over them.
        core.getCategories().registerAll(ExampleCategories.class);

        core.getThemeService().register(Theme.of("Froggy", Color.rgb(0xADF773), Color.rgb(0x80F393)));
        core.getThemeService().register(Theme.of("Sunset", Color.rgb(0xFD9115), Color.rgb(0xF56AE6)));

        core.getModuleRegistry().registerAll(new ExampleKillAura(), new ExampleSprint());
        core.getCommandRegistry().registerAll(new ExampleToggleCommand(), new ExampleEchoCommand());

        core.getHudService().registerAll(
                new ExampleWatermark(),
                new ExampleClock(),
                new ExampleDial(),
                new ExampleRadar(),
                new ExampleBrokenElement());

        // No Render2D or Render3D is installed anywhere in this file. Every draw
        // call the suites trigger is a no-op, which is the point.
        core.start();
        return new TestClient(core, platform);
    }

    /**
     * Returns the client to a known state between suites, so each one can be read
     * and debugged on its own rather than depending on what ran before it.
     */
    public void reset() {
        core.getHudService().closeEditor();
        core.getHudService().resetAll();

        // Module state too, or a suite inherits whatever the previous one toggled.
        core.getModuleRegistry().disableAll();
        core.getModuleRegistry().applyDefaults();

        platform.setScreen(854f, 480f);
        platform.setMouse(0f, 0f);
        platform.setInGame(true);
        platform.clearMessages();
        broken.setExplodeOnRender(false);
        broken.setExplodeOnMeasure(false);
        killAura.resetCounters();
        sprint.resetTicks();
    }

    /** Shorthand: the layout of a registered element, by id. */
    public HudLayout layout(String id) {
        return core.getHudService().layoutOf(id);
    }
}
