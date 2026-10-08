package dev.px.gui.test;

import dev.px.core.Core;
import dev.px.core.render.Render;
import dev.px.core.test.example.ExampleCategories;
import dev.px.core.test.harness.FakePlatform;
import dev.px.core.test.harness.FixedFont;
import dev.px.gui.legacy.GuiService;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A real Core with the GUI installed, a fake platform and one module carrying
 * every setting type. No render backend: draws are no-ops and fonts measure with
 * fixed widths, so the suites test what the tree is and what a click does.
 */
@Getter
public final class GuiTestClient {

    private final Core core;
    private final FakePlatform platform;
    private final GuiService gui;
    private final ExampleAura killAura;

    private GuiTestClient(Core core, FakePlatform platform, GuiService gui) {
        this.core = core;
        this.platform = platform;
        this.gui = gui;
        this.killAura = core.getModuleRegistry().get(ExampleAura.class);
    }

    public static GuiTestClient boot() throws IOException {
        File dataDirectory = Files.createTempDirectory("gui-test").toFile();
        dataDirectory.deleteOnExit();

        FakePlatform platform = new FakePlatform(dataDirectory);
        Core core = Core.builder("GuiTestClient", "1.0")
                .platform(platform)
                .build();
        core.getCategories().registerAll(ExampleCategories.class);
        core.getModuleRegistry().registerAll(new ExampleAura());
        GuiService gui = GuiService.install(core);
        core.start();

        Render.setDefaultFont(new FixedFont());
        return new GuiTestClient(core, platform, gui);
    }

    /** Back to a known state between suites. */
    public void reset() {
        gui.close();
        core.getModuleRegistry().disableAll();
        core.getModuleRegistry().applyDefaults();
        platform.setScreen(854f, 480f);
        platform.setMouse(0f, 0f);
        platform.setInGame(true);
        platform.clearMessages();
    }
}
