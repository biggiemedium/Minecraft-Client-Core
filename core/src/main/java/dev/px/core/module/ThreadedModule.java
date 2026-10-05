package dev.px.core.module;

import dev.px.core.concurrent.TaskHandle;
import dev.px.core.concurrent.ThreadService;

/**
 * A module with work that must not run on the game thread: network calls, file
 * scans, anything that would otherwise stutter the frame.
 *
 * <p>The background task starts when the module is enabled and is cancelled when
 * it is disabled, so a subclass only implements {@link #runInBackground()} and
 * never touches an executor.
 *
 * <p>The body gets a dedicated thread through {@link ThreadService#loop}, not one
 * of the shared workers, so a loop here cannot starve timers, one-shot work or
 * another threaded module however long it runs. Any number can be enabled at once.
 *
 * <p>The task runs off-thread, so treat any game state it reads as a snapshot and
 * publish results either through a {@code volatile} field the game-thread
 * handlers read, or by handing a {@link Runnable} to
 * {@link ThreadService#sync(Runnable)}:
 *
 * <pre>{@code
 * @Override
 * protected void runInBackground() {
 *     while (!Thread.currentThread().isInterrupted()) {
 *         List<String> found = scan();
 *         Core.threads().sync(() -> results = found);   // game thread
 *         try {
 *             Thread.sleep(500L);
 *         } catch (InterruptedException e) {
 *             return;                                   // disabled; stop
 *         }
 *     }
 * }
 * }</pre>
 */
public abstract class ThreadedModule extends Module {

    /** Injected by Core at startup so subclasses need no constructor wiring. */
    private static volatile ThreadService threads;

    private TaskHandle task = TaskHandle.NONE;

    public static void bindThreadService(ThreadService service) {
        threads = service;
    }

    /**
     * The background body.
     *
     * <p>Loop here if the work is continuous, and honour interruption:
     * {@code while (!Thread.currentThread().isInterrupted()) { ... }}, and return
     * from any {@code InterruptedException} rather than continuing. Returning ends
     * the task until the next enable.
     */
    protected abstract void runInBackground();

    @Override
    protected void onEnable() {
        ThreadService service = threads;
        if (service == null) {
            // Quietly doing nothing here would leave the module reporting itself
            // enabled with its body never running, and nothing in the log to say
            // so. Core binds the service in its constructor, so reaching this
            // means the module was enabled without a Core at all.
            throw new IllegalStateException(getName() + " is a ThreadedModule, but no ThreadService"
                    + " is bound; build Core before enabling it");
        }
        task = service.loop(getName(), this::runInBackground);
    }

    @Override
    protected void onDisable() {
        task.cancel();
        task = TaskHandle.NONE;
    }

    /** @return whether the background task is currently running. */
    protected final boolean isTaskRunning() {
        return task.isRunning();
    }
}
