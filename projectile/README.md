# Projectile

Projectiles for Minecraft clients, built on [Core](../README.md) and just as
version-independent: where an arrow, a pearl or a potion goes, what it hits, and
how to aim one at somebody who is moving. It is what a Trajectories module (where
this will land, drawn before you throw), a BowAim module, and a "where did their
pearl go" module are built on.

Like Core, it has **no Minecraft on its classpath** and **no game values in its
code**. Gravity, drag, the order a projectile ticks in, how hard each one is
thrown and what it can hit are supplied by your client. The
[wiki](https://minecraft.wiki/w/Entity#Motion) gives them for current Java, and
[§1](#1-your-projectiles) and [§2](#2-launching) show them written out as a
client would.

**Status.** Motion for every tick order, launches, flights through your blocks
and entities, following projectiles already in the air, and aiming at points and
at moving targets are done. See [Not yet included](#not-yet-included).

**Requires:** Core, Java 8. Inside this repository:

```groovy
dependencies {
    implementation project(':projectile')     // brings Core with it
}
```

Without Gradle, copy `projectile/src/main/java/dev/px/projectile` alongside Core's sources.

---

## Contents

1. [Your projectiles](#1-your-projectiles)
2. [Launching](#2-launching)
3. [Where it goes](#3-where-it-goes)
4. [Someone else's](#4-someone-elses)
5. [Aiming](#5-aiming)
6. [When Mojang changes something](#6-when-mojang-changes-something)
7. [Package map](#7-package-map)
8. [Verifying](#8-verifying)

---

## The one idea

The library supplies **mechanisms**; your client supplies **facts**.

| Projectile ships | Your client supplies |
|---|---|
| stepping a projectile, in any of the six orders | its gravity, drag and order |
| the line test from one tick to the next, against blocks then entities | your blocks' shapes, the entities it can hit, and how much bigger it finds them |
| a launch from where you look | the power, pitch offset, spread, how much of your movement it carries, where it appears |
| a bound on where the randomness can take it | how much randomness there is |
| an aim solver that flies its answer through your world before trusting it | where your target will be, and where on them to aim |

There is no list of projectiles. You make one `ProjectileRules` for each thing
your client throws or shoots; leave a part out and the builder names what is
missing, so no game number slips in as a default.

---

## 1. Your projectiles

How a projectile flies, from the [wiki](https://minecraft.wiki/w/Entity#Motion):
each tick it accelerates (gravity), drags (its velocity is multiplied by the
drag) and moves, in an order that depends on what it is.

```java
// Current Java, from minecraft.wiki/w/Entity#Motion and minecraft.wiki/w/Projectile#Collision
ProjectileRules arrow = ProjectileRules.builder("arrow")
        .gravity(0.05)
        .drag(DragRule.inside((x, y, z) -> isWater(x, y, z), 0.6f, 0.99f))   // minecraft.wiki/w/Arrow#Movement
        .order(StepOrder.POSITION_DRAG_ACCELERATION)
        .entityMargin(0)
        .build();

ProjectileRules pearl = ProjectileRules.builder("pearl")
        .gravity(0.03)
        .drag(0.99f)
        .order(StepOrder.ACCELERATION_DRAG_POSITION)
        .entityMargin(0.3)
        .build();
```

| Projectile | `gravity` | `drag` | `order` | `entityMargin` |
|---|---|---|---|---|
| Arrow | 0.05 | 0.99, 0.6 in water | `POSITION_DRAG_ACCELERATION` | 0 |
| Trident | 0.05 | 0.99, not slowed by water | `POSITION_DRAG_ACCELERATION` | 0 |
| Llama spit | 0.06 | 0.99 | `POSITION_DRAG_ACCELERATION` | not on the wiki |
| Egg, snowball, ender pearl | 0.03 | 0.99 | `ACCELERATION_DRAG_POSITION` (1.21.2+) | 0.3 |
| Splash and lingering potion | 0.05 | 0.99 | `ACCELERATION_DRAG_POSITION` (1.21.2+) | 0.3 |
| Experience bottle | 0.07 | 0.99 | `ACCELERATION_DRAG_POSITION` (1.21.2+) | 0.3 |

Sources: gravity, drag and order from [Entity#Motion](https://minecraft.wiki/w/Entity#Motion),
which notes that thrown projectiles "changed in Java Edition 1.21.2, from
'Position, Drag, Acceleration' to 'Acceleration, Drag, Position'"; arrows in
water from [Arrow#Movement](https://minecraft.wiki/w/Arrow#Movement); tridents from
[Trident](https://minecraft.wiki/w/Trident); entity margins from
[Projectile#Entity collision](https://minecraft.wiki/w/Projectile#Entity_collision),
which says thrown projectiles "treat entities as being 0.6 blocks larger than
they actually are in every axis" and arrows and tridents do not inflate them.

- **Gravity** is positive downward: the wiki's table writes −0.05.
- **Drag** is marked a `float` on the wiki. Write `0.99f`: the game multiplies by
  the float, which is not quite 0.99.
- **`DragRule`** is asked once a tick, with where the projectile was when the tick
  began. `DragRule.inside(cells, inside, other)` asks about the cell the position
  is in. The wiki does not say how the game decides a projectile is in water;
  write your own rule if yours asks differently.
- **`StepOrder`** has all six orders, so whatever your version does, one fits.

`Motion` is a projectile moving by its rules through nothing, a tick at a time:
the cheap half of a flight, for when only the motion matters.

```java
Motion motion = Motion.of(arrow, position, velocity);
motion.tick();
motion.getPosition();
```

---

## 2. Launching

How a projectile leaves whoever throws it, following
[Projectile#Initial conditions](https://minecraft.wiki/w/Projectile#Initial_conditions):
the direction is where they look, turned by the pitch offset; it is scaled by
the power, then gets some of their velocity.

```java
LaunchRules pearlThrow = LaunchRules.builder()
        .power(1.5)
        .spread(0.0172275)
        .shooterVelocity(ShooterVelocity.ALL_BUT_VERTICAL_ON_GROUND)
        .origin(LaunchOrigin.below(0.1))                       // not on the wiki: your game's
        .build();

LaunchRules bow = LaunchRules.builder()
        .power(() -> bowPower(useTicks()))                     // 0 to 3 with the draw: your formula
        .spread(0.0172275)
        .shooterVelocity(ShooterVelocity.ALL_BUT_VERTICAL_ON_GROUND)
        .origin(LaunchOrigin.below(0.1))
        .build();

Shooter you = Shooter.of(eyePosition(), playerVelocity(), isOnGround());
Launch launch = pearlThrow.launch(you, rotation());
```

| Launch | `power` | `pitchOffset` | `spread` |
|---|---|---|---|
| Snowball, egg, ender pearl | 1.5 | 0 | 0.0172275 |
| Potion | 0.5 | −20 | 0.0172275 |
| Experience bottle | 0.7 | −20 | 0.0172275 |
| Bow | 0 to 3, with the draw | 0 | 0.0172275 |
| Crossbow | 3.15 | 0 | 0.0172275 |
| Trident | 2.5 | 0 | not on the wiki |

Sources: thrown projectiles from
[Projectile#Initial conditions](https://minecraft.wiki/w/Projectile#Initial_conditions);
bows and crossbows from [Arrow](https://minecraft.wiki/w/Arrow) ("0 to 3 for bows
depending on how far they're drawn, 3.15 for crossbows", inaccuracy 1); the
trident from the speed in its [projectile calculator](https://minecraft.wiki/w/Trident).

What the wiki leaves to you, and the library therefore asks for:

- **A bow's power by draw time.** The wiki gives the range, not the formula.
  `power` takes a `DoubleSupplier`, read at each launch, so it follows the draw.
- **Where it appears.** The wiki gives a dispenser's offset, not a player's.
  `LaunchOrigin.EYE`, `LaunchOrigin.below(blocks)`, or your own.
- **How much of your movement an arrow carries.** For thrown projectiles the wiki
  says your velocity is added, "if the player is on the ground, the Y component
  is unaffected": `ALL_BUT_VERTICAL_ON_GROUND`. For bows it says the path depends
  on "the user's movement speed" without saying how. Pick `NONE`, `ALL` or
  `ALL_BUT_VERTICAL_ON_GROUND`.

`Shooter` takes your game's own velocity for the player, not a difference of
positions: the projectile takes it as the game hands it over.

**Spread.** The game nudges each axis of the direction by a random amount of up
to `spread`, then scales it by the power. The launch leaves the randomness out —
its velocity is the middle of everywhere it could go — and keeps the bound:
`launch.getSpread()` is how far the randomness can move the velocity on each
axis. The Projectile page puts the nudge before the scaling, as here; the Arrow
page describes it added afterwards. If your version does the latter, give
`spread / power`.

---

## 3. Where it goes

A `Flight` runs the motion through your world. Each tick's move is a line from
where the projectile is to where it is going, checked as
[Projectile#Collision](https://minecraft.wiki/w/Projectile#Collision) describes:
against block shapes first, then against every entity it can hit, up to the
block. The nearest entity wins, even over a block at the same point.

```java
Flight pearls = Flight.builder(pearl)
        .blocks(myBlocks)                                        // your BlockView
        .entities(Core.entities(), players, mobs)                // what it can hit, you included
        .filter(entity -> !(entity.get() instanceof EndermanEntity))   // your game's exceptions
        .build();

Trajectory path = pearls.launch(pearlThrow.launch(you, rotation()));
```

A Trajectories module is that, drawn:

```java
@ModuleInfo(name = "Trajectories", description = "Shows where it will land", category = "Render")
public final class Trajectories extends Module {

    private final ColorSetting colour = color("Colour", Color.WHITE);

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        Thrown held = thrownFor(heldItem());                     // yours: which rules your held item means
        if (held == null) return;

        Shooter you = Shooter.of(eyePosition(), playerVelocity(), isOnGround());
        Trajectory path = held.flight.launch(held.launch.launch(you, rotation()));

        for (int i = 1; i < path.getPoints().size(); i++) {
            Render.line3D(path.at(i - 1), path.at(i), 1.5f, colour.resolve());
        }
        Hit hit = path.getHit();
        if (hit.getType() == Hit.Type.ENTITY) {
            Render.boxOutline(hit.getEntity().getBox(), 1.5f, colour.resolve());
        } else if (hit.getType() == Hit.Type.BLOCK) {
            Render.boxOutline(Box.block(hit.getCell().getX(), hit.getCell().getY(), hit.getCell().getZ()),
                    1.5f, colour.resolve());
        }
        Render.boxOutline(path.landingSpread(), 1f, colour.resolve());   // where the randomness could land it
    }
}
```

Which item is held, and what that means, is yours: the library never looks at
your inventory. Map your held item to a `Flight` and `LaunchRules` pair however
your client likes.

**What it gives back.** `Trajectory.getPoints()` is the position after every
tick, from the start to where it ended; `velocityAt(tick)` the velocity, for
damage that depends on speed; `getHit()` what ended it: a `BLOCK` with its cell
and the face hit, an `ENTITY` with its `Tracked`, or `NONE` when it ran out of
ticks (`maxTicks`, 300 unless you set it, read at each flight).

**Spread.** `spreadAt(tick)` bounds, on each axis, how far the launch's
randomness could have moved the projectile by then, and `landingSpread()` is the
box around where it ended. It is exact for the motion — the suite checks that the
furthest random throw reaches the bound and none passes it — but does not know a
different path would hit something else.

**Your blocks.** The same `BlockView` as Core's [`world`](../docs/13-package-map.md)
package and the combat library: one method, the shape in a cell. For projectiles,
give the game's collision shape. A cell's shape is only tried when the line
crosses that cell, and then the whole shape counts, which is how the wiki says
fences and walls are met: "only seen by the projectile if the raycast intersects
the full cube space where the block is actually located". The combat library's
README asks for shapes cut to their cell for explosion rays; if you use both, give
each its own `BlockView`.

**Entities.** The trackers you give, plus you when an `EntityService` is given,
less anything your `filter` refuses. They are where their trackers saw them this
tick: a flight does not move them. A projectile you `launch` never hits you; when
the game lets one clear its owner is not on the wiki.

A trajectory is cheap: about a microsecond for a 24-tick flight to the ground in
the benchmark, with no entities. Drawing one every frame costs nothing you will
notice.

---

## 4. Someone else's

A projectile already in the air is followed from its tracker:

```java
Trajectory theirs = pearls.from(trackedPearl, velocityOf(trackedPearl.get()));   // its velocity, from your game
Trajectory guessed = pearls.from(trackedPearl);                                // from how it moved last tick
```

With your game's velocity it is exact. Without it, the flight works the velocity
out from the projectile's last move, carried through the rest of that tick by its
rules: the suite checks this matches the exact one for every order. It needs two
ticks of history to have a move to go on. Either way, a projectile never hits
itself, though it can hit you — so the same flight tells you where an enemy's
pearl lands and whether an arrow is coming at you.

`from(position, velocity)` is there for anything else; started inside a tracked
projectile's own box, it would hit it, so give tracked projectiles as `Tracked`.

---

## 5. Aiming

`AimSolver` finds the rotation that lands a projectile on a point, or on
someone where they will be when it gets there:

```java
AimSolver bowAim = AimSolver.builder(arrowFlight, bow)
        .lookahead(Lookahead.predicted(Core.prediction()))    // where they will be; where they are unless set
        .aimPoint(AimPoint.CENTRE)                            // or EYES, height(0.75), your own
        .arcs(Arc.LOW, Arc.HIGH)                              // flat, or lobbed if flat is blocked; LOW unless set
        .build();
```

A BowAim module:

```java
@Subscribe(stage = Stage.PRE)
private void onTick(TickEvent event) {
    if (!isDrawingBow()) return;                              // yours
    Tracked<PlayerEntity> target = lock.update();            // a TargetLock: Core's docs, §12
    if (target == null) return;

    Aim shot = bowAim.at(Shooter.of(eyePosition(), playerVelocity(), isOnGround()), target);
    if (shot != null) {
        Core.rotations().request(this, shot.getRotation());   // how you turn is yours
    }
}
```

**How it works.** For a point, it faces it and tries pitches from straight up to
straight down, flying each through nothing with `Motion`, to find the pitch that
carries the projectile highest over the point. The low and high arcs are the
pitches either side of it that pass through the point; it narrows each down by
halving. If your own movement carries the path sideways, it turns by the miss
until there is none. For a target, it asks your `Lookahead` where the target will
be when the projectile arrives, aims there, and asks again with the new flight
time until the time stops changing. Then it flies the aim through the world with
your `Flight` and keeps it only if the projectile reaches the target before
anything else stops it. Otherwise it tries the next arc.

`Lookahead` is Core's (`core.movement.prediction`), shared with the combat
searches: `none()`, `extrapolated()`, `predicted(Core.prediction())`, or your own.

**What it gives back.** An `Aim` has the rotation, the arc, the aim point, the
target as it will be, the `impact` (where it meets their box), the arrival tick,
the `Launch`, and the `Trajectory`. The trajectory does not look for the target,
who is somewhere else by then: draw it up to the impact. When there is no aim,
`getLastStats()` says why: `OUT_OF_RANGE`, `BLOCKED` (every arc that reaches
hits something first), or `NO_CONVERGENCE` (the target's expected position and
the flight time kept moving each other). It also counts the paths flown and the
rounds taken.

**What it leaves to you.** The rotation is a direction only: turning to it,
smoothing, and when to let go are your module's. It aims with the power your
launch rules give now, so re-aim each tick while a bow draws. If the projectile
leaves a few ticks after you aim — a turn still to make — give `delay`, and the
target is looked up that much further ahead.

**What it costs.** About 0.2 ms an aim in the benchmark: some 450 to 600
motions through nothing, and one flight through your world per arc tried. A
motion is a few dozen ticks of arithmetic; only the final flight touches your
world.

---

## 6. When Mojang changes something

Change the rule that changed. When 1.21.2 reordered thrown projectiles, that was
one line per projectile:

```java
.order(StepOrder.ACCELERATION_DRAG_POSITION)     // was POSITION_DRAG_ACCELERATION before 1.21.2
```

The same throw lands somewhere else afterwards — the suite checks the old and new
orders part by nearly a block within 40 ticks — and nothing in this module
changes.

---

## 7. Package map

| Package | Classes | What it is |
|---|---|---|
| `projectile` | `ProjectileRules`, `StepOrder`, `DragRule`, `LaunchRules`, `LaunchOrigin`, `ShooterVelocity`, `Shooter`, `Launch` | how each projectile flies and leaves you, in your game's numbers |
| `projectile.flight` | `Flight`, `Trajectory`, `Hit`, `Motion` | where it goes through your world, and what stops it |
| `projectile.aim` | `AimSolver`, `Aim`, `AimStats`, `AimPoint`, `Arc` | the rotation that lands it on a point or someone moving, and why there was none |

From Core it uses `world` (`BlockView`, `Rays.first`, `RayHit`), `math`
(`Box.clip`), `entity` (`Tracked`, `EntityTracker`, `EntityService`),
`movement.prediction` (`Lookahead`) and `util.math` (`RotationMath`).

---

## 8. Verifying

`dev.px.projectile.test.ProjectileSmokeTest` runs **102 checks** in a plain JVM
— no Minecraft, no window. The "game" is a block grid and a few bodies written by
the tests, with the wiki's numbers written the way a client would
(`WikiRules`), and the truth is the wiki itself wherever it gives one.

```
sh gradlew :projectile:compileTestJava
java -cp projectile/build/classes/java/main:projectile/build/classes/java/test:\
core/build/classes/java/main:core/build/classes/java/testFixtures:<gson.jar> \
     dev.px.projectile.test.ProjectileSmokeTest
```

Block shapes, first hits and box clips are Core's, and checked by Core's
`WorldTests`. The suites share Core's test fixtures — `Checks`,
`RecordingLogger`, `GridCollisionSpace` and `MovementRig` — through
`testFixtures(project(':core'))`.

| Suite | Covers |
|---|---|
| `MotionTests` | every one of the six orders against the wiki's closed-form position for 120 ticks; the wiki's terminal velocities and travel distances for arrows, llama spit, pearls, potions and experience bottles; a pearl's order before and after 1.21.2; arrows in water travelling the wiki's 2.5, drag asked where the tick began, the tick it splashes; a projectile seen only by its positions followed exactly in every order and through water; reach as the carry of a nudge to the starting velocity; launches facing where you look, a potion's pitch offset, where it appears, the shooter's velocity all, none, or all but vertical on the ground, a bow's power read at each launch, spread scaled by power; builders naming what is missing |
| `FlightTests` | a flight through nothing tick for tick its motion, running out of ticks, its limit read live; landing on a floor's top in the tick it would pass through, the last point the hit; a wall's west face; hitting someone, missing them by 0.15 as an arrow and hitting them as a pearl; someone behind a wall not hit, someone in front of one hit, someone pressed against a wall's face hit and not the wall, the nearer of two; filters, ignoring, no entities; never hitting you with your own shot, hitting you with one shot at you, never with only trackers; a projectile seen for one tick falling, seen for two followed exactly to its landing, never hitting itself; spread bounding the furthest random throw and reached; a flight with no blocks refused |
| `AimTests` | five points aimed at and passed within a thousandth of a block, flown and measured; a potion landing on its spot and not reaching one too far; low and high arcs, both passing through; out of range, too high, straight up; turning against your own sideways run; a wall blocking the flat shot and the lob going over; a pane in front of the point met in the same tick; someone in the way, the target never blocking its own aim, aim points; someone sprinting across, the aim leading them and the arrow, flown tick by tick against their real movement, hitting them in the tick it said, while the arrow aimed where they are misses; someone outrunning the arrow never settling; an aim flicking between two neighbouring ticks settling; a delay looked ahead and counted; missing parts and an undrawn bow |

The logic was mutation-tested: 26 deliberate breaks in this module and 8 in the
Core pieces it added, each caught by the suites.

---

## Not yet included

- **Fireballs and wind charges.** The wiki says they have no gravity but "get
  acceleration from getting damaged"; their acceleration is a direction, not a
  pull downward, and `ProjectileRules` has only gravity.
- **Entities moving during a flight.** A flight sees entities where their
  trackers are now. The aim solver looks its target up where it will be, but a
  bystander walking into the path is not foreseen.
- **Blocks the wiki treats specially:** scaffolding's top, which is "only hit if
  the projectile begins its movement below it", and end portals, which have "no
  hard hitbox". A `BlockView` gives one shape per cell, whichever way the line
  comes.
- **Bounces and piercing.** A flight ends at what it hits: no arrows deflected
  off an entity, no piercing, no trident returning.
- **Pearls through portals and gateways**, which the wiki describes as carrying
  on in the other dimension.
- **Aiming for spread.** Aims go through the middle of everywhere the randomness
  could send them; `Trajectory.spreadAt` says how wide that is, but the solver
  does not yet pick the aim point that leaves the most room.
