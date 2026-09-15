package dev.px.core.concurrent;

import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client's background thread pool.
 *
 * <p>Everything off the game thread goes through here so shutdown has a single
 * place to wait on, and so a stray task cannot keep the JVM alive: the threads
 * are daemons and named, which also makes them identifiable in a profiler or a
 * crash report.
 *
 * <p>A task that throws is logged rather than vanishing. A raw
 * {@code ExecutorService} swallows exceptions into the {@link Future} nobody
 * checks, which is how background failures go unnoticed for weeks.
 */
@Getter
@RequiredArgsConstructor
public final class ThreadService implements Service {

    private final String name = "Threads";

    private final CoreLogger logger;
    private final int poolSize;

    private ScheduledExecutorService executor;

    public ThreadService(CoreLogger logger) {
        this(logger, 3);
    }

    @Override
    public void start() {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "core-worker-" + counter.getAndIncrement());
            thread.setDaemon(true);
            // Below normal: background work must never compete with the render thread.
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        executor = Executors.newScheduledThreadPool(poolSize, factory);
    }

    @Override
    public void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.warn("Background tasks did not finish within 2s; abandoning them");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    /** Runs {@code task} once, off the game thread. */
    public Future<?> submit(Runnable task) {
        return executor.submit(wrap(task));
    }

    /** Runs {@code task} once after a delay. */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return executor.schedule(wrap(task), delay, unit);
    }

    /**
     * Runs {@code task} repeatedly.
     *
     * <p>Uses fixed-delay rather than fixed-rate scheduling: if one run overruns
     * the period, fixed-rate fires the backlog all at once, which is exactly the
     * wrong behaviour for polling a web API or a file.
     */
    public ScheduledFuture<?> repeat(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return executor.scheduleWithFixedDelay(wrap(task), initialDelay, period, unit);
    }

    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable thrown) {
                // InterruptedException on cancel is expected; do not report it as a fault.
                if (!(thrown instanceof InterruptedException) && !Thread.currentThread().isInterrupted()) {
                    logger.error("Background task failed", thrown);
                }
            }
        };
    }
}
