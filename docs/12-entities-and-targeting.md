## 12. Entities and targeting

Two layers. **`Core.entities()`** is the world: read once a tick through your
adapter and handed to **trackers** — one per kind of thing you care about, typed
by the game's own class. **`Core.targets()`** picks from a tracker: selectors
that filter and rank it, and locks that hold a choice across ticks. ESP, radar
and nametags can use the first without the second.

### A tracker per kind of thing, in the game's own types

Core has no list of entity types, no idea what "dead" or "teammate" means, and
no numbers taken from any game — no reach, no hitbox size, no eye height. So it
does not describe entities at all. You write a tracker for each kind you want
followed, typed by the game's class, and it hands back the game's own object:

```java
public final class CrystalTracker extends EntityTracker<EntityEnderCrystal> {
    public CrystalTracker() { super(EntityEnderCrystal.class); }
}

public final class LivingTracker extends EntityTracker<EntityLivingBase> {
    public LivingTracker() { super(EntityLivingBase.class); }

    @Override protected boolean accepts(EntityLivingBase e) {        // who counts, asked every tick
        return e.deathTime == 0 && !Core.social().isFriend(e.getName());
    }
}

Core.entities().registerAll(new LivingTracker(), new CrystalTracker());

Tracked<EntityEnderCrystal> crystal = Core.entities().get(CrystalTracker.class).nearest(6);
crystal.get().getEntityId();                       // the game's object, already typed
```

The class argument is there because Java forgets `E` at runtime. It can be an
interface (`IMob`), and subclasses come along: `EntityTracker<EntityLivingBase>`
holds players and monsters alike. Trackers overlap freely — Core reads the
world **once** a tick, works out per class which trackers want it (one map
lookup, cached), reads each wanted entity's geometry once, and hands it to all
of them. An entity no tracker wants is never read.

| You write | For |
|---|---|
| `extends EntityTracker<E>` | a tracker other code looks up by class, `Core.entities().get(CrystalTracker.class)` |
| `accepts(E)` | which entities of the type to keep; one that stops passing is let go |
| `onTracked` / `onUntracked` | reacting as entities arrive and leave |
| `EntityTracker.of(type, predicate)` | a tracker one module owns, with no class of its own |

### Wiring it up

The adapter's half is one class that lists the world and says where things are:

```java
public final class LegacyEntities implements EntitySource<Entity> {
    public Iterable<Entity> entities() { return mc.theWorld == null ? null : mc.theWorld.loadedEntityList; }
    public Entity self()               { return mc.thePlayer; }

    public double x(Entity e)          { return e.posX; }
    public double y(Entity e)          { return e.posY; }
    public double z(Entity e)          { return e.posZ; }
    public double width(Entity e)      { return e.width; }
    public double height(Entity e)     { return e.height; }
    public double eyeHeight(Entity e)  { return e.getEyeHeight(); }   // optional: 0 if left out
    public float yaw(Entity e)         { return e.rotationYaw; }      // optional
    public float pitch(Entity e)       { return e.rotationPitch; }    // optional
}

Core.entities().setSource(new LegacyEntities());
```

Core refreshes every tracker at the start of every tick, ahead of every module,
so all of them read the same world. **Nothing you leave out gets a default from
a game**: no eye height puts the eyes at the position, no facing is yaw 0. A
source that throws for one entity skips it for the tick and is logged once; one
that throws while listing keeps the last tick's world. `setAutoRefresh(false)`
and `refresh()` let you pick a different point in your version's loop.

The local player is in no tracker. It is `Core.entities().getSelf()`, and it is
where targeting looks from.

### Between ticks

A tick is late for anything that reacts to a packet. A crystal aura wants the
crystal the moment its spawn packet arrives, not up to 50ms later, and wants it
gone the moment it is destroyed so it is not hit twice:

```java
// in your packet handling, on the game thread
crystals.track(spawnedCrystal);      // tracked now; queries find it straight away
crystals.forget(destroyedCrystal);   // let go now
```

Tracking something already tracked returns it unchanged, so its velocity stays a
per-tick one.

### The snapshot

A `Tracked<E>` is the game's object, from `get()`, plus what Core can measure
itself: a position, a box, a facing, the eye height, `getVelocityX/Y/Z` from the
last tick's move, `getTicksTracked()`, `extrapolate(n)`, and the last few ticks
of where it was, `positionAgo(n)` — as many as the tracker's `setHistory` keeps
(30 unless set), which is what `Core.prediction()` reads. `isFreshAgo(n)` says
whether each of those positions was news: servers do not send every entity's
position every tick, so a position that has not changed may mean no news. Give
your `EntitySource` a `positionStamp` that changes whenever the server sends one
and Core knows exactly; without it, each entity learns how often its positions
arrive, and `setUpdateGap` sets the least it waits before calling stillness a stop.
`projected(position)` gives a stand-in for the entity somewhere else — the same
game object, its box moved — for asking "what if it were there" of anything that
measures a `Tracked`, such as damage where a prediction says it will be.

It is **one object per entity per tracker for as long as the tracker keeps
it**, updated in place each tick and never recycled. Holding one across ticks is
safe: it keeps moving with the entity, and `isTracked()` turns false when the
tracker lets go — because the entity left the world or stopped being accepted.
Two trackers holding one entity hold two snapshots of it.

The position is the **tick's**, read before the world moves. It is what modules
should decide with. To draw smoothly between ticks, use the game's own
interpolated position from `get()`.

