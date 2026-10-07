## 10. Movement

Three things that all come down to "where is the player going", in one package
with the game-shaped parts behind two small interfaces.

| `dev.px.core.movement` | | Needs |
|---|---|---|
| `.rotation` | who gets the head, and how it turns | `RotationSink`, two methods |
| `MovementCorrection` | keeping the keys honest once it has turned | nothing |
| `.simulation` | where things have been and where they are going | `CollisionSpace`, one method |

Neither interface lives on `Platform`, which stays 13 methods with no player,
world or packets in it.

### Rotations

One module owning where the player looks is fine. Two is a silent bug: whichever
handler ran last that tick wins, the outcome depends on subscription order, and
there is nowhere to look to find out who took the head.

So modules stop writing the rotation and start **asking** for it.

```java
// in a module's tick handler -- ask every tick, for as long as you want it
Core.rotations().request(this, eye.rotationTo(target), RotationPriority.HIGH, 30f);

// in the adapter, once, wherever rotations are written
Core.rotations().apply();
```

`request` files a claim; `apply()` resolves every live claim into one rotation
and hands it to your `RotationSink`. Nothing else changes about how a module is
written.

### Priorities and ties

Highest wins. `RotationPriority` has the usual five anchors (`LOWEST` 0 through
`HIGHEST` 100) and any int in between works, same as `event.Priority`.

Equal priorities go to **whoever acquired the rotation first**, and stay there
for as long as that module keeps asking. The obvious alternative — most recent
request wins — makes two same-priority modules trade the head back and forth once
a tick, which looks exactly like a bug and is very hard to find. Give them
different priorities if the other one should win.

### Claims expire, so nothing has to remember to let go

A claim lasts one tick unless renewed. A module keeps the rotation by continuing
to want it, and loses it by going quiet — which is what happens when it is
switched off, returns early, or throws.

That is the whole reason it works this way. Acquire-and-release has one failure
mode that matters: a module disabled while holding the rotation never releases,
and the player's head stays locked with nothing to blame. There is a `release()`
for letting go early, but **nothing depends on it being called**.

```java
rotations.request(this, RotationRequest.at(target)
        .priority(RotationPriority.HIGHEST)
        .step(30f)                      // degrees per tick; default is snap
        .hold(3)                        // survive two skipped ticks
        .mode(RotationMode.SILENT));
```

### There is no call ordering to get wrong

The obvious design resolves on the tick event, which means it has to run after
every module has filed. "After every module" is not something a priority can
express, because a module is free to use any priority too — and getting it wrong
makes every rotation one tick stale, which is subtle to diagnose and very visible
in game.

So resolution is lazy. `beginTick()` only opens a window; the rotation is computed
on the first **read** after that, which is by definition after whoever wrote.
Filing a claim invalidates the result again, and the turn always steps from the
rotation held when the tick opened — so reading twice, or reading before a module
has asked, cannot advance the turn twice or land on a different answer.

**Read and request in any order you like.** The only call an adapter places
deliberately is `apply()`, and placing it badly costs a tick of latency on that
one call and nothing else. `beginTick()` is wired to `TickEvent` automatically; an
adapter that does not post ticks calls it from its game loop, the same way it
would `runPendingSync()`.

### Letting go

When the last claim expires the rotation eases back to where the player is
actually looking, at `setReleaseStep(...)` degrees a tick, rather than snapping.

In `CLIENT` mode that costs nothing — the camera is already where the service left
it, so there is nothing to walk back and the service goes idle immediately. In
`SILENT` mode it is what stops the server-side head teleporting when a module
stops aiming.

### The sink

Two methods, and the only part of any of this that needs the game:

