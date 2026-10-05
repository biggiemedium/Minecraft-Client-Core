package dev.px.core.concurrent;

/**
 * A cancellable piece of long-running background work.
 *
 * <p>Returned by {@link ThreadService#loop}, which is the API for work that
 * continues until it is switched off: a polling loop, a watcher, anything whose
 * body is {@code while (!Thread.currentThread().isInterrupted())}. A
 * {@link java.util.concurrent.Future} is the wrong shape for that &mdash; it
 * describes a computation that finishes and produces something, and a loop never
 * does either.
 *
 * <p>Cancellation interrupts the thread running the body, so a loop that blocks
 * in {@code Thread.sleep} or on a socket wakes up rather than running to the end
 * of its current pass. Bodies must therefore treat interruption as the signal to
 * return.
 */
public interface TaskHandle {

    /**
     * A handle to nothing, already finished.
     *
     * <p>Returned instead of null when work is requested from a
     * {@link ThreadService} that is not running, so callers never have to
     * null-check and never see a {@link NullPointerException} from a lifecycle
     * mistake.
     */
    TaskHandle NONE = new TaskHandle() {

        @Override
        public void cancel() {
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    };

    /**
     * Interrupts the work and stops it being restarted. Doing this twice, or to
     * work that already finished, is harmless.
     *
     * <p>Returns as soon as the interrupt is delivered rather than waiting for
     * the body to notice, so a slow loop does not block the caller &mdash; which
     * is usually the game thread, inside a module's {@code onDisable}.
     */
    void cancel();

    /** @return whether the body is still executing. */
    boolean isRunning();
}
