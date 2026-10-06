package dev.px.combat.test;

import dev.px.core.test.harness.Checks;

/**
 * Runs every combat suite in a plain JVM: no Minecraft, no window. The "game"
 * is a few plain classes and a block grid written by the tests, which is the
 * proof that combat, like Core, needs no game to work.
 */
public final class CombatSmokeTest {

    private CombatSmokeTest() {
    }

    public static void main(String[] args) {
        CrystalRulesTests.run();
        DamageMonitorTests.run();
        VectorTests.run();
        SearchTests.run();
        BedTests.run();
        HoleTests.run();
        PlaceTests.run();
        System.exit(Checks.summary());
    }
}