```java
public final class PlayerRotationSink implements RotationSink {

    public Vec2 getRotation() {
        EntityPlayerSP player = mc.thePlayer;
        return player == null ? Vec2.ZERO : Vec2.rotation(player.rotationYaw, player.rotationPitch);
    }

    public void apply(Vec2 rotation, RotationMode mode) {
        if (mode == RotationMode.CLIENT) {
            mc.thePlayer.rotationYaw = rotation.getYaw();
            mc.thePlayer.rotationPitch = rotation.getPitch();
        } else {
            pendingPacketRotation = rotation;         // written into the movement packet
        }
    }
}
```

Core decides *what* the rotation is — arbitration, stepping, clamping, easing —
and the sink decides *how* it is written. `CLIENT` and `SILENT` are a tag Core
attaches and the sink honours; Core does not know the difference.

It lives in `dev.px.core.movement.rotation`, keeping its seam local the same way
`PathSpace` and `ShaderBackend` do.

Optionally, `setSensitivity(slider)` rounds resolved rotations to the mouse grid
that sensitivity can actually produce (`RotationMath.snapToSensitivity`). Off
until the adapter supplies it, because Core cannot read the game's options.

### Movement correction

Turning the head is only half the problem. Minecraft derives motion from the key
input rotated by the player's yaw, so the moment a rotation reaches the movement
code the player walks off at an angle — into the wall, or off the edge.

```java
float applied = Core.rotations().getRotation().getYaw();
MovementCorrection.Input fixed =
        MovementCorrection.correct(cameraYaw, moveForward, moveStrafing, applied);
```

It solves for the keys that, at the new yaw, produce the motion the old yaw and
the player's real keys would have.

**Whether you need it depends on your sink.** A `SILENT` implementation that only
writes the outgoing packet leaves the game's own yaw alone, so motion was never
wrong. One that sets the yaw field around the movement update does need it. Core
supplies the arithmetic and stays out of the decision.

There are two modes because vanilla input is quantised — forward and strafe are
each −1, 0 or +1, which is eight directions and no more:

| | Accuracy | Looks like |
|---|---|---|
| `STRICT` (default) | up to 22.5° off | values a keyboard actually produces |
| `EXACT` | exact | fractional input no vanilla client sends |

No right answer, so it is an argument. `STRICT` is the default because a small
drift is a better failure than a property that cannot be explained.

### Simulation

`MovementMath.predict` says of itself that it is *"a trajectory, not a
simulation"* — it carries motion forward and walks straight through the floor.
`Simulation` is the version that collides.

```java
Core.simulation().setCollisionSpace(new WorldCollisionSpace());   // one method

MotionState now = MotionState.of(position, velocity, onGround);
MotionState landing = Core.simulation().landing(now, held, 40);
List<MotionState> path = Core.simulation().trace(now, held, 20);  // to draw it
```

The order of operations is reproduced from the game rather than invented, because
a plausible reordering gives answers that are *almost* right:

1. friction from the block underfoot — decided **before** moving, applied
   **after**, which is why stepping off ice slides one more tick
2. jump, plus the horizontal kick a sprint jump gets — and the keys still
   accelerate at the **ground** rate that tick, since the game's jump touches
   nothing but the velocity
3. the keys, scaled by 0.98 every tick, become acceleration, scaled against the
   cube of friction on the ground and flat in the air
4. move, sweeping **Y, then X, then Z**, zeroing the velocity on each blocked axis
5. step up, if the horizontal move was blocked from the ground
6. gravity and drag on the vertical, friction on the horizontal

The axis order is the part everyone gets wrong. All three at once lets a player
slide along walls they should have stopped against; X and Z before Y lets a
falling one clip the lip of a block they should have landed on.

**Modelled:** walking, sprinting, sneaking, jumping and sprint-jumping, gravity,
air drag, ground friction with per-block slipperiness, axis-separated collision,
stepping up ledges.

**Not modelled:** water and lava, ladders, cobwebs, slime, elytra, riding,
knockback, levitation, and the sneak clamp at an edge. Potion effects are not
modelled but they compose — hand in a `PhysicsProfile` already adjusted for them.

