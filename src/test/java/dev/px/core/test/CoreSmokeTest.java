package dev.px.core.test;

import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;
import dev.px.core.test.suite.CommandTests;
import dev.px.core.test.suite.ConfigTests;
import dev.px.core.test.suite.EventTests;
import dev.px.core.test.suite.HudEditorTests;
import dev.px.core.test.suite.HudLayoutTests;
import dev.px.core.test.suite.MathTests;
import dev.px.core.test.suite.ModuleTests;
import dev.px.core.test.suite.RegistryTests;
import dev.px.core.test.suite.ServiceTests;
import dev.px.core.test.suite.SettingTests;
import dev.px.core.test.suite.ShapeTests;
import dev.px.core.test.suite.SpatialTests;
import dev.px.core.test.suite.UtilTests;

/**
 * Runs every suite against one booted client.
 *
 * <p>The point of this file is what is missing from it. There is no Minecraft on
 * the classpath, no window, no OpenGL context, no render backend and no font.
 * Core boots, registers modules and HUD elements, dispatches events and
 * commands, resolves layouts, drags elements around and persists all of it,
 * in a plain JVM.
 *
 * <p>If a check here ever needs a game to pass, the abstraction has leaked.
 *
 * <pre>
 * javac -cp lombok.jar;gson.jar -d build/classes @sources.txt
 * java  -cp gson.jar;build/classes;build/test-classes dev.px.core.test.CoreSmokeTest
 * </pre>
 */
public final class CoreSmokeTest {

    private CoreSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Core test suite  (no Minecraft, no render backend)");

        // Standalone suites: pure logic, no client needed.
        RegistryTests.run();
        ShapeTests.run();
        MathTests.run();
        UtilTests.run();
        SpatialTests.run();

        TestClient client = TestClient.boot();

        ServiceTests.run(client);
        EventTests.run(client);
        SettingTests.run(client);
        ModuleTests.run(client);
        CommandTests.run(client);
        HudLayoutTests.run(client);
        HudEditorTests.run(client);

        // Config runs last: it mutates state across every other section, so
        // running it earlier would leave the others reading a loaded profile.
        ConfigTests.run(client);

        client.getCore().stop();
        Checks.section("Shutdown");
        Checks.check("services stopped in reverse order",
                !client.getCore().getServices().isRunning());

        System.exit(Checks.summary());
    }
}
