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
import dev.px.core.util.Validate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Iterator;
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
 *
 * <p>A bind in {@link BindMode#HOLD} lasts until its key or button comes up,
 * whatever the modifiers are by then. Releases are heard even when something
 * cancelled them, so opening a screen over a held key cannot leave a module
 * stuck on. If the game can lose a release (the window losing focus), call
 * {@link #releaseAll()} when it does.
 */
@Getter
@RequiredArgsConstructor
public final class InputService implements Service {

    private final String name = "Input";

    private final EventBus bus;
    private final ModuleRegistry modules;
    private final Platform platform;

    private final List<Action> actions = new ArrayList<>();

    /** Hold binds that went down and are waiting for their key or button to come up. */
    @Getter(AccessLevel.NONE)
    private final List<Held> held = new ArrayList<>();

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
        return register(actionName, defaultBind, body, NOTHING);
    }

    /**
     * Registers an action with a body for each end of a hold, such as a zoom that
     * lasts while the key is down. {@code onRelease} runs only when the bind is in
     * {@link BindMode#HOLD}; a press bind runs {@code onPress} alone.
     *
     * @return the action's bind setting, so the caller can persist it in a config section
     */
    public BindSetting register(String actionName, Bind defaultBind, Runnable onPress, Runnable onRelease) {
        Validate.notNull(onPress, "press body of action " + actionName);
        Validate.notNull(onRelease, "release body of action " + actionName);
        BindSetting setting = new BindSetting(actionName, defaultBind);
        actions.add(new Action(actionName, setting, onPress, onRelease));
        return setting;
    }

    /**
     * Ends every hold at once, as if each held key had come up: hold modules
     * switch off and hold actions run their release body.
     */
    public void releaseAll() {
        List<Held> ending = new ArrayList<>(held);
        held.clear();
        for (Held hold : ending) {
            hold.release.run();
        }
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

    @Subscribe(priority = Priority.LOW, receiveCancelled = true)
    private void onKey(KeyEvent event) {
        if (event.isReleased()) {
            release(event.getKey(), MouseButton.NONE);
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (capturing != null) {
            // Escape clears the bind rather than binding Escape, which no user wants.
            if (event.getKey() == Key.ESCAPE) {
                capturing.clear();
            } else {
                capturing.bindTo(event.getKey(), toArray(event.getModifiers()));
            }
            capturing = null;
            event.cancel();
            return;
        }
        // Modifier keys alone never trigger a bind; they only qualify one.
        if (event.getKey().isModifier()) {
            return;
        }
        fireMatching(bind -> bind.matches(event.getKey(), event.getModifiers()), event.getKey(), MouseButton.NONE);
    }

    @Subscribe(priority = Priority.LOW, receiveCancelled = true)
    private void onMouse(MouseEvent event) {
        if (event.isReleased()) {
            release(Key.NONE, event.getButton());
            return;
        }
        if (event.isCancelled() || capturing != null) {
            return;
        }
        // Left and right click are the game's own; binding them would make the client unusable.
        if (event.getButton() == MouseButton.LEFT || event.getButton() == MouseButton.RIGHT) {
            return;
        }
        fireMatching(bind -> bind.matches(event.getButton(), event.getModifiers()), Key.NONE, event.getButton());
    }

    private void fireMatching(java.util.function.Predicate<Bind> matcher, Key key, MouseButton button) {
        for (Action action : actions) {
            Bind bind = action.binding.get();
            if (!matcher.test(bind)) {
                continue;
            }
            if (!bind.isHold()) {
                action.body.run();
            } else if (hold(action, key, button, action.release)) {
                action.body.run();
            }
        }
        // Binds only toggle modules in-game, so a menu keystroke cannot silently
        // flip state the player cannot see.
        if (!platform.isInGame()) {
            return;
        }
        for (Module module : modules.all()) {
            BindSetting toggle = module.getToggleBind();
            if (toggle == null || !matcher.test(toggle.get())) {
                continue;
            }
            if (!toggle.isHold()) {
                module.toggle();
            } else if (hold(module, key, button, module::disable)) {
                module.enable();
            }
        }
    }

    /**
     * Remembers that {@code owner} is held by this key or button.
     *
     * @return false if it already was, as when the game repeats a held key's press
     */
    private boolean hold(Object owner, Key key, MouseButton button, Runnable release) {
        for (Held hold : held) {
            if (hold.owner == owner && hold.key == key && hold.button == button) {
                return false;
            }
        }
        held.add(new Held(owner, key, button, release));
        return true;
    }

    private void release(Key key, MouseButton button) {
        List<Held> ending = new ArrayList<>();
        for (Iterator<Held> it = held.iterator(); it.hasNext(); ) {
            Held hold = it.next();
            boolean matches = key.isBound() ? hold.key == key : hold.button == button;
            if (matches) {
                it.remove();
                ending.add(hold);
            }
        }
        for (Held hold : ending) {
            hold.release.run();
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

        /** Runs when a hold bind comes up. Does nothing unless given. */
        private final Runnable release;
    }

    /** A hold in progress: who is held, by what, and how it ends. */
    @RequiredArgsConstructor
    private static final class Held {

        private final Object owner;
        private final Key key;
        private final MouseButton button;
        private final Runnable release;
    }

    private static final Runnable NOTHING = () -> { };
}