This is a faithful reproduction of the common case, not a bit-exact
reimplementation. Treat a twenty-tick prediction as a good estimate, read the
exclusion list before trusting it somewhere unusual, and gate on `isReliable()`
so the model tells you itself when it has stopped applying.

#### The oracle

One method, and it returns boxes rather than a yes or no — a sweep needs to know
*how far* it can travel, and that is a distance, not a flag:

```java
CollisionSpace world = region -> {
    List<Box> boxes = new ArrayList<>();
    forEachBlockIn(region, pos -> { if (isSolid(pos)) boxes.add(collisionBoxOf(pos)); });
    return boxes;
};
```

Called **once per simulated tick**, over the whole region the hitbox could reach,
so one call serves all three sweeps and the step retry. `slipperinessAt` is an
optional second method with a sensible default, for clients that care about ice.

#### How accurate it is

Measured by the test suite against the figures the Minecraft Wiki gives (checked
2026-10-06), not against our own constants:

| | Model | Minecraft | | Source |
|---|---|---|---|---|
| Walking | 4.3172 b/s | 4.317 | +0.00% | [Walking](https://minecraft.wiki/w/Walking) |
| Sprinting | 5.6123 b/s | 5.612 | +0.01% | [Sprinting](https://minecraft.wiki/w/Sprinting) |
| Sneaking | 1.2952 b/s | 1.295 | +0.01% | [Player § Movement speed](https://minecraft.wiki/w/Player#Movement_speed) |
| Sprint-jumping | 7.1268 b/s | 7.127 | −0.00% | [Sprinting](https://minecraft.wiki/w/Sprinting) |
| Walking on ice | 4.1572 b/s | 4.157 | +0.01% | [Walking](https://minecraft.wiki/w/Walking) |
| Walking on blue ice | 4.3760 b/s | 4.376 | +0.00% | [Walking](https://minecraft.wiki/w/Walking) |
| Walking on slime | 3.0400 b/s | 3.040 | −0.00% | [Walking](https://minecraft.wiki/w/Walking) |
| Jump height | 1.2522 blocks | 1.2522 | exact | [Jumping](https://minecraft.wiki/w/Jumping) |
| Jump Boost I / II | 1.8361 / 2.5168 | 1.8361 / 2.5168 | exact | [Jumping](https://minecraft.wiki/w/Jumping) |
| First tick of a fall | −0.0784 | −0.0784 | exact | gravity and drag, [Entity § Motion](https://minecraft.wiki/w/Entity#Motion) |
| Terminal fall speed | 78.4 b/s | 78.4 | exact | [Entity § Motion](https://minecraft.wiki/w/Entity#Motion) |

Nothing was tuned to produce these: they fall out of the game's constants
applied in the game's order. The suite holds them to a tenth of a percent.

Two rules took them there, and both are easy to miss. Horizontal movement ran a
uniform 2.05% fast until the keys were scaled by 0.98 each tick, as the game does
before they become acceleration (`PhysicsProfile.inputScale`); a diagonal hides
it, because the game normalises two keys after scaling them. And sprint-jumping
ran at 6.09 until the tick of each jump accelerated at the ground rate, as the
game's does.

> One trap worth knowing, because it cost this code a real bug. `walkSpeed`
> (0.216) is a **top speed** — what a readout shows and what `MovementMath`
> returns. `moveSpeedAttribute` (0.1) is the game's movement-speed attribute,
> which is what the acceleration formula takes. The game calls both of them "walk
> speed" in different places. Feeding the first into the formula that wants the
> second makes a simulated player travel two and a half times too fast, and a test
> that asserts against `walkSpeed` will not notice.

#### The biggest lever is yours, not ours

**Collision shape fidelity matters more for accuracy than any constant we ship.**

The oracle hands back boxes, so non-cube blocks are fully supported — but only if
you bother to return their real shapes. An adapter that answers `Box.block(x,y,z)`
for everything models a slab as a full cube, a fence as waist-high instead of
1.5 blocks, a carpet as a block, and every stair as a solid step. A prediction
walks the player up things they would bump into and through things they would
stand on, and no amount of tuning `gravity` will fix it.

Returning the game's own collision shape is usually one call:

```java
CollisionSpace world = region -> {
    List<Box> boxes = new ArrayList<>();
    forEachBlockIn(region, pos -> {
        AxisAlignedBB shape = world.getBlockState(pos).getBlock()
                .getCollisionBoundingBox(world, pos, state);       // the real one
        if (shape != null) {
            boxes.add(Box.of(shape.minX, shape.minY, shape.minZ,
                             shape.maxX, shape.maxY, shape.maxZ));
        }
    });
    return boxes;
};
```

If you do one thing to make prediction accurate on your version, do this one.

#### Knowing when to stop trusting it

The exclusion list above is not the danger — a caller cannot *see* the exclusion
list at runtime, so a prediction goes from good to confidently wrong with nothing
marking the transition. `DriftMonitor` marks it.

```java
// once a tick, with the input that was held over the tick that just elapsed
Core.simulation().observe(currentState, heldInput);

if (Core.simulation().isReliable()) {
    MotionState landing = Core.simulation().landing(currentState, heldInput, 40);
}
```

It simulates one step from the state it was last given and compares that to where
the player actually ended up. While the answer is close the model is describing
the game; the moment someone steps into water or deploys an elytra the error jumps
and `isReliable()` goes false. That turns every unmodelled branch from a silent
wrong answer into a detectable one — on any version, without us having to
enumerate what changed.

Errors are tracked per axis, because the two fail for different reasons and
knowing which moved is most of the diagnosis: vertical drift points at gravity,
drag or a collision shape; horizontal drift points at friction, the speed
attribute, or a branch that is not modelled. Teleports are recognised and dropped
rather than folded into the average.

#### Calibrating against your version

`PhysicsCalibration` is the offline half — a development tool, not something a
shipped client runs. The adapter records what actually happened over a few hundred
ticks, it replays each transition, and the difference is a number:

```java
calibration.record(before, heldInput, after);          // one per tick

System.out.println(calibration.check(PhysicsProfile.vanilla(), world).describe());
//  120 samples: mean 0.004182 b/t (h 0.004182, v 0.000000), worst 0.004212

PhysicsProfile base = PhysicsProfile.vanilla();
System.out.println(calibration.tune(base::withMoveSpeedAttribute, 0.08, 0.12, 41, world));
//  best 0.098000 -> 120 samples: mean 0.000003 b/t ...
```

`tune` is a plain linear sweep over one field — one dimension, dominated by
replaying the samples, and it cannot converge on a local minimum that is not
there. Narrow the range and call it again to refine. The suite proves it works by
planting an answer: it generates samples under a profile it never reveals and
checks the scan finds its way back to that constant.

**Sample quality is everything.** Record from flat ground with no water, no
ladders and no lag spikes, because a sample taken during an unmodelled branch is
noise the scan will happily fit a constant to. `DriftMonitor` is how you find a
clean stretch to record.

### Prediction: where *they* will be

"Where will *I* be" and "where will *they* be" look like one problem and are not.
Your input is known, so simulate it. Theirs is not: nobody tells the client what
another player is pressing. `Core.prediction()` works it out from how they have
moved, runs it through the same `Simulation` — and, because players do not always
move by the rules, also says how soon they could possibly be anywhere.

```java
Core.prediction().setPhysics(myEntityPhysics);                 // their effects and attributes, from your client

Prediction next = Core.prediction().predict(target, 6);       // any Tracked entity
Vec3 likely = next.positionAt(4);                             // likely: the heaviest future
int soonest = next.earliestPossible(holeBox);                 // possible: a floor, however they move
MotionEstimate now = next.getEstimate();                      // what they are pressing, as best it can tell
Behaviour seen = next.getBehaviour();                         // how they actually move: speed, drops, how legit

// a future of your own, weighed alongside the rest
Prediction holing = Core.prediction().predict(target, 8, Scenario.toward("hole", holeCentre));
double odds = holing.chanceBy(state -> inHole(state.getPosition()), 5);
if (holing.isReliable()) { ... }
```

Nothing needs feeding. Every `Tracked` keeps the last few ticks of where it was
(`EntityTracker.setHistory`, 30 unless set), and which of those positions were
news (`isFreshAgo`), and the service reads that.

1. **News, not ticks.** Servers do not send every entity's position every tick —
   vanilla, as far as we know, sends a walking player's every other tick. A
   position that has not changed may mean they stopped or that nothing was sent.
   Your `EntitySource.positionStamp` says which; without it, each entity learns
   how often its positions arrive and treats holding still for longer than that as
   stopping. Predictions start from the last real position and are already as far
   along as the entity is (`Prediction.getAge()`).
2. **Rules.** Your `EntityPhysics` says what each entity moves by right now — its
   Speed or Slowness, its attributes, its pose — on top of the simulation's
   profile. What it tells the prediction is known, so a player with Speed II is
   never mistaken for a cheater. Return null for an entity that is not walking —
   riding, gliding, swimming — and only how it has been moving is used.
3. **Estimate.** Between each two positions that were news, every input they could
   have held — standing, walking, sprinting or sneaking, straight or on two keys,
   jumping or not — is run through `Simulation` for the ticks between, and the one
   landing closest is kept. Collisions, step-ups and the sprint-jump kick come for
   free. On positions a tick apart the starting velocity is exactly what the rules
   carry — not the distance last moved, which on the ground is nearly twice as
   much.
4. **Behaviour.** Every new span is learned from (`Behaviour`): the fastest they
   have moved, risen and dropped, how often the server sends them, and how much of
   their movement the rules explain. When their ground movement keeps outrunning
   the rules — a speed hack, or an effect your client did not report — the rules
   are scaled up for them, so "keeps going" and "heads for the hole" get there as
   fast as they do. A move faster than the teleport speed is a pearl or a setback
   and is not learned from.
5. **Futures.** `Scenario.HOLDS`, `STOPS` and `CARRIES` unless you set others, plus
   any you pass, are each played forward with collision. `CARRIES` keeps moving
   exactly as they last moved with no friction: how a cheat that sets velocity
   directly moves, and nothing by the rules does.
6. **Weights.** Each scenario is also played from a few positions back and
   compared with where they really went: smaller error, more weight. A scenario's
   `getPrior()` multiplies its weight before any evidence, for futures the evidence
   cannot tell apart. Someone no scenario explains is marked unreliable.
7. **Possible.** `earliestPossible(box)` is the soonest they could touch a box, at
   the fastest of what their rules allow and what they have been seen to do,
   straight through any wall, falling or climbing as fast as they ever have. It is
   a floor: only moving faster than they have yet been seen to can beat it.

Against the simulation's own truth: steady movement, sprint-jumping, running off a
ledge or into a wall, positions every other tick with or without stamps, Speed II
known or unknown, a speed hack running the rules twice as fast, and a strafe hack
setting velocity directly are all predicted to within rounding ten ticks out,
where a straight line misses a sprint-jumper by 2.6 blocks. The possible bound is
never late across them all. Someone who changes what they are doing cannot be
predicted by anything, but the weights say so.

**Feed it the server's positions.** The position the game draws for another
player is eased toward the one the server sent over a few ticks, which smooths
and delays every move. Report the position the server last sent, and a stamp
that changes whenever it sends one.

**Horizons need history.** Futures are weighed by playing them from that far
back, so a prediction further ahead than the history kept, less three ticks, is
weighed by priors alone and is never reliable.

**On 1.8, expect worse.** Its entity packets round positions to 1/32 of a block,
and a single tick's move read through that much rounding gets direction wrong by
several degrees. Fitting over several ticks would fix it at about three times the
cost; it is not done.

A prediction costs about 0.1–0.2 ms: one fetch from your `CollisionSpace`, then a
few hundred simulated steps against the boxes it returned.

#### Looking ahead

Anything that lands some ticks after you start it — an explosion, an arrow —
needs the target where it will be then, and `Lookahead` is the one-method answer
the libraries on Core take for it:

```java
Lookahead.none()                                   // where they are now
Lookahead.extrapolated()                          // a straight line along their last move
Lookahead.predicted(Core.prediction())            // the likeliest future; where they are while it is unreliable
(entity, ticks) -> myOwnGuess(entity, ticks)       // anything else

Tracked<?> then = lookahead.at(target, 8);        // a Tracked.projected stand-in, or the entity itself
```

The combat searches and the projectile aim solver both take one, so a client
decides once how it guesses where people will be.

#### Recording real movement, and replaying it

Every number above comes from movement the simulation produced itself. Real
players are measured by recording them:

```java
MovementRecorder recorder = new MovementRecorder(Core.prediction())
        .names(entity -> ((PlayerEntity) entity).getName().getString());
recorder.putMetadata("server", "my test server, 1.21, strafe module on");
recorder.begin("strafe vs legit", Core.entities().get(EnemyTracker.class));
recorder.bus(Core.bus());
// ... play ...
MovementJson.write(recorder.end(), Files.newBufferedWriter(path));

// later, in a test or on your desk
ReplayReport report = MovementReplay.of(MovementJson.read(Files.newBufferedReader(path)))
        .horizons(1, 5, 10)
        .configure(prediction -> prediction.setBacktest(6))         // whatever you want to try
        .run();
System.out.println(report);
```

A recording keeps, per tick and per entity, exactly what the prediction reads —
position, facing, size, stamp — plus the rules your client gave and the blocks
around each entity, re-read every few ticks so placed and broken blocks are kept.
A replay puts a real `EntityService` and `PredictionService` back in the same
place and scores every horizon against the positions the server really sent: the
mean, 95th percentile and worst miss, how much of it called itself reliable and
what those missed by, how often the possible bound was late, and what was learned
of each entity — including how often your server sends positions.

The way to know how a cheat looks is to record one: run your own client's speed,
strafe or hole-snap module on a local server and record it from a second client.

### Where the physics numbers live

Related change: gravity, drag, friction, walk speed and the effect multipliers
used to be `public static final` on `MovementMath`. They are not universal truths
— they are one version's values — so a client on 1.21 silently got 1.8's answers
with no way to say otherwise short of forking the class. They are a
`PhysicsProfile` now:

```java
double speed = MovementMath.walkSpeed(2, 0);                   // the default profile
double ice   = MovementMath.friction(slippery, motion, 1d);    // an explicit one

MovementMath.setProfile(PhysicsProfile.vanilla().withWalkSpeed(0.2806d));   // client-wide
```

`walkSpeed` and `moveSpeedAttribute` are the pair to be careful with: the first is
a speed in blocks per tick, the second is the attribute the acceleration formula
multiplies. See the accuracy notes above.

#### Where each default comes from

Every default in `PhysicsProfile.vanilla()` is the Minecraft Wiki's, checked on
2026-10-06, and each field's Javadoc names its page. They are defaults, not
anything Core depends on: when Mojang changes one, build a profile that says so
(`withGravity`, `withStepHeight`, …) and nothing else moves.

| Field | Default | Source |
|---|---|---|
| `moveSpeedAttribute` | 0.1 | "the default base value is 0.1", [Walking](https://minecraft.wiki/w/Walking) |
| `inputScale` | 0.98 | implied: base 0.1 and a walking acceleration of 0.098, [Player § Movement speed](https://minecraft.wiki/w/Player#Movement_speed) |
| `groundFriction` | 0.91 | horizontal drag, and 0.91 × friction on the ground, [Entity § Motion](https://minecraft.wiki/w/Entity#Motion) |
| `defaultSlipperiness` | 0.6 | "Friction is 0.6 by default" (0.8 slime, 0.98 ice, 0.989 blue ice), [Entity § Motion](https://minecraft.wiki/w/Entity#Motion) |
| `gravity` | 0.08 | `gravity` attribute, [Attribute](https://minecraft.wiki/w/Attribute); [Entity § Motion](https://minecraft.wiki/w/Entity#Motion) |
| `drag` | 0.98 | vertical drag, [Entity § Motion](https://minecraft.wiki/w/Entity#Motion) |
| `jumpVelocity` | 0.42 | `jump_strength` attribute, [Attribute](https://minecraft.wiki/w/Attribute) |
| `stepHeight` | 0.6 | `step_height` attribute, [Attribute](https://minecraft.wiki/w/Attribute) |
| `sprintMultiplier` | 1.3 | the `sprinting` modifier, 0.3 `add_multiplied_total`, [Attribute](https://minecraft.wiki/w/Attribute); "30 percent faster", [Sprinting](https://minecraft.wiki/w/Sprinting) |
| `sneakMultiplier` | 0.3 | `sneaking_speed` attribute (1.21+), [Attribute](https://minecraft.wiki/w/Attribute) |
| `speedPerLevel` | 0.2 | "+20% multiplied by the effect level", [Speed](https://minecraft.wiki/w/Speed) |
| `slownessPerLevel` | 0.15 | "15% × level", [Slowness](https://minecraft.wiki/w/Slowness) |
| `hitboxWidth` × `hitboxHeight` | 0.6 × 1.8 | standing, [Player](https://minecraft.wiki/w/Player) (1.5 sneaking, 0.6 swimming) |
| `walkSpeed` | 0.21585 | 0.098 / (1 − 0.546), [Player § Movement speed](https://minecraft.wiki/w/Player#Movement_speed) |
| `groundAccelerationBase` | 0.16277136 | the game's code; pinned by the walking, ice and slime speeds above |
| `airAcceleration`, `sprintAirBonus` | 0.02, 0.006 | the game's code; pinned by sprint-jumping at 7.127 |
| `sprintJumpBoost` | 0.2 | the game's code; pinned by sprint-jumping at 7.127 |
| `jumpBoostPerLevel` | 0.1 | pinned by the Jump Boost I and II heights, [Jumping](https://minecraft.wiki/w/Jumping) |

The last four are not stated on the wiki, so the suite pins them by the speeds it
does give: a wrong value moves at least one row of the accuracy table, and the
suite holds every row to a tenth of a percent.

**Since 1.20.5 most of these are per-player attributes** a server can change:
`movement_speed`, `jump_strength`, `gravity`, `step_height` and `scale` (1.20.5),
and `sneaking_speed` (1.21) — see [Attribute](https://minecraft.wiki/w/Attribute). On those versions,
read them from the player when you build the profile rather than trusting the
default. `Core.prediction()` uses one profile for everyone, resized to each
entity's hitbox; an enemy with Speed II or a changed attribute is predicted as if
they had neither.

Every method that needs them has both forms. `TICKS_PER_SECOND` stays a constant,
because it is not physics — it is how the protocol defines a tick, and a server
running slower is lag rather than a different rule.

Water, cobwebs, ladders and per-block slipperiness are deliberately **not** in
the profile. They are branches in the game's movement code, or facts about a
block, not multipliers: the honest place for them is a simulation that models the
branches and a `CollisionSpace` that reports the block — which is why `friction`
takes slipperiness as an argument.

### Timeline recording

`Core.timeline()` records what the client and server said to each other, and
what the client was doing at the time, into one exactly-ordered timeline
between two points you choose.

```java
Core.network().setDescriber(new MyPacketDescriber());    // once, from the adapter; see §11
Core.timeline().putMetadata("gameVersion", "1.8.9");

Core.timeline().begin("velocity test");
// ... play ...
Core.timeline().mark("hit");
// ... play ...
Timeline timeline = Core.timeline().end();

try (Writer out = Files.newBufferedWriter(path)) {
    TimelineJson.write(timeline, out);                    // JSON Lines, one entry per line
}
```

Every entry carries a **seq** (global, gapless, the one true order), a monotonic
**nanos** since `begin`, the client **tick** it happened in, and its
**`EntryType`**: tick start and end, packet, motion before and after the client
reports it, correction, world change, mark. Packets add direction, phase, the
describer's type name and the name of its `PacketKind`, and whatever position, velocity,
rotation, ground state and extra fields the describer pulled out. Motion
entries add position, velocity, ground, the held `MovementInput`, sprint and
sneak state, and rotation with last tick's rotation.

It is built from events rather than hooks of its own, and adapters post them
for their modules anyway:

| `Core.hooks()` call | When | Gives the timeline |
|---|---|---|
| `tickStart()` / `tickEnd()` | already called | tick boundaries |
| `packetSent(p)` | before an outbound packet is written | outbound traffic |
| `packetReceived(p)` | network thread, as an inbound packet is decoded | true arrival order |
| `packetApplied(p)` | game thread, as that packet's handler runs | the tick it took effect |
| `motionPre(...)` / `motionPost(...)` | around the client's movement packet | what the client believed |
| `worldLoaded` / `worldUnloaded` | already called | world load and unload |

Post `received` and `applied` with the **same packet instance**: that is what
links them, so `timeline.linked(applied)` finds the arrival and the difference
in `nanos` is how long it sat in the queue. An applied packet the describer
marked `asCorrection()` — whatever your version's teleports, knockback and
pushes are — is followed immediately by a `CORRECTION` entry pointing at it,
whose `clientSeq` field is the client's last motion entry, the state the
server just overruled. Core decides nothing about which packets those are.

The queries are about how entries relate, not about single entries:
`responsesTo(sent, Packets.TELEPORT, 5)` — your own kind — finds what arrived within five ticks
of an outbound packet, going by timing; `correlated(entry)` pairs a
transaction, keep-alive or teleport confirm with its reply exactly, by the
correlation key the describer gave both; `between("jump", "landed")` slices
between two marks.

**Ordering holds across threads.** Handlers run on the posting thread, so
packets arrive from the network thread while ticks arrive from the game thread.
Seq, time and tick are all assigned under one lock in the same step that
appends the entry, so seq never ties and time never runs backwards along it.
Tick boundaries listen at the extremes of the priority range, so a packet a
module sends from its own tick handler lands inside that tick.

**The describer** is the one piece of version knowledge it needs: one method
from packet to `PacketDescription`, installed on `Core.network()` and shared
with the network services in §11. Without one, packets are recorded under
their class name as `OTHER`, which in an obfuscated build means `a`. A describer
that throws costs that packet its description, not its entry, and is logged
once.

**Idle is free.** The recorder subscribes to nothing and allocates nothing until
`begin`, and `end` gives both back. It reads nothing from `RotationService` or
`SimulationService`, and neither knows it exists. A recording keeps at most
`setCapacity(n)` entries (262,144 by default), dropping the oldest and counting
them; `setFilter` narrows which packets are kept.

### Not using any of this

Install no `RotationSink` and that service is inert: claims are still accepted
and arbitrated, nothing is ever applied, `isActive()` stays false and `apply()`
returns false. A client that keeps writing rotations itself loses nothing, and a
module that files a claim into a client with no sink is harmless rather than
broken.

Install no `CollisionSpace` and the tracker still works in full — it needs no
world at all — while simulation falls back to `CollisionSpace.empty()` and
answers "where would this go if nothing were in the way". That is a useful
question, so nothing warns about it.

`MovementCorrection` is static and needs nothing installed, and nothing outside
`dev.px.core.movement` references any of it.

Never call `Core.timeline().begin(...)` and the recorder never subscribes to
anything. Install no `PacketDescriber` and a recording still works, with
packets named by class.
