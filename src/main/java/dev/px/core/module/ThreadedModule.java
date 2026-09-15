package dev.px.core.module;

import dev.px.core.concurrent.ThreadService;

import java.util.concurrent.Future;

/**
 * A module with work that must not run on the game thread: network calls, file
 * scans, anything that would otherwise stutter the frame.
 *
 * <p>The background task starts when the module is enabled and is cancelled when
 * it is disabled, so a subclass only implements {@link #runInBackground()} and
 * never touches an executor.
 *
 * <p>The task runs off-thread, so treat any game state it reads as a snapshot
 * and push results back through a field the game-thread handlers read.
 */
public abstract class ThreadedModule extends Module {

    /** Injected by Core at startup so subclasses need no constructor wiring. */
    private static volatile ThreadService threads;

    private Future<?> task;

    public static void bindThreadService(ThreadService service) {
        threads = service;
    }

    /**
     * The background body.
     *
     * <p>Loop here if the work is continuous, and honour interruption:
     * {@code while (!Thread.currentThread().isInterrupted()) { ... }}. Returning
     * ends the task until the next enable.
     */
    protected abstract void runInBackground();

    @Override
    protected void onEnable() {
        ThreadService service = threads;
        if (service != null) {
            task = service.submit(this::runInBackground);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel(true);
            task = null;
        }
    }

    /** @return whether the background task is currently running. */
    protected final boolean isTaskRunning() {
        return task != null && !task.isDone();
    }
}
