package dev.px.testkit.test;

import dev.px.core.test.harness.Checks;

/**
 * Runs the test kit's own suite: that the sandbox behaves as the game would
 * show itself to Core. The flow engine's suite, in Core, runs in the sandbox as
 * well.
 */
public final class TestkitSmokeTest {

    private TestkitSmokeTest() {
    }

    public static void main(String[] args) {
        SandboxTests.run();
        System.exit(Checks.summary());
    }
}
