package dev.px.core.notification;

import dev.px.core.render.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Severity of a notification, which decides its accent colour and default lifetime. */
@Getter
@RequiredArgsConstructor
public enum NotificationType {

    INFO(Color.of(90, 170, 255), 3000L),
    SUCCESS(Color.of(95, 215, 120), 3000L),
    WARNING(Color.of(255, 190, 70), 5000L),
    ERROR(Color.of(255, 90, 90), 7000L);

    private final Color color;

    /** Errors stay longer because they are the ones worth reading. */
    private final long defaultDurationMillis;
}
