# Combat

PvP for Minecraft clients, built on [Core](../README.md) and just as
version-independent. Combat does the heavy lifting — geometry, raycasting,
damage prediction, checking predictions against the real game — so the crystal
aura in your client can be mostly settings.

Like Core, it has **no Minecraft on its classpath** and **no game values in its
code**. Explosion formulas, armour maths, which blocks hold a crystal, how much
clear space one needs: all of it is supplied by your client, as small rules that
each answer one question. When Mojang changes how explosions or crystals work,
you swap the one rule that changed. Nothing in this module needs a rewrite, and
the [damage monitor](#8-the-damage-monitor) and [test vectors](#9-test-vectors)
tell you which rule it was.

**Status.** The rules layer, the search — where to place, what to break, the
best few places, every threshold and timing rule, protecting friends, your own
filters, ranking by what turning costs you, looking ahead, placing then breaking, and strict placement — for
crystals, beds and respawn anchors, finding holes and predicting who gets into one, planning block placements, the damage
monitor and test vectors are done. See
[Not yet included](#not-yet-included) for what comes next.

**Requires:** Core, Java 8, Gson (ships with Minecraft). Inside this repository:

```groovy
dependencies {
    implementation project(':combat')     // brings Core with it
}
```

Without Gradle, copy `combat/src/main/java/dev/px/combat` alongside Core's sources.

---

## Contents

1. [Your world](#1-your-world)
2. [Explosions](#2-explosions)
3. [Crystals](#3-crystals)
4. [Searching](#4-searching)
5. [Beds and respawn anchors](#5-beds-and-respawn-anchors)
6. [Holes](#6-holes)
7. [Placing blocks](#7-placing-blocks)
8. [The damage monitor](#8-the-damage-monitor)
9. [Test vectors](#9-test-vectors)
10. [When Mojang changes something](#10-when-mojang-changes-something)
11. [Package map](#11-package-map)
12. [Verifying](#12-verifying)

---

## The one idea

The library supplies **mechanisms**; your client supplies **facts**.

| Combat ships | Your client supplies |
|---|---|
| ray traversal through a block grid | which blocks stop a ray, and their shapes |
| sampling a hitbox and counting clear rays | where your version aims those rays |
| putting rules together in order | the damage formula, armour, enchantments, effects, difficulty |
| "N clear blocks above a base, no entity in the space" | which blocks are bases, what counts as clear, how many |
| comparing predictions with what happened | how to read health off your entity |
| recording, saving and replaying explosions | the values your armour maths reads |

Every rule is required. Leave one out and the builder names what is missing, so
no game number ever slips in as a default.

---

## 1. Your world

Combat sees blocks through Core's [`world`](../docs/13-package-map.md) package —
`BlockView`, `BlockShape`, `CellTest`, `Rays` and `Obstructions` live in Core, so
every library on it shares the same view of your world. You implement one
interface per version:

```java
public final class MyBlocks implements BlockView {
    private final Map<BlockState, BlockShape> cache = new HashMap<>();

    @Override
    public BlockShape shapeAt(int x, int y, int z) {
        BlockState state = world.getBlockState(new BlockPos(x, y, z));
        return cache.computeIfAbsent(state, this::shapeOf);   // build once per state, reuse
    }
}
```

A `BlockShape` is what stops a line through a cell — for combat, an explosion's rays: `BlockShape.EMPTY`,
`BlockShape.FULL`, or boxes relative to the cell:

```java
static final BlockShape SLAB = BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1));
```

Give the shape your game's ray test actually uses. The wiki describes current
Java as using collision boxes, with a shape that sticks out of its cell (fences,
walls) only counting inside its own cell — so for those, give the part inside the
cell. Shapes are immutable; build one per kind of block and reuse it, since
`shapeAt` is called dozens of times per ray.

Two more small pieces:

```java
CellTest base  = (x, y, z) -> isObsidianOrBedrock(x, y, z);     // a yes/no question about one cell
Obstructions entities = Obstructions.of(Core.entities(), everythingTracker);   // entities in the way
```

`Obstructions.of` reads Core's [entity trackers](../docs/12-entities-and-targeting.md),
plus the local player. Only what your trackers hold is seen, so if items or
experience orbs should block a placement in your game, track them.

`Rays.clear(from, to, blocks)` tells you whether a straight line gets through —
useful for wall-range checks of your own. It walks exactly the cells the line
crosses with Core's `VoxelRay`. A line is stopped when it passes through a shape
for some length; touching it at a point or an edge does not count. A line lying
in a face follows the grid's own rule, that a boundary belongs to the cell above
it: along the top of a block it is clear, so a target is not hidden by the floor
it stands on, and along the seam between two stacked blocks it is stopped, as a
solid wall should stop it.

---

## 2. Explosions

An `ExplosionModel` predicts how much an explosion hurts a target, from four
rules that each answer one question:

| Rule | Question | Package |
|---|---|---|
| `measureFrom` | which point of the target is distance measured to? | `explosion` |
| `Exposure` | how much of the target does the blast reach? | `explosion.rule` |
| `Falloff` | how hard does it hit at that distance and exposure? | `explosion.rule` |
| `Mitigation` | what reduces it before it lands? | `explosion.rule` |

```java
ExplosionModel<LivingEntity> explosions = ExplosionModel.<LivingEntity>builder()
        .measureFrom(Tracked::getPosition)
        .exposure(Exposure.sampled(myGrid))
        .falloff(myFalloff)
        .mitigation(Mitigation.fromState(myCapture, myDifficulty, myArmour, myProtection))
        .build();

DamageEstimate estimate = explosions.estimate(origin, Explosive.of("end crystal", 6), target, blocks);
estimate.getDamage();      // after mitigation
estimate.getRaw();         // before
estimate.getExposure();    // 0 to 1
estimate.getDistance();
```

The same model serves crystals, beds and respawn anchors — they differ only in
their `Explosive`. A target beyond the falloff's range costs no raycasting at
all.

### Writing a profile for your version

Here is a profile written from the [Minecraft wiki](https://minecraft.wiki/w/Explosion)'s
description of current Java. **These numbers are your client's, not the
library's** — check them against your version with the [monitor](#8-the-damage-monitor)
and [test vectors](#9-test-vectors).

The wiki gives distance as measured to the target's **feet**, and:

```
impact = (1 − distance / (2 · power)) · exposure
damage = 7 · power · (impact² + impact) + 1          within 2 · power, at least 1
```

```java
Falloff falloff = Falloff.of(
        power -> 2 * power,                                   // how far it reaches
        (distance, exposure, power) -> {                      // what it does there
            double impact = (1 - distance / (2 * power)) * exposure;
            return 7 * power * (impact * impact + impact) + 1;
        });
```

Some clients truncate this to a whole number, as older versions did. Whether
yours should is exactly the kind of question the monitor answers.

Exposure is the share of rays, cast from sample points in the target's box to the
explosion, that no block stops. Where the sample points go is your version's
rule. One written from the wiki's description of the grid:

```java
SampleGrid grid = (box, sink) -> {
    double w = box.getWidth(), h = box.getHeight(), d = box.getDepth();
    double sx = 1 / (w * 2 + 1), sy = 1 / (h * 2 + 1), sz = 1 / (d * 2 + 1);
    double ox = (1 - Math.floor(1 / sx) * sx) / 2;
    double oz = (1 - Math.floor(1 / sz) * sz) / 2;
    for (double fx = 0; fx <= 1; fx += sx)
        for (double fy = 0; fy <= 1; fy += sy)
            for (double fz = 0; fz <= 1; fz += sz)
                sink.point(box.getMin().getX() + fx * w + ox,
                           box.getMin().getY() + fy * h,
                           box.getMin().getZ() + fz * d + oz);
};
Exposure exposure = Exposure.sampled(grid);
```

The game's own loop may step in `float`, which can change how many points fit at
the edges. Test vectors will show it if that matters for your version.
`SampleGrid.uniform(x, y, z)` is plain evenly-spaced geometry for tests and
approximations, and `Exposure.FULL` ignores terrain entirely.

### Mitigation, written once

Mitigation needs things only your game knows — armour, toughness, enchantments,
Resistance, difficulty, whether a shield is up. Write each step against a
`TargetState`: named values you capture off the entity.

```java
StateCapture<LivingEntity> capture = e -> TargetState.builder()
        .put("armor", Math.floor(e.getAttributeValue(EntityAttributes.GENERIC_ARMOR)))
        .put("toughness", e.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS))
        .put("protection", myProtectionOf(e))
        .build();

StateMitigation armour     = (damage, s) -> myArmourFormula(damage, s.get("armor"), s.get("toughness"));
StateMitigation protection = (damage, s) -> myProtectionFormula(damage, s.get("protection"));

Mitigation<LivingEntity> live   = Mitigation.fromState(capture, myDifficulty, armour, protection);
Mitigation<TargetState>  replay = Mitigation.ofState(myDifficulty, armour, protection);
```

The same steps then run live and against recorded explosions, so **the profile you
test is the profile you play with**. Steps run in the order given.

A mitigation can also read your entity directly — `Mitigation.chain(...)` with
`(damage, target) -> ... target.get() ...` — and that works live, but it cannot be
replayed from a recording. `Mitigation.none()` says on purpose that nothing
reduces damage; leaving armour out over-predicts against anyone wearing some.

---

## 3. Crystals

`CrystalRules` puts everything version-specific about end crystals in one place:

```java
CrystalRules<LivingEntity> crystals = CrystalRules.<LivingEntity>builder()
        .placement(Placement.clearance(myBlocks::isCrystalBase, 2, myBlocks::isReplaceable, 2.0))
        .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0))
        .explosive(Explosive.of("end crystal", 6))
        .model(explosions)
        .blocks(myBlocks)
        .obstructions(Obstructions.of(Core.entities(), everythingTracker))
        .build();
```

| Rule | What it says | Wiki, current Java |
|---|---|---|
| `Placement` | which blocks hold a crystal, how much clear space it needs | obsidian or bedrock, two clear blocks, no entity in the space |
| `CrystalBody` | where it sits from the base, its hitbox, where it explodes from | centre of the block above, 2 × 2, from its bottom |
| `Explosive` | its power | 6 |

On placement clearance, sources disagree. The wiki says two clear blocks; many
clients' "native" placement checks only one on 1.13 and later. `Placement.clearance`
takes the number as a parameter — `clearance(base, 1, clear, 2.0)` or `(base, 2, ...)`
— so check your version in game and give it the right one. The entity space and the
block clearance are separate numbers on purpose.

Asking it questions:

```java
if (crystals.canPlace(x, y, z)) {
    double toThem = crystals.damage(x, y, z, target);                      // a crystal placed here
    double toMe   = crystals.damage(x, y, z, Core.entities().getSelf());
}

double fromExisting = crystals.damage(crystalTracked, target);             // a crystal already in the world
DamageEstimate why  = crystals.estimate(x, y, z, target);                  // with the working shown

crystals.position(x, y, z);   // where it would be
crystals.box(x, y, z);        // its hitbox
crystals.origin(x, y, z);     // where it would explode from
crystals.getRange();          // how far it hurts anything
```

This answers questions about **single** explosions. It does not decide what
several do together: the wiki gives current Java as applying only the highest
explosion damage a target takes in a tick, and whatever plans several explosions
at once — [the search](#4-searching) — respects that.

---

## 4. Searching

`CrystalSearch` does the searching your crystal aura would otherwise do by hand:
where to place, and which crystal to break. Your aura still decides **when** to
act and **how** — rotations, swapping, packets, rendering. The search decides
what is worth doing.

```java
Thresholds<LivingEntity> thresholds = Thresholds.<LivingEntity>builder()
        .minDamage(minDamage::getDouble)
        .maxSelfDamage(maxSelf::getDouble)
        .antiSuicide(() -> 0.5)                                   // never leave yourself within half a heart
        .lethal(lethalMultiplier::getDouble, true)                 // a kill may ignore the self-damage cap
        .facePlace(faceHealth::getDouble, faceDamage::getDouble)
        .facePlaceWhen(faceKey::isDown)
        .armourBreak(this::mostWornPiece, armourPercent::getDouble, faceDamage::getDouble)
        .maxProtectedDamage(friendMax::getDouble)                  // the most any friend may take
        .protectedMargin(() -> 2)                                  // and never leave one within a heart
        .breakMinAge(ticksExisted::getInt)
        .inhibit(inhibitTicks::getInt)
        .build();

CrystalSearch<LivingEntity> search = CrystalSearch.<LivingEntity>builder()
        .rules(crystals)                                           // your CrystalRules
        .entities(Core.entities())                                 // you
        .targets(Core.targets(), enemies)                          // a TargetSelector: who counts
        .protect(friends)                                          // a TargetSelector: who must not be hurt
        .crystals(Core.entities().get(CrystalTracker.class))       // crystals already in the world
        .vitals(myVitals)
        .placeReach(Reach.of(placeRange::getDouble, placeWall::getDouble))
        .breakReach(Reach.of(breakRange::getDouble, breakWall::getDouble).measuredTo(ReachPoint.NEAREST))
        .thresholds(thresholds)
        .bus(Core.bus())                                           // ticks itself
        .build();
```

Every setting is read through a supplier on every search, so binding them to your
module's settings is all it takes for a slider to apply at once. Everything is
off or unlimited unless you turn it on: the library has no opinion on how
aggressive your aura should be.

```java
// each tick, in your aura:
BreakOption<LivingEntity> hit = search.findBreak();
if (hit != null) {
    attack(hit.getCrystal().get());
    search.attacked(hit.getCrystal());              // inhibit it; record what it did this tick
}
PlaceOption<LivingEntity> spot = search.findPlace();
if (spot != null) {
    place(spot.getX(), spot.getY(), spot.getZ());   // the base block: place on top of it
}

// from your spawn packet handler, for an instant break:
BreakOption<LivingEntity> now = search.spawned(crystalEntity);
```

Each option says what to do and why: the target, the damage to them and to you,
its score, its `Trigger` — `MINIMUM`, `FACEPLACE`, `ARMOUR_BREAK` or `LETHAL` —
and `getAim()`, the point to look at to act on it. Draw the damage, log the
reason, or skip a faceplace you do not want right now.

### The best few

`findPlaces(n)` returns the best `n` places, best first, each on its own base and
offered against the target it is best for. They are **alternatives**: somewhere
to go when you cannot rotate to the first in time, or your server would refuse
it. They are not a set to fill together — two may be too close to both hold a
crystal; planning several placements a tick is [not written yet](#not-yet-included).

```java
for (PlaceOption<LivingEntity> option : search.findPlaces(3)) {
    if (canRotateTo(option.getAim())) {
        place(option.getX(), option.getY(), option.getZ());
        break;
    }
}
```

When reaching is about turning, an [aim cost](#aiming) is usually better: the
search then ranks by it, rather than you skipping down the list.

### Aiming

Every option says where you would look to act on it — `getAim()`: the centre of
a base's top face, a crystal's centre, the cell a bed's foot goes in, the half
of a bed to click. Your rotation manager turns there; the search never turns
you, and never asks which rotation manager you use.

What it does ask, if you let it, is what turning there is worth. An `AimCost`
takes your eyes and the aim point and answers in **damage**, and options rank by
their score less that cost:

```java
// each degree from where you look now gives up 0.05 damage; past 90 degrees, never
AimCost turning = AimCost.angle(myRotations::getServerRotation, () -> 0.05, () -> 90);

// or anything your rotations know: ticks to get there at your turn speed, say
AimCost ticks = (eye, at) -> {
    int needed = myRotations.ticksToReach(eye.rotationTo(at));
    return needed > 1 ? Double.POSITIVE_INFINITY : needed * 4;   // one tick of turning is worth 4 damage
};

CrystalSearch.<LivingEntity>builder()
        ...
        .placeAimCost(turning)
        .breakAimCost(AimCost.NONE)        // your server does not check where you look to attack
        .build();
```

- **A trade.** A cost of 3 means a spot you are already looking at ranks the
  same as one 3 damage stronger you would have to turn to. Make it large and the
  quickest turn always wins, damage only breaking ties.
- **A limit.** Infinity — or NaN — means you cannot turn there in time. The spot
  or crystal is dropped before any damage is raycast, and counted in
  `SearchStats.getUnaimable()`.
- **A bonus.** A cost may be negative: favour the spot you are already turning
  toward, so the aura does not flip between two nearly equal ones every tick.

`getScore()` stays what your `Score` said; `getAimCost()` is the cost, and
`getRank()` — the score less the cost — is what the options were ranked by.
Filters see all three. Placing and breaking take separate costs (`useAimCost`
for beds), both `AimCost.NONE` unless set.

The cost is worked out once per spot, from geometry alone, and the search lowers
each spot's bound by it before ordering them, so branch and bound stays exact
whatever you return. The suite checks that against a brute-force search in 40
random layouts with turning costs, limits and bonuses.

A bed's facing comes from your yaw as you place it, which the aim point does not
capture: narrow `facings` to the way you face if your cost must account for it.

`findPlace()` is `findPlaces(1)`. Branch and bound still applies: it stops once
nothing left could beat the `n`th best, so asking for more costs more estimates.
The test suite checks the best four against every option, in 20 random layouts.
`BedSearch.findPlaces(n)` does the same for beds.

### Looking ahead

An explosion lands a few ticks after you decide on it: your ping, and for a
crystal the wait to break it. By then the target has moved. Tell the search how
long, and where they will be, and it scores damage there:

```java
CrystalSearch.<LivingEntity>builder()
        ...
        .lookahead(Lookahead.predicted(Core.prediction()))       // where they will be
        .placeDelay(() -> pingTicks() + breakDelay.getInt())      // until a crystal placed now goes off
        .useDelay(() -> pingTicks())                              // until one broken now goes off
        .build();
```

`Lookahead` is a one-method interface, and the search never knows how it is
answered: `Lookahead.none()` (the default — where they are now),
`Lookahead.extrapolated()` (a straight line), `Lookahead.predicted(...)` over
Core's prediction (its likeliest future, and where they are now while it is not
reliable), or your own. It is asked once per entity per search — targets, you, and
everyone you protect, so a friend walking into the blast is spared — and never
while the delay is 0. Options still name the real entity; only the damage is
measured at the stand-in, a `Tracked.projected(position)`.

### Placing, then breaking

A crystal you placed exists on the server a few ticks before your client sees it.
Say you placed it, and searches sharing the log keep out of its way until it shows
up or its wait (`pendingTicks`, half a second unless set) runs out:

```java
PlaceOption<LivingEntity> spot = search.findPlace();
place(spot);
search.placed(spot);                         // the next search will not collide with it

for (PlaceOption<LivingEntity> each : search.planPlaces(2)) {   // several a tick, none in another's way
    place(each);
    search.placed(each);
}

BreakOption<LivingEntity> hit = search.findBreak();
if (hit != null && hit.isOwn()) { ... }      // it showed up where you placed one: yours
```

`planPlaces(n)` differs from `findPlaces(n)`: the best few are alternatives, two of
which may stand in each other's way; a plan is a set to place together, each the
best that does not collide with those before it. A crystal that shows up where you
placed one — in the world or from its spawn packet — is yours (`isOwn()`), so an
aura can break its own first, or only its own.

### Watching the search

A `SearchListener` hears every exact damage estimate a search makes, and its
verdict — passed, too weak, suicidal, endangering a friend, over the self cap,
outranked or filtered:

```java
.listener(judged -> seen.merge(judged.getSubject(), judged.getDamage(), Math::max))   // draw these
```

For drawing the damage a search saw, and for seeing why it chose nothing. It hears
only what was estimated: turn `pruning` off while you look to see every spot.

### Strict placement

Give a search your `Clicks`, and only spots your server would take are offered,
each with the click that places it:

```java
CrystalSearch.<LivingEntity>builder()
        ...
        .clicks(clicks)
        .build();

PlaceOption<LivingEntity> spot = search.findPlace();
turnTo(spot.getClick().getRotation(eye));                        // getAim() is its hit point
Game.interact(spot.getClick());
```

A crystal goes on top of its base whichever face is clicked, so any face your
rules accept will do; a base above your eyes is still offered by a server that
takes the faces you can see, and refused by one that insists on the top. For a
bed, the click goes into the foot's cell, and must face the bed the right way
when looked along, since the game takes a bed's facing from your yaw. Using a bed
clicks the half reached. Spots with no click your rules accept are counted in
`SearchStats.getUnclickable()`.

### Protecting friends

`.protect(selector)` names who must not be hurt, with a `TargetSelector` of
yours — your friends from Core's `SocialService`, teammates, anyone. The search
then:

- **never targets them**, even when the target selector would pick them. They
  are left out before `maxTargets` cuts the list, so a friend standing nearest
  never pushes an enemy out.
- refuses any placement or break that does more than **`maxProtectedDamage`**
  to any one of them, or that leaves one within **`protectedMargin`** of death —
  lethal or not, like anti-suicide. The margin needs their health: when your
  `Vitals` do not trust it, only the cap protects them.

Both are off until set, and setting them protects nobody until `.protect(...)`
says who. Give the selector range enough to cover everywhere your explosions
reach: your place range plus your `Falloff`'s range.

Checking costs little. Most friends are cleared by the same no-ray bound the
search uses for targets: only one the bound says could be hurt too much is
raycast. `getLastPlaceStats()` counts those estimates, and the spots refused for
endangering someone.

### Your own filters

Thresholds cover what most auras need; `.filter(...)` covers the rest. A filter
sees each option the search is about to choose as a `Proposal`: the target, the
damage to them and to you, the trigger, the score — and `getProtected()`, what
the explosion would do to each protected entity it reaches.

```java
.filter(p -> p.getTrigger() == Trigger.LETHAL || !inHole(p.getTarget().get()))  // only kill campers
.filter(p -> p.getMostProtectedDamage() < p.getDamage() / 2)                    // friends take half at most
```

- A filter is asked only about an option that passed every threshold and would
  be chosen for its spot, so it runs a handful of times a search.
- Damage to protected entities is raycast only when a filter asks for it, once
  per explosion.
- Several filters are all required, asked in the order given.
- Filters judge breaking as well as placing, and beds as well as crystals.
- Refusing never lets the search skip a spot it should have tried: branch and
  bound stays exact, which the test suite checks against brute force.

### How an option is judged

Per target, in this order:

1. **Lethal**, if on: damage × multiplier is at least what the target can take
   (its `Vitals` pool). A lethal option needs no minimum, and may ignore the
   self-damage cap if you said so. A target whose health is hidden is never
   called lethal.
2. Otherwise it must do the **minimum damage** — or the lower **faceplace**
   minimum, when the target is at or below the faceplace health or faceplacing is
   forced, or the **armour-break** minimum, when its most worn armour is at or
   below that.
3. **Anti-suicide**, if on, refuses anything that leaves you within its margin of
   death — lethal or not.
4. **Protection**, when the search protects anyone, refuses anything that hurts
   one of them past the protected cap or margin — lethal or not.
5. The **self-damage cap** refuses anything that hurts you more.
6. Your **filters**, if any, have the last word on an option that would be chosen.

The best option by `Score` wins: `Score.DAMAGE` by default, or `Score.balanced(w)`
to trade damage for safety, or your own. Ties go to the one that hurts you least.

### Timing

- **Inhibit.** After `attacked(crystal)`, that crystal is left alone for the
  inhibit window: the server has not answered yet. The window is read when you
  attack, so changing the setting mid-fight applies from the next attack.
- **Minimum age.** Crystals younger than `breakMinAge` ticks are left alone —
  except from `spawned(...)`, which tracks the crystal the moment its spawn packet
  arrives and judges it at once. That is the point of breaking on spawn.
- **One explosion a tick.** The wiki gives current Java as applying only the
  highest explosion damage a target takes in a tick. After `attacked(...)`, another
  crystal this tick only counts against a target if it hits it harder — and the
  same holds for the damage to you. It resets on the next tick. Give your crystal
  and bed searches [one log](#one-engine-for-every-explosive) and the rule holds
  across both.

### How the place search works, and what it costs

1. **Scan** every cell in a cube around your eyes that reaches the place range,
   cheapest test first: distance, then `CrystalRules.canPlace`, then — past the
   wall range — one ray for visibility, then your aim cost: a spot you cannot
   turn to is dropped here.
2. **Bound** each candidate against each target: the damage it would do with
   nothing in the way. That costs no raycasting at all. A candidate whose bound
   cannot meet any target's threshold is dropped there.
3. **Branch and bound.** Candidates are tried best bound first with exact,
   raycast estimates, each bound lowered by the spot's aim cost. Once the best
   option found ranks higher than the next bound, nothing left can win, and the
   search stops.

With R the place range, T the targets, S the sample points an `Exposure` casts
from, L the cells a ray crosses (about √3 × its length), B the bases that pass
the scan, V the candidates still viable after bounding, and K the crystals in
break range:

| Step | Cost |
|---|---|
| scan | (2⌈R⌉ + 1)³ cells: distance and `canPlace` each, one ray each past the wall range, one aim cost per base seen |
| bound | O(B · T), no rays |
| order | O(V log V), one sort |
| exact estimates, worst case | O(V · (T + 1) · S · L) |
| exact estimates, typically | a handful: the bound stops the search early |
| break | O(g + K · (T + 1) · S · L), with g the grid buckets the break range covers |
| `spawned`, `attacked` | O((T + 1) · S · L): one crystal |
| protecting F entities | O(F) bounds per spot judged; an exact estimate only for one the bound cannot clear |

For a place range of 5 that is 1331 cells; with a player's hitbox sampled at
S = 45 and rays of about 20 cells, one exact estimate is around 900 cell tests —
which is why the bound matters more than anything else here. In the test arena,
67 viable placements are settled with 4 exact estimates.

Pruning assumes damage never falls as exposure rises, and that a score is never
more than the damage to the target. An aim cost needs neither: it is exact.
Both hold for everything shipped here; turn
pruning off with `.pruning(false)` for a model or score that breaks either.
Protection then raycasts every friend instead of trusting the bound. The
test suite checks branch and bound against a brute-force search in 30 random
layouts.

`getLastPlaceStats()` and `getLastBreakStats()` say what the last search did —
cells scanned, bases found, spots you could not turn to, candidates pruned,
estimates paid for, spots refused for friends or by your filters — for profiling, and for seeing why nothing was
found.

### What it reuses from Core

| Need | Core piece |
|---|---|
| who the targets are, in range, filtered and sorted | `TargetService` with your `TargetSelector` |
| who is protected | `TargetService` with a second `TargetSelector` of yours |
| crystals in break range without scanning every entity | `EntityTracker.forEachWithin`, over Core's `SpatialGrid` |
| breaking a crystal before the world lists it | `EntityTracker.track` |
| wall checks | `Rays`, over Core's `VoxelRay` |
| ticking | Core's event bus |

Inhibit keeps its own small map of when each explosive is free again rather than
Core's `ExpiringCache`: that cache's lifetime is fixed when it is made, and the
inhibit window is a setting that can change mid-fight.

### One engine for every explosive

`CrystalSearch` is a thin layer over `ExplosiveSearch`, the engine in
`combat.search.engine`: the scan, the no-ray bound, branch and bound, every
threshold, scoring and timing live there once. What a crystal adds is a
**device** — about sixty lines saying where a crystal can go, how far it is,
where it explodes from, and what it is remembered by. `BedSearch` is the same
engine with a bed device.

| Device | Says |
|---|---|
| `Device` | the explosive's model and power, and whether it can go off here now |
| `PlaceDevice` | the spots in one cell, whether the cell is in sight, where one placed there explodes from, the blocks as it finds them, and whether placing sets it off at once |
| `UseDevice` | the ones already in the world near you, whether one is in reach, where it explodes from, what it is remembered by, and its age |

Two things follow.

**One log, one explosion a tick.** The game applies only the strongest
explosion a target takes in a tick, whatever exploded. An `AttackLog` shared by
your auras holds what each has set off, so a bed aura knows the crystal aura
already hit harder this tick:

```java
AttackLog log = AttackLog.ticking(Core.bus());          // ticks itself, once
CrystalSearch<LivingEntity> crystals = CrystalSearch.<LivingEntity>builder() /* ... */ .log(log).build();
BedSearch<LivingEntity> beds = BedSearch.<LivingEntity>builder() /* ... */ .log(log).build();
```

Without `.log(...)`, each search keeps its own, ticked by `.bus(...)` or by
`tick()`, as before.

**Your own explosive.** Whatever a version adds next is a device of yours: implement `PlaceDevice` and `UseDevice`, then call
`ExplosiveSearch.findPlace(device)` (or `findPlaces(device, n)`) and
`findUse(device)`. Every threshold, protection, your filters, the branch and
bound and the timing come with it. `getEngine()` on either search
hands you an engine with the same settings.

---

## 5. Beds and respawn anchors

Beds explode when used in the Nether, the End, or a dimension where they are
disabled. `BedRules` is their counterpart of `CrystalRules`, and `BedSearch` the
counterpart of `CrystalSearch`, on the [same engine](#one-engine-for-every-explosive).

### The rules

From the [wiki](https://minecraft.fandom.com/wiki/Bed), for current Java:

- A bed takes two blocks. The **foot** goes on the block you select, the
  **head** one block further on, in the direction you face.
- It needs **no blocks beneath it** (since 18w22a). Bedrock, and Java from 17w47a
  until then, wanted solid blocks underneath.
- Used where it does not work, it explodes with **power 5**, **centred on the
  head**, and sets fire.

Each of those is a rule you give, not a fact in the code:

```java
BedRules<LivingEntity> beds = BedRules.<LivingEntity>builder()
        .placement(BedPlacement.clearance(Game::isReplaceable, 0.5625, 0.5625))   // room, and entity heights
        .explodingAt(0.5, 0.5, 0.5)                    // from the head block's corner
        .usableFrom(BedPart.FOOT, BedPart.HEAD)        // which halves set it off
        .explodesWhen(() -> !Game.bedsWorkHere())      // only your game knows the dimension
        .lookup(Game::bedHeadAt)                       // beds already in the world
        .explosive(Explosive.of("bed", 5))
        .model(explosions)                             // the same model as your crystals
        .blocks(blocks)
        .obstructions(entities)
        .build();
```

| Rule | Says | For current Java |
|---|---|---|
| `BedPlacement` | whether both cells are free, and how tall a space in each must hold no entity | `clearance(...)`; add `.onlyAbove(isSolid)` for a version that wants support |
| `explodingAt` | where the explosion starts, from the head's corner | the middle of the head |
| `usableFrom` | which halves set it off | both |
| `explodesWhen` | whether beds explode where you are, read live | not in the Overworld |
| `BedLookup` | which way the bed whose head is in a cell faces; null for none | from the block state |

The wiki says only that a bed is "half a block tall"; the entity heights are
your numbers. `Bed` is a position — a foot and a facing — and two beds over the
same cells are equal, so a bed found again next tick is the same bed.

### The bed is gone when it explodes

A bed's explosion starts inside its own head, and a bed is a solid half block.
Asked of the world as it stands, every ray would end inside the bed: exposure 0,
damage next to nothing. The game removes the bed before it explodes, so every
prediction `BedRules` makes uses `blocksWhenFired(bed)` — your blocks with the
bed's two cells empty. It is the one thing beds need that crystals never did.

The game can tell you of the explosion before it tells you the bed is gone, so
the monitor takes the world as the explosion found it:

```java
monitor.exploded(origin, beds.getExplosive(), beds.blocksWhenFired(bed));   // or blocks.without(foot, head)
```

A recorder given those blocks snapshots them, so the test vector holds no bed.

### Searching

```java
BedSearch<LivingEntity> search = BedSearch.<LivingEntity>builder()
        .rules(beds)
        .entities(Core.entities())
        .targets(Core.targets(), enemies)
        .vitals(myVitals)
        .placeReach(Reach.of(placeRange::getDouble, placeWall::getDouble))
        .useReach(Reach.of(useRange::getDouble, useWall::getDouble))
        .thresholds(thresholds)                    // the same Thresholds class as crystals
        .log(log)                                  // shared with your crystal aura
        .build();

// each tick, in your bed aura:
BedUseOption<LivingEntity> standing = search.findUse();
if (standing != null) {
    use(standing.getCell());                       // the half in reach, the nearer if both are
    search.used(standing.getBed());
}
BedPlaceOption<LivingEntity> spot = search.findPlace();
if (spot != null) {
    place(spot.getFoot(), spot.getFacing().toYaw());   // face this way: the head goes ahead of you
    use(spot.getFoot());
    search.used(spot.getBed());
}
```

What is a bed's own, against crystals:

- **Only where beds explode.** While `explodesWhen` is false, both searches find
  nothing and scan nothing.
- **Up to four spots a cell**, one per facing. `.facings(...)` narrows that, read
  live — to the way you already face, say, if your aura does not turn to place.
  Reach is measured to the foot's cell, the one you place into; which face to
  click to put it there is your client's business.
- **At once.** A bed is placed and used in the same tick, so what has already
  gone off this tick counts against a placement, as it does against a bed already
  standing.
- **Standing beds are blocks.** They are found by a scan of the cells around you
  with your `BedLookup`, one per head, and remembered by position for inhibit.
- **No minimum age.** The game does not say how long a bed has stood, so
  `breakMinAge` never stops one.

The scan, the bound, branch and bound, every threshold and trigger are the
crystal search's, unchanged. The test suite checks branch and bound against a
brute-force search in 20 random layouts with beds too.

### Respawn anchors

From the [wiki](https://minecraft.wiki/w/Respawn_Anchor), for current Java:

- Using a glowstone **block** on an anchor adds a charge, up to four.
- Using a **charged** anchor in the Overworld, the End, or a dimension where
  anchors are disabled makes it explode, **power 5**, setting fire. The anchor is
  destroyed, "similar to when a bed is used in the Nether".
- Shields block its explosion since 1.19.3 — your `Mitigation`'s, as for crystals.

The wiki does not say where the explosion is centred, nor which held items charge
an anchor rather than set it off. The first is a rule you give; the second is
your module's, along with every item switch.

```java
AnchorRules<LivingEntity> anchors = AnchorRules.<LivingEntity>builder()
        .placement(AnchorPlacement.clearance(Game::isReplaceable))  // .nextTo(Game::isSolid) without click rules
        .explodingAt(0.5, 0.5, 0.5)                                 // from the block's corner
        .explodesWhen(() -> !Game.anchorsWork())                    // your game knows the dimension
        .lookup(Game::anchorChargesAt)                              // AnchorLookup.NONE for no anchor
        .explosive(Explosive.of("respawn anchor", 5))
        .model(explosions)                                          // the same model as crystals and beds
        .blocks(blocks)
        .obstructions(Obstructions.of(Core.entities(), players))
        .build();

AnchorSearch<LivingEntity> search = AnchorSearch.<LivingEntity>builder()
        .rules(anchors)
        .entities(Core.entities())
        .targets(Core.targets(), enemies)
        .vitals(vitals)
        .placeReach(Reach.of(5, 3))
        .useReach(Reach.of(5, 3))
        .thresholds(thresholds)
        .clicks(clicks)                                             // optional: strict faces, hit vectors
        .log(log)                                                   // shared with crystals and beds
        .build();

AnchorUseOption<LivingEntity> standing = search.findUse();
if (standing != null) {
    if (standing.getChargesNeeded() > 0) { holdGlowstone(); use(standing.getCell()); }
    holdSomethingElse(); use(standing.getCell());
    search.used(standing.getAnchor());
}
AnchorPlaceOption<LivingEntity> spot = search.findPlace();
if (spot != null) {
    holdAnchor(); place(spot.getClick());                           // into the cell
    holdGlowstone(); use(spot.getUseClick());                       // on the anchor
    holdSomethingElse(); use(spot.getUseClick());
    search.used(spot.getCell());
}
```

What is an anchor's own:

- **Only where anchors explode.** While `explodes()` is false — the Nether —
  nothing is found.
- **The anchor is gone when it explodes.** It is a whole block with the explosion
  inside it, so every prediction is made with its cell empty, as for beds.
- **One spot per cell**, aimed at the cell. With click rules a spot needs a click
  to place it and a click on the anchor once placed (`getUseClick()`).
- **In one tick or over several.** By default the anchor is placed, charged and
  set off in one tick, so what already went off this tick counts against it.
  A server that takes one click a tick needs three: set `oneTick(() -> false)`,
  call `placed(spot)` once it is down, and `findUse()` finds it on the next ticks,
  empty and yours (`isOwn()`), with `getChargesNeeded()` saying a charge comes
  first. `planPlaces(n)` gives several anchors to place together this way.
- **Standing anchors** are found by a scan with your `AnchorLookup`, charged or
  empty (`useEmpty(() -> false)` for charged only), and remembered by cell for
  inhibit, whatever their charges.
- **No minimum age**, as for beds.

Branch and bound is checked against brute force in 20 random layouts with
anchors too.

---

## 6. Holes

A hole is open cells walled in by blocks an explosion cannot break. Which blocks
those are is your game's to say; the shapes are the library's. `HoleWatch` then
answers what an auto-fill, a surround breaker or a hole-aware aura asks: which
holes might this player get into, and when?

```java
HoleRules holes = HoleRules.builder()
        .walls((x, y, z) -> Game.isObsidianOrBedrock(x, y, z))
        .open((x, y, z) -> Game.isAirOrReplaceable(x, y, z))
        .headroom(2)                                             // cells a player needs to stand in
        .safe((x, y, z) -> Game.isBedrock(x, y, z))              // bedrock all round: nothing breaks it
        .build();

HoleWatch watch = HoleWatch.builder()
        .finder(new HoleFinder(holes))
        .prediction(Core.prediction())
        .obstructions(Obstructions.of(Core.entities(), players)) // someone else in it fills it
        .horizon(() -> pingTicks() + placeDelay.getInt())        // how far ahead your fill lands
        .radius(range::getDouble)
        .build();

for (HoleEntry entry : watch.watch(enemy)) {
    if (!entry.isInside() && !entry.isOccupied()
            && entry.getChance() > minChance.getDouble()           // likely to go in...
            && entry.getEarliestPossible() > myFillTicks()) {      // ...and cannot possibly beat the fill
        fill(entry.getHole());
    }
}
```

`HoleFinder` finds singles, doubles along either axis and quads: open cells, each
with a floor and headroom, walled on every side that is not one of its own cells.
Only the cells at the player's feet are walled; corners do not matter. A hole is
safe when every wall and floor block passes your safe test.

Each `HoleEntry` gives both answers Core's prediction does:

- **Likely** — `getChance()` and `getLikelyTick()`: the share of the player's
  predicted futures that end up in the hole, and when more than half of them do.
  One "heads for it" future per hole is weighed with the prediction's own, by how
  well each explains the player's last few ticks: a hole they have been walking
  toward carries weight, one they have been walking past does not.
- **Possible** — `getEarliestPossible()`: the soonest they could be in it at all,
  at the fastest of their rules and what they have been seen to do. A speed hacker
  heading for a hole is predicted in sooner, and could possibly be in sooner, than
  a legit player from the same spot.
- **If they go for it** — `getArrivalTick()`: when they would be in, at their own
  pace, whatever the chance.

**Sprinting carries a player over a one-block hole.** Only a future that stops
over it drops in, so until a sprinter slows down, running at a hole and running
over it look the same and the chance is split between them. `getArrivalTick()`
still gives the timing, and `HoleWatch.Builder.prior` leans toward holes when you
believe players running at one usually mean it.

Keep the horizon at least three ticks under your trackers' history: futures are
weighed by playing them from that far back. Friends work the same way — watch
them too, and never fill a hole a friend is likely to reach.

`assess(entity, holes)` does the same for holes you found yourself — around you,
say, rather than around them — and `priorOf(entity -> ...)` leans per entity.

### Filling holes before an enemy gets in

`HoleFill` is the search an auto-fill would otherwise do by hand, built like the
crystal search: it finds the best few holes to fill within your place range, and
your client keeps control of every setting, filter and ranking. What to fill them
with — obsidian, webs, whatever you hold — and how to place them is yours.

```java
HoleFill<LivingEntity> search = HoleFill.<LivingEntity>builder()
        .finder(new HoleFinder(holeRules))
        .prediction(Core.prediction())
        .entities(Core.entities())
        .targets(Core.targets(), enemies)
        .protect(friends)                                         // never a hole a friend is heading for
        .placeReach(Reach.of(placeRange::getDouble, wallRange::getDouble))
        .blocks(myBlocks)
        .horizon(() -> 15)
        .fillDelay(() -> pingTicks() + placeDelay.getInt())       // until your fill lands
        .minChance(minChance::getDouble)
        .prior(enemy -> enemy.getHealth() < 8 ? 2 : 1)            // weak players run for holes
        .protectOwn(selfProtect::isOn)                            // optional: keep the hole you are heading for
        .escapeRadius(() -> escape.isOn() ? escapeRange.getDouble() : 0)   // optional: keep holes near you
        .clicks(strictClicks)                                     // optional: only holes your server lets you fill
        .filter(fill -> fill.getTiming() != FillOption.Timing.LATE)
        .bus(Core.bus())
        .build();

for (FillOption<LivingEntity> fill : search.findFills(fillsPerTick.getInt())) {
    Plan plan = planner.plan(eye, fill.getCells(), looking);     // obsidian or webs: your planner, your item
    place(plan);
    search.filled(fill);                                        // not offered again while the server catches up
}
```

How it decides:

1. **Holes in reach** — every cell within your place range, past the wall range
   in sight, and with `Clicks`, every cell with a click your server accepts.
2. **Kept** — holes within your escape radius, if you set one; the hole you are
   in or likely heading for, if you protect your own; any a friend is in or likely
   heading for; any you filled that the server has not shown yet.
3. **Threatened** — each enemy predicted against the holes left, with a "heads
   for it" future per hole. A hole is offered for the enemy who would be in it
   soonest with at least the minimum chance; one an enemy is already in, or
   someone else is in, is not.
4. **Timed** against your fill delay — `SAFE` when your fill lands before they
   could possibly be in, `RACE` before they likely are, `LATE` after — with the
   slack: how many ticks you could still wait.
5. **Yours** — your filters, then the ranking: anything in time before anything
   late, then the soonest, then the likeliest, unless you `rank` them yourself.

Each `FillOption` lists the cells to fill, the one the enemy comes to first first
— all of a double or quad, since filling one leaves a smaller hole — and with
`Clicks`, a click for each. `getLastStats()` says why every hole left out was
left out. Fills share pending placements with your crystal search through a
shared `AttackLog`, so neither places where the other just did.

---

## 7. Placing blocks

Surround, auto-fill, self-trap, scaffold, burrow: every one of them places
blocks, and the hard part is the same in all of them — which block to click,
which face, where on it, in what order, and whether the server will take it.

### How your server takes a click

```java
Clicks clicks = Clicks.builder()
        .support((x, y, z) -> Game.isSolid(x, y, z) && !Game.opensWhenClicked(x, y, z))
        .replaceable((x, y, z) -> Game.isAirOrReplaceable(x, y, z))
        .faces(FaceRule.facingEye()                              // "strict direction"
                .and(FaceRule.exposed(Game::isFullBlock))
                .and(FaceRule.reach(Reach.of(4.5, 3), blocks)))
        .hit(HitPoint.CENTRE)
        .build();

Click click = clicks.best(eye, cell, looking);                  // into a cell: against a block beside it
Click onBase = clicks.bestOn(eye, base);                        // on a block: what a crystal is placed by
```

A `Click` is the block to click, the face, and the hit point — in world
coordinates for newer versions, `getRelativeHit()` within the block for older
ones — and `getRotation(eye)`, what to turn to first.

Vanilla accepts any face; anticheats are stricter, each in its own way, so
`FaceRule` is built from the checks they make:

| Rule | Accepts |
|---|---|
| `FaceRule.ANY` | everything: vanilla |
| `FaceRule.facingEye()` | a face turned toward you: your eyes beyond the plane it lies in. Not the top of a block above your eyes, nor the far side of one |
| `FaceRule.exposed(solid)` | a face not covered by the block beside it |
| `FaceRule.visible(blocks)` | a hit point with a clear line from your eyes |
| `FaceRule.reach(reach, blocks)` | within range, and past the wall range only with a clear line |

Combine them with `and`, or write your own for a check none of these makes.
`airPlace(...)` lets a cell with nothing beside it be clicked on itself, for
servers that allow it.

### Presets: one style per server

How careful to be is one value, `PlacementStyle`: which faces, where on them,
whether against nothing, how many a tick, how many supports. Save one per server
your client plays on and hand it to every module that places.

```java
PlacementStyle relaxed = PlacementStyle.vanilla(reach, blocks);   // any face in reach, as many a tick as you like
PlacementStyle careful = PlacementStyle.strict(reach, blocks);    // faces toward you, seen, in reach; one a tick

PlacementStyle myStrictServer = careful.withHit(HitPoint.NEAREST).withSupports(1);   // yours, named as you like

PlacementPlanner planner = myStrictServer.planner(
        myStrictServer.clicks(Game::isSolid, Game::isAirOrReplaceable),
        Obstructions.of(Core.entities(), players)).build();
```

`strict` is for servers whose anticheat replays your look ray against every
click: a face turned toward you, seen along a clear line, in reach, never in mid
air, and one placement a tick, since one rotation is sent a tick. It names no
anticheat — it is the library's own `FaceRule`s put together — so start from it
for whichever your server runs and change what it needs.

### Planning several a tick

```java
PlacementPlanner planner = PlacementPlanner.builder()
        .clicks(clicks)
        .obstructions(Obstructions.of(Core.entities(), players))   // nothing goes where someone stands
        .perTick(blocksPerTick::getInt)
        .supports(1)                                               // a block under one with nothing to click
        .build();

Plan plan = planner.plan(eye, surroundCells, looking);           // most important first
for (Plan.Step step : plan.getSteps()) {
    turnTo(step.getClick().getRotation(eye));
    Game.interact(step.getClick());
}
```

Cells are placed in the order given until the tick's limit. A block planned this
tick counts as there for every cell after it, so a bridge builds out over a drop,
each block clicked against the last. A cell with nothing to click gets supports
beside it first — below tried first — up to `supports` deep. Cells already
filled, with someone in them, unreachable, or past the limit are left out, each
with its reason in `getSkipped()`. When the limit leaves no room for a cell and
its supports, the supports are started with what is left, so at one a tick the
most important cell is still built toward, a block at a time, rather than passed
over for a lesser one.

### Shapes: what to place

`Shapes` gives the cells around a box that modules fill: `occupied` (every cell
it is in), `feet`, `head`, `around` (beside its feet: a surround), `above` (over
its head: a trap's roof), and `union` to join them. A player on a block edge is
in two cells and on a corner in four, and every shape follows. Give it the box
where the entity will be — `lookahead.at(enemy, ticks).getBox()` — to place where
it is going.

An auto-web is a shape and a planner:

```java
PlacementPlanner webs = PlacementPlanner.builder()
        .clicks(webClicks)                                       // replaceable: air, not a web already
        .obstructions(Obstructions.of(Core.entities()))          // a web goes into a player: only you are in the way
        .perTick(() -> 2)
        .build();

Tracked<? extends LivingEntity> soon = lookahead.at(enemy, placeTicks());   // where they will be when it lands
Plan plan = webs.plan(eye, Shapes.union(Shapes.feet(soon.getBox()), Shapes.head(soon.getBox())), looking);
```

Webs have no collision box, so the server lets one go into a player: give the
planner an `Obstructions` of only you (`Obstructions.of(entities)` with no
trackers), and it never webs you but will web them. Their feet web is clicked
against the floor; their head web against the feet web just planned. When to web
— an enemy in a hole, or about to enter one — is your module's, from `HoleWatch`.

For a target whose way is less certain, `Occupancy` turns a prediction into
cells: how likely they are in each over a span of ticks, and the first tick they
could be. Every future counts its weight once per cell.

```java
Occupancy soon = Occupancy.of(Core.prediction().predict(enemy, 10), placeTicks(), placeTicks() + 3);
Plan plan = webs.plan(eye, soon.likelyCells(0.3), looking);      // soonest first, then likeliest
```

### Trapping a player

`TrapSearch` is the search an auto-trap would otherwise do by hand: who to trap,
which cells, in what order, and this tick's clicks. What to place with — obsidian,
webs, anything — is your module's.

```java
TrapSearch<LivingEntity> search = TrapSearch.<LivingEntity>builder()
        .prediction(Core.prediction())
        .entities(Core.entities())
        .targets(Core.targets(), enemies)
        .protect(friends)                                         // never trapped, whatever the selector says
        .planner(style.planner(clicks, Obstructions.of(Core.entities(), players)).build())
        .pattern(() -> antiStep.isOn() ? TrapPattern.ANTI_STEP : TrapPattern.FULL)
        .placeDelay(() -> pingTicks() + placeDelay.getInt())
        .looking(rotations::getServerRotation)
        .bus(Core.bus())
        .build();

TrapOption<LivingEntity> trap = search.findTrap();
if (trap != null) {
    place(trap.getPlan());
    search.placed(trap);                                          // pending until the server shows them
}
```

**Patterns.** `TrapPattern.FULL` is a ring at the feet, a ring at the head and a
roof; `TOP_ONLY` the head ring and roof; `ANTI_STEP` adds a ring above the head
ring, so nothing is low enough to step up onto. Build your own with
`TrapPattern.builder()`, and `offset(part, dx, dy, dz)` adds cells at an offset
from each cell their feet are in. Every pattern follows a player across two or
four columns, and never includes a cell they are in.

**The likeliest way out first.** `TrapOrder.ESCAPES_FIRST`, unless you set
`BOTTOM_UP`:

- *Walled in at the feet, as in a hole* — the only way out is a jump, so the roof
  goes first, then the head ring. A roof alone already holds them.
- *In the open* — walking out is quickest: the feet ring first, the side they are
  likeliest to walk out of first, by their predicted futures; then the roof, so
  they cannot jump onto the ring; then the head ring.

A roof has nothing to click against. The planner gives it supports — a block on
the head ring, one on top of it, then the roof against that — so give the
search's planner `supports` of 2 (both presets do) and obstructions that include
players, so no support goes into their head.

**Who.** A target walled in, or standing still, is trapped around where they
stand. Someone moving — or not seen long enough to tell — is left alone unless
`trapMoving` is on: a trap is built around where someone is, and a moving player
is rarely there when it lands. With it on, they are trapped around where the
prediction puts them `placeDelay` ticks ahead.

**Never into them.** A solid block inside a player is refused. A cell they stand
in now, or are in when the block lands with at least `avoidChance` (0.1 unless
set) across their futures, waits: it is in `getDeferred()`, not the plan.

**Strict servers.** Give the search a planner from a `PlacementStyle` with one a
tick, and each tick goes on the cell that matters most. In a hole that is three
ticks for the roof — head ring, the block above it, the roof — then the rest of
the head ring.

A `TrapOption` has the whole trap in order (`getCells()`, each with its part),
what is still missing, what waits, this tick's plan, whether they are walled in
(`isEnclosed()`) and whether the roof is up or going up this tick (`isRoofed()`).
`findTraps(n)` gives up to `n`, the trap nearest done first unless you `rank`
them; `filter`s have the last word, `maxTargets` caps who is weighed, and
`getLastStats()` says why anyone was left out — moving, sealed already,
unplaceable or filtered. Share an `AttackLog` with your other searches through
`log(...)` so traps, fills and crystals keep out of each other's cells.

---

## 8. The damage monitor

The `DamageMonitor` checks your `ExplosionModel` against what explosions actually
do in game, so a version change shows up as a warning — not as a crystal aura that
quietly gets worse.

```java
DamageMonitor<LivingEntity> monitor = DamageMonitor.<LivingEntity>builder()
        .model(explosions)                          // the same model your aura scores with
        .blocks(myBlocks)
        .vitals(myVitals)
        .targets(Core.entities(), livingTracker)    // you, and everyone you track
        .bus(Core.bus())                            // ticks itself, posts DamageDriftEvent
        .build();

// in your adapter, on the game thread:
monitor.exploded(position, crystalExplosive);       // as the explosion packet is handled
monitor.popped(entity);                             // a totem pop, if your version reports them
```

`Vitals` reads health off your entity:

```java
Vitals<LivingEntity> vitals = new Vitals<LivingEntity>() {
    public double pool(LivingEntity e)            { return e.getHealth() + e.getAbsorptionAmount(); }
    public boolean isTrusted(LivingEntity e)      { return e == mc.player || serverShowsHealth; }
    public boolean isRecentlyHurt(LivingEntity e) { return e.hurtTime > 0; }
};
```

### How it measures

1. When an explosion is reported, every watched target in range gets the model's
   prediction and its health from just **before** — the end of the last tick, if
   that was higher, so a health update handled ahead of the explosion packet is
   still caught.
2. For the next few ticks (`settleTicks`, 3 by default) health is watched; the
   most it fell is what the explosion did.
3. Several explosions in one tick count as the strongest, since that is the only
   one the game applies. A totem pop only proves the damage was **at least**
   everything the target had.
4. Samples that measure something else are thrown away: a target still recovering
   from a hit, one whose health the server hides or fakes, one that leaves first.

### How it judges

By **medians**, so a sword hit landing in the same moment cannot sway it; and
separately for the samples that test each rule:

| Bucket | Targets | Drift here points at |
|---|---|---|
| `OPEN` | fully exposed, barely armoured | `Falloff` — the formula |
| `ARMOURED` | fully exposed, heavily armoured | `Mitigation` — armour, enchantments, effects |
| `COVERED` | partly behind blocks | `Exposure` — rays and block shapes |

```java
if (!monitor.isReliable()) {
    DamageReport report = monitor.report();
    report.getSuspect();                                     // FALLOFF, MITIGATION, EXPOSURE or UNKNOWN
    report.get(DamageSample.Bucket.ARMOURED).getMedianError();   // positive: under-predicting
}

@Subscribe
private void onDrift(DamageDriftEvent event) {
    if (!event.isReliable()) Core.notifications().warn("Crystals", "Damage model is off: " + event.getReport().getSuspect());
}
```

Several pops the model said would not happen make it unreliable on their own.
Predictions are trusted until there is evidence otherwise. Every threshold —
`settleTicks`, `window`, `minSamples`, `tolerance(absolute, relative)` — is a
tuning knob with a default, not a fact about any game.

**Limits.** In crystal PvP nearly every target wears armour, so `OPEN` samples
are rare and a drift in `ARMOURED` cannot fully rule out the formula; your own
samples help most, since your health is always readable. And in a heavy fight
targets are often still recovering from the last hit, so many samples are thrown
away — on purpose, since they would measure the recovery rule, not the explosion.

---

## 9. Test vectors

A **test vector** is one real explosion saved as a test case: the explosive and
where it went off; the target's position, size and captured `TargetState`; a
snapshot of the blocks between them; the damage that really happened; and what
your model predicted at the time.

### Record while you play

```java
VectorRecorder<LivingEntity> recorder = VectorRecorder.<LivingEntity>builder()
        .state(capture)          // the same StateCapture your mitigation uses
        .blocks(myBlocks)
        .build();

DamageMonitor<LivingEntity> monitor = DamageMonitor.<LivingEntity>builder()
        // ...
        .recorder(recorder)
        .build();
```

Every sample the monitor keeps becomes a vector. The target's state and the
blocks around it are captured the moment the explosion is reported, as the
explosion found them — every block in the box holding both the target and the
explosion, plus a margin. The recorder keeps the newest 1000 unless told
otherwise.

### Save

```java
try (Writer out = Files.newBufferedWriter(path)) {
    VectorJson.write(recorder.toSet("1.21.1 hard", Collections.singletonMap("server", "example.net")), out);
}
```

One readable JSON file per set, small enough to keep in your repository beside
the profile it tests:

```json
{
  "format": "px-explosion-vectors", "version": 1,
  "label": "1.21.1 hard", "metadata": { "server": "example.net" },
  "vectors": [ {
    "explosive": { "name": "end crystal", "power": 6.0 },
    "origin": [0.5, 65.0, 0.5],
    "target": { "position": [3.5, 65.0, 0.5], "width": 0.6, "height": 1.8, "eyeHeight": 1.62 },
    "state": { "armor": 20.0, "toughness": 12.0 },
    "blocks": { "palette": [ [[0,0,0,1,1,1]] ], "cells": [ [2,65,0,0] ] },
    "observed": 11.2, "popped": false,
    "recorded": { "inRange": true, "distance": 3.0, "exposure": 1.0, "raw": 44.5, "damage": 11.5 }
  } ]
}
```

Block shapes are stored once in a palette, and cells refer to them by index.
`VectorJson.read` gives back a set equal to the one written, and refuses files of
another format or from a newer version.

### Replay in your tests

```java
ExplosionModel<TargetState> profile = ExplosionModel.<TargetState>builder()
        .measureFrom(Tracked::getPosition)
        .exposure(exposure)
        .falloff(falloff)
        .mitigation(Mitigation.ofState(myDifficulty, armour, protection))
        .build();

ReplayResult result = VectorReplay.against(profile).run(VectorJson.read(reader));
if (!result.isPassed()) {
    System.out.println(result);        // every failure, and the suspect rule
}
```

Each vector's target is put back where it stood, among the blocks it was
recorded among, with no game. A vector passes when the prediction is within the
tolerance (0.05 by default — health is a float, so an exact profile still differs
in the last places) of what was observed. A popped vector, whose observed damage
is only a lower bound, passes when the prediction is at least that much.

Failures are grouped by the same buckets as the monitor, so `result.getSuspect()`
names the rule to fix. A version change you did not know about fails your vectors
before you ever play on it.

---

## 10. When Mojang changes something

| If your version changes… | Swap… |
|---|---|
| the damage formula, explosion range | your `Falloff` |
| where exposure rays are aimed | your `SampleGrid` |
| which blocks stop rays, or their shapes | your `BlockView` |
| armour, toughness, enchantments, effects, difficulty | one `StateMitigation` step |
| where armour or enchantments are read from | your `StateCapture` |
| which blocks hold a crystal, or how much space it needs | your `Placement` |
| a crystal's position, hitbox or explosion point | your `CrystalBody` |
| an explosive's power | its `Explosive` |
| where a bed can go, or whether it needs support | your `BedPlacement` |
| where a bed explodes from, or which halves set it off | `explodingAt`, `usableFrom` in your `BedRules` |
| a whole new explosive | a new `Explosive` for its damage; a `PlaceDevice` and `UseDevice` to search for it |

Then re-record a few vectors on the new version and replay them: when they pass,
the profile matches the game.

---

## 11. Package map

| Package | Classes | What it is |
|---|---|---|
| `combat.explosion` | `ExplosionModel`, `Explosive`, `DamageEstimate` | how any explosion hurts a target, with the working shown |
| `combat.explosion.rule` | `Exposure`, `SampleGrid`, `Falloff`, `Mitigation` | the four rules a model is made of |
| `combat.explosion.state` | `TargetState`, `StateCapture`, `StateMitigation` | captured target values, so one mitigation works live and replayed |
| `combat.crystal` | `CrystalRules`, `CrystalBody`, `Placement` | where a crystal goes, where it sits, what it does |
| `combat.bed` | `BedRules`, `Bed`, `BedPart`, `BedPlacement`, `BedLookup` | where a bed goes, finding standing ones, what one does |
| `combat.anchor` | `AnchorRules`, `Anchor`, `AnchorPlacement`, `AnchorLookup` | where a respawn anchor goes, finding standing ones with their charges, what one does |
| `combat.monitor` | `DamageMonitor`, `Vitals`, `DamageSample`, `DamageReport`, `DamageDriftEvent`, `SampleRecorder` | checks predictions against the real game |
| `combat.vector` | `TestVector`, `VectorSet`, `BlockSnapshot` | real explosions, kept as test cases |
| `combat.vector.capture` | `VectorRecorder` | records vectors from the monitor while you play |
| `combat.vector.io` | `VectorJson` | one JSON file per set |
| `combat.vector.replay` | `VectorReplay`, `ReplayResult`, `VectorOutcome` | runs a profile against saved explosions |
| `combat.search` | `CrystalSearch`, `BedSearch`, `AnchorSearch` | where to place, what to break or use |
| `combat.search.engine` | `ExplosiveSearch`, `Device`, `PlaceDevice`, `UseDevice`, `Found`, `SearchStats` | the search every explosive shares, and what each search cost |
| `combat.search.rule` | `Thresholds`, `Reach`, `ReachPoint`, `Score`, `AimCost`, `OptionFilter` | your aura's settings: what is worth doing, how far, how it is ranked, what turning is worth, and your own last word |
| `combat.search.option` | `Option`, `PlaceOption`, `BreakOption`, `BedPlaceOption`, `BedUseOption`, `AnchorPlaceOption`, `AnchorUseOption`, `Trigger`, `Proposal`, `Harm` | what the search found and why, and what a filter is shown |
| `combat.search.timing` | `AttackLog` | inhibit, and what has been dealt this tick, shareable across searches |
| `combat.hole` | `HoleRules`, `HoleFinder`, `Hole`, `HoleShape`, `HoleWatch`, `HoleEntry`, `HoleFill`, `FillOption`, `FillFilter`, `FillStats` | what a hole is in your game, finding them, who might get into one, and the best ones to fill first |
| `combat.place` | `Clicks`, `Click`, `FaceRule`, `HitPoint`, `PlacementStyle`, `PlacementPlanner`, `Plan`, `Shapes`, `Occupancy` | how your server takes a click, a preset per server, placing several blocks a tick, which cells to fill, and where a target is likely to be |
| `combat.trap` | `TrapSearch`, `TrapPattern`, `TrapOrder`, `TrapOption`, `TrapFilter`, `TrapStats` | who to trap, which cells, the likeliest way out first, and this tick's placements |

---

## 12. Verifying

`dev.px.combat.test.CombatSmokeTest` runs **496 checks** in a plain JVM — no
Minecraft, no window. The "game" is a few plain classes and a block grid written
by the tests, with its own version profile written the way a client would, and
the checks prove the library does what any profile says.

```
sh gradlew :combat:compileTestJava
java -cp combat/build/classes/java/main:combat/build/classes/java/test:\
core/build/classes/java/main:core/build/classes/java/testFixtures:<gson.jar> \
     dev.px.combat.test.CombatSmokeTest
```

Block shapes, rays and obstructions are Core's, and checked by Core's `WorldTests`. The suites share Core's test fixtures — `Checks`, `FakePlatform`, `RecordingLogger`,
`GridCollisionSpace` and `MovementRig` —
through `testFixtures(project(':core'))`.

| Suite | Covers |
|---|---|
| `CrystalRulesTests` | uniform grids; exposure in the open, behind a wall, partly covered; a model put together from its rules, with range culling, the formula's minimum, mitigation order and the measure point; placement by clearance, with one or two clear blocks and entities inside, touching and above; the crystal body; `CrystalRules` from a base and from an existing crystal, behind a wall, and builders naming what is missing |
| `DamageMonitorTests` | a matching model trusted; a wrong formula, wrong armour and wrong exposure each noticed and blamed on the right rule; several explosions in one tick; a health update handled before the explosion; recovering, hidden, untrusted, leaving and out-of-range targets; pops as lower bounds and unexpected pops; one disturbed sample not swaying the verdict; ticking from the bus, closing and clearing |
| `VectorTests` | target state; block snapshots at negative coordinates, palettes and rebuilding; recording from the monitor, with state, blocks and target, thrown-away samples, same-tick merging and capacity; JSON written and read back equal, other formats and newer versions refused; replaying the right profile from the saved file alone, and a wrong formula, wrong armour and wrong sampling each failing and blamed on the right rule; tolerance; popped vectors |
| `SearchTests` | placing on a floor of bases with the scan's counts; branch and bound picking what a brute-force search picks in 30 random layouts while pruning most candidates; the best few places, ordered, on their own bases, and the best four matching every option in 20 layouts; place range, the nearest-point measure and wall range; minimum damage, the self-damage cap, anti-suicide; lethal, its multiplier and its self-cap override, never for hidden health; faceplacing by health and by keybind, armour breaking; protecting a friend by cap and by margin, never for hidden health, never as a target, before the cut to `maxTargets`, against kills and breaks, cleared by the bound without raycasting, and refusing exactly what raycasting every friend refuses in 20 layouts; filters refusing, seeing the option and exact damage to friends only when asked, in order, on breaks too, choosing the right one of two enemies, and branch and bound staying exact under one in 15 layouts; picking the crystal to break, break range, minimum age; inhibit and the one-explosion-per-tick rule across ticks; breaking on spawn; balanced scoring; aiming — aim points, unaimable spots dropped before bounding, a turning cost traded against damage, bonuses, filters seeing the cost, separate place and break costs, spawn breaks, the angle cost, and the best one and best four matching every option in 40 layouts with costs, limits and bonuses; looking ahead — never asked without a delay, damage scored where the enemy will be against the real entity, your own damage and a friend walking into the blast looked ahead too, breaking by its own delay, Core's prediction as the lookahead, unreliable predictions left where they are; pending placements keeping the next off their room, expiring, shared through the log; a plan of places none in another's way; crystals that show up where you placed them yours, on break and on spawn; a listener hearing every estimate and each verdict; strict placement — a click on a face turned toward you, aimed at its hit, any face for a crystal unless your rules want the top, planned places with clicks; missing parts, no targets, ticking from the bus |
| `BedTests` | a bed's cells and equality by position; the best three beds, ranked; placement with room, walls, entities on either half, floating, and support under both halves; finding standing beds by their heads; the bed's own cells hiding its explosion, and predictions made with them empty; placing in the Nether, one spot per facing, narrowed facings, nothing in the Overworld; branch and bound against brute force in 20 layouts; using the nearer usable half, head-only versions, use range; aiming at the foot's cell and at the half to click, and beds you cannot turn to; clicks into the foot that face the bed its way, and the half used; inhibit by position; one explosion a tick, across a crystal search and a bed search sharing a log; the monitor and recorder told the bed is gone; builders naming what is missing |
| `AnchorTests` | finding anchors with their charges, empty ones, equality, negative charges refused; placement with room, never into the floor or anyone in the block, mid air only without a support rule; the anchor's own cell hiding its explosion, predictions made with it empty, the floor still counting, the origin where your rules put it; rules naming what is missing; an anchor placed for the enemy, in reach, one spot a cell, the best three ranked, nothing where anchors do not explode; branch and bound against brute force in 20 layouts; a charged anchor set off, an empty one needing a charge, charged only when asked, use range, wall range in plain sight and behind a wall, none in the Nether; inhibit by cell whatever the charges, dealt damage recorded, a one-tick place counting against it; placing over several ticks — pending, then found empty and yours; clicks to place and to use, only the placing click over several ticks, none where your server would not take one, spots whose anchor could not then be clicked refused; missing parts |
| `PlaceTests` | every way into a hole, each on the face turned toward it; strict direction from the side; the nearest click and the least turn; clicking a block itself from above and below; air places; faces behind walls, out of reach and covered; hit points at the centre and nearest, relative hits; a surround planned two a tick in order, filled and occupied cells skipped with reasons, a bridge built out against itself, out-of-order cells unreachable, supports one and two deep counted against the limit, started when the cell itself does not fit, before a later cell that would; shapes around a player in the middle of a block, on an edge and on a corner, a box filling one cell exactly, unions; webbing a player feet then head, refused for solid blocks, never into yourself, and ahead of a running target |
| `HoleFillTests` | a hole an enemy heads for offered with its cells, chance and timing, safe with slack, holes nobody heads for and past your reach not; an enemy already in one too late; a double filled whole, nearest cell first; the best two ranked soonest first; escape holes kept only when set; your own hole kept only when protected; friends' holes kept, and friends never enemies even under an every-player selector; occupied holes; safe, race and late; pending fills and their wait; filters, your own ranking, clicks per cell and unclickable holes; a prior per enemy; missing parts |
| `TrapTests` | full, top-only, anti-step and custom patterns, across two columns, never into the player; occupancy of someone still and someone walking, soonest first; vanilla and strict styles, fewer faces strictly, your own presets and their limit; a target in a hole roofed first, the roof stood on a head-ring block and the one above it, never on their head, then sealed and not offered; in the open the feet ring first, then the roof; bottom up and top only read live; strictly one a tick, roofed in three; moving and briefly-seen targets left alone, trapped where they will be when asked, the side they walk out of first, cells they stand in or likely will waiting, more with a warier avoid chance; pending traps and their wait; the trap nearest done first, friends never trapped, filters, your own ranking, `maxTargets`; out of reach, missing parts |
| `HoleTests` | singles, doubles along x and z and quads found in an obsidian ground, and trenches, missing headroom and a dirt wall not; safe only with bedrock walls and floor; nearest first, radius, found by any cell, equal when found again, shapes to choose; in the hole by footprint and wall height; a walker heading for a hole likely in, no sooner than possible and near the likely tick; a runner passing it not; holes beyond the horizon left out; a speed hacker in sooner and possibly sooner, the sprint-over split, and a prior leaning toward holes; the hole it is in first, a hole someone else is in occupied; builders naming what is missing |

---

## Not yet included

- **A fast path when nothing wins** — with every spot too weak (an enemy deep in
  a hole), pruning never starts and every viable spot is estimated: about twice a
  normal search on the test grid. Worth measuring on real worlds first.
- **Melee and city** — later capabilities alongside the crystal search. Auto-fill
  and traps have their searches; surround is a module on the placement planner.
- **Reference profiles** — whether to ship an optional `combat-vanilla` module of
  tested profiles per version is still open.
