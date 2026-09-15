package dev.px.core;

import dev.px.core.platform.Platform;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

/**
 * Builds the {@link Core} instance.
 *
 * <p>Only the {@link Platform} is required. Everything else has a working
 * default, so a minimal bootstrap is three lines and a client only supplies what
 * it actually wants to change.
 */
public final class CoreBuilder {

    final String clientName;
    final String clientVersion;

    Platform platform;
    CoreLogger logger;

    CoreBuilder(String clientName, String clientVersion) {
        this.clientName = Validate.notBlank(clientName, "client name");
        this.clientVersion = Validate.notBlank(clientVersion, "client version");
    }

    /** The game seam. Required. */
    public CoreBuilder platform(Platform platform) {
        this.platform = platform;
        return this;
    }

    /** Defaults to a console logger prefixed with the client name. */
    public CoreBuilder logger(CoreLogger logger) {
        this.logger = logger;
        return this;
    }

    public Core build() {
        Validate.notNull(platform, "platform");
        return new Core(this);
    }
}
