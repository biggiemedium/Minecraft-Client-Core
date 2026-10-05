package dev.px.core.util;

/** Argument checks that fail loudly at registration instead of quietly at runtime. */
public final class Validate {

    private Validate() {
    }

    public static <T> T notNull(T value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        return value;
    }

    public static String notBlank(String value, String what) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
