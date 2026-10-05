package dev.px.core.notification;

import dev.px.core.render.animation.Animation;
import dev.px.core.render.animation.Easing;
import lombok.Getter;

/**
 * One transient message shown on screen.
 *
 * <p>Carries its own slide-in animation and expiry, so the renderer is a loop
 * that draws whatever {@link NotificationService#getActive()} returns and never
 * has to track per-notification state.
 */
@Getter
public final class Notification {

    private final String title;
    private final String message;
    private final NotificationType type;
    private final long durationMillis;
    private final long createdAt = System.currentTimeMillis();

    /** Runs 0 to 1 on appearance and back to 0 on expiry. Drive opacity and offset from it. */
    private final Animation presence = Animation.fade(220L, Easing.QUAD_OUT).target(1f);

    Notification(String title, String message, NotificationType type, long durationMillis) {
        this.title = title;
        this.message = message == null ? "" : message;
        this.type = type;
        this.durationMillis = durationMillis;
    }

    public long getAgeMillis() {
        return System.currentTimeMillis() - createdAt;
    }

    /** @return whether the lifetime has elapsed and the fade-out should start. */
    public boolean isExpired() {
        return getAgeMillis() >= durationMillis;
    }

    /** @return whether the fade-out has finished and it can be dropped. */
    public boolean isGone() {
        return presence.isIdleAtZero();
    }

    void beginDismissal() {
        presence.target(0f);
    }

    /** @return remaining lifetime as 1 down to 0, for drawing a countdown bar. */
    public float getRemaining() {
        return durationMillis <= 0L ? 0f
                : Math.max(0f, 1f - getAgeMillis() / (float) durationMillis);
    }
}