Reads are primitives first. `getX()`, `getMinX()` and
`squaredDistanceToBox(x, y, z)` read fields and allocate nothing;
`getPosition()`, `getEyePosition()` and `getBox()` build their value once per
tick on first ask.

### Selectors

A `TargetSelector<E>` is built once, kept in a field and run as often as needed.
**It matches exactly what you tell it to.** The default is every entity in its
tracker, any range, any angle, nearest first.

```java
private final TargetSelector<EntityLivingBase> enemies = TargetSelector.from(LivingTracker.class)
        .range(reach::getDouble)                       // your setting, read on every query
        .fov(fov::getDouble)
        .where(e -> e.hurtTime == 0)                   // the game's object, already typed
        .sort(TargetSort.by(EntityLivingBase::getHealth))
        .build();
```

`from(LivingTracker.class)` finds the tracker on each query, so a module can
build its selectors in field initialisers before any tracker is registered; until
one is, the selector finds nothing. `from(tracker)` takes one directly.

| Builder call | Keeps |
|---|---|
| `range(n)` / `range(supplier)` | boxes within reach of the origin, measured to the nearest point |
| `fov(degrees)` | boxes whose centre is inside a cone around the local player's view |
| `minTicksTracked(n)` / `maxTicksTracked(n)` | entities tracked for long enough, or recently enough |
| `where(predicate)` | entities whose game object passes; runs after the built-ins |
| `whereTracked(predicate)` | entities whose measurements pass — velocity, box, ticks tracked |

Filters run cheapest first: ticks tracked, range (the tracker's grid means far
entities are never visited), field of view (a dot product against
`cos(fov / 2)`, no inverse trigonometry), then your predicates. `toBuilder()`
makes a variant. Friends, teams and death are questions about your game, so they
are your tracker's `accepts` or your `where`.

`TargetSort` ranks by a score per candidate, lowest first, so choosing the best
is one pass and not a sort. Core ships only what means the same in any world —
`DISTANCE`, `ANGLE`, `NEWEST`, `OLDEST` — and everything else reads the game's
object: `TargetSort.by(EntityLivingBase::getHealth)`, or a lambda over the
`Tracked<E>` and the query's `TargetContext`. Any of them can be `reversed()`; a
key that returns `NaN` ranks last either way. Ties go to the nearer box.

```java
Core.targets().best(enemies);                       // or null
Core.targets().all(enemies, 3);                     // three best, in order
Core.targets().count(enemies);
Core.targets().forEach(enemies, e -> ...);          // unsorted, allocates nothing
Core.targets().best(crystals, enemy.getPosition()); // measured from another point
Core.targets().accepts(enemies, tracked);           // O(1), one entity
```

Selectors nest: a `where` predicate may run a query of its own ("players with
two or more monsters beside them"), and each query gets its own working state.

### Locks

`Core.targets().lock(selector)` returns a `TargetLock<E>`. `update()` once a
tick keeps the held target for as long as it passes the selector (an O(1)
check) and searches only when it stops, so a module does not flick between two
entities that trade places at the top of the ranking. `hasChanged()` says when
it switched, `lockOn(tracked)` holds one the player chose, `setSticky(false)`
takes the best every tick, `release()` lets go.

### Geometry

Range is measured to the **nearest point of the box**, because that is what
reaching something means in any world with boxes: an entity whose position is
four blocks away and whose box is two wide is three away. `Box` has
`closestPoint`, `distanceTo` and `inset`, and `Tracked` has `distanceToBox`,
`closestPoint` and `aimPoint(from, inset)` — the nearest point of the box shrunk
by `inset`, so a ray aimed there lands inside instead of grazing an edge. How
much reach and how much inset are both yours: Core has no default for either.

```java
Tracked<?> self = Core.entities().getSelf();
if (target.distanceToBox(self.getEyePosition()) <= reach.getDouble()) {
    Vec3 aim = target.aimPoint(self.getEyePosition(), 0.05);
    Core.rotations().request(this, self.getEyePosition().rotationTo(aim));
}
```

### Cost

With n entities in the world, n<sub>t</sub> in a tracker, k candidates a query
visits and m that pass:

| Operation | Cost |
|---|---|
| refresh, once a tick | O(n) class lookups; an entity is read once however many trackers want it, and not at all if none do |
| per tracker | O(n<sub>t</sub>), nothing allocated for entities already tracked |
| `get`, `contains`, `track`, `accepts`, a lock keeping its target | O(1) |
| range query over a small tracker (≤ `setScanLimit`, 32 unless changed) | O(n<sub>t</sub>), a plain scan |
| range query over a larger one | O(b + k) through the tracker's `SpatialGrid`, b buckets the radius covers |
| `best`, `count`, `forEach` | O(k), one pass, no allocation |
| `all` | O(k + m log m), over candidates pooled between calls |

A tracker's grid is built on the first range query of the tick that needs one,
in O(n<sub>t</sub>), and not at all on a tick with no such query. The grid
indexes positions, so the search is widened by the furthest any box reached from
its position that tick and every candidate is measured exactly. `setCellSize`
tunes it; near the radius you query most is best. The suite checks 450 random
queries against a brute-force scan, at two cell sizes.

Game thread only, like the tick it refreshes on.

### Not using any of this

Install no `EntitySource` or register no tracker and nothing is read:
`getSelf()` is null and every targeting query answers null, empty or zero.
