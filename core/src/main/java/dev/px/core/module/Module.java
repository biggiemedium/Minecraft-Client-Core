package dev.px.core.module;

import dev.px.core.event.EventBus;
import dev.px.core.module.category.Category;
import dev.px.core.module.toggle.ModuleToggleEvent;
import dev.px.core.module.toggle.Toggleable;
import dev.px.core.setting.impl.BindSetting;
import dev.px.core.util.Validate;
import lombok.Getter;

import java.util.Locale;

/**
 * A toggleable client feature.
 *
 * <p>Identity comes from {@link ModuleInfo}, so a subclass is usually nothing but
 * the annotation, its setting fields, and its {@code @Subscribe} handlers:
 *
 * <pre>{@code
 * @ModuleInfo(name = "Sprint", description = "Always sprints", category = "Movement")
 * public final class Sprint extends Module {
 *
 *     private final BooleanSetting omni = bool("Omnidirectional", false);
 *
 *     @Subscribe
 *     private void onTick(TickEvent event) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>Two things that used to be manual are gone. Settings register by being
 * declared, and the module is subscribed to the bus once at registration rather
 * than on every toggle, with its handlers gated by {@link #isListening()}.
 * Because nothing here calls {@code subscribe} in {@code onEnable}, a module
 * that overrides {@code onEnable} without calling {@code super} can no longer
 * silently stop receiving events.
 *
 * <p>A module has no key and no settings of its own. One that should switch on
 * and off from a key says so with {@link #toggledBy}; one that should not, such
 * as a module only ever toggled from the GUI, simply leaves it out:
 *
 * <pre>{@code
 * private final BindSetting key = toggledBy(bind("Keybind", Key.R));
 * }</pre>
 *
 * <p>A client that wants every module bindable says it once, in its own base class.
 */
@Getter
public abstract class Module extends Toggleable {

    /** Bus used to announce toggles. Injected by Core at startup. */
    private static volatile EventBus bus;

    private final ModuleInfo info;
    private final String categoryName;

    /** Resolved by {@link ModuleRegistry} at registration; null before that. */
    private Category category;

    /**
     * The bind that switches this module on and off, or null when it declared none.
     * Held here as well as wherever the subclass keeps it, so it lists first among
     * the module's settings.
     */
    private BindSetting toggleBind;

    protected Module() {
        // getClass() is legal here: the implicit super() has already returned, and it
        // reports the most-derived class, which is the one carrying the annotation.
        this.info = requireInfo(getClass());
        this.categoryName = info.category().isEmpty() ? inferCategory(getClass()) : info.category();
        identify(info.name(), info.description());
    }

    public static void bindBus(EventBus eventBus) {
        bus = eventBus;
    }

    /**
     * Makes {@code bind} the key that toggles this module, and returns it so it can
     * be kept in a field. At most one per module; it is saved and drawn like any
     * other setting, and lists first.
     *
     * @throws IllegalStateException if this module already has a toggle bind
     */
    protected final BindSetting toggledBy(BindSetting bind) {
        Validate.notNull(bind, "toggle bind of " + getClass().getName());
        if (toggleBind != null) {
            throw new IllegalStateException(getClass().getName() + " already has a toggle bind ("
                    + toggleBind.getName() + "); a module has at most one");
        }
        this.toggleBind = bind;
        return bind;
    }

    /** @return whether a fresh install starts with this module on. */
    public final boolean isEnabledByDefault() {
        return info.enabled();
    }

    /**
     * Extra text shown after the module name wherever it is listed, such as the
     * current mode or target. Empty for none.
     */
    public String getDisplayInfo() {
        return "";
    }

    /** @return the listed label: the name plus {@link #getDisplayInfo()} when present. */
    public final String getDisplayName() {
        String extra = getDisplayInfo();
        return extra == null || extra.isEmpty() ? getName() : getName() + " " + extra;
    }

    @Override
    protected final void onStateChanged(boolean nowEnabled) {
        EventBus target = bus;
        if (target != null) {
            target.post(new ModuleToggleEvent(this, nowEnabled));
        }
    }

    void assignCategory(Category resolved) {
        this.category = resolved;
    }

    static ModuleInfo requireInfo(Class<?> type) {
        ModuleInfo found = type.getAnnotation(ModuleInfo.class);
        if (found == null) {
            throw new IllegalStateException(type.getName()
                    + " extends Module but is missing its @ModuleInfo annotation");
        }
        return found;
    }

    /** A module in package ...module.combat infers the category name Combat. */
    private static String inferCategory(Class<?> type) {
        String packageName = type.getPackage() == null ? "" : type.getPackage().getName();
        int lastDot = packageName.lastIndexOf('.');
        String segment = lastDot < 0 ? packageName : packageName.substring(lastDot + 1);
        if (segment.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(segment.charAt(0)) + segment.substring(1).toLowerCase(Locale.ROOT);
    }
}
