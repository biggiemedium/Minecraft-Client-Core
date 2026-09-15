package dev.px.core.test.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * The assertion vocabulary the suites share.
 *
 * <p>Deliberately not JUnit. Core has no test dependencies at all, and adding
 * one would mean every version project that copies Core inherits it. A few
 * hundred lines of plain Java runs anywhere, including from a game launcher.
 *
 * <p>Checks never abort: a failing assertion is recorded and the suite keeps
 * going, so one broken behaviour does not hide the twenty after it.
 */
public final class Checks {

    private static final List<String> failures = new ArrayList<>();

    private static int total;
    private static String section = "general";

    private Checks() {
    }

    /** Starts a named group. Purely for readable output. */
    public static void section(String name) {
        section = name;
        System.out.println();
        System.out.println("  " + name);
        System.out.println("  " + line(name.length()));
    }

    public static void check(String description, boolean passed) {
        total++;
        if (passed) {
            System.out.println("    ok    " + description);
        } else {
            failures.add(section + "  >  " + description);
            System.out.println("    FAIL  " + description);
        }
    }

    /** Reports the actual value on failure, so a broken number is diagnosable from the log. */
    public static void checkEquals(String description, float expected, float actual) {
        boolean passed = eq(expected, actual);
        report(description, passed, String.valueOf(expected), String.valueOf(actual));
    }

    public static void checkEquals(String description, Object expected, Object actual) {
        boolean passed = expected == null ? actual == null : expected.equals(actual);
        report(description, passed, String.valueOf(expected), String.valueOf(actual));
    }

    /** Asserts that {@code body} completes without throwing. */
    public static void checkSurvives(String description, Runnable body) {
        try {
            body.run();
            check(description, true);
        } catch (Throwable thrown) {
            check(description + "  (threw " + thrown.getClass().getSimpleName()
                    + ": " + thrown.getMessage() + ")", false);
        }
    }

    /** Asserts that {@code body} throws, which is how fail-fast behaviour is verified. */
    public static void checkThrows(String description, Class<? extends Throwable> expected, Runnable body) {
        try {
            body.run();
            check(description + "  (nothing was thrown)", false);
        } catch (Throwable thrown) {
            check(description, expected.isInstance(thrown));
        }
    }

    public static boolean eq(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static void report(String description, boolean passed, String expected, String actual) {
        check(passed ? description : description + "  (expected " + expected + ", got " + actual + ")", passed);
    }

    /**
     * Prints the tally.
     *
     * @return a process exit code: 0 when everything passed
     */
    public static int summary() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println(total + "/" + total + " checks passed");
            return 0;
        }
        System.out.println((total - failures.size()) + "/" + total + " checks passed, "
                + failures.size() + " failed:");
        for (String failure : failures) {
            System.out.println("  - " + failure);
        }
        return 1;
    }

    /** Java 8 has no String.repeat. */
    private static String line(int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append('-');
        }
        return text.toString();
    }
}
