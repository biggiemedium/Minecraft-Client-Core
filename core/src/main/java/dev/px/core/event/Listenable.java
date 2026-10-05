package dev.px.core.event;

/**
 * Lets an object gate its own handlers without subscribing and unsubscribing.
 *
 * <p>{@link dev.px.core.module.Module} implements this as its enabled flag, so a
 * module is subscribed once at registration and its handlers simply go quiet
 * while it is off. That keeps handler order stable across toggles and avoids
 * the churn of re-scanning a class every time it is switched on.
 *
 * <p>Handlers annotated {@code @Subscribe(ignoreListening = true)} bypass this.
 */
public interface Listenable {

    /** @return whether this object's handlers should currently receive events. */
    boolean isListening();
}
