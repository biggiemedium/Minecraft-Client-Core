package dev.px.core.input;

import dev.px.core.event.EventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.MouseEvent;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleRegistry;
import dev.px.core.platform.Platform;
import dev.px.core.service.Service;
import dev.px.core.setting.Setting;
import dev.px.core.setting.impl.BindSetting;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns key and mouse events into module toggles and registered actions.
 *
 * <p>The old client did this inside a manager that also owned the GUI instances
 * and hard-coded eight keys in an if-chain, including two that saved configs by
 * name. Bindings are data here: a module binds through its {@link BindSetting},
 * and anything else registers an {@link Action} the user can rebind like any
 * other setting.
 *
 * <p>Runs at {@link Priority#LOW} so a GUI that is capturing input can cancel
 * the event first and stop a keystroke both typing into a text box and toggling
 * a module.
 */
@Getter
@RequiredArgsConstructor
public final class InputService implements Service {

    private final String name = "Input";

    private final EventBus bus;
    private final ModuleRegistry modules;
    private final Platform platform;

    private final List<Action> actions = new ArrayList<>();

    /** Set while a keybind button in the GUI is waiting for the next key. */
    private BindSetting capturing;

    @Override
    public void start() {
        bus.subscribe(this);
    }

    @Override
    public void stop() {
        bus.unsubscribe(this);
    }

    /**
     * Registers a named, rebindable action such as opening the click GUI.
     *
     * @return the action's bind setting, so the caller can persist it in a config section
     */
    public BindSetting register(String actionName, Bind defaultBind, Runnable body) {
        BindSetting setting = new BindSetting(actionName, defaultBind);
        actions.add(new Action(actionName, setting, body));
        return setting;
    }

    /**
     * Routes the next key press into {@code setting} instead of acting on it.
     *
     * <p>How a keybind button works: the GUI calls this, the user presses a key,
     * and the binding is captured without the key also firing whatever it is
     * currently bound to.
     */
    public void capture(BindSetting setting) {
        this.capturing = setting;
    }

    public boolean isCapturing() {
        return capturing != null;
    }

    @Subscribe(priority = Priority.LOW)
    private void onKey(KeyEvent event) {
        if (!event.isPressed()) {
            return;
        }
        if (capturing != null) {
            // Escape clears the bind rather than binding Escape, which no user wants.
            capturing.set(event.getKey() == Key.ESCAPE ? Bind.NONE : Bind.of(event.getKey(), toArray(event.getModifiers())));
            capturing = null;
            event.cancel();
            return;
        }
        // Modifier keys alone never trigger a bind; they only qualify one.
        if (event.getKey().isModifier()) {
            return;
        }
        fireMatching(bind -> bind.matches(event.getKey(), event.getModifiers()));
    }

    @Subscribe(priority = Priority.LOW)
    private void onMouse(MouseEvent event) {
        if (!event.isPressed() || capturing != null) {
            return;
        }
        // Left and right click are the game's own; binding them would make the client unusable.
        if (event.getButton() == MouseButton.LEFT || event.getButton() == MouseButton.RIGHT) {
            return;
        }
        fireMatching(bind -> bind.matches(event.getButton(), event.getModifiers()));
    }

    private void fireMatching(java.util.function.Predicate<Bind> matcher) {
        for (Action action : actions) {
            if (matcher.test(action.binding.get())) {
                action.body.run();
            }
        }
        // Binds only toggle modules in-game, so a menu keystroke cannot silently
        // flip state the player cannot see.
        if (!platform.isInGame()) {
            return;
        }
        for (Module module : modules.all()) {
            if (matcher.test(module.getKeybind().get())) {
                module.toggle();
            }
        }
    }

    /** @return every action bind, so a config section can persist them. */
    public List<Setting<?>> getActionSettings() {
        List<Setting<?>> settings = new ArrayList<>(actions.size());
        for (Action action : actions) {
            settings.add(action.binding);
        }
        return settings;
    }

    private static Modifier[] toArray(Set<Modifier> modifiers) {
        return modifiers.toArray(new Modifier[0]);
    }

    /** A rebindable client action that is not a module toggle. */
    @Getter
    @RequiredArgsConstructor
    public static final class Action {

        private final String name;
        private final BindSetting binding;
        private final Runnable body;
    }
}
