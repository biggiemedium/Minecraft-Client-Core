package dev.px.gui.test;

import dev.px.core.test.harness.Checks;

/**
 * Runs every GUI suite in a plain JVM: no Minecraft, no window, no render
 * backend.
 */
public final class GuiSmokeTest {

    private GuiSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("GUI test suite  (no Minecraft, no render backend)");

        GuiTestClient client = GuiTestClient.boot();
        GuiTests.run(client);
        WireframeTests.run(client);
        WidgetTests.run(client);
        ContainerTests.run(client);
        LayerTests.run(client);
        InputTests.run(client);

        client.getCore().stop();
        System.exit(Checks.summary());
    }
}
