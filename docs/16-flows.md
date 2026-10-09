## 16. Flows and shared memory

Automation written as **flows of steps**: a crystal PvP bot, a trip to the
world border, an auto-farm. Core runs them; it ships no behaviours. The steps
are yours, or come ready-made from the libraries built on Core: the
[navigation module](../navigation/README.md#6-travel-steps)'s `Travel` makes the
travel steps below, and the [combat module](../combat/README.md#11-fighting)'s
`Fight` the fight steps.

```java
static final Key<Tracked<Player>> TARGET = Key.of("target");

Step bot = Flow.loop(Flow.sequence(
        new FindEnemy(enemies, TARGET).ensures(tracked(TARGET)),
        travel.to(c -> near(c.get(TARGET), 5)).ensures(within(TARGET, 6)),   // navigation's Travel
        fight.against(TARGET).until(lowHealth)))                         // combat's Fight
    .interrupt(lowHealth, new Retreat(home).then(new Heal()))
    .stuckAfter(Span.seconds(30), new Recover());

FlowHandle running = Core.flows().start("bot", bot, 50);
Core.flows().reflex("put out fire", onFire, 90, new Extinguish());
```

| Package | What it is |
|---|---|
| `flow` | `Step`, `Flow` (the ways steps fit together), `FlowContext`, `FlowService` (`Core.flows()`), `FlowHandle`, `Key`, `Condition`, `Control`, `Span`, `Status`, `StopReason` |
| `flow.view` | The live view: `FlowView`, `StepView`, `StepState` |
| `memory` | `Memory` (`Core.memory()`), `Fact`, `Recollection` |

### A step

```java
final class Mine extends Step {

    private final Vec3i pos;

    Mine(Vec3i pos) {
        this.pos = pos;
        uses(Control.ROTATION, Control.ATTACK);
    }

    protected void start(FlowContext c)  { }
    protected Status tick(FlowContext c) {
        c.look(eye.rotationTo(pos.center()));
        c.hold(Click.ATTACK);
        return broken(pos) ? Status.DONE : Status.RUNNING;
    }
    protected void stop(FlowContext c, StopReason why) { }     // FINISHED, FAILED, PAUSED or CANCELLED
    protected double progress(FlowContext c) { return breakProgress(pos); }   // for stuckAfter
}

// or inline, when it is small
Step aimed = Flow.step("wait until aimed").uses(Control.ROTATION)
        .tick(c -> { c.look(aim); return aimed() ? Status.DONE : Status.RUNNING; });
```

`start` once, `tick` every tick until `DONE` or `FAILED` — fail with
`c.fail("why")`, so the reason reaches the view and the parent — then `stop`
with why. A step paused for something more urgent is stopped with `PAUSED` and
started again later with `c.isResuming()` true; keep what it needs in fields. A
step that throws fails, with the exception as its reason, and is logged once.

**Reach everything through the context**, not through `Core`'s statics: the
flow's keys (`c.get`, `c.put`), shared memory (`c.recall`, `c.remember`), the
controls (`c.look`, `c.move`, `c.click`, `c.hold`) and services
(`c.service(SimulationService.class)`). The same step then runs unchanged in the
game and in the [test kit](../testkit/README.md)'s sandbox.

**Declare the controls a step claims** with `uses(...)`. Claims go to Core's
rotation and control services with the flow as owner, at the flow's priority,
for one tick. A reflex pauses only the flows whose running steps use what it
uses, and it can only know that before they fight if they said so; a claim on an
undeclared control still works and is warned about once.

### Fitting steps together

| | |
|---|---|
| `Flow.sequence(a, b, c)` | one after another; a failure fails the lot |
| `Flow.loop(body)` | again and again, one round a tick at most |
| `Flow.firstOf(a, b, c)` | options in the order written, falling back on failure. The library never scores or chooses |
| `Flow.parallel(a, b)` | side by side; every branch must finish |
| `Flow.race(a, b)` | side by side; the first to finish wins and the rest are cancelled |
| `Flow.awaitEvent(Type.class, filter).into(KEY)` | until a matching event is posted; it listens only while it runs |
| `Flow.waitFor(span)`, `waitUntil(cond)`, `action(name, c -> ...)`, `check(name, cond)` | the small ones |

and on any step:

| | |
|---|---|
| `.ensures(cond)` | what the step achieved. If it finishes and `cond` does not hold, it fails; when its flow resumes after a pause, a sequence rewinds to the earliest step whose `ensures` no longer holds |
| `.until(cond)` | finishes it early, and successfully, as soon as `cond` holds |
| `.when(cond)` | runs it only if `cond` holds as it would start; otherwise it fails, so a `firstOf` moves on |
| `.retry(n)`, `.orElse(other)` | a failed step tried again, or replaced |
| `.timeout(span)` | fails it if it has not finished within `span`, pauses included |
| `.stuckAfter(span, recovery)` | pauses it for `recovery` whenever the step running inside reports the same `progress` for `span`, then resumes it. Steps that report none are never stuck: give those a `timeout` |
| `.interrupt(cond, handler)` | pauses it for `handler` whenever `cond` holds, then resumes it, rewinding as above |
| `.then(next)` | a sequence of two |

A step that finishes at once lets the next one start in the same tick, up to
`setChangesPerTick` (64) per flow per tick, so a run of instant steps costs no
ticks and a loop of them cannot spin.

### Rewinding

Rewinding happens on **resume** — after an interrupt, a reflex, or the player
coming back into the world — and only there. A sequence goes back to the
earliest step whose `ensures` no longer holds and runs from there; with nothing
undone, the paused step carries on where it was. `ensures` is also checked once
when the step finishes. It is never checked continuously, which keeps it cheap;
the guard against one that flickers is a cap: a sequence that rewinds more than
`setMaxRewinds` (16) times fails, naming the step whose work keeps being lost.

### Running

`Core.flows()` ticks every flow on `TickEvent` PRE, after the controls and
rotations open the tick. A flow begins on the first pass after it is started.

- **Many at once**, each with a priority (on `RotationPriority`'s scale), sharing
  the controls the way modules do.
- **Reflexes** are global: `reflex(name, condition, priority, step)` fires its step
  whenever the condition holds, pausing the flows below its priority whose running
  steps use a control it uses — and only those; the rest carry on. When it
  finishes, they resume.
- **Leaving the world pauses every flow**, and joining one resumes them,
  rewinding as they do. Nothing is kept past a restart.
- **`FlowHandle`** pauses, resumes and cancels a flow, reads its keys from
  outside, and says why it failed or what paused it. A paused or ended flow lets
  go of the controls at once.

### The live view

```java
for (FlowView flow : Core.flows().view()) {
    hud.line(flow.getName() + " [" + flow.getState() + "] " + flow.getRoot().describe());   // "bot [RUNNING] loop > sequence > travel"
    if (flow.getReason() != null) hud.line("  " + flow.getReason());                     // "travel: LocalPlanner found no way ..."
}
```

A snapshot tree of every flow, reflex and recently ended flow: each step's
state, why it failed or paused, its declared controls, how many ticks and how
much time it has taken, and the keys and facts it read and wrote. Headless: it
draws nothing and listens to nothing.

### Shared memory

```java
static final Fact<Boolean> UNMINABLE = Fact.of("unminable");
static final Fact<Vec3> STASH = Fact.of("stash");

Core.memory().remember(UNMINABLE, pos, true, Span.seconds(300));   // skip it for five minutes
Core.memory().remember(STASH, chest);                              // for good
Core.memory().knows(UNMINABLE, pos);
Core.memory().all(UNMINABLE);                                      // with their ages, oldest first
```

Typed facts every flow shares: one value, or one per subject (compared by
`equals`), each with an age and an optional expiry in ticks or seconds. Expired
facts are never recalled and are cleared out as time passes. Kept for the
session; leaving a world does not clear it, so clear what belongs to one world
yourself on `WorldEvent`. Game thread only.

### Time

`Span.ticks(n)` counts ticks as they happen; `Span.seconds(s)` is wall-clock
time. No tick rate is assumed, so the two are never converted: use ticks for
what keeps pace with the game, seconds for human-scale waits that should not
stretch when the server lags.

### What your adapter supplies

Nothing new: flows run on the tick and world hooks (§14). Steps that move the
player need the control and rotation sinks (§10).
