package dev.px.core;

import dev.px.core.concurrent.ThreadService;
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
    int threadPoolSize = ThreadService.DEFAULT_POOL_SIZE;

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

    /**
     * Worker threads available for one-shot background work.
     *
     * <p>Defaults to {@value dev.px.core.concurrent.ThreadService#DEFAULT_POOL_SIZE}.
     * This sizes only the {@link ThreadService#submit} tier: timers get their own
     * pool and each {@link ThreadService#loop} gets its own thread, so raising it
     * is only worth doing for a client that fires a lot of concurrent requests.
     */
    public CoreBuilder threadPoolSize(int size) {
        Validate.check(size >= 1, "thread pool size must be at least 1, got " + size);
        this.threadPoolSize = size;
        return this;
    }

    public Core build() {
        Validate.notNull(platform, "platform");
        return new Core(this);
    }
}
