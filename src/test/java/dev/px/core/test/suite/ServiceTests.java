package dev.px.core.test.suite;

import dev.px.core.service.Service;
import dev.px.core.service.ServiceContainer;
import dev.px.core.service.ServiceException;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;
import dev.px.core.util.ConsoleLogger;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-ordered startup.
 *
 * <p>This is what replaced the old client's hand-ordered construction block,
 * whose constraints were recorded as comments like
 * {@code // Settings manager before event processor}. A service states what it
 * needs and the container works the order out.
 */
public final class ServiceTests {

    /** Written by the fake services below, so ordering can be asserted. */
    private static final List<String> log = new ArrayList<>();

    private ServiceTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Services");

        // ---- ordering ------------------------------------------------------
        log.clear();
        ServiceContainer container = new ServiceContainer(new ConsoleLogger("test"));
        // Registered in the wrong order on purpose: C first, A last.
        container.register(new ServiceC());
        container.register(new ServiceB());
        container.register(new ServiceA());
        container.startAll();

        Checks.checkEquals("services start in dependency order, not registration order",
                "[start A, start B, start C]", log.toString());
        Checks.check("the container reports itself running", container.isRunning());
        Checks.check("get resolves a registered service", container.get(ServiceB.class) != null);

        log.clear();
        container.stopAll();
        Checks.checkEquals("services stop in reverse order",
                "[stop C, stop B, stop A]", log.toString());

        // ---- failure modes -------------------------------------------------
        ServiceContainer cyclic = new ServiceContainer(new ConsoleLogger("test"));
        cyclic.register(new ServiceX());
        cyclic.register(new ServiceY());
        Checks.checkThrows("a dependency cycle is reported rather than looping",
                ServiceException.class, cyclic::startAll);

        ServiceContainer missing = new ServiceContainer(new ConsoleLogger("test"));
        missing.register(new ServiceC());
        Checks.checkThrows("an unregistered dependency is reported at startup",
                ServiceException.class, missing::startAll);

        ServiceContainer broken = new ServiceContainer(new ConsoleLogger("test"));
        broken.register(new ServiceThatFails());
        Checks.checkThrows("a service that throws aborts startup",
                ServiceException.class, broken::startAll);
        Checks.check("a failed startup leaves the container stopped", !broken.isRunning());

        ServiceContainer late = new ServiceContainer(new ConsoleLogger("test"));
        late.register(new ServiceA());
        late.startAll();
        Checks.checkThrows("registering after startup is refused",
                IllegalArgumentException.class, () -> late.register(new ServiceB()));
        late.stopAll();

        // ---- the real client ------------------------------------------------
        Checks.check("the booted client is running", client.getCore().getServices().isRunning());
        Checks.check("every service is reachable by type",
                client.getCore().getServices().get(dev.px.core.concurrent.ThreadService.class) != null);
    }

    // ------------------------------------------------------------- fixtures

    @Getter
    abstract static class Logged implements Service {
        @Override public void start() { log.add("start " + getName()); }
        @Override public void stop() { log.add("stop " + getName()); }
    }

    static final class ServiceA extends Logged {
        @Override public String getName() { return "A"; }
    }

    @SuppressWarnings("unchecked")
    static final class ServiceB extends Logged {
        @Override public String getName() { return "B"; }
        @Override public Class<? extends Service>[] dependsOn() { return new Class[] { ServiceA.class }; }
    }

    @SuppressWarnings("unchecked")
    static final class ServiceC extends Logged {
        @Override public String getName() { return "C"; }
        @Override public Class<? extends Service>[] dependsOn() { return new Class[] { ServiceB.class }; }
    }

    @SuppressWarnings("unchecked")
    static final class ServiceX extends Logged {
        @Override public String getName() { return "X"; }
        @Override public Class<? extends Service>[] dependsOn() { return new Class[] { ServiceY.class }; }
    }

    @SuppressWarnings("unchecked")
    static final class ServiceY extends Logged {
        @Override public String getName() { return "Y"; }
        @Override public Class<? extends Service>[] dependsOn() { return new Class[] { ServiceX.class }; }
    }

    static final class ServiceThatFails implements Service {
        @Override public String getName() { return "Faulty"; }
        @Override public void start() throws Exception { throw new Exception("deliberate startup failure"); }
    }
}
