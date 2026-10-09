package dev.px.navigation.test;

import dev.px.core.test.harness.Checks;

/**
 * Runs every navigation suite in a plain JVM: no Minecraft, no window. The
 * "game" is a block grid moved through by Core's own movement rules, which is
 * the proof that navigation, like Core, needs no game to work.
 */
public final class NavigationSmokeTest {

    private NavigationSmokeTest() {
    }

    public static void main(String[] args) {
        PlannerTests.run();
        NavigatorTests.run();
        DangerTests.run();
        TravelTests.run();
        System.exit(Checks.summary());
    }
}
