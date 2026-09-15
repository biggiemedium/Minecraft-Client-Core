package dev.px.core.service;

import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the client's services and starts them in dependency order.
 *
 * <p>Ordering is a topological sort over {@link Service#dependsOn()}, with
 * registration order preserved among independent services so startup stays
 * deterministic. A dependency cycle or a missing dependency is reported with the
 * services involved rather than surfacing later as a null field.
 *
 * <p>Shutdown runs in reverse start order, and a service that throws while
 * stopping is logged and skipped so one bad teardown cannot strand the rest.
 */
@RequiredArgsConstructor
public final class ServiceContainer {

    @SuppressWarnings("unchecked")
    static final Class<? extends Service>[] NO_DEPENDENCIES = new Class[0];

    private final CoreLogger logger;

    private final Map<Class<? extends Service>, Service> services = new LinkedHashMap<>();
    private final List<Service> started = new ArrayList<>();

    private boolean running;

    /**
     * Adds a service. Must be called before {@link #startAll()}.
     *
     * @return the service, so it can be registered and kept in one expression
     */
    public <S extends Service> S register(S service) {
        Validate.notNull(service, "service");
        Validate.check(!running, "Cannot register " + service.getName() + " after startup");
        Class<? extends Service> type = service.getClass();
        Validate.check(!services.containsKey(type), type.getName() + " is already registered");
        services.put(type, service);
        return service;
    }

    /** @return the registered service of this type, or null. */
    public <S extends Service> S get(Class<S> type) {
        Service exact = services.get(type);
        if (exact != null) {
            return type.cast(exact);
        }
        // Fall back to an assignable match so a service can be looked up by its interface.
        for (Service candidate : services.values()) {
            if (type.isInstance(candidate)) {
                return type.cast(candidate);
            }
        }
        return null;
    }

    public <S extends Service> S require(Class<S> type) {
        S found = get(type);
        if (found == null) {
            throw new IllegalStateException("Service " + type.getName() + " is not registered");
        }
        return found;
    }

    public boolean isRunning() {
        return running;
    }

    public List<Service> all() {
        return Collections.unmodifiableList(new ArrayList<>(services.values()));
    }

    /**
     * Starts every registered service in dependency order.
     *
     * @throws ServiceException if ordering fails or a service throws while starting
     */
    public void startAll() {
        Validate.check(!running, "Services are already running");
        for (Service service : resolveOrder()) {
            try {
                service.start();
                started.add(service);
                logger.debug("Started service " + service.getName());
            } catch (Exception e) {
                // Roll back so a failed startup does not leave half a client alive.
                stopAll();
                throw new ServiceException("Service " + service.getName() + " failed to start", e);
            }
        }
        running = true;
    }

    /** Stops started services in reverse order. Safe to call more than once. */
    public void stopAll() {
        for (int i = started.size() - 1; i >= 0; i--) {
            Service service = started.get(i);
            try {
                service.stop();
            } catch (Exception e) {
                logger.error("Service " + service.getName() + " failed to stop", e);
            }
        }
        started.clear();
        running = false;
    }

    /** Depth-first topological sort. Registration order breaks ties. */
    private List<Service> resolveOrder() {
        List<Service> ordered = new ArrayList<>(services.size());
        Map<Service, Boolean> state = new IdentityHashMap<>();
        for (Service service : services.values()) {
            visit(service, state, ordered, new ArrayList<>());
        }
        return ordered;
    }

    private void visit(Service service, Map<Service, Boolean> state, List<Service> ordered, List<Service> path) {
        Boolean mark = state.get(service);
        if (Boolean.TRUE.equals(mark)) {
            return;
        }
        if (Boolean.FALSE.equals(mark)) {
            throw new ServiceException("Service dependency cycle: " + describeCycle(path, service));
        }
        state.put(service, Boolean.FALSE);
        path.add(service);
        for (Class<? extends Service> dependency : service.dependsOn()) {
            Service resolved = get(dependency);
            if (resolved == null) {
                throw new ServiceException("Service " + service.getName() + " depends on "
                        + dependency.getName() + ", which is not registered");
            }
            visit(resolved, state, ordered, path);
        }
        path.remove(path.size() - 1);
        state.put(service, Boolean.TRUE);
        ordered.add(service);
    }

    private static String describeCycle(List<Service> path, Service repeated) {
        StringBuilder text = new StringBuilder();
        for (Service service : path) {
            text.append(service.getName()).append(" -> ");
        }
        return text.append(repeated.getName()).toString();
    }
}
