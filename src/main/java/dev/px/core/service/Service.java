package dev.px.core.service;

import dev.px.core.registry.Named;

/**
 * A long-lived client subsystem with a start and a stop.
 *
 * <p>The old client had eighteen managers constructed in one hand-ordered block,
 * with the ordering constraints written down as comments:
 * {@code // Settings manager before event processor}, {@code // put this after
 * everything bc it calls on ColorManager, Module Manager, ...}. A service states
 * its dependencies in {@link #dependsOn()} and the container works the order out,
 * so adding a service cannot silently break an invariant nobody remembers.
 */
public interface Service extends Named {

    /**
     * Services that must be started before this one.
     *
     * <p>Declare what you actually read during {@link #start()}, not everything
     * you eventually touch: a dependency only needs to exist before startup, and
     * over-declaring creates cycles that are not really there.
     */
    default Class<? extends Service>[] dependsOn() {
        return ServiceContainer.NO_DEPENDENCIES;
    }

    /**
     * Brings the service up. Throwing aborts startup with the service named, which
     * is the right outcome for a broken subsystem: a half-started client is worse
     * than one that says which piece failed.
     */
    void start() throws Exception;

    /** Tears the service down. Called in reverse start order, and must not throw. */
    default void stop() {
    }
}
