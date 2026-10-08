# Navigation — plan

A working document, not documentation. Nothing here is built yet. It records
what is decided (all of it agreed with James on 2026-10-08), what has to be
settled while building, and the order of the work.

The plan covers two things that only make sense together:

1. **The flow engine**: a way for client developers to write automation (a
   crystal PvP bot, a trip to the world border) as flows of steps. It lives in
   **core**, so every module can ship ready-made steps.
2. **Navigation**: getting the player somewhere, through any pathfinder
   (Baritone included) or our own precise local planner. It lives in this
   module, with its contract in core.

---

## Why this exists

Baritone is good at one thing: going from A to B. Everything built on top of it
stays "kind of dumb", for reasons that have nothing to do with pathfinding:

| Why bots built on Baritone are dumb | What this design does instead |
|---|---|
| Fixed scripts that break the moment something unexpected happens | Flows of steps with fallbacks (`firstOf`), failure handling, and interrupts that pause and resume |
| No memory: rediscovering everything, looping on the same unbreakable block | Shared memory: typed facts with an age and an expiry ("unminable at x, for 5 minutes") |
| Nothing checks that a step worked | Steps declare what they achieved (`.ensures`), and the flow rewinds to whatever no longer holds |
| An interruption wipes the plan | Interrupts pause; resuming rewinds only as far as needed |
| The world is planned as frozen, with mobs as static obstacles | Our planner avoids where hostiles **will** be, using core's prediction |
| No way to notice being stuck | Steps report progress, and `.stuckAfter(time, recovery)` notices when it stops |
| You can't see why it did something | A live view of every flow: each step's status, why it failed or paused, timings |
| Can only be tested by playing | A headless test kit, so developers test their own flows against a simulated world |

**The library ships no behaviours.** No "organise stash", no "build a house".
It is the engine developers write those on. Resources, mobs, recipes and every
step's meaning are the developer's (rule 1). Steps that touch the inventory are
the developer's own code; the library never picks an item (rule 3).

---

## What a developer writes

A crystal PvP bot:

```java
static final Key<Tracked<Player>> TARGET = Key.of("target");

Flow bot = Flow.loop(Flow.sequence(
        find(enemies).into(TARGET).ensures(tracked(TARGET)),
        travel(near(TARGET, 5)).ensures(within(TARGET, 6)),    // Baritone or ours, via PathProvider
        fight(crystals).until(dead(TARGET).or(lowHealth))))    // a step combat ships, on CrystalSearch
    .interrupt(lowHealth, retreat(home).then(new Heal()))      // Heal is the developer's Step class
    .stuckAfter(seconds(30), recover());

flows.start(bot, priority(50));
flows.reflex(onFire, priority(90), extinguish());              // global; pauses only flows needing the same controls
```

A trip to the world border. The border's position is a game fact, so it is the
developer's constant:

```java
Flow border = Flow.sequence(
        firstOf(                                               // tried in the order written
            elytra(toward(BORDER)).when(hasElytra),
            nether(toward(BORDER)),
            walk(toward(BORDER))))
    .interrupt(hungry, new Eat())
    .stuckAfter(seconds(30), recover());
```

A step of the developer's own, as a class, or with the builder when it is small:

```java
final class Mine extends Step {
    protected void start(FlowContext c)  { c.claim(Control.ROTATION, lookAt(pos)); }
    protected Status tick(FlowContext c) { return broken(pos) ? Status.DONE : Status.RUNNING; }
    protected void stop(FlowContext c, StopReason why) { /* FINISHED, FAILED, PAUSED or CANCELLED */ }
    protected double progress(FlowContext c) { return breakProgress(pos); }   // for stuckAfter
}

Step aimed = Flow.step("wait until aimed").tick(c -> aimed(c) ? Status.DONE : Status.RUNNING);
```

Every name above is a sketch of the agreed shape, not a final signature.

---

## Decided

### The flow engine (core)

