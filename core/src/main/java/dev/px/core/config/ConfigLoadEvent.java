package dev.px.core.config;

import dev.px.core.event.Event;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Posted after a config profile finishes loading.
 *
 * <p>Config loads apply settings silently, on purpose: firing a change event per
 * setting would have modules reacting to half-applied state. The cost is that
 * anything caching a value derived from a setting is stale once a profile loads.
 * This is the single point at which to rebuild such a cache.
 *
 * <p>Prefer deriving on demand where you can. Use this only where recomputing
 * every frame would be genuinely expensive.
 */
@Getter
@AllArgsConstructor
public final class ConfigLoadEvent extends Event {

    private final String profile;
}
