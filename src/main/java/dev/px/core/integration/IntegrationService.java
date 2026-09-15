package dev.px.core.integration;

import dev.px.core.concurrent.ThreadService;
import dev.px.core.service.Service;
import dev.px.core.setting.SettingHolder;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.util.CoreLogger;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Drives the optional external integrations: rich presence and now-playing.
 *
 * <p>Both are polled or pushed on the thread pool, never on the game thread. The
 * old client ran Discord RPC on its own manager and Spotify on another, each with
 * bespoke threading, and both were commented out in the end.
 *
 * <p>Neither provider is required. With none installed the settings are still
 * present and the service does nothing, so the client behaves identically with
 * and without them.
 */
@Getter
public final class IntegrationService extends SettingHolder implements Service {

    private final BooleanSetting presenceEnabled = bool("Rich Presence", false)
            .describe("Publish activity to Discord");
    private final BooleanSetting mediaEnabled = bool("Now Playing", false)
            .describe("Poll the media provider for the current track");
    private final NumberSetting<Integer> mediaPollSeconds = integer("Media Poll Interval", 5, 1, 60)
            .visibleWhen(mediaEnabled);

    private final CoreLogger logger;
    private final ThreadService threads;

    @Setter
    private PresenceProvider presenceProvider;

    @Setter
    private MediaProvider mediaProvider;

    /** Supplies the presence to publish. Set by the client; polled on the schedule below. */
    @Setter
    private Supplier<RichPresence> presenceSupplier;

    private volatile MediaTrack currentTrack;
    private RichPresence lastPublished;
    private long lastMediaPoll;

    public IntegrationService(CoreLogger logger, ThreadService threads) {
        this.logger = logger;
        this.threads = threads;
    }

    @Override
    public String getName() {
        return "Integrations";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Service>[] dependsOn() {
        return new Class[] { ThreadService.class };
    }

    @Override
    public void start() {
        // One repeating task each, rather than a thread per integration.
        threads.repeat(this::tickPresence, 2, 2, TimeUnit.SECONDS);
        threads.repeat(this::tickMedia, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (presenceProvider != null && presenceProvider.isConnected()) {
            presenceProvider.disconnect();
        }
    }

    /** @return the last polled track, or null. Safe to read from the render thread. */
    public MediaTrack getCurrentTrack() {
        return mediaEnabled.isOn() ? currentTrack : null;
    }

    private void tickPresence() {
        if (presenceProvider == null) {
            return;
        }
        if (!presenceEnabled.isOn()) {
            if (presenceProvider.isConnected()) {
                presenceProvider.disconnect();
                lastPublished = null;
            }
            return;
        }
        try {
            if (!presenceProvider.isConnected()) {
                presenceProvider.connect();
            }
            if (presenceSupplier == null) {
                return;
            }
            RichPresence presence = presenceSupplier.get();
            // Presence APIs rate-limit, so only push on an actual change.
            if (presence != null && !presence.matches(lastPublished)) {
                presenceProvider.publish(presence);
                lastPublished = presence;
            }
        } catch (Exception e) {
            logger.error("Rich presence update failed", e);
        }
    }

    private void tickMedia() {
        if (mediaProvider == null || !mediaEnabled.isOn() || !mediaProvider.isAvailable()) {
            return;
        }
        long interval = mediaPollSeconds.getInt() * 1000L;
        long now = System.currentTimeMillis();
        if (now - lastMediaPoll < interval) {
            return;
        }
        lastMediaPoll = now;
        try {
            currentTrack = mediaProvider.poll();
        } catch (Exception e) {
            logger.error("Media poll failed", e);
            currentTrack = null;
        }
    }
}
