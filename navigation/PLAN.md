# Navigation — plan

A working document, not documentation. It records what is decided (agreed with
James on 2026-10-08), what was built and decided while building, what is still
to settle, and the order of the work.

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

## Built (2026-10-08)

Steps 1 to 7 of the order of work are built; see [README.md](README.md)
for how to use them. What was decided while building, beyond what is above:

| Area | Decision |
|---|---|
| Controls | One `ControlService` (`Core.controls()`) for the movement keys and the two buttons (`Click.ATTACK`, `Click.USE`), each arbitrated on its own with `RotationService`'s rules: priority, first-acquirer tie-break, claims that expire. A movement claim is a whole `MovementInput` with the yaw its keys are meant for; claims are never merged. A button is clicked (once a tick, however often applied) or held, and a hold is always let go when it ends. Sinks: `MovementSink` (one method), `ClickSink` (two). `RotationService` was left as it is rather than moved onto the shared claims code |
| The contract | `Goal`: `isMet(feet)`, `gap(feet)` and `anchor()` for goals that move. `Gap` is a lower bound split into across, up and down, because each is covered at its own speed and one distance would have to be divided by the fastest, falling. `PathProvider.plan(goal, from)` takes the state to plan from; `drives()`, `follow(goal)` and `cancel()` default to a provider that only plans. `Route` is coarse or precise; `Progress` is running, arrived, failed or stuck |
| The planner's search | States are keyed by position, horizontal speed, vertical velocity and footing — not direction of travel, which multiplied the states for nothing. Moves are three gaits at eight headings plus straight at the goal, six ticks each. The search is weighted (greed 2 by default): measured on open ground, round a wall, over a gap and into a hole, it found the same routes as a strict search for a small fraction of the states. A strict search is one setting away |
| Partial routes | A plan that runs out of range, ticks or states returns the closest route on the ground, if it gets at least a tick closer; otherwise none, with `PlanStats` counting every move thrown away under why |
| Safety | The drop limit, a rule refusing states, and whether sprinting is allowed are the developer's, read live. The planner falls any distance until told otherwise |
| The executor | Replans for drift, for a route that no longer lands where it did when replayed through the world (every ten ticks), for a goal that moved, for a partial route's end, and on a schedule if asked. Failing is sticky until the next `travel`. Stuck is reported, not acted on: no closer for a while, or drift every time it plans |
| Calling order | The navigator ticks on `TickEvent` PRE, after the tick opens and before the game reads its keys; its claims last that tick |
| Danger | `Hostiles` predicts each hostile once per plan and weighs every future by its share of belief. Asking a `Lookahead` per tick was a prediction per tick per hostile (about 100 ms a plan), and predictions further ahead than the tracker's history are unreliable, so the likeliest alone would fall back to where the hostile stands now |
| A Core fix | `Simulation`'s sweep now treats boxes within 1e-7 of each other as touching. Compared exactly, a box pressed into a corner could sit a rounding error inside a wall and jump out through it; the planner found that route |


### Steps 3 and 4 (2026-10-08)

The flow engine, shared memory and the test kit are built: see
[Core §16](../docs/16-flows.md) and [testkit/README.md](../testkit/README.md).

| Area | Decision |
|---|---|
| Names | `Flow` is the factories; a flow is just its root `Step`, started with `Core.flows().start(name, step, priority)`, which returns a `FlowHandle`. So the sketch's `Flow bot = ...` is `Step bot = ...`, and `priority(50)` is a plain `50` |
| Controls | **Steps declare what they claim** with `uses(Control...)` (`ROTATION`, `MOVEMENT`, `ATTACK`, `USE`). A reflex pauses only the flows below its priority whose *running* steps use a control its step uses. An undeclared claim still works and is warned about once (agreed with James) |
| Claims | Through the context: `c.look`, `c.move`, `c.click`, `c.hold`, filed with the flow as owner at its priority, for one tick. A paused or ended flow lets go at once |
| `ensures` | **Checked when the step finishes and on resume only** (agreed with James). Finishing without it holding fails the step; on resume a sequence rewinds to the earliest broken one and starts everything after it afresh. The flicker guard is a cap: more than 16 rewinds fails the sequence, naming the step |
| Resuming | A paused step is started again with `c.isResuming()` true and carries on; a step rewound past starts afresh, and so does everything inside it |
| Timing | A flow begins on its first flow pass after `start`, so a timeout or a wait counts the ticks its step actually gets. Up to 64 step changes per flow per tick, so instant steps cost no ticks and cannot spin |
| Services for steps | Through `c.service(Type.class)`, from what the host provides: Core provides its own, the sandbox its own. Steps reach nothing through `Core`'s statics, which is the documented rule |
| Memory | `Fact<T>`, one value or one per subject (by `equals`), with an age and an optional expiry as a `Span`. Kept for the session; leaving a world does not clear it |
| Time | `Span.ticks(n)` or `Span.seconds(s)`, never converted into each other. Every clock is injectable |
| The live view | `FlowView` / `StepView` snapshots: state, why, declared controls, ticks and time spent, keys and facts read and written, children. Ended flows stay until `clearFinished()` |
| Test kit | **Its own sandbox, no `Core` singleton** (agreed with James): its own services, `SimWorld` (collision space and block view), `SimPlayer` (the sinks; moved by the real `Simulation`), `SimEntity` (scripted, seen through an `EntitySource`), `FakeProviders`, a fixed clock. Depends only on core |
| Core's tests | **Use the test kit, test scope only** (agreed with James): core's main code never imports it |

