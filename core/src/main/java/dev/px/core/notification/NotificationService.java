package dev.px.core.notification;

import dev.px.core.service.Service;
import dev.px.core.setting.SettingHolder;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.NumberSetting;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The on-screen notification queue.
 *
 * <p>Core owns the lifecycle; drawing belongs to whichever HUD element the client
 * writes. That split is what lets a notification be raised from a background
 * thread: {@link #post} only enqueues, and the queue is drained on the render
 * thread in {@link #getActive()}.
 *
 * <p>Being a {@link SettingHolder} gives the user control over the cap and
 * whether notifications appear at all, without a separate settings mechanism.
 */
@Getter
public final class NotificationService extends SettingHolder implements Service {

    private final BooleanSetting enabled = bool("Notifications", true);
    private final NumberSetting<Integer> maxVisible = integer("Max Visible", 5, 1, 12)
            .describe("Oldest notifications are dismissed beyond this many");
    private final NumberSetting<Float> durationScale = number("Duration Scale", 1f, 0.25f, 3f)
            .describe("Multiplies how long notifications stay on screen");

    /** Written from any thread, drained on the render thread. */
    private final ConcurrentLinkedQueue<Notification> incoming = new ConcurrentLinkedQueue<>();

    private final List<Notification> active = new ArrayList<>();

    @Override
    public String getName() {
        return "Notifications";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        incoming.clear();
        active.clear();
    }

    public Notification info(String title, String message) {
        return post(title, message, NotificationType.INFO);
    }

    public Notification success(String title, String message) {
        return post(title, message, NotificationType.SUCCESS);
    }

    public Notification warn(String title, String message) {
        return post(title, message, NotificationType.WARNING);
    }

    public Notification error(String title, String message) {
        return post(title, message, NotificationType.ERROR);
    }

    public Notification post(String title, String message, NotificationType type) {
        return post(title, message, type, (long) (type.getDefaultDurationMillis() * durationScale.getFloat()));
    }

    /**
     * Queues a notification. Safe to call from any thread.
     *
     * @return the notification, whether or not notifications are currently enabled
     */
    public Notification post(String title, String message, NotificationType type, long durationMillis) {
        Notification notification = new Notification(title, message, type, durationMillis);
        if (enabled.isOn()) {
            incoming.add(notification);
        }
        return notification;
    }

    /**
     * Drains the queue, retires finished notifications, and returns what to draw.
     *
     * <p>Call once per frame from the renderer. Newest last, so a top-down list
     * draws in arrival order.
     */
    public List<Notification> getActive() {
        Notification arrival;
        while ((arrival = incoming.poll()) != null) {
            active.add(arrival);
        }

        // Over the cap, retire from the front: the oldest have been read already.
        int excess = active.size() - maxVisible.getInt();
        for (int i = 0; i < excess && i < active.size(); i++) {
            active.get(i).beginDismissal();
        }

        for (Notification notification : active) {
            if (notification.isExpired()) {
                notification.beginDismissal();
            }
        }
        active.removeIf(Notification::isGone);
        return Collections.unmodifiableList(active);
    }

    /** Dismisses everything on screen, e.g. when a config is loaded. */
    public void clear() {
        active.forEach(Notification::beginDismissal);
    }
}
