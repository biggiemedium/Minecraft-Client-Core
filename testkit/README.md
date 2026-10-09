# Test kit

A world with no game in it, for testing flows — and anything else built on
[Core](../README.md) — in a plain JVM. A bot that "backs off when a zombie
arrives while it is mining, then resumes and finishes" is something you can
check in a unit test, in milliseconds, without starting Minecraft.

It is for your **tests**, not your client: add it as `testImplementation`. It
depends only on Core.

```groovy
dependencies {
    testImplementation project(':testkit')     // inside this repository
}
```

---

## Contents

1. [A sandbox](#1-a-sandbox)
2. [The world](#2-the-world)
3. [The player](#3-the-player)
4. [Other entities](#4-other-entities)
5. [Fake pathfinders](#5-fake-pathfinders)
6. [Package map](#6-package-map)
7. [Verifying](#7-verifying)

---

## 1. A sandbox

```java
Sandbox sandbox = Sandbox.create();                         // or create(yourPhysicsProfile)
sandbox.world().floor(64, -20, 20);
SimPlayer me = sandbox.player(Vec3.of(0.5, 64, 0.5));
SimEntity zombie = sandbox.entity("zombie", Vec3.of(8.5, 64, 0.5), tick -> MovementInput.forward(90f));

FlowHandle bot = sandbox.flows().start("bot", myFlow, 50);
int ticks = sandbox.runUntil(bot::isDone, 400);              // or run(n), tick()

check(bot.getState() == FlowHandle.State.DONE);
System.out.println(bot.view());                             // why it did what it did
```

**Its own services.** Each sandbox builds its own controls, rotations,
simulation, prediction, entities, targeting, memory and flows, with no `Core`
instance behind them, so any number run side by side in one JVM without
touching each other. That is also the one rule for steps you want to test:
**reach services through the `FlowContext`**, not through `Core`'s statics. The
sandbox provides its simulation, prediction, entities, targeting, `SimWorld`,
`SimPlayer` and itself to steps through `c.service(...)`.

**A tick** runs as the game's would:

1. `TickEvent` PRE: the entity service reads the world, the controls and
   rotations open the tick, and the flows run.
2. The rotation and the keys are applied to the player, as an adapter would
   where the game reads its input.
3. The player moves by the real rules with them, and every entity by its script.
4. `TickEvent` POST, and the clock moves on.

**Its own clock.** Each tick moves the clock on by `setTickMillis` (50 ms by
default — the sandbox's choice, not a claim about any game's tick rate), so a
flow waiting `Span.seconds(2)` finishes after the same number of ticks every
run.

**Events.** `post(event)` puts any event on the sandbox's bus, as the game would
through your adapter; `leaveWorld()` and `joinWorld()` post the world events
that pause and resume every flow. `logger()` keeps everything the services
logged, for `hasWarning("...")`. `close()` stops the services.

---

## 2. The world

```java
sandbox.world()
        .floor(64, -20, 20)                  // a floor whose top is at y = 64
        .fill(5, 64, -3, 5, 65, 3)           // a wall two blocks high
        .slab(8, 64, 0, 0.5)                 // half a block
        .shape(9, 64, 0, myShape)            // any BlockShape
        .remove(0, 63, 4)                    // a hole in the floor
        .slipperiness(2, 63, 2, 0.98);       // ice, by your rules
```

It is both the `CollisionSpace` the simulation and the navigation planner move
through and the `BlockView` rays and the combat library read. Change it between
ticks to change the world under a running flow.

---

## 3. The player

```java
SimPlayer me = sandbox.player(Vec3.of(0.5, 64, 0.5));
me.getPosition();         me.getState();          me.getTrail();      // where the flows took it
me.getClicks();           // ["hold USE", "release USE", "click ATTACK"]
me.getLastKeys();         // what it moved with last tick
me.push(Vec3.of(0.4, 0.4, 0));        // knock it back
me.teleport(...);   me.face(...);   me.rules(differentProfile);       // make the game disagree with the plan
```

It is the movement, click and rotation sink, as your adapter's would be. Keys
last the tick they were applied; with none, it stands still. Keys meant for a
yaw other than the one it faces are corrected to it exactly, as an adapter using
`MovementCorrection` would. Claims belong to the tick they are filed in: file
them from a flow, or during the tick, not between ticks.

---

## 4. Other entities

```java
SimEntity zombie = sandbox.entity("zombie", Vec3.of(8.5, 64, 0.5), tick -> MovementInput.forward(90f))
        .tag("hostile");
zombie.script(tick -> MovementInput.none(0f));      // stop
zombie.remove();                                    // gone from the world

sandbox.targets().best(sandbox.selector());         // found like any tracked entity
sandbox.tracked(zombie);                            // as Core sees it
sandbox.prediction().predict(sandbox.tracked(zombie), 10);
```

Moved by Core's movement rules with keys from a script, and seen by the entity
service only as positions — read as each tick opens, as in game. What an entity
*is* — hostile, a friend — is its name and tag; the kit knows nothing about mobs.

---

## 5. Fake pathfinders

```java
FakeProviders.straightLine();                 // a coarse route straight at the goal's anchor
FakeProviders.unreachable();                  // never a route
FakeProviders.routes(first, second);          // these, in order, then none
FakeProviders.driver(sandbox.getPlayer(), 0.3);   // drives: moves the player itself, as Baritone would
```

Each counts how often it was asked (`getPlans`, `getFollows`, `getCancels`), so a
test can check what was planned for, and when.

---

## 6. Package map

| Package | What it is |
|---|---|
| `dev.px.testkit` | `Sandbox` (services, tick runner, clock), `SimWorld`, `SimPlayer`, `SimEntity`, `FakeProviders`, `SandboxLogger` |

---

## 7. Verifying

`dev.px.testkit.test.TestkitSmokeTest` runs **30 checks**: the world's blocks,
shapes, collisions and slipperiness; the player standing still without keys,
moving by the real rules with them, correcting keys meant for another yaw, being
pushed; held buttons let go; entities tracked as the tick opens, moving by script,
found by targeting nearest first, predicted reliably, removed; the clock; two
sandboxes sharing nothing; and the fake pathfinders.

Core's flow engine is tested in the sandbox too: `FlowTests`, in Core's suite.
