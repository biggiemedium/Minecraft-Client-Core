package dev.px.core.module;

import dev.px.core.event.Listenable;
import dev.px.core.setting.SettingHolder;
import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * A named, describable thing that can be switched on and off and owns settings.
 *
 * <p>Shared by {@link Module} and by HUD elements. The old client had this class
 * too, but both subclasses redeclared {@code name}, {@code description} and
 * their accessors anyway, and {@code Module} kept a second settings list that
 * shadowed the inherited one so only one of the two was ever saved. Here the
 * state lives in exactly one place.
 *
 * <p>Implementing {@link Listenable} is what lets an instance stay subscribed to
 * the event bus permanently while its handlers go quiet when it is off.
 *
 * <p>Instances are always constructed disabled. A subclass that is enabled by
 * default is switched on by its registry once everything is up, so the
 * {@link #onEnable()} hook never runs against a half-built client.
 */
@Getter
public abstract class Toggleable extends SettingHolder implements Listenable {

    private String name = "";
    private String description = "";
    private boolean enabled;

    /**
     * Subclasses that know their identity at construction pass it here. Those
     * that read it from an annotation use the no-arg form and call
     * {@link #identify}, since {@code getClass()} is only usable once
     * {@code super()} has returned.
     */
    protected Toggleable(String name, String description) {
        identify(name, description);
    }

    protected Toggleable() {
    }

    protected final void identify(String newName, String newDescription) {
        this.name = Validate.notBlank(newName, "name of " + getClass().getName());
        this.description = newDescription == null ? "" : newDescription;
    }

    /**
     * Switches state and fires the matching lifecycle hook.
     *
     * <p>Setting the state it already holds does nothing, so a config load that
     * re-asserts the current value will not run {@link #onEnable()} twice.
     */
    public final void setEnabled(boolean shouldEnable) {
        if (this.enabled == shouldEnable) {
            return;
        }
        this.enabled = shouldEnable;
        onStateChanged(shouldEnable);
        if (shouldEnable) {
            onEnable();
        } else {
            onDisable();
        }
    }

    public final void toggle() {
        setEnabled(!enabled);
    }

    public final void enable() {
        setEnabled(true);
    }

    public final void disable() {
        setEnabled(false);
    }

    /** Called when switched on. Override to acquire state. */
    protected void onEnable() {
    }

    /** Called when switched off. Override to release state. */
    protected void onDisable() {
    }

    /**
     * Called before the hooks, on every state change.
     *
     * <p>Base-class plumbing such as posting a toggle event goes here rather than
     * in {@link #onEnable()}, so a concrete subclass that overrides the hooks
     * cannot skip it by forgetting {@code super}.
     */
    protected void onStateChanged(boolean nowEnabled) {
    }

    @Override
    public boolean isListening() {
        return enabled;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + name + ", " + (enabled ? "on" : "off") + ")";
    }
}
