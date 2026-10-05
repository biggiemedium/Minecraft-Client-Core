## 8. Threading

Three tiers, because background work in a client comes in three shapes and one
pool cannot serve all of them. A `while (true)` loop sharing a fixed pool with a
one-second timer means the loop owns a thread forever and the timer quietly stops
firing — nothing throws, nothing is logged.

| Call | For | Runs on |
|---|---|---|
| `submit(Runnable)` / `submit(Callable<T>)` | work that finishes: a login, an update check, a file scan | a pool of `threadPoolSize()` workers that time out when idle |
| `schedule` / `repeat` | timers | their own small pool, which the tier above cannot starve |
| `loop(label, body)` | work that runs until switched off | a dedicated daemon thread per loop |

```java
Core.threads().submit(() -> {
    String latest = Http.getOrNull(VERSION_URL);          // worker thread
    Core.threads().sync(() -> {                           // game thread
        Core.notifications().info("Update", latest + " is available");
    });
});
```

`repeat` is fixed-*delay*, not fixed-rate: if one run overruns the period,
fixed-rate fires the backlog all at once, which is the wrong behaviour for
polling an API. A run that throws is logged and the schedule **continues** — a
raw `scheduleWithFixedDelay` cancels every remaining run the first time its body
throws, so one network blip would stop the poll for the session. Everywhere else
a failure is logged *and* rethrown, so a `Future` you check still reports it.

### Getting back to the game thread

Background work must not touch game state. `sync(Runnable)` queues a task and
`runPendingSync()` runs the queue, and Core wires that drain to `TickEvent` — so
**if your adapter calls the tick hooks, this already works.** If it does not, call
`Core.threads().runPendingSync()` from your game loop yourself; `ThreadService`
warns if a backlog builds up and nobody is draining it.

Tasks run in queue order on the next drain, even when `sync` is called from the
game thread already: running inline would let a task execute in the middle of
another one's drain. A drain is bounded to the backlog present on entry, so a
task that queues another cannot spin a frame. `runPendingSync(limit)` spreads a
large backlog over several ticks, and `isGameThread()` answers whether you are
somewhere it is safe to touch the world — `false` when no thread has been
marked, since the safe answer to that question when unknown is no.

### Modules

`ThreadedModule` is the shape for a module whose work is a loop. It gets its own
thread through `loop`, so any number can be enabled at once:

```java
@ModuleInfo(name = "Scanner", description = "Scans in the background", category = "Render")
public final class Scanner extends ThreadedModule {

    private volatile List<String> found = Collections.emptyList();

    @Override
    protected void runInBackground() {
        while (!Thread.currentThread().isInterrupted()) {
            List<String> scanned = scan();
            Core.threads().sync(() -> found = scanned);
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                return;                      // disabled; stop
            }
        }
    }
}
```

Honour interruption — return from an `InterruptedException` rather than
continuing. Cancellation is not treated as a failure, so switching a module off
logs nothing even though the body unwinds with an exception.

### Lifecycle

Work requested before `start()` or after `stop()` is dropped with one warning and
an already-finished handle, never a `NullPointerException`. Shutdown is two-phase:
work already running gets 1.5s to finish on its own — a config half-written to
disk is worth waiting for — and only then are the stragglers interrupted.

Pool size comes from the builder:

```java
Core.builder("LeapFrog", "2.0").platform(...).threadPoolSize(4).build();
```

That sizes only the `submit` tier; timers have their own pool and each `loop` has
its own thread, so it is worth raising only for a client firing many concurrent
requests.
