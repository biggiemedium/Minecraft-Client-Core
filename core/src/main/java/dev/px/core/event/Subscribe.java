package dev.px.core.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler.
 *
 * <p>The method must take exactly one parameter, and that parameter's type is
 * the event it receives &mdash; there is no type token to keep in sync. Handlers
 * may have any visibility and any name.
 *
 * <pre>{@code
 * @Subscribe(priority = Priority.HIGH, stage = Stage.PRE)
 * private void onMotion(PlayerMotionEvent event) {
 *     event.setYaw(rotation.getYaw());
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {

    /** Higher runs first. See {@link Priority} for the conventional values. */
    int priority() default Priority.NORMAL;

    /** Restricts the handler to one stage of a {@link StagedEvent}. */
    Stage stage() default Stage.ANY;

    /** Whether to still run once another handler has cancelled the event. */
    boolean receiveCancelled() default false;

    /**
     * Whether to run even while the owning {@link Listenable} reports that it is
     * not listening &mdash; for a {@link dev.px.core.module.Module}, that means
     * while it is disabled.
     *
     * <p>Use it for handlers that must observe the game regardless of toggle
     * state, such as a keybind watcher or a statistic counter.
     */
    boolean ignoreListening() default false;
}
