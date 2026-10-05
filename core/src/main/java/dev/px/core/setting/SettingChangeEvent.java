package dev.px.core.setting;

import dev.px.core.event.Event;
import dev.px.core.event.EventBus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Posted after a {@link Setting} changes through {@link Setting#set}.
 *
 * <p>Lets unrelated systems react to configuration &mdash; the HUD rebuilding a
 * cached layout, a module re-deriving a value it only computes on change &mdash;
 * without every setting needing its own listener wiring.
 */
@Getter
@RequiredArgsConstructor
public final class SettingChangeEvent extends Event {

    private final Setting<?> setting;
    private final Object previousValue;
    private final Object newValue;

    /**
     * Bus used for posting. Injected by Core at startup rather than reached through
     * a static facade, so the setting package stays independently testable.
     */
    private static volatile EventBus bus;

    public static void bindBus(EventBus eventBus) {
        bus = eventBus;
    }

    static void post(Setting<?> setting, Object previous, Object updated) {
        EventBus target = bus;
        if (target != null) {
            target.post(new SettingChangeEvent(setting, previous, updated));
        }
    }

    /** @return the changed setting, typed, or {@code null} if it is some other setting. */
    @SuppressWarnings("unchecked")
    public <T extends Setting<?>> T as(Class<T> type) {
        return type.isInstance(setting) ? (T) setting : null;
    }
}