Still to build: step 8, the ready-made steps — `travel(...)` here first.


### Step 8: the travel step (2026-10-09)

Built: `Travel` in `dev.px.navigation`; see [README.md §6](README.md#6-travel-steps).

| Area | Decision |
|---|---|
| Shape | **A factory built once** (agreed with James): `Travel.builder().provider(...).player(...).build()`, then `travel.to(goal)` or `travel.to(c -> goal)` makes a new `Travel.Trip` step each time, since a step runs in one place at a time. `travel.at(goal)` is the condition for `ensures` |
| The player's state | **A supplier the developer gives the builder** (agreed with James), read each tick. Core has no player of its own; a `PlayerSource` adapter piece in Core was considered and left until a second ready-made step needs it |
| Stuck | **Keeps running** (agreed with James): the navigator keeps trying and the step's progress is the blocks left, for the flow's `stuckAfter`. `failWhenStuck`, read each tick, fails it instead |
| The navigator | One per step, built on its first start from the flow's services: its controls, rotations and `SimulationService`, at the flow's priority read each tick. The developer's `navigator(...)` settings are applied first, so the flow's services and priority always win. The navigator claims as its own owner; the step stops it on every stop, so a pause lets go at once and cancels a provider that drives |
| Resuming | The trip begins afresh: the goal is asked for again and the navigator plans from where the player is |
| Failing | No route, a null goal, a null player state and a host with no simulation each fail the step with a reason; nothing throws |
| Testing | Navigation's tests use the test kit at test scope (`testImplementation project(':testkit')`), as Core's do |

Step 8 continues below with `fight(...)` in combat; the others come as modules
need them.


### Step 8: the fight step (designed and built 2026-10-09)

The aim: a developer whose client already has a killaura can plug it in, and
keep their own strafe and rotation logic.

```java
Fight<Player> fight = Fight.<Player>builder()
        .attack(myKillAura)            // required: when to swing, and at what
        .aim(myRotations)              // optional: how the head turns; snaps by default
        .footwork(myStrafe)            // optional: keys while fighting; none by default
        .build();

Step bot = Flow.sequence(
        findEnemy.into(TARGET),
        travel.to(c -> Goal.near(c.get(TARGET), 4)),
        fight.against(TARGET));        // or against(c -> tracked)
```

| Area | Decision |
|---|---|
| Where | `dev.px.combat.fight`, in combat. Combat still depends only on Core: it never imports navigation |
| Plugging in | **Three parts, each answering or driving** (agreed with James). `Attack<E>`, the aura: where to look and whether to click or hold `ATTACK` or `USE` this tick, with its timing, reach and rays inside it. `Aim`: how the head turns to that point, as a `RotationRequest`; snaps by default. `Footwork<E>`: the keys this tick, as a `MovementInput` with the yaw they are meant for, or none to leave them to a travel step or the player. Each part either **answers**, and the step files the claims through the flow at its priority, or **drives**, acting through the client's own code, with `start` and `stop` so a pause still stops it, as `PathProvider.drives()` does for Baritone. Parts mix: the developer's aura with ready-made footwork, a ready-made crystal attack with their aim |
| Modules | Unchanged rule: a flow never turns a KillAura module on or off. The aura's logic is a plain object both the module and the step call, as one `CrystalSearch` serves AutoCrystal and a bot |
| A client's own rotation manager | Reached through Core's `RotationSink`, which hands the chosen rotation to it; the fight step does not need to know about it. Strafing while aiming elsewhere is the adapter's `MovementSink` correcting keys to the faced yaw (`MovementCorrection`) |
| Ending | Done when the target is lost (no longer tracked or passing the selector) or an `until` holds; failed with no target at the start. Given combat's `Vitals`, its progress is the target's health, so `stuckAfter` notices a fight that is not landing hits |
| Ready-made, this round | **The framework and a `CrystalAttack` on `CrystalSearch`** (agreed with James). Melee stays the developer's killaura for now; a ready-made `MeleeAttack` and geometric footwork come later if wanted |
| Chasing | **Travel can leave the head** (agreed with James): a Travel and Navigator setting that claims only the keys, relying on the adapter's movement correction, so `Flow.race(fight.against(T), Flow.loop(travel.to(...)))` has the fight own the head while travel moves. Corrected keys are a little less exact, so the navigator re-plans a little more |
| Game facts | None in the library: reach, attack cooldown and hit delay live in the developer's `Attack`, or are rules they give a ready-made one. The crystal attack never holds an item: whether crystals are in hand is the developer's setting it reads (rule 3) |

Built: `dev.px.combat.fight` (`Fight`, `Fight.Round`, `Attack`, `Aim`,
`Footwork`, `Part`, `Bout`, `Strike`, `CrystalAttack`), and `turnHead` on
`Navigator` and `Travel`; see [combat §11](../combat/README.md#11-fighting).
Settled while building:

| Area | Decision |
|---|---|
| What parts are given | A `Bout`, made fresh each tick: the target's `Tracked`, the eyes, where the head is (Core's rotation service, so after the fight's aim is filed, where it will be), ticks fought (pauses not counted), the flow's `FlowContext`, and `isFacing(box)` |
| The eyes | Those of the player the flow host's `EntityService` tracks; `eyes(...)` on the builder overrides. Neither known fails the step, saying so |
| A strike | `Strike.at(point).click(button)` or `.hold(button)`, `.whenFacing(box)`: clicked only if a ray from the eyes along the head as turned this tick meets the box. Reach is the attack's to check. `Strike.failed(reason)` is how any attack, driving or not, fails the fight |
| Hearing a click | `Attack.struck(bout, strike)` is called only when the button was claimed, so cooldowns and the crystal search's bookkeeping count only clicks that went out |
| Parts that drive | One `drives()` on a shared `Part`, with `start` and `stop`. A driving part's per-tick method is still called; the step files nothing for it. A driving attack's strike is ignored unless it failed |
| The crystal attack | One action a tick, breaking first. A break is an attack on the crystal facing its box; a place is a use facing the block the option clicks. `attacked` and `placed` are told on `struck`. `placing` (crystals in hand) is the developer's, required; `breaking` is on by default. The search's targets are its own |
| Several steps | The parts are the developer's objects, shared by every step one `Fight` makes: run one at a time |
| Chasing | `Navigator.Builder.turnHead(BooleanSupplier)`, read each tick, and `Travel.Builder.turnHead(boolean)`, which also drops `ROTATION` from what the steps declare |
| Testing | Combat's tests use the test kit at test scope, as navigation's do. A `CrystalSearch` reads the player with its targets' type, so in the sandbox, whose player is a `SimPlayer`, the test's search is over `Object` |


---

## To settle while building

- **Handing over between a long-range provider and the local planner:** settled
  for providers that plan: a coarse route is handed to the local planner a
  stretch (16 blocks) at a time. Still open for a driving provider: how Baritone
  is paused while the local planner takes a section.
- **Planning off the game thread:** v1 plans on the game thread inside a state
  budget, reading each block once per plan into a cache. Off-thread planning
  still needs a snapshot of the blocks or a thread-safe `CollisionSpace`.
- **Steps that change the world:** v1's planner only moves. Breaking through or
  bridging, as Baritone does, would come with a construction module and the
  placement planner moved into core.
- **Water and elytra:** core's `Simulation` has neither (see the movement
  roadmap). They come once `Simulation` takes movement rules the adapter can
  add to.
- **Server movement checks:** general presets only, such as limiting sprint-jumps
  or turning speed (rule 4). Nothing written for one anticheat.
- **Flickering `ensures` conditions:** settled: `ensures` is checked on finishing
  and on resume only, and a sequence that rewinds past a cap fails, naming the step.
- **Unloaded chunks:** what the planner and executor do at the edge of what the
  client can see.
- **The exact API names and signatures**, decided as each piece is built (done
  for steps 1 to 7).

---

## Order of work

1. **Core: controls.** *Built.* Claims for movement keys and clicks with priorities, like
   `RotationService`, and the sink the adapter implements.
2. **Core: the navigation contract.** *Built.* `Goal`, `PathProvider`, `Route`, `Progress`.
3. **Core: the flow engine and shared memory.** *Built.* Steps, keys, sequence, loop,
   until, `firstOf`, `parallel`, `race`, `awaitEvent`, interrupts and reflexes,
   rewinding on resume, `stuckAfter`, the live view.
4. **`testkit/`.** *Built.* The simulated world, fake providers and the tick runner. Built
   alongside step 3, since the engine's own tests need it.
5. **Navigation: the executor** *(built)*, following routes from providers that only plan,
   and the guide to writing a Baritone adapter (driving).
6. **Navigation: the local precise planner.** *Built.*
7. **Navigation: danger.** *Built.*
8. **Ready-made steps:** `travel(...)` here *(built)*, `fight(...)` in combat *(built)*, and the
   others as modules need them.

Each step gets its suite, its mutation checks and its docs, like everything else
in the repository.
