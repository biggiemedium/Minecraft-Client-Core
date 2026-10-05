package dev.px.core.concurrent;

import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import lombok.Getter;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client's background threading.
 *
 * <p>Everything off the game thread goes through here so shutdown has a single
 * place to wait on, and so a stray task cannot keep the JVM alive: every thread
 * is a daemon and is named, which also makes it identifiable in a profiler or a
 * crash report.
 *
 * <h2>Three tiers, because the work is three shapes</h2>
 *
 * <p>A single pool cannot serve all three. Putting a module's {@code while(true)}
 * loop on the same fixed pool as a one-second timer means the loop occupies a
 * thread forever and the timer silently stops firing, which is the worst kind of
 * failure: nothing throws and nothing is logged.
 *
 * <ul>
 *   <li>{@link #submit} &mdash; one-shot work that finishes: a login, an update
 *       check, a file scan. A pool of {@link #getPoolSize()} threads that time
 *       out when idle, so a client that never submits anything pays nothing.
 *   <li>{@link #schedule} and {@link #repeat} &mdash; timers, on their own small
 *       pool that the tier above cannot starve. Keep these bodies short; if one
 *       needs to block, have it {@link #submit} the blocking part.
 *   <li>{@link #loop} &mdash; work that runs until switched off, on a dedicated
 *       thread of its own. Costs one thread per active loop and cannot affect
 *       anything else.
 * </ul>
 *
 * <h2>Getting back to the game thread</h2>
 *
 * <p>Background work must not touch game state. {@link #sync} queues a
 * {@link Runnable} for the game thread and {@link #runPendingSync()} drains the
 * queue there. {@link dev.px.core.Core} wires the drain to
 * {@link dev.px.core.event.impl.TickEvent}, so posting ticks is all an adapter
 * has to do:
 *
 * <pre>{@code
 * Core.threads().submit(() -> {
 *     String latest = Http.getOrNull(VERSION_URL);          // worker thread
 *     Core.threads().sync(() -> {                           // game thread
 *         Core.notifications().info("Update", latest + " is available");
 *     });
 * });
 * }</pre>
 *
 * <h2>Failure and lifecycle</h2>
 *
 * <p>A task that throws is logged rather than vanishing into a {@link Future}
 * nobody checks, which is how background failures go unnoticed for weeks. It is
 * then rethrown, so the {@code Future} still reports the failure to a caller who
 * does check &mdash; except for {@link #repeat}, where swallowing is the point:
 * a raw {@code scheduleWithFixedDelay} cancels all remaining runs the first time
 * its body throws.
 *
 * <p>Work requested before {@link #start()} or after {@link #stop()} is dropped
 * with one warning and an already-finished handle, never a
 * {@link NullPointerException}.
 */
public final class ThreadService implements Service {

    /** Worker threads for {@link #submit} when the client does not say otherwise. */
    public static final int DEFAULT_POOL_SIZE = 3;

    /**
     * Threads for {@link #schedule} and {@link #repeat}.
     *
     * <p>Two is enough because timer bodies are meant to be short, and keeping it
     * separate from the worker pool is the whole point: a blocking {@code submit}
     * cannot delay a timer, and a slow timer cannot delay another.
     */
    private static final int TIMER_POOL_SIZE = 2;

    /** How long in-flight work gets to finish on its own before being interrupted. */
    private static final long SHUTDOWN_GRACE_MILLIS = 1_500L;

    /** How long it then gets to respond to that interruption. */
    private static final long SHUTDOWN_HARD_MILLIS = 500L;

    /** Idle worker threads are released after this long. */
    private static final long WORKER_KEEPALIVE_SECONDS = 30L;

    /** A backlog this deep means nobody is draining {@link #sync}. */
    private static final int SYNC_BACKLOG_WARN = 512;

    private final CoreLogger logger;

    /** Worker threads available to {@link #submit}. Timers and loops are extra. */
    @Getter
    private final int poolSize;

    // Both are replaced on start and cleared on stop, and are read from every
    // thread that submits work, so both must be volatile: a plain field gives a
    // worker no guarantee of ever seeing either write.
    private volatile ThreadPoolExecutor workers;
    private volatile ScheduledThreadPoolExecutor timers;

    // Fields rather than locals, so a start/stop/start cycle keeps issuing fresh
    // numbers instead of a second "core-worker-1" alongside a surviving one.
    private final AtomicInteger workerNumber = new AtomicInteger(1);
    private final AtomicInteger timerNumber = new AtomicInteger(1);
    private final AtomicInteger loopNumber = new AtomicInteger(1);

    private final List<LoopHandle> loops = new CopyOnWriteArrayList<>();

    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

    /** Kept alongside {@link #pending} because {@code ConcurrentLinkedQueue.size()} is O(n). */
    private final AtomicInteger pendingCount = new AtomicInteger();

    private final AtomicBoolean warnedDown = new AtomicBoolean();
    private final AtomicBoolean warnedBacklog = new AtomicBoolean();

    private volatile Thread gameThread;

    public ThreadService(CoreLogger logger) {
        this(logger, DEFAULT_POOL_SIZE);
    }

    public ThreadService(CoreLogger logger, int poolSize) {
        this.logger = Validate.notNull(logger, "logger");
        Validate.check(poolSize >= 1, "thread pool size must be at least 1, got " + poolSize);
        this.poolSize = poolSize;
    }

    @Override
    public String getName() {
        return "Threads";
    }

    // ----------------------------------------------------------- lifecycle

    /** Brings both pools up. Calling this twice is a no-op rather than a leak. */
    @Override
    public synchronized void start() {
        if (workers != null) {
            return;
        }
        warnedDown.set(false);
        warnedBacklog.set(false);

        ScheduledThreadPoolExecutor timerPool =
                new ScheduledThreadPoolExecutor(TIMER_POOL_SIZE, factory("core-timer-", timerNumber));
        // Without this a cancelled schedule/repeat sits in the delay queue until
        // its delay elapses, holding everything its body captured.
        timerPool.setRemoveOnCancelPolicy(true);

        ThreadPoolExecutor workerPool = new ThreadPoolExecutor(
                poolSize, poolSize,
                WORKER_KEEPALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                factory("core-worker-", workerNumber));
        workerPool.allowCoreThreadTimeOut(true);

        timers = timerPool;
        workers = workerPool;
    }

    /**
     * Stops accepting work and winds down what is already running.
     *
     * <p>Two phases. Running tasks are first left alone for
     * {@value #SHUTDOWN_GRACE_MILLIS}ms, because a config half-written to disk or
     * a request mid-flight is worth waiting for; only then are the stragglers
     * interrupted. Going straight to {@code shutdownNow()} kills both.
     */
    @Override
    public synchronized void stop() {
        // Loops first: they are the tasks most likely to be parked in a blocking
        // call, so interrupting them before the wait starts gives them the whole
        // grace window rather than whatever is left of it.
        List<LoopHandle> running = new ArrayList<>(loops);
        loops.clear();
        for (LoopHandle loop : running) {
            loop.cancel();
        }

        ThreadPoolExecutor workerPool = workers;
        ScheduledThreadPoolExecutor timerPool = timers;
        workers = null;
        timers = null;

        pending.clear();
        pendingCount.set(0);
        gameThread = null;

        if (timerPool != null) {
            timerPool.shutdown();
        }
        if (workerPool != null) {
            workerPool.shutdown();
        }

        if (!await(timerPool, workerPool, SHUTDOWN_GRACE_MILLIS)) {
            List<Runnable> never = new ArrayList<>();
            if (timerPool != null) {
                never.addAll(timerPool.shutdownNow());
            }
            if (workerPool != null) {
                never.addAll(workerPool.shutdownNow());
            }
            if (!never.isEmpty()) {
                logger.debug("Discarded " + never.size() + " background task(s) that never started");
            }
            if (!await(timerPool, workerPool, SHUTDOWN_HARD_MILLIS)) {
                logger.warn("Background tasks did not respond to interruption; abandoning them");
            }
        }

        joinAll(running, SHUTDOWN_HARD_MILLIS);
    }

    /** @return whether work can currently be accepted. */
    public boolean isRunning() {
        ThreadPoolExecutor pool = workers;
        return pool != null && !pool.isShutdown();
    }

    // -------------------------------------------------------- one-shot work

    /**
     * Runs {@code task} once, off the game thread.
     *
     * <p>For work that finishes. A body that loops until cancelled belongs on
     * {@link #loop} instead, or it occupies one of {@link #getPoolSize()} worker
     * threads for as long as it runs.
     */
    public Future<?> submit(Runnable task) {
        Validate.notNull(task, "task");
        ThreadPoolExecutor pool = workers;
        if (pool == null) {
            return dropped("submit");
        }
        try {
            return pool.submit(reporting(task));
        } catch (RejectedExecutionException e) {
            return dropped("submit");
        }
    }

    /**
     * Runs {@code task} once, off the game thread, for a result.
     *
     * <p>The value arrives through the returned {@link Future}, and so does a
     * failure: {@code get()} throws {@link java.util.concurrent.ExecutionException}
     * as it should, having already logged the cause.
     */
    public <T> Future<T> submit(Callable<T> task) {
        Validate.notNull(task, "task");
        ThreadPoolExecutor pool = workers;
        if (pool == null) {
            return dropped("submit");
        }
        try {
            return pool.submit(reporting(task));
        } catch (RejectedExecutionException e) {
            return dropped("submit");
        }
    }

    // ------------------------------------------------------------- timers

    /** Runs {@code task} once after a delay, on the timer pool. */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        Validate.notNull(task, "task");
        ScheduledThreadPoolExecutor pool = timers;
        if (pool == null) {
            return droppedScheduled("schedule");
        }
        try {
            return pool.schedule(reporting(task), delay, unit);
        } catch (RejectedExecutionException e) {
            return droppedScheduled("schedule");
        }
    }

    /**
     * Runs {@code task} repeatedly until the returned future is cancelled.
     *
     * <p>Fixed-delay rather than fixed-rate: if one run overruns the period,
     * fixed-rate fires the backlog all at once, which is exactly the wrong
     * behaviour for polling a web API or a file.
     *
     * <p>A run that throws is logged and the schedule continues. That is the one
     * place this class swallows an exception on purpose &mdash; a raw
     * {@code scheduleWithFixedDelay} cancels every remaining run the first time
     * its body throws, so one transient network blip stops the poll for the rest
     * of the session.
     */
    public ScheduledFuture<?> repeat(Runnable task, long initialDelay, long period, TimeUnit unit) {
        Validate.notNull(task, "task");
        ScheduledThreadPoolExecutor pool = timers;
        if (pool == null) {
            return droppedScheduled("repeat");
        }
        try {
            return pool.scheduleWithFixedDelay(surviving(task), initialDelay, period, unit);
        } catch (RejectedExecutionException e) {
            return droppedScheduled("repeat");
        }
    }

    // -------------------------------------------------------- long-running

    /**
     * Runs {@code body} on a dedicated daemon thread until it returns or the
     * returned handle is cancelled.
     *
     * <p>This is the tier for a body shaped like
     * {@code while (!Thread.currentThread().isInterrupted()) { ... }}. It gets a
     * thread of its own rather than one of the shared workers, so any number of
     * loops can run without starving timers or one-shot work.
     *
     * @param label a short name for the thread, e.g. the module's name
     */
    public TaskHandle loop(String label, Runnable body) {
        Validate.notNull(body, "loop body");
        if (workers == null) {
            warnDown("loop");
            return TaskHandle.NONE;
        }
        LoopHandle handle = new LoopHandle(label == null || label.trim().isEmpty() ? "task" : label.trim(), body);
        loops.add(handle);
        handle.begin();
        return handle;
    }

    /** @return how many {@link #loop} bodies are currently executing. */
    public int getActiveLoopCount() {
        int active = 0;
        for (LoopHandle loop : loops) {
            if (loop.isRunning()) {
                active++;
            }
        }
        return active;
    }

    // --------------------------------------------------------- game thread

    /**
     * Records the calling thread as the game thread.
     *
     * <p>Called by {@link dev.px.core.Core#start()}, which an adapter invokes
     * from the game thread. Only {@link #isGameThread()} depends on it.
     */
    public void markGameThread() {
        gameThread = Thread.currentThread();
    }

    /**
     * @return whether the caller is on the thread {@link #markGameThread()} recorded.
     *
     * <p>False when no thread has been recorded, rather than true: claiming to be
     * on a thread nobody identified would send game-state mutations straight to a
     * worker, and the safe answer to "am I allowed to touch the world?" when the
     * answer is unknown is no.
     */
    public boolean isGameThread() {
        Thread marked = gameThread;
        return marked != null && marked == Thread.currentThread();
    }

    /**
     * Queues {@code task} to run on the game thread.
     *
     * <p>The way background work publishes a result that touches game state.
     * Tasks run in the order they were queued, on the next drain, even when
     * called from the game thread already &mdash; running inline would mean a
     * task could execute in the middle of another one's drain, and the ordering
     * would depend on which thread the caller happened to be.
     */
    public void sync(Runnable task) {
        Validate.notNull(task, "task");
        pending.add(task);
        if (pendingCount.incrementAndGet() >= SYNC_BACKLOG_WARN && warnedBacklog.compareAndSet(false, true)) {
            logger.warn("Over " + SYNC_BACKLOG_WARN + " tasks are waiting for the game thread;"
                    + " nothing is calling ThreadService.runPendingSync()."
                    + " Post TickEvent, or drain it yourself from the game loop.");
        }
    }

    /**
     * Runs everything {@link #sync} has queued. Call from the game thread.
     *
     * <p>Bounded to the backlog present on entry, so a task that queues another
     * cannot spin the game thread inside one tick; the new task runs on the next
     * drain. A task that throws is logged and the rest still run, matching how
     * the event bus treats a broken handler.
     *
     * @return how many tasks ran
     */
    public int runPendingSync() {
        return runPendingSync(pendingCount.get());
    }

    /**
     * Runs at most {@code limit} queued tasks. For a game loop that would rather
     * spread a large backlog over several ticks than spend one long frame on it.
     *
     * @return how many tasks ran
     */
    public int runPendingSync(int limit) {
        int ran = 0;
        while (ran < limit) {
            Runnable task = pending.poll();
            if (task == null) {
                break;
            }
            pendingCount.decrementAndGet();
            ran++;
            try {
                task.run();
            } catch (RuntimeException thrown) {
                // One broken callback must not cost the frame that drained it.
                logger.error("Game-thread task failed", thrown);
            }
        }
        return ran;
    }

    /** @return how many tasks are waiting for the game thread. */
    public int getPendingSyncCount() {
        return pendingCount.get();
    }

    // ------------------------------------------------------------ internals

    /**
     * Logs a failure and rethrows it, so the {@link Future} stays truthful for a
     * caller who checks it while the failure is still reported for one who does not.
     */
    private Runnable reporting(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException | Error thrown) {
                if (!isCancellation(thrown)) {
                    logger.error("Background task failed", thrown);
                }
                throw thrown;
            }
        };
    }

    private <T> Callable<T> reporting(Callable<T> task) {
        return () -> {
            try {
                return task.call();
            } catch (Exception | Error thrown) {
                if (!isCancellation(thrown)) {
                    logger.error("Background task failed", thrown);
                }
                throw thrown;
            }
        };
    }

    /**
     * Logs a failure and swallows it, so a repeating schedule survives one bad run.
     *
     * <p>{@link Error} is deliberately not caught: a repeating task is not worth
     * keeping alive through an {@link OutOfMemoryError}, and swallowing one leaves
     * the JVM running in a state nothing can reason about.
     */
    private Runnable surviving(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException thrown) {
                if (!isCancellation(thrown)) {
                    logger.error("Repeating task failed; it will run again", thrown);
                }
            }
        };
    }

    /**
     * @return whether {@code thrown} is how cancellation looks, rather than a fault.
     *
     * <p>The interrupt flag alone is not enough to tell the difference:
     * {@code Thread.sleep} and {@code Object.wait} <em>clear</em> it when they
     * throw, so the commonest loop body of all &mdash; poll, sleep, rethrow the
     * {@code InterruptedException} wrapped &mdash; arrives here with an innocent
     * {@link RuntimeException} and a clean flag. Reading it as a fault logs a
     * stack trace every time the user switches the module off.
     */
    private static boolean isCancellation(Throwable thrown) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable cause = thrown;
        // Bounded: a cause chain can be circular, and a self-referential one is
        // not worth hanging the worker over.
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (cause instanceof InterruptedException
                    || cause instanceof InterruptedIOException
                    || cause instanceof ClosedByInterruptException
                    || cause instanceof CancellationException
                    || cause instanceof RejectedExecutionException) {
                return true;
            }
            Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return false;
    }

    private ThreadFactory factory(String prefix, AtomicInteger counter) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            // A hint, not a guarantee: most platforms treat Java thread priority
            // loosely or ignore it outright. It costs nothing and occasionally helps.
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setUncaughtExceptionHandler((died, thrown) ->
                    logger.error("Thread " + died.getName() + " died", thrown));
            return thread;
        };
    }

    /** One warning per start cycle, so a module toggled after shutdown does not spam the log. */
    private void warnDown(String what) {
        if (warnedDown.compareAndSet(false, true)) {
            logger.warn("ThreadService is not running; dropping " + what + "(...)."
                    + " Work was requested before start() or after stop().");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Future<T> dropped(String what) {
        warnDown(what);
        return (Future<T>) (Future<?>) DroppedTask.INSTANCE;
    }

    private ScheduledFuture<?> droppedScheduled(String what) {
        warnDown(what);
        return DroppedTask.INSTANCE;
    }

    private static boolean await(ExecutorService first, ExecutorService second, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        return awaitOne(first, deadline) && awaitOne(second, deadline);
    }

    private static boolean awaitOne(ExecutorService service, long deadline) {
        if (service == null) {
            return true;
        }
        long remaining = Math.max(0L, deadline - System.currentTimeMillis());
        try {
            return service.awaitTermination(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Joins loop threads against one shared budget rather than giving each the full one. */
    private void joinAll(List<LoopHandle> handles, long budgetMillis) {
        long deadline = System.currentTimeMillis() + budgetMillis;
        for (LoopHandle handle : handles) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                break;
            }
            handle.join(remaining);
        }
        for (LoopHandle handle : handles) {
            if (handle.isRunning()) {
                logger.warn("Background loop " + handle.label + " ignored interruption; abandoning it");
            }
        }
    }

    /** A {@link #loop} body and the thread running it. */
    private final class LoopHandle implements TaskHandle {

        private final String label;
        private final Runnable body;

        private volatile Thread thread;
        private volatile boolean cancelled;
        private volatile boolean finished;

        private LoopHandle(String label, Runnable body) {
            this.label = label;
            this.body = body;
        }

        /** Separate from the constructor so the thread never sees a half-built handle. */
        private void begin() {
            Thread worker = new Thread(this::run, "core-loop-" + loopNumber.getAndIncrement() + "-" + label);
            worker.setDaemon(true);
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            thread = worker;
            worker.start();
        }

        private void run() {
            try {
                body.run();
            } catch (RuntimeException | Error thrown) {
                if (!cancelled && !isCancellation(thrown)) {
                    logger.error("Background loop " + label + " failed", thrown);
                }
            } finally {
                finished = true;
                loops.remove(this);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Thread worker = thread;
            if (worker != null) {
                worker.interrupt();
            }
            loops.remove(this);
        }

        @Override
        public boolean isRunning() {
            Thread worker = thread;
            return !finished && worker != null && worker.isAlive();
        }

        private void join(long millis) {
            Thread worker = thread;
            if (worker == null) {
                return;
            }
            try {
                worker.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * What {@link #submit} and friends return when the service is down.
     *
     * <p>Reports itself cancelled and done, so the usual
     * {@code future.isDone()} / {@code future.cancel()} handling works without a
     * null check and without a lifecycle mistake surfacing as a
     * {@link NullPointerException} three call frames away.
     */
    private static final class DroppedTask implements ScheduledFuture<Object> {

        private static final DroppedTask INSTANCE = new DroppedTask();

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return other == this ? 0 : -1;
        }

        @Override
        public boolean cancel(boolean interruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            throw new CancellationException("ThreadService was not running; the task never ran");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new CancellationException("ThreadService was not running; the task never ran");
        }
    }
}
