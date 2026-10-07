package dev.px.projectile.test;

import dev.px.core.test.harness.Checks;

/**
 * Runs every projectile suite in a plain JVM: no Minecraft, no window. The
 * "game" is a block grid and a few bodies written by the tests, which is the
 * proof that projectiles, like Core, need no game to work.
 */
public final class ProjectileSmokeTest {

    private ProjectileSmokeTest() {
    }

    public static void main(String[] args) {
        MotionTests.run();
        FlightTests.run();
        AimTests.run();
        System.exit(Checks.summary());
    }
}
