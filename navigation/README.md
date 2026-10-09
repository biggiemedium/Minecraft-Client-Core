# Navigation

Getting the player somewhere, for Minecraft clients, built on [Core](../README.md)
and just as version-independent. Any pathfinder can do the long-range work —
Baritone through an adapter you write, or anything that returns a list of
blocks — and this module's own **local planner** does the short-range work
precisely: it plans the exact keys for every tick, and every move it considers is
played through Core's movement simulation against the world, so the sprint-jump
over a gap, the run-up before it and the step into a 1×1 hole are moves the game
will actually make.

Like Core, it has **no Minecraft on its classpath** and **no game values in its
code**. The movement rules are your `PhysicsProfile`, the world is your
`CollisionSpace`, and what is safe — how far to fall, where not to go — and who
is hostile and how dangerous they are, are rules you supply. The planner falls
any distance until you say otherwise, because how far is safe is a fact about the
game.

**Status.** The navigator (precise routes key for key, coarse routes a stretch at
a time, steering, providers that drive), the local planner, danger from
hostiles where they will be, and **travel steps** for Core's
[flows](../docs/16-flows.md) are done. They are built on two new pieces of Core:
**controls** (`Core.controls()`, claims on the movement keys and buttons, like
rotation claims) and **the navigation contract** (`Goal`, `PathProvider`,
`Route`, `Progress`). See [Not yet included](#not-yet-included) and
[the plan](PLAN.md) for what comes next.

**Requires:** Core, Java 8. Inside this repository:

```groovy
dependencies {
    implementation project(':navigation')     // brings Core with it
}
```

Without Gradle, copy `navigation/src/main/java/dev/px/navigation` alongside Core's sources.

---

## Contents

1. [Your adapter](#1-your-adapter)
2. [Goals](#2-goals)
3. [The local planner](#3-the-local-planner)
4. [The navigator](#4-the-navigator)
5. [Danger](#5-danger)
6. [Travel steps](#6-travel-steps)
7. [Baritone, or any provider that drives](#7-baritone-or-any-provider-that-drives)
8. [Package map](#8-package-map)
9. [Verifying](#9-verifying)

---

## 1. Your adapter

Nothing beyond what Core already asks for, wired as follows:

| What | Where | Why |
|---|---|---|
| `CollisionSpace` | `Core.simulation().setCollisionSpace(...)` | the world the planner simulates against, one block at a time ([Core §10](../docs/10-movement.md)) |
| `PhysicsProfile` | `Core.simulation().setProfile(...)` or `MovementMath.setProfile(...)` | the rules: walking speed, jump, gravity — with the player's effects already applied |
| `MovementSink` | `Core.controls().setMovementSink(...)` | how the claimed keys reach the game |
| `RotationSink` | `Core.rotations().setSink(...)` | how the yaw each tick's keys are meant for reaches the game |
| the player's state | each tick, into `navigator.tick(...)` | where it plans from and checks the route against |

**The order within a tick matters.** Claims last one tick, so the navigator files
them after Core opens the tick and before the game reads its keys:

```java
// once, at startup
Core.bus().on(TickEvent.class, tick -> {
    if (tick.getStage() == Stage.PRE && navigator.isTravelling()) {
        EntityPlayerSP p = mc.thePlayer;
        progress = navigator.tick(MotionState.of(
                Vec3.of(p.posX, p.posY, p.posZ), Vec3.of(p.motionX, p.motionY, p.motionZ), p.onGround));
    }
});

// in your adapter, where the game reads the player's keys, before the player moves
Core.rotations().apply();
Core.controls().applyMovement();
```

The keys in each claim are meant for the yaw in the same claim. Applying the
rotation before the keys, as above, makes them agree; if your client writes
rotations later — just before the movement packet, say — the `MovementSink`
example in Core corrects the keys to the yaw the game moves with this tick.

---

## 2. Goals

A goal is plain geometry from Core's `dev.px.core.navigation`:

```java
Goal.block(x, y, z)                    // the feet in that block
Goal.near(point, 2)                    // within two blocks of a point
Goal.near(target, 4)                   // within four blocks of someone, wherever they go
Goal.column(x, z)                      // anywhere in that column
Goal.level(y)                          // any block at that height
Goal.avoid(region)                     // anywhere out of a region
Goal.anyOf(home, Goal.near(bed, 1))    // whichever is nearer
Goal.allOf(Goal.column(x, z), Goal.avoid(lava))
```

Each says whether the feet are there (`isMet`) and how far at least they are
(`gap`), split into across, up and down because each is covered at its own
speed. The gap is a lower bound, never more than the truth; the planner's
estimate is built on it. A goal that depends on the world ("next to a chest") is
yours, written against `Goal`.

---

## 3. The local planner

```java
LocalPlanner planner = LocalPlanner.builder()
        .simulation(Core.simulation())                  // the rules and the world
        .range(24)                                      // blocks; further is a long-range provider's job
        .maxDrop(() -> settings.safeFall.get())         // your rule: how far is safe to fall
        .allowed(state -> !isLava(state.getPosition())) // your rule: where the player may not be
        .canSprint(() -> mc.thePlayer.getFoodStats().getFoodLevel() > 6)   // your rule
        .build();

Route route = planner.plan(Goal.block(x, y, z), playerState);
if (route == null) {
    log(planner.getLastStats().getReason());   // "no route after 9 states; 412 moves fell further than your drop limit"
}
```

It searches over movement states — position, speed, footing — not blocks. Each
step of the search holds one **gait** (walk, sprint, sprint-jump; sneak, jump and
wait if you add them) at one of eight headings or straight at the goal, for six
ticks, and simulates every tick. The route that comes back is exact: every tick's
keys and yaw, and the state the player will be in after it. It ends on the tick
the goal is first met.

**Limits, all yours to set and all read live as each plan begins:**

| Setting | Default | What it bounds |
|---|---|---|
| `range` | 24 blocks | how far from the start a plan may reach; beyond it, a partial route towards the goal |
| `maxTicks` | 200 | how long a route may take |
| `maxStates` | 2000 | the work one plan may do; run out, and the closest route found comes back marked incomplete |
| `maxDrop` | unlimited | how far below where a fall began the player may land |
| `allowed` | everything | your rule, checked on every simulated tick |
| `canSprint` | yes | whether sprinting gaits are tried at all |

**Tuning knobs**, for the search itself: `gaits`, `headings` (8), `moveTicks` (6),
`resolution` (states closer than 0.25 blocks and 0.25 blocks a tick count as one),
`greed` (2) and `topSpeed` (measured from your profile).

**Greed.** The search's estimate never overestimates — the goal's gap over the
fastest the rules let the player move — and a strict search (`greed(1)`) finds the
quickest route the moves allow. By default the search leans on that estimate
twice as hard, which promises routes at most twice as slow. In practice, on open
ground, round a wall, over a gap and into a hole, it found the same routes as the
strict search, after a few dozen states instead of thousands:

| Case | Route | States | Time (warm JVM) |
|---|---|---|---|
| 13 blocks of open ground | 40 ticks | 13 | ~1 ms |
| round a 7-wide wall between player and goal | 44 ticks | 787 | ~35 ms |
| a 3-block gap, sprint-jumped | 38 ticks | 8 | <1 ms |
| into a 1×1 hole | 25 ticks | 123 | ~6 ms |

The wall is the expensive case: a goal straight behind a wall fills the space in
front of it before the search finds the way round. `maxStates` caps it.

**What it does not do:** it only moves — no breaking through or bridging — and it
plans what Core's `Simulation` models, so not water, ladders, cobwebs or elytra,
and not the sneak clamp at edges. `PlanStats` says how every plan ended and counts
every move it threw away under why: out of range, out of ticks, refused by your
rule, fell too far.

---

## 4. The navigator

```java
Navigator navigator = Navigator.builder()
        .provider(planner)                    // the planner above, any planner, or one that drives
        .controls(Core.controls())
        .rotations(Core.rotations())
        .simulation(Core.simulation())
        .priority(RotationPriority.NORMAL)    // read each tick
        .build();

navigator.travel(Goal.near(target, 3));
// each tick, see §1
Progress progress = navigator.tick(player);
```

**A precise route** is followed key for key: each tick the keys and the yaw they
are meant for are claimed at the navigator's priority. Before each tick it checks
the player is where the route expected; every ten ticks it plays the rest of the
route through the world as it is now. It plans again when:

| `Replan` | When |
|---|---|
| `START` | there is no route yet, or the goal moved away after arriving |
| `DRIFT` | the player is more than `drift` (0.3 blocks) from where the route expected: knocked back, slowed, or the simulation is off |
| `BLOCKS_CHANGED` | the rest of the route, replayed through the world now, no longer ends where it did — a block placed in the way is noticed before the player walks into it |
| `GOAL_MOVED` | a goal that follows something has moved more than `goalMoved` (1.5 blocks) |
| `ROUTE_ENDED` | a partial route ran out short of the goal |
| `SECTION` | the next stretch of a coarse route |
| `REFRESH` | `replanEvery` ticks passed, if you set it: for danger that moves |

**A coarse route** — positions from a long-range pathfinder — is followed a
stretch at a time: the waypoints within `sectionReach` (16 blocks) become a goal
for the `local` planner, and its precise route is followed as above. With no
local planner the navigator steers: it faces the next waypoint, walks, and jumps
when the next one is higher than a step.

**Arriving, failing, being stuck.** Once the goal is met it lets go of the
controls and reports `ARRIVED`, and keeps the goal, so a goal that walks away is
followed again. With no route at all it reports `FAILED` with the planner's
reason (`"LocalPlanner found no way to Goal.block(6, 64, 0): no route after 9
states"`), lets go, and stays failed until the next `travel`. When the goal has
come no closer for `stuckTicks` (100), or the route keeps parting from the player
the moment it is planned — water, a ladder, a profile that does not match the
game — it reports `STUCK` with why, and keeps trying: what to do about it is yours.

`getStats()` counts every plan by reason; `getSection()` is the precise route
being followed, for drawing where the player is about to go.

---

## 5. Danger

```java
Hostiles<Mob> hostiles = Hostiles.<Mob>builder()
        .targets(Core.targets())
        .selector(mobs)                                          // who counts as hostile: your selector
        .predicted(Core.prediction())                            // where they will be
        .rule((mob, distance, tick) -> distance < 3 ? 20 : 0)   // how dangerous: your rule
        .build();

LocalPlanner planner = LocalPlanner.builder().simulation(Core.simulation()).danger(hostiles).build();
```

As each plan begins, every hostile is predicted over the next 40 ticks. A move
then costs more for each tick it ends near where a hostile is expected to be
*at that tick*, so the route goes round the zombie walking towards the path,
not round the spot it stood on. The rule's answer is in ticks: 20 makes a tick
there as bad as a route 20 ticks longer.

Core's prediction gives several futures per hostile, each with a share of
belief, and every one is weighed by its share: a confident prediction counts
almost only its likeliest future, an unsure one spreads the danger over every
way the hostile might go. Any other `Lookahead` — a straight line, your own model
— is taken as certain.

A plan weighs hostiles where they were predicted when it was made, so for
hostiles that change their minds, `replanEvery` on the navigator plans again on
a schedule. Who is hostile, how near counts and how much it costs are yours: a
mob's reach is a fact about the game. Anything else dangerous — a region about to
explode — is a few lines against `Danger`.

---

## 6. Travel steps

```java
static final Key<Tracked<Player>> TARGET = Key.of("target");

Travel travel = Travel.builder()
        .provider(planner)                                   // any provider, as for the navigator
        .player(() -> MotionState.of(
                Vec3.of(p.posX, p.posY, p.posZ), Vec3.of(p.motionX, p.motionY, p.motionZ), p.onGround))
        .navigator(b -> b.local(planner).stuckTicks(60))     // any Navigator setting
        .build();

Step bot = Flow.loop(Flow.sequence(
        new FindEnemy(enemies, TARGET),
        travel.to(c -> Goal.near(c.get(TARGET), 5)),         // a goal from the flow's keys
        new Fight(crystals)))
    .stuckAfter(Span.seconds(30), new Recover());

Goal home = Goal.near(base, 2);
Step goHome = travel.to(home).ensures(travel.at(home));      // walked back to after an interrupt
```

A `Travel` is built once and makes a new step with every `to(...)`, since a step
runs in one place at a time. Each step takes its trip with a `Navigator` of its
own, as §4 describes, built the first time it starts from the services of the
flow it runs in: it claims the keys and the head through the flow's controls and
rotations, at the flow's priority read each tick, and checks routes with the
flow's simulation. So the same step runs in the game and in the
[test kit](../testkit/README.md)'s sandbox. The step declares `MOVEMENT` and
`ROTATION`, so a reflex that needs either pauses it, and one that needs neither
leaves it walking.

The player's state is yours, read each tick as the flow runs: Core has no player
of its own. The `navigator(...)` settings are applied first, then the step sets
the provider, controls, rotations, simulation and priority itself, so the flow's
always win.

| The navigator reports | The step |
|---|---|
| `ARRIVED` | is done, and lets go of the controls |
| `FAILED` | fails with the navigator's reason (`"LocalPlanner found no way to ..."`), so a `firstOf`, `retry` or `orElse` takes over |
| `STUCK` | keeps running, and the navigator keeps trying. Its progress is the blocks left, so `.stuckAfter(span, recovery)` notices when that stops falling. With `failWhenStuck(true)` (read each tick) it fails instead, with why |

**Leaving the head alone.** With `turnHead(false)` on the `Travel` builder, its
steps claim only the movement keys and declare only `MOVEMENT`. That leaves the
head to something running beside them, such as a fight aiming at its target
([combat §11](../combat/README.md#11-fighting)):
`Flow.race(fight.against(TARGET), Flow.loop(chase.to(...)))`. Your `MovementSink`
corrects the keys to the yaw the player faces. Corrected keys are a little less
exact than the route's, so expect a few more plans for drift. The navigator's
own setting, `Navigator.Builder.turnHead(...)`, is read each tick.

**Pausing and resuming.** Paused, the step stops its navigator: the keys and the
head are let go at once, and a provider that drives is cancelled. On resuming, a
goal made from the flow's keys is asked for again and the trip begins afresh from
wherever the player is. `travel.at(goal)` holds while the feet are in the goal:
as an `ensures`, it makes a sequence rewind to the trip when an interrupt has
moved the player away, as in `goHome` above.

**Reading it.** `to(...)` returns a `Travel.Trip`; its `getNavigator()` is there
for drawing the route (`getSection()`) and for the stats on why it planned, and
`getProgress()` is the navigator's last report. A goal from the keys that comes
back null, a player supplier that gives null, or a host that provides no
`SimulationService` fails the step, saying which.

---

## 7. Baritone, or any provider that drives

Baritone moves the player itself, and depends on Minecraft, so the library never
imports it: you wrap it in a `PathProvider` that **drives**. A sketch against
Baritone's public API — the names below are from its 1.x releases; check them
against the version you ship:

```java
public final class BaritoneProvider implements PathProvider {

    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private Goal following;

    public boolean drives() {
        return true;
    }

    public Route plan(Goal goal, MotionState from) {
        return null;                                     // it drives; it does not hand out routes
    }

    public Progress follow(Goal goal) {
        if (goal != following) {
            baritone.getCustomGoalProcess().setGoalAndPath(new AsBaritoneGoal(goal));
            following = goal;
        }
        Vec3 feet = Vec3.of(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        if (goal.isMet(feet)) {
            return Progress.arrived();
        }
        if (!baritone.getCustomGoalProcess().isActive()) {
            return Progress.failed("Baritone stopped short of " + goal);
        }
        return Progress.running(goal.gap(feet).distance());
    }

    public void cancel() {
        baritone.getPathingBehavior().cancelEverything();
        following = null;
    }

    /** Any Core goal, as Baritone asks about one: block by block. */
    private static final class AsBaritoneGoal implements baritone.api.pathing.goals.Goal {

        private final Goal goal;

        AsBaritoneGoal(Goal goal) {
            this.goal = goal;
        }

        public boolean isInGoal(int x, int y, int z) {
            return goal.isMet(Vec3.of(x + 0.5, y, z + 0.5));
        }

        public double heuristic(int x, int y, int z) {
            return goal.gap(Vec3.of(x + 0.5, y, z + 0.5)).distance() * BLOCK_COST;   // in Baritone's cost units
        }
    }
}
```

`BLOCK_COST` is Baritone's cost of walking a block, which is a constant of the
Baritone version you use. Baritone turns the head and presses keys through its
own handling; if your version or fork exposes them, forward them as claims —
`Core.rotations().request(...)` and `Core.controls().move(...)` at your priority —
and a module with a higher priority still wins. The navigator itself claims
nothing while a driving provider drives.

For long distances with precise local moves, give the navigator a long-range
provider that returns a **coarse** route instead, and the local planner as
`local`: it plans each stretch precisely, as in §4.

---

## 8. Package map

| Package | What it is |
|---|---|
| `dev.px.navigation` | `Navigator` — follows any provider's routes through Core's controls and plans again when they part from the player; `Replan`, why it planned; `NavigatorStats`; `Travel` — travel steps for Core's flows, each a `Travel.Trip` with a navigator of its own |
| `dev.px.navigation.plan` | `LocalPlanner` — the precise short-range planner, a `PathProvider` over Core's `Simulation`; `Gait`, the keys a move holds; `PlanStats`, how a plan ended and what it threw away |
| `dev.px.navigation.danger` | `Danger` and `DangerField` — what makes a route dangerous, frozen for one plan; `Hostiles` — hostiles where they will be, by Core's prediction or any `Lookahead`; `DangerRule`, yours |

In Core, used throughout: `dev.px.core.navigation` (`Goal`, `Gap`, `PathProvider`,
`Route`, `Progress`) and `dev.px.core.control` (`ControlService`, `MovementSink`,
`ClickSink`, `Click`) — see [Core §10](../docs/10-movement.md).

---

## 9. Verifying

`dev.px.navigation.test.NavigationSmokeTest` runs **130 checks** in a plain JVM.
The game is a block grid moved through by Core's own movement rules, and the
hostiles are bodies in Core's `MovementRig`, seen only as positions. Travel
steps run in the [test kit](../testkit/README.md)'s sandbox, which the tests
use at test scope only.

| Suite | Covers |
|---|---|
| `PlannerTests` | routes across open ground, round a wall, onto a ledge, up half-blocks without jumping, over a three-block gap by sprint-jumping and never into it, and down into a 1×1 hole; every route's states exactly what the rules do with its keys; ending on the tick the goal is first met, even halfway through a move; a strict search's route no quicker than the default's, for over ten times the states; only the gaits given; no sprinting, no gap; the drop limit and your rule refusing moves and named in the reason; partial routes inside the range and from a spent budget; no route at all from a sealed shaft; each block asked about once per plan |
| `NavigatorTests` | arriving having planned once, key for key, facing each tick's yaw, then letting go of the keys and the head but keeping the goal; planning again for a shove, for a wall built across the route before it is reached, for a goal that moves and one that moves away after arriving; coarse routes followed a stretch at a time by the local planner, and steered along without one, jumping up a block; a provider that drives followed, passed on and cancelled; failing with the planner's reason, claiming nothing, and staying failed; stuck when no closer, and when every route is pushed off by something the simulation does not know; a higher claim from elsewhere winning the keys and the head |
| `DangerTests` | a hostile standing in the way walked past without danger and kept two blocks from with it; one walking across the path met where it goes when avoided where it is, and cleared when avoided where it will be; the rule told the gap between boxes, negative costs ignored, the limit, no hostiles meaning no danger, the horizon standing for later ticks; refreshing on a schedule |
| `TravelTests` | a trip arriving through the flow, planned once, claiming the keys and the head on the way and letting go after, reporting the blocks left; a goal already met; one `Travel` making a step for each place it is used; looped, setting off again when the goal moves away; goals from the flow's keys, and a null one failing; no route failing with the navigator's reason, so a `firstOf` moves on; stuck running on by default, recovered by `stuckAfter` and arriving, failing with `failWhenStuck`; a reflex on other controls leaving it walking, one on the movement keys pausing it, letting go at once and planning afresh on resume; `ensures(travel.at(goal))` rewinding to the trip after the player is thrown off; a provider that drives cancelled on pause and on finishing; the flow's priority read each tick; with `turnHead(false)`, arriving on its keys alone with the head held elsewhere and declaring only the keys; a null player state, a host with no simulation, and the builder naming what is missing |

Mutation-checked: the drop limit, your rule, the range, the goal checked
mid-move, danger, the replay through the world, drift, a goal that moves,
stuck, drift streaks, danger looking ahead, the block cache, the estimate, and
staying failed each fail a check when broken. One mutation is equivalent:
moving to a coarse route's next stretch when its section's goal is met, which a
section's route ending on that tick already does.

In travel steps: finishing on arrival, failing with the navigator, stopping the
navigator on pause, starting the trip again on resume, staying running when
stuck and `failWhenStuck`, the progress `stuckAfter` reads, the flow's priority
read live, applying the `navigator(...)` settings, `at(goal)`, a null goal, and
leaving the head alone with `turnHead(false)` (in the navigator and in what the
step declares) each fail a check when broken.

```
sh gradlew compileJava compileTestJava -q -Dorg.gradle.java.home=<JDK 20>
java -cp navigation/build/classes/java/main:navigation/build/classes/java/test:core/build/classes/java/main:core/build/classes/java/testFixtures:testkit/build/classes/java/main:$GSON \
     dev.px.navigation.test.NavigationSmokeTest
```

---

## Not yet included

- **A travel step that stays.** A travel step finishes on arrival. To keep near
  a goal that moves until something else is done, loop it:
  `Flow.loop(travel.to(goal)).until(done)` sets off again whenever the goal has
  moved away.
- **Planning off the game thread.** Plans run on the game thread inside their
  state budget; the planner reads the world through your `CollisionSpace`, which
  is not safe to read from another thread.
- **Breaking and bridging.** The planner only moves.
- **Water, ladders, elytra**, until Core's `Simulation` takes movement rules the
  adapter can add to.
- **Unloaded chunks.** The planner treats what your `CollisionSpace` does not
  report as air; keep the range inside what the client has loaded.
- **Presets for strict servers**, such as a cap on turning speed: the navigator's
  `turn(...)` template already limits how fast the head turns, and routes are
  re-planned when that drifts them, but the planner does not yet plan with a turn
  limit in mind.
