package dev.px.core.test.suite;

import dev.px.core.registry.Named;
import dev.px.core.registry.Registry;
import dev.px.core.test.harness.Checks;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The generic {@link Registry}, which replaced four hand-written manager classes.
 *
 * <p>Pure unit tests: no client, no game.
 */
public final class RegistryTests {

    private RegistryTests() {
    }

    public static void run() {
        Checks.section("Registry");

        Registry<Entry> registry = new Registry<>();
        Entry alpha = registry.register(new Entry("Alpha"));
        Entry beta = registry.register(new Entry("Beta"));
        registry.register(new Special("Gamma"));

        Checks.checkEquals("register returns the entry", "Alpha", alpha.getName());
        Checks.checkEquals("size counts registrations", 3f, registry.size());
        Checks.check("lookup by exact name", registry.get("Beta") == beta);
        Checks.check("lookup by name ignores case", registry.get("bEtA") == beta);
        Checks.check("unknown name returns null", registry.get("Nothing") == null);
        Checks.check("find returns an empty Optional", !registry.find("Nothing").isPresent());
        Checks.check("lookup by class returns the first assignable entry",
                registry.get(Special.class) instanceof Special);
        Checks.check("contains is case-insensitive", registry.contains("alpha"));

        Checks.checkThrows("duplicate names are rejected at registration",
                IllegalArgumentException.class, () -> registry.register(new Entry("alpha")));

        Checks.checkThrows("require throws for a missing type",
                IllegalStateException.class, () -> registry.require(Missing.class));

        Checks.check("iteration keeps registration order",
                registry.all().get(0) == alpha && registry.all().get(1) == beta);

        Checks.checkEquals("where filters", 1f,
                registry.where(entry -> entry.getName().startsWith("B")).size());

        registry.sortByName();
        Checks.checkEquals("sortByName reorders in place", "Alpha", registry.all().get(0).getName());

        Checks.check("unregister removes the entry", registry.unregister(beta));
        Checks.check("unregistering twice is a no-op", !registry.unregister(beta));
        Checks.check("the name is freed for reuse", !registry.contains("Beta"));

        // Regression: SocialService and AccountService replaced a whole list by
        // iterating all() and unregistering, which walks a live view and threw
        // ConcurrentModificationException on any second config load.
        Checks.checkSurvives("clear empties a populated registry without a CME", registry::clear);
        Checks.checkEquals("clear leaves nothing behind", 0f, registry.size());

        // Hooks fire so subclasses can wire entries up as they arrive.
        List<String> events = new ArrayList<>();
        Registry<Entry> hooked = new Registry<Entry>() {
            @Override protected void onRegistered(Entry entry) { events.add("+" + entry.getName()); }
            @Override protected void onUnregistered(Entry entry) { events.add("-" + entry.getName()); }
        };
        Entry hookedEntry = hooked.register(new Entry("Hooked"));
        hooked.unregister(hookedEntry);
        Checks.checkEquals("registration hooks fire in order", "[+Hooked, -Hooked]", events.toString());

        hooked.register(new Entry("One"));
        hooked.register(new Entry("Two"));
        events.clear();
        hooked.clear();
        Checks.checkEquals("clear notifies for every entry", "[-One, -Two]", events.toString());
    }

    @Getter
    @RequiredArgsConstructor
    static class Entry implements Named {
        private final String name;
    }

    static final class Special extends Entry {
        Special(String name) {
            super(name);
        }
    }

    /** Never registered, so require() has something to fail on. */
    static final class Missing extends Entry {
        Missing(String name) {
            super(name);
        }
    }
}
