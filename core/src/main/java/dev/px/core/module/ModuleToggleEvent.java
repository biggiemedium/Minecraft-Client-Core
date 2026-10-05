package dev.px.core.module;

import dev.px.core.event.Event;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Posted after a module is switched on or off.
 *
 * <p>The old client did this inline: {@code Module.toggle()} reached into the
 * notification module, read two of its settings, built the message, and posted
 * it, which made the base module class depend on one particular concrete module.
 * Notifications, the ArrayList, and anything else now just subscribe to this.
 */
@Getter
@RequiredArgsConstructor
public final class ModuleToggleEvent extends Event {

    private final Module module;
    private final boolean nowEnabled;
}