| Area | Decision |
|---|---|
| Where | **Core**, kept small and with no game knowledge, so combat, navigation and others can ship ready-made steps |
| Writing flows | A fluent builder: `sequence`, `loop`, `until`, `interrupt`, `firstOf`, `parallel`, `race` |
| Choosing between options | **The developer's order only.** `firstOf(...)` tries options as written and falls back on failure. The library never scores or chooses. A scored `choose(...)` was considered and turned down |
| Steps | `Step` is the one concept: a class with `start`, `tick` returning a `Status`, and `stop(context, StopReason)`. `Flow.step(...)` is a builder shorthand that makes a `Step` |
| Passing data | Typed keys (`Key<T>`), scoped to the flow |
| Failure | A failed step fails its parent unless it says `.retry(n)` or `.orElse(...)` |
| Side by side | `parallel(...)`: every branch must finish. `race(...)`: the first to finish wins and the rest are cancelled |
| Game events | `awaitEvent(Type.class, filter)` finishes when a matching bus event arrives. It subscribes only while it runs, and can put the event into a key |
| Interrupts | **Both kinds.** Global reflexes (`flows.reflex(...)`) cover every flow, and each flow adds its own (`.interrupt(...)`). Both pause and resume |
| Which flows a reflex pauses | **Only those needing the controls it claims.** Others carry on |
| Resuming | **Rewind where steps declare `.ensures(...)`, otherwise resume.** On resume, a sequence goes back to the earliest step whose `ensures` no longer holds; steps without one carry on where they were |
| Many flows | Any number run at once, each with a priority, sharing the controls |
| Being stuck | Steps can report a progress number. `.stuckAfter(time, recovery)` fires when it stops changing, and plain `.timeout(time)` covers steps that report nothing |
| Time | Ticks as the basic unit, with seconds allowed for human-scale timeouts. No tick rate is assumed: seconds are wall-clock, ticks are counted |
| Persistence | **Within the session only.** Leaving the world pauses every flow, and rejoining resumes them. A client restart loses them |
| Seeing inside | A read-only live view of every running flow: each step's status, why it failed or was paused, its timings, and the keys and shared facts it touches. Headless: a GUI can draw it |
| Names | `Flow`, `Step`, `reflex`, `ensures`, `firstOf`, `stuckAfter` |

### Modules and flows

**Modules and flows never touch each other.** A flow never turns a module on
or off. Both are built on the same plain library objects: a `CrystalSearch` built
once can serve the AutoCrystal module and a bot's `fight` step alike. Neither
reading module settings nor shared module "capabilities" was chosen.

### Controls (core)

Rotation, movement keys and clicks (attack and use) are claimed with a priority
on every claim, set by the developer on the flow step or in the module. This is
how `RotationService` already works for rotation. Movement and clicks need the
same treatment: claims, priorities, expiry, and a small sink interface the
adapter implements, like `RotationSink`.

### Shared memory (core)

Typed facts that every flow can read and write, each with an age and an optional
expiry: a stash location, a danger zone, a block to skip for five minutes. Game
thread only, and shown in the live view.

### Navigation

| Area | Decision |
|---|---|
| Module | `navigation/` (`dev.px.navigation`), optional, depending only on core |
| The contract | **In core**: goals, `PathProvider`, the route and progress types. Anything (a flow step, a combat feature) can ask to go somewhere without depending on this module |
| Two kinds of provider | A provider that **only plans** returns a route, and our executor follows it through the controls. A provider that **drives** (Baritone) moves the player itself, and its adapter routes rotations and keys through core's claims as far as its API allows |
| Baritone | Supported through a driving provider **the developer writes**. Baritone depends on Minecraft, so the library never imports it. This module documents how to write the adapter |
| Our planner in v1 | **A local precise planner**: short range, every move checked by simulating it with core's `Simulation`, producing exact keys and rotation for each tick. Long distances are Baritone's (or any other long-range provider) |
| Danger | **In v1, for our planner.** A position costs more at the time a predicted hostile would be there. Who counts as hostile is the developer's `TargetSelector`, and how dangerous it is is the developer's rule |

### Testing

A **test kit as its own test-scope module** (`testkit/`), which clients add as
`testImplementation`. It has a simulated world (blocks; entities moving through
the real `Simulation`), fake providers, and a tick runner. A developer can then
test "a zombie arrives while it's mining; it backs off, resumes and finishes"
with no game running. It also becomes our own test bed.

---

## Design detail

### The contract in core

| Piece | What it is |
|---|---|
| `Goal` | Plain geometry. `isMet(position)`, plus a distance estimate for the planner's heuristic. Shapes: at a block, near a point, an XZ column, a Y level, avoid a region, any of / all of. A goal can follow a moving point (a `Tracked` target) |
| `PathProvider` | `plan(Goal)` returns a `Route` for a provider that only plans. `drives()` says which kind it is. `follow(Goal)` returns `Progress` for a provider that drives. `cancel()` |
| `Route` | The planned positions, and for our planner the expected movement state and inputs for each tick. Named `Route` because core's `util.spatial.Path` already exists |
| `Progress` | Running, arrived, failed (with a reason), or stuck, plus the distance remaining, which `stuckAfter` reads |

