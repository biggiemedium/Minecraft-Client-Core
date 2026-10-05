package dev.px.core.test.suite;

import dev.px.core.concurrent.TaskHandle;
import dev.px.core.concurrent.ThreadService;
import dev.px.core.event.Stage;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ThreadService}: the three tiers, the lifecycle, and the game-thread queue.
 *
 * <p>Each group here corresponds to a defect that was in the single-pool version,
 * so a regression shows up as a named failure rather than as an intermittent one
 * in whatever suite happens to run next.
 */
public final class ConcurrentTests {

    /** Long enough for a worker to get scheduled, short enough not to pad the suite. */
    private static final long WAIT_MILLIS = 2_000L;

    private ConcurrentTests() {
    }

    public static void run(TestClient client) throws Exception {
        Checks.section("Threading");

        poolCannotBeStarvedByLoops();
        lifecycleDegradesInsteadOfThrowing();
        shutdownLetsWorkFinish();
        cancellationIsNotReportedAsFailure();
        theExecutorIsNotExposed();
        futuresTellTheTruth();
        repeatSurvivesAFailedRun();
        startIsIdempotentAndThreadsAreNamedFreshly();
        gameThreadQueue(client);
    }

    // ---- 1. loops must not occupy the worker pool ------------------------

    private static void poolCannotBeStarvedByLoops() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        // One worker thread: if loops ran on the pool, a single loop would consume
        // the whole service and everything below would time out.
        ThreadService threads = new ThreadService(logger, 1);
        threads.start();
        try {
            List<TaskHandle> handles = new ArrayList<>();
            CountDownLatch spinning = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                handles.add(threads.loop("spin" + i, () -> {
                    spinning.countDown();
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }));
            }
            Checks.check("four loops start on a one-worker service",
                    spinning.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
            Checks.checkEquals("every loop is running", 4, threads.getActiveLoopCount());

            CountDownLatch submitted = new CountDownLatch(1);
            threads.submit(submitted::countDown);
            Checks.check("one-shot work still runs while four loops are active",
                    submitted.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

            CountDownLatch ticked = new CountDownLatch(2);
            threads.repeat(ticked::countDown, 0, 30, TimeUnit.MILLISECONDS);
            Checks.check("timers still fire while four loops are active",
                    ticked.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

            for (TaskHandle handle : handles) {
                handle.cancel();
            }
            Checks.check("a cancelled loop stops", waitFor(() -> threads.getActiveLoopCount() == 0));
        } finally {
            threads.stop();
        }
        Checks.checkEquals("nothing was logged as a failure", 0, logger.errorCount());
    }

    // ---- 2. no NPE before start or after stop ----------------------------

    private static void lifecycleDegradesInsteadOfThrowing() {
        RecordingLogger logger = new RecordingLogger();
        ThreadService threads = new ThreadService(logger, 2);

        Checks.check("a service that was never started is not running", !threads.isRunning());
        Checks.checkSurvives("submit before start does not throw", () -> threads.submit(() -> {
        }));
        Checks.check("work before start is reported once", logger.loggedWarning("not running"));

        Future<?> beforeStart = threads.submit(() -> {
        });
        Checks.check("the returned future is already done", beforeStart.isDone());
        Checks.check("and reports itself cancelled", beforeStart.isCancelled());

        threads.start();
        Checks.check("the service is running once started", threads.isRunning());
        threads.stop();
        Checks.check("and not once stopped", !threads.isRunning());

        // The old version nulled a non-volatile field here, so every one of these
        // was a NullPointerException from whatever thread got there first.
        Checks.checkSurvives("submit after stop does not throw", () -> threads.submit(() -> {
        }));
        Checks.checkSurvives("schedule after stop does not throw",
                () -> threads.schedule(() -> {
                }, 1, TimeUnit.MILLISECONDS));
        Checks.checkSurvives("repeat after stop does not throw",
                () -> threads.repeat(() -> {
                }, 1, 1, TimeUnit.MILLISECONDS));
        Checks.checkSurvives("loop after stop does not throw", () -> threads.loop("dead", () -> {
        }));
        Checks.check("a loop requested after stop reports itself not running",
                !threads.loop("dead", () -> {
                }).isRunning());
        Checks.checkSurvives("stopping twice is harmless", threads::stop);
    }

    // ---- 3. in-flight work gets a grace period ---------------------------

    private static void shutdownLetsWorkFinish() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ThreadService threads = new ThreadService(logger, 2);
        threads.start();

        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        threads.submit(() -> {
            started.countDown();
            try {
                // Stands in for a config being written or a request in flight.
                Thread.sleep(250L);
                finished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Checks.check("the task reached the worker", started.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

        threads.stop();
        // shutdownNow() as the first move interrupted this task mid-sleep and
        // finished stayed false.
        Checks.check("work already running is allowed to finish during shutdown", finished.get());
        Checks.check("a clean shutdown warns about nothing", !logger.loggedWarning("abandoning"));
    }

    // ---- 4. cancelling is not a failure ----------------------------------

    private static void cancellationIsNotReportedAsFailure() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ThreadService threads = new ThreadService(logger, 2);
        threads.start();
        try {
            // Exactly what the commonest loop body leaves behind when it is
            // cancelled: Thread.sleep throws InterruptedException and *clears* the
            // interrupt flag, the body rethrows it wrapped, and the handler sees a
            // plain RuntimeException on a thread that no longer looks interrupted.
            // Reading that as a fault logged an error on every module toggle.
            // Stated directly rather than through a real interrupt, so the check
            // does not depend on timing.
            Future<?> wrapped = threads.submit(() -> {
                throw new RuntimeException("interrupted", new InterruptedException("cancelled"));
            });
            awaitDone(wrapped);
            Checks.checkEquals("a wrapped InterruptedException is cancellation, not failure",
                    0, logger.errorCount());

            // And the same thing again through a real cancellation.
            CountDownLatch looping = new CountDownLatch(1);
            CountDownLatch unwound = new CountDownLatch(1);
            TaskHandle loop = threads.loop("sleeper", () -> {
                looping.countDown();
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    unwound.countDown();
                    throw new RuntimeException("interrupted", e);
                }
            });
            Checks.check("the loop is sleeping", looping.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
            loop.cancel();
            Checks.check("the interrupt reaches the loop body",
                    unwound.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
            Checks.check("a cancelled loop stops running", waitFor(() -> !loop.isRunning()));
            Checks.checkEquals("cancelling a loop logs nothing", 0, logger.errorCount());

            // The other half of the contract: suppression must not be so broad
            // that a genuine fault goes quiet.
            logger.clear();
            threads.loop("broken", () -> {
                throw new IllegalStateException("deliberate");
            });
            Checks.check("a loop that genuinely fails is still reported",
                    waitFor(() -> logger.loggedError("Background loop broken failed")));
        } finally {
            threads.stop();
        }
    }

    // ---- 5. the pool is not handed out ----------------------------------

    private static void theExecutorIsNotExposed() {
        List<String> leaked = new ArrayList<>();
        for (java.lang.reflect.Method method : ThreadService.class.getMethods()) {
            Class<?> returned = method.getReturnType();
            if (java.util.concurrent.ExecutorService.class.isAssignableFrom(returned)) {
                leaked.add(method.getName());
            }
        }
        // A public getExecutor() let any caller shut the pool down or submit work
        // that bypassed the exception handling this class exists to provide.
        Checks.checkEquals("no public method hands out the executor",
                "[]", leaked.toString());
    }

    // ---- 6. futures report what happened --------------------------------

    private static void futuresTellTheTruth() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ThreadService threads = new ThreadService(logger, 2);
        threads.start();
        try {
            Callable<String> work = () -> "done";
            Checks.checkEquals("submit(Callable) returns the value",
                    "done", threads.submit(work).get(WAIT_MILLIS, TimeUnit.MILLISECONDS));

            Future<?> failing = threads.submit(() -> {
                throw new IllegalStateException("deliberate");
            });
            boolean surfaced;
            try {
                failing.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                surfaced = false;
            } catch (ExecutionException e) {
                surfaced = e.getCause() instanceof IllegalStateException;
            }
            // Swallowing in the wrapper used to make every future look successful.
            Checks.check("a failed task surfaces through its future", surfaced);
            Checks.check("and is logged for the caller who does not check",
                    logger.loggedError("Background task failed"));
        } finally {
            threads.stop();
        }
    }

    // ---- repeat keeps going after a bad run ------------------------------

    private static void repeatSurvivesAFailedRun() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ThreadService threads = new ThreadService(logger, 2);
        threads.start();
        try {
            AtomicInteger runs = new AtomicInteger();
            threads.repeat(() -> {
                if (runs.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient");
                }
            }, 0, 25, TimeUnit.MILLISECONDS);
            // A raw scheduleWithFixedDelay stops for good after the first throw.
            Checks.check("a repeating task runs again after one bad run",
                    waitFor(() -> runs.get() >= 3));
            Checks.check("the bad run is logged", logger.loggedError("Repeating task failed"));
        } finally {
            threads.stop();
        }
    }

    // ---- 8. restart hygiene ---------------------------------------------

    private static void startIsIdempotentAndThreadsAreNamedFreshly() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ThreadService threads = new ThreadService(logger, 2);
        Checks.checkEquals("the pool size is what was asked for", 2, threads.getPoolSize());
        Checks.checkThrows("a pool size below one is refused",
                IllegalArgumentException.class, () -> new ThreadService(logger, 0));

        threads.start();
        List<String> first = nameOfWorkerThread(threads);
        Checks.checkSurvives("starting twice is a no-op", threads::start);
        Checks.check("the service is still running after a second start", threads.isRunning());

        threads.stop();
        threads.start();
        List<String> second = nameOfWorkerThread(threads);
        try {
            // A counter local to start() reissued core-worker-1 to the new
            // generation, so two threads in one crash report shared a name.
            Checks.check("a restarted pool issues fresh thread names",
                    !first.isEmpty() && !second.isEmpty() && !first.get(0).equals(second.get(0)));
            Checks.check("worker threads are named for the pool they belong to",
                    !second.isEmpty() && second.get(0).startsWith("core-worker-"));
        } finally {
            threads.stop();
        }
    }

    private static List<String> nameOfWorkerThread(ThreadService threads) throws Exception {
        List<String> names = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ran = new CountDownLatch(1);
        threads.submit(() -> {
            names.add(Thread.currentThread().getName());
            ran.countDown();
        });
        ran.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        return new ArrayList<>(names);
    }

    // ---- the game-thread queue ------------------------------------------

    private static void gameThreadQueue(TestClient client) throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ThreadService threads = new ThreadService(logger, 2);
        threads.start();
        try {
            List<String> order = Collections.synchronizedList(new ArrayList<>());
            threads.sync(() -> order.add("first"));
            threads.sync(() -> order.add("second"));
            Checks.checkEquals("queued work has not run yet", 2, threads.getPendingSyncCount());
            Checks.checkEquals("nothing ran without a drain", 0, order.size());

            Checks.checkEquals("draining runs everything queued", 2, threads.runPendingSync());
            Checks.checkEquals("in the order it was queued", "[first, second]", order.toString());
            Checks.checkEquals("and leaves the queue empty", 0, threads.getPendingSyncCount());

            // A task that queues another must not be able to spin the game thread
            // inside one drain.
            AtomicInteger depth = new AtomicInteger();
            threads.sync(new Runnable() {
                @Override
                public void run() {
                    if (depth.incrementAndGet() < 5) {
                        threads.sync(this);
                    }
                }
            });
            Checks.checkEquals("a re-queuing task runs once per drain", 1, threads.runPendingSync());
            Checks.checkEquals("and its next run is waiting for the next drain",
                    1, threads.getPendingSyncCount());
            // Let the chain finish, so the count below starts from empty.
            while (threads.runPendingSync() > 0) {
                // each drain runs one link and queues the next
            }
            Checks.checkEquals("a re-queuing chain terminates", 5, depth.get());

            threads.sync(() -> {
                throw new IllegalStateException("deliberate");
            });
            AtomicBoolean laterRan = new AtomicBoolean();
            threads.sync(() -> laterRan.set(true));
            Checks.checkEquals("a broken game-thread task does not stop the drain",
                    2, threads.runPendingSync());
            Checks.check("the tasks after it still ran", laterRan.get());
            Checks.check("and the failure is logged", logger.loggedError("Game-thread task failed"));

            Checks.checkEquals("a bounded drain honours its limit", 0, threads.runPendingSync(0));

            // Nothing has been marked, and claiming "yes" when unknown would send
            // game-state mutations to a worker thread.
            Checks.check("an unmarked service does not claim to be on the game thread",
                    !threads.isGameThread());
            threads.markGameThread();
            Checks.check("marking records the calling thread", threads.isGameThread());

            CountDownLatch offThread = new CountDownLatch(1);
            AtomicBoolean workerSawGameThread = new AtomicBoolean(true);
            threads.submit(() -> {
                workerSawGameThread.set(threads.isGameThread());
                offThread.countDown();
            });
            offThread.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            Checks.check("a worker knows it is not the game thread", !workerSawGameThread.get());
        } finally {
            threads.stop();
        }

        // The booted client wires the drain to TickEvent, so an adapter that posts
        // ticks needs no threading code of its own.
        ThreadService live = client.getCore().getThreadService();
        AtomicBoolean ranOnTick = new AtomicBoolean();
        live.sync(() -> ranOnTick.set(true));
        Checks.checkEquals("Core leaves sync work queued until a tick",
                1, live.getPendingSyncCount());
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.check("posting a tick drains the game-thread queue", ranOnTick.get());
        Checks.checkEquals("Core marks the thread that called start() as the game thread",
                true, live.isGameThread());
    }

    /** Waits for a future to settle, however it settles. */
    private static void awaitDone(Future<?> future) {
        try {
            future.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception expected) {
            // The point is that it finished, not how.
        }
    }

    /** Polls {@code condition} up to {@link #WAIT_MILLIS}, so a slow machine does not fail a run. */
    private static boolean waitFor(Callable<Boolean> condition) {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return true;
                }
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
