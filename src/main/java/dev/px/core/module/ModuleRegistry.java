package dev.px.core.module;

import dev.px.core.event.EventBus;
import dev.px.core.registry.Registry;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every module in the client.
 *
 * <p>Registration does three things a caller used to have to remember: it
 * resolves the module's {@link Category} against the {@link CategoryRegistry},
 * subscribes the module to the event bus for good, and fails loudly on a bad
 * category name instead of leaving the module orphaned in the GUI.
 *
 * <p>Lookup, iteration and sorting come from {@link Registry}; only the
 * module-specific parts live here.
 */
@RequiredArgsConstructor
public final class ModuleRegistry extends Registry<Module> {

    private final CategoryRegistry categories;
    private final EventBus bus;

    @Override
    protected void onRegistered(Module module) {
        module.assignCategory(resolveCategory(module));
        // Subscribed once, for the module's whole lifetime. Its handlers are gated
        // by Listenable#isListening, so toggling costs nothing and handler order
        // stays stable across toggles.
        bus.subscribe(module);
    }

    @Override
    protected void onUnregistered(Module module) {
        bus.unsubscribe(module);
    }

    private Category resolveCategory(Module module) {
        String wanted = module.getCategoryName();
        Category found = categories.get(wanted);
        if (found == null) {
            throw new IllegalStateException("Module " + module.getName() + " (" + module.getClass().getName()
                    + ") wants category " + wanted + ", which is not registered. Registered categories: "
                    + categories.stream().map(Category::getName).collect(Collectors.joining(", ")));
        }
        return found;
    }

    /**
     * Switches on every module whose {@link ModuleInfo#enabled()} is true.
     *
     * <p>Called once by Core after all services are up, not during registration,
     * so a default-on module's {@code onEnable} sees a fully built client. A
     * config load afterwards overrides whatever this set.
     */
    public void applyDefaults() {
        for (Module module : all()) {
            if (module.isEnabledByDefault()) {
                module.enable();
            }
        }
    }

    public List<Module> inCategory(Category category) {
        return where(module -> module.getCategory() == category);
    }

    public List<Module> inCategory(String categoryName) {
        return where(module -> module.getCategory() != null
                && module.getCategory().getName().equalsIgnoreCase(categoryName));
    }

    public List<Module> enabled() {
        return where(Module::isEnabled);
    }

    /** @return enabled modules that opted into the ArrayList, longest label first. */
    public List<Module> arrayListEntries() {
        return enabled().stream()
                .filter(module -> module.getVisible().isOn())
                .sorted(Comparator.comparingInt((Module module) -> module.getDisplayName().length()).reversed())
                .collect(Collectors.toList());
    }

    public boolean isEnabled(Class<? extends Module> type) {
        Module module = get(type);
        return module != null && module.isEnabled();
    }

    /** Disables every module. Used on disconnect and by the panic keybind. */
    public void disableAll() {
        all().forEach(Module::disable);
    }

    @Override
    protected String describe() {
        return "module";
    }
}