### The executor (this module)

- Follows a `Route` from a provider that only plans, claiming movement and
  rotation at the step's priority.
- With our planner's routes, compares each tick's real state with the expected
  one, as `DriftMonitor` does, and replans when they drift apart.
- With a coarse route from elsewhere, steers along it and hands precise sections
  to the local planner.
- Replans when a moving goal moves far enough, or when the blocks along the route
  change.

### The local planner (this module)

- Searches over movement states (position, velocity, on the ground), not blocks.
- Moves are input choices held for a few ticks: walk, sprint, sneak, jump, and a
  set of headings.
- **Every move is checked by stepping it through core's `Simulation`** against
  the world's collision boxes, so a planned sprint-jump is one the game will
  actually do: parkour gaps, edges, ice, stepping into a 1×1 hole.
- The cost is time, plus danger. The heuristic is distance over the top speed
  core's `Envelope` already uses, so it never overestimates.
- **Range is a tuning knob** (a block radius and a tick budget), named and
  overridable. Beyond it, a long-range provider takes over.
- Output: exact inputs for each tick, with the expected state after each one.

### Danger

- Core's `PredictionService` gives where each hostile is likely to be, and could
  possibly be, over the next ticks.
- A move ending at tick *t* near a hostile's predicted position at *t* costs more.
- How dangerous something is, and how near counts, is the developer's rule. A
  mob's reach is a game fact.
- Limited to the local planner's range, where the cost is affordable.

---

## Builds on what exists

| Already in core | Used for |
|---|---|
| `movement.simulation`: `Simulation`, `CollisionSpace`, `MovementInput`, `MotionState`, `DriftMonitor` | Checking moves, expected states, noticing drift |
| `movement.prediction`: `PredictionService`, `Prediction`, `Envelope`, `Lookahead` | Danger through time, heuristics, goals that move |
| `movement.rotation`: `RotationService`, `RotationSink` | The model for movement and click claims |
| `util.spatial`: `AStar`, `PathSpace`, `Path` | The search, or the pattern for it |
| `world`: `BlockView`, `BlockShape`, `Rays` | The world the planner asks about |
| `entity` and `target`: `EntityService`, `TargetSelector` | Who is a hostile |
| `concurrent.ThreadService`, the event bus | Planning off the game thread, `awaitEvent` |
| Test fixtures: `MovementRig`, `GridCollisionSpace` | The start of the test kit |

---

## To settle while building

- **Handing over between a long-range provider and the local planner:** when the
  local planner takes a section, and how a driving provider like Baritone is
  paused while it does.
- **Planning off the game thread:** the planner needs a snapshot of the blocks,
  or a `CollisionSpace` that is safe to read from another thread.
- **Steps that change the world:** v1's planner only moves. Breaking through or
  bridging, as Baritone does, would come with a construction module and the
  placement planner moved into core.
- **Water and elytra:** core's `Simulation` has neither (see the movement
  roadmap). They come once `Simulation` takes movement rules the adapter can
  add to.
- **Server movement checks:** general presets only, such as limiting sprint-jumps
  or turning speed (rule 4). Nothing written for one anticheat.
- **Flickering `ensures` conditions:** a guard so a flow doesn't bounce between
  rewinding and resuming.
- **Unloaded chunks:** what the planner and executor do at the edge of what the
  client can see.
- **The exact API names and signatures**, decided as each piece is built.

---

## Order of work

1. **Core: controls.** Claims for movement keys and clicks with priorities, like
   `RotationService`, and the sink the adapter implements.
2. **Core: the navigation contract.** `Goal`, `PathProvider`, `Route`, `Progress`.
3. **Core: the flow engine and shared memory.** Steps, keys, sequence, loop,
   until, `firstOf`, `parallel`, `race`, `awaitEvent`, interrupts and reflexes,
   rewinding on resume, `stuckAfter`, the live view.
4. **`testkit/`.** The simulated world, fake providers and the tick runner. Built
   alongside step 3, since the engine's own tests need it.
5. **Navigation: the executor**, following routes from providers that only plan,
   and the guide to writing a Baritone adapter (driving).
6. **Navigation: the local precise planner.**
7. **Navigation: danger.**
8. **Ready-made steps:** `travel(...)` here, `fight(...)` in combat, and the
   others as modules need them.

Each step gets its suite, its mutation checks and its docs, like everything else
in the repository.
