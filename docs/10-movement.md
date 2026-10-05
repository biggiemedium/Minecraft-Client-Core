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
2. jump, plus the horizontal kick a sprint jump gets
3. input becomes acceleration, scaled against the cube of friction on the ground
   and flat in the air
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

Measured by the test suite against the figures Minecraft is measured at, not
against our own constants:

| | Model | Minecraft | |
|---|---|---|---|
| Walking | 4.405 b/s | 4.317 | +2.05% |
| Sprinting | 5.727 b/s | 5.612 | +2.05% |
| Sneaking | 1.322 b/s | 1.295 | +2.05% |
| Jump height | 1.2522 blocks | 1.2522 | exact |
| First tick of a fall | −0.0784 | −0.0784 | exact |

**Vertical is exact** — those numbers fall straight out of gravity, drag and the
jump constant applied in the right order, and nothing was tuned to produce them.

**Horizontal is uniformly 2% fast.** Uniform across all three, so it is one
systematic offset rather than noise. Core reproduces the game's formula with the
game's documented constants and that is where it lands; closing the last two
percent is what calibration below is for, because the honest way to get it is to
measure your version rather than have us guess harder.

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

#### Tracking, which is a different question

"Where will *I* be" and "where will *they* be" look like one problem and are not,
so they are answered by different mechanisms:

```java
// every tick, for anything worth knowing about -- the key is opaque, so Core
// still never learns what an entity is
Core.simulation().record(other.getEntityId(), position, other.onGround);

Vec3 soon = Core.simulation().predict(id, 2);
```

Your own movement is deterministic: the input is known, so simulate it. Another
player's is a guess — nobody tells the client their intentions, so all there is to
go on is where they have been. `predict` extends their **measured** velocity in a
straight line, which is worth trusting for a tick or two of lag compensation and
not much further. `coast` sits between the two: it falls and collides, but still
assumes they do nothing of their own accord.

Velocity is measured from position deltas, not read from a motion field, because
for anything but the local player there is no motion field to read. Which also
means: **record on the tick, not on the frame.** Positions read during rendering
are interpolated — smoothed and slightly behind — and differencing them gives a
velocity that is both damped and late.

Every track is a bounded ring, so memory is `tracked × historyTicks` and does not
grow over a session, and tracks nobody updates are dropped once a tick.

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

Every method that needs them has both forms. `TICKS_PER_SECOND` stays a constant,
because it is not physics — it is how the protocol defines a tick, and a server
running slower is lag rather than a different rule.

Sprinting, sneaking, water, cobwebs and per-block slipperiness are deliberately
**not** in the profile. They are branches in the game's movement code, not
multipliers, and the honest place for them is a simulation that models the
branches — which is why `friction` takes slipperiness as an argument.

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
