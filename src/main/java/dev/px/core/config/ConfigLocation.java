package dev.px.core.config;

import dev.px.core.util.Validate;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where one {@link ConfigSection}'s file lives: inside every profile, or shared
 * by all of them, at a path of your choosing.
 *
 * <pre>{@code
 * ConfigLocation.profile("modules")               // configs/profiles/<profile>/modules.json
 * ConfigLocation.profile("combat/aura")           // configs/profiles/<profile>/combat/aura.json
 * ConfigLocation.shared("accounts")               // configs/shared/accounts.json
 * ConfigLocation.shared("private/accounts")       // configs/shared/private/accounts.json
 * }</pre>
 *
 * <p>The path is relative and has no extension: Core adds {@code .json}, or
 * {@code .enc} for a section you {@linkplain ConfigService#encrypt encrypt}. It is
 * one or more names separated by {@code /}, each made of letters, digits,
 * {@code .}, {@code _} and {@code -}, so it can make folders but can never leave
 * the config directory.
 *
 * <p>Immutable. Equal when the scope and path are, ignoring case, since two
 * paths that differ only in case are one file on most players' disks.
 */
public final class ConfigLocation {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private final boolean shared;
    private final String path;

    private ConfigLocation(boolean shared, String path) {
        this.shared = shared;
        this.path = path;
    }

    /** A file inside each profile's folder: switching profile switches it. */
    public static ConfigLocation profile(String path) {
        return new ConfigLocation(false, validate(path));
    }

    /** A file every profile shares: switching profile leaves it alone. */
    public static ConfigLocation shared(String path) {
        return new ConfigLocation(true, validate(path));
    }

    public boolean isShared() {
        return shared;
    }

    /** @return the relative path, {@code /}-separated, without an extension */
    public String getPath() {
        return path;
    }

    /** @return where this is, as a cipher's context: {@code "shared/accounts"} or {@code "profile/modules"} */
    String context() {
        return (shared ? "shared/" : "profile/") + path;
    }

    static String validate(String path) {
        Validate.notNull(path, "path");
        String trimmed = path.trim();
        Validate.check(!trimmed.isEmpty(), "a config path must not be empty");
        Validate.check(!trimmed.startsWith("/") && !trimmed.endsWith("/"),
                "a config path is relative and names a file: " + path);
        for (String segment : trimmed.split("/", -1)) {
            Validate.check(SEGMENT.matcher(segment).matches() && !segment.equals(".") && !segment.equals(".."),
                    "a config path is names of letters, digits, '.', '_' and '-' separated by '/': " + path);
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConfigLocation)) {
            return false;
        }
        ConfigLocation that = (ConfigLocation) other;
        return shared == that.shared && path.equalsIgnoreCase(that.path);
    }

    @Override
    public int hashCode() {
        return path.toLowerCase(Locale.ROOT).hashCode() * 31 + (shared ? 1 : 0);
    }

    @Override
    public String toString() {
        return context();
    }
}
