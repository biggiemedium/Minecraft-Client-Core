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
the [damage monitor](#6-the-damage-monitor) and [test vectors](#7-test-vectors)
tell you which rule it was.

**Status.** The rules layer, the search — where to place, what to break, every
threshold and timing rule — for crystals and beds, the damage monitor and test
vectors are done. See
[Not yet included](#not-yet-included) for what comes next.

**Requires:** Core, Java 8, Gson (ships with Minecraft). Inside this repository:

```groovy
dependencies {
    implementation project(':combat')     // brings Core with it
}
```

Without Gradle, copy `combat/src/main/java/dev/px/combat` alongside Core's sources.

---

## A full crystal aura

A complete crystal aura on Core and Combat, ready to copy into a client. It is
kept up to date as the library grows, so it always shows the current way to
build one. The numbered sections below explain every piece in it.

Three files and a few lines of wiring:

| File | What it is | Changes when |
|---|---|---|
| `CombatSetup.java` | everything that knows the game: trackers, blocks, health, the explosion profile | the game version changes |
| `CrystalAura.java` | the module: settings, and acting on what the search finds | you change how your aura behaves |
| `CrystalSpawnEvent.java` | the one event Core does not have | never |

Every Core and Combat call is the real API, and the files compile as they are.
Minecraft types use 1.21 Yarn names; other game calls go through `Game.…`, your
own small wrappers, because the right method names depend on your version and
mappings.

### `CombatSetup.java`

```java
package your.client.combat;

import dev.px.combat.crystal.CrystalBody;
import dev.px.combat.crystal.CrystalRules;
import dev.px.combat.crystal.Placement;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.explosion.rule.Falloff;
import dev.px.combat.explosion.rule.Mitigation;
import dev.px.combat.explosion.rule.SampleGrid;
import dev.px.combat.explosion.state.StateCapture;
import dev.px.combat.explosion.state.StateMitigation;
import dev.px.combat.explosion.state.TargetState;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.world.BlockShape;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Obstructions;
import dev.px.core.Core;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.target.TargetSelector;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Combat for one game version: what the aura targets, the blocks it sees, how
 * much health things have, and how explosions hurt. When Mojang changes how
 * crystals or explosions work, this is the file you edit.
 */
public final class CombatSetup {

    /** Everything living the aura might hit. */
    public static final class LivingTracker extends EntityTracker<LivingEntity> {
        public LivingTracker() { super(LivingEntity.class); }

        @Override protected boolean accepts(LivingEntity entity) { return entity.isAlive(); }
    }

    /** End crystals already in the world. */
    public static final class CrystalTracker extends EntityTracker<EndCrystalEntity> {
        public CrystalTracker() { super(EndCrystalEntity.class); }
    }

    /** The blocks: their shapes for explosion rays, which hold a crystal, which are clear. */
    public static final class GameBlocks implements BlockView {
        private final Map<Object, BlockShape> shapes = new HashMap<>();

        @Override public BlockShape shapeAt(int x, int y, int z) {
            return shapes.computeIfAbsent(Game.blockStateAt(x, y, z), Game::collisionShapeOf);   // once per state
        }

        public boolean isBase(int x, int y, int z)  { return Game.isObsidianOrBedrock(x, y, z); }
        public boolean isClear(int x, int y, int z) { return Game.isAirOrReplaceable(x, y, z); }
    }

    public final LivingTracker living = new LivingTracker();
    public final CrystalTracker crystals = new CrystalTracker();
    public final GameBlocks blocks = new GameBlocks();
    public final Vitals<LivingEntity> vitals;
    public final StateCapture<LivingEntity> capture;
    public final ExplosionModel<LivingEntity> explosions;
    public final CrystalRules<LivingEntity> rules;
    public final TargetSelector<LivingEntity> enemies;

    public CombatSetup() {
        vitals = new Vitals<LivingEntity>() {
            @Override public double pool(LivingEntity e)            { return e.getHealth() + e.getAbsorptionAmount(); }
            @Override public boolean isTrusted(LivingEntity e)      { return e == Game.player() || Game.serverShowsHealth(); }
            @Override public boolean isRecentlyHurt(LivingEntity e) { return e.hurtTime > 0; }
        };

        // What the armour maths reads, captured off the entity, so test vectors can replay it.
        capture = e -> TargetState.builder()
                .put("armor", Game.armor(e))
                .put("toughness", Game.armorToughness(e))
                .put("protection", Game.blastProtectionPoints(e))
                .put("difficulty", Game.difficultyName())
                .build();

        explosions = ExplosionModel.<LivingEntity>builder()
                .measureFrom(Tracked::getPosition)                      // the wiki: to the feet
                .exposure(Exposure.sampled(Profile.GRID))
                .falloff(Profile.FALLOFF)
                .mitigation(Mitigation.fromState(capture, Profile.DIFFICULTY, Profile.ARMOUR, Profile.PROTECTION))
                .build();

        rules = CrystalRules.<LivingEntity>builder()
                .placement(Placement.clearance(blocks::isBase, 2, blocks::isClear, 2.0))   // 1 or 2: check your version
                .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0))
                .explosive(Explosive.of("end crystal", 6))
                .model(explosions)
                .blocks(blocks)
                .obstructions(Obstructions.of(Core.entities(), living, crystals))
                .build();

        // Who counts as an enemy: players in range who are not friends.
        enemies = TargetSelector.from(LivingTracker.class)
                .range(12)
                .where(e -> e instanceof PlayerEntity && !Core.social().isFriend(Game.nameOf(e)))
                .build();
    }

    /** This version's explosion rules, written from the Minecraft wiki. Check them with the monitor and test vectors. */
    public static final class Profile {

        /** impact = (1 − distance / 2·power) · exposure; damage = 7 · power · (impact² + impact) + 1. */
        public static final Falloff FALLOFF = Falloff.of(
                power -> 2 * power,
                (distance, exposure, power) -> {
                    double impact = (1 - distance / (2 * power)) * exposure;
                    return 7 * power * (impact * impact + impact) + 1;
                });

        /** Where rays are aimed in the target's box, per the wiki's description of the grid. */
        public static final SampleGrid GRID = (box, sink) -> {
            double w = box.getWidth(), h = box.getHeight(), d = box.getDepth();
            double sx = 1 / (w * 2 + 1), sy = 1 / (h * 2 + 1), sz = 1 / (d * 2 + 1);
            double ox = (1 - Math.floor(1 / sx) * sx) / 2;
            double oz = (1 - Math.floor(1 / sz) * sz) / 2;
            for (double fx = 0; fx <= 1; fx += sx) {
                for (double fy = 0; fy <= 1; fy += sy) {
                    for (double fz = 0; fz <= 1; fz += sz) {
                        sink.point(box.getMin().getX() + fx * w + ox,
                                   box.getMin().getY() + fy * h,
                                   box.getMin().getZ() + fz * d + oz);
                    }
                }
            }
        };

        public static final StateMitigation DIFFICULTY = (damage, state) -> {
            switch (state.text("difficulty")) {
                case "peaceful": return 0;
                case "easy":     return Math.min(damage / 2 + 1, damage);
                case "hard":     return damage * 1.5;
                default:         return damage;
            }
        };

        public static final StateMitigation ARMOUR =
                (damage, state) -> Game.damageAfterArmour(damage, state.get("armor"), state.get("toughness"));

        public static final StateMitigation PROTECTION =
                (damage, state) -> Game.damageAfterProtection(damage, state.get("protection"));

        private Profile() {
        }
    }
}
```

### `CrystalAura.java`

```java
package your.client.combat;

import dev.px.combat.search.CrystalSearch;
import dev.px.combat.search.option.BreakOption;
import dev.px.combat.search.option.PlaceOption;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.rule.ReachPoint;
import dev.px.combat.search.rule.Thresholds;
import dev.px.core.Core;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.Render3DEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleInfo;
import dev.px.core.movement.rotation.RotationPriority;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.BindSetting;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.setting.impl.NumberSetting;
import net.minecraft.entity.LivingEntity;

@ModuleInfo(name = "Crystal Aura", description = "Places and breaks end crystals", category = "Combat")
public final class CrystalAura extends Module {

    // ---- what to do
    private final BooleanSetting place    = bool("Place", true);
    private final BooleanSetting breaking = bool("Break", true);
    private final BooleanSetting instant  = bool("Instant", true).describe("Break crystals the moment they spawn");

    // ---- reach
    private final NumberSetting<Double> placeRange = decimal("Place Range", 4.5, 1, 6);
    private final NumberSetting<Double> placeWall  = decimal("Place Wall Range", 3.5, 0, 6);
    private final NumberSetting<Double> breakRange = decimal("Break Range", 4.5, 1, 6);
    private final NumberSetting<Double> breakWall  = decimal("Break Wall Range", 3.5, 0, 6);

    // ---- damage
    private final NumberSetting<Double> minDamage   = decimal("Min Damage", 6, 0, 36);
    private final NumberSetting<Double> maxSelf     = decimal("Max Self Damage", 10, 0, 36);
    private final BooleanSetting        antiSuicide = bool("Anti Suicide", true);
    private final BooleanSetting        lethal      = bool("Lethal", true);
    private final NumberSetting<Double> lethalMult  = decimal("Lethal Multiplier", 1.5, 1, 4).visibleWhen(lethal);
    private final BooleanSetting        ignoreSelf  = bool("Lethal Ignores Max Self", true).visibleWhen(lethal);

    // ---- faceplace and armour breaking
    private final NumberSetting<Double> faceHealth = decimal("Faceplace Health", 8, 0, 36);
    private final NumberSetting<Double> faceDamage = decimal("Faceplace Damage", 2, 0, 10);
    private final BindSetting           faceKey    = bind("Faceplace Key");
    private final NumberSetting<Double> armourWorn = decimal("Armour Break %", 15, 0, 100);

    // ---- timing
    private final NumberSetting<Integer> ticksExisted = integer("Ticks Existed", 0, 0, 10);
    private final NumberSetting<Integer> inhibit      = integer("Inhibit Ticks", 3, 0, 10);

    // ---- rotations and rendering
    private final BooleanSetting       rotate = bool("Rotate", true);
    private final NumberSetting<Float> turn   = number("Rotation Speed", 180f, 10f, 180f).visibleWhen(rotate);
    private final ColorSetting         colour = color("Colour", Color.rgb(0xADF773));

    private final CrystalSearch<LivingEntity> search;

    private boolean faceHeld;
    private PlaceOption<LivingEntity> shown;          // the last placement, for rendering

    public CrystalAura(CombatSetup combat) {
        Thresholds<LivingEntity> thresholds = Thresholds.<LivingEntity>builder()
                .minDamage(minDamage::getDouble)
                .maxSelfDamage(maxSelf::getDouble)
                // a margin of -∞ switches anti-suicide off; a multiplier of 0 switches lethal off
                .antiSuicide(() -> antiSuicide.isOn() ? 0.5 : Double.NEGATIVE_INFINITY)
                .lethal(() -> lethal.isOn() ? lethalMult.getDouble() : 0, ignoreSelf::isOn)
                .facePlace(faceHealth::getDouble, faceDamage::getDouble)
                .facePlaceWhen(() -> faceHeld)
                .armourBreak(Game::mostWornArmourPercent, armourWorn::getDouble, faceDamage::getDouble)
                .breakMinAge(ticksExisted::getInt)
                .inhibit(inhibit::getInt)
                .build();

        this.search = CrystalSearch.<LivingEntity>builder()
                .rules(combat.rules)
                .entities(Core.entities())
                .targets(Core.targets(), combat.enemies)
                .crystals(combat.crystals)
                .vitals(combat.vitals)
                .placeReach(Reach.of(placeRange::getDouble, placeWall::getDouble))
                .breakReach(Reach.of(breakRange::getDouble, breakWall::getDouble).measuredTo(ReachPoint.NEAREST))
                .thresholds(thresholds)
                .bus(Core.bus())                      // ticks the search ahead of this module
                .build();
    }

    @Subscribe(stage = Stage.PRE, priority = Priority.HIGH)
    private void onTick(TickEvent event) {
        if (breaking.isOn()) {
            BreakOption<LivingEntity> hit = search.findBreak();
            if (hit != null) {
                aimAt(hit.getCrystal().getCenter());
                Game.attack(hit.getCrystal().get());          // your version's attack packet and swing
                search.attacked(hit.getCrystal());            // inhibit it; only the strongest lands this tick
            }
        }
        shown = null;
        if (place.isOn()) {
            PlaceOption<LivingEntity> spot = search.findPlace();
            if (spot != null) {
                aimAt(spot.getOrigin());
                Game.placeCrystalOn(spot.getX(), spot.getY(), spot.getZ());   // swap, click the base, swap back
                shown = spot;
            }
        }
    }

    /** Your adapter posts this when the game adds a crystal: break it before the next tick. */
    @Subscribe
    private void onCrystalSpawn(CrystalSpawnEvent event) {
        if (!instant.isOn() || !breaking.isOn()) {
            return;
        }
        BreakOption<LivingEntity> now = search.spawned(event.getCrystal());
        if (now != null) {
            Game.attack(now.getCrystal().get());
            search.attacked(now.getCrystal());
        }
    }

    /** Faceplaces every target while the key is held. */
    @Subscribe
    private void onKey(KeyEvent event) {
        if (faceKey.get().matches(event.getKey(), event.getModifiers())) {
            faceHeld = event.isPressed();
        }
    }

    @Subscribe
    private void onRender(Render3DEvent event) {
        if (shown != null) {
            Render.boxOutline(Box.block(shown.getX(), shown.getY(), shown.getZ()), 1.5f, colour.resolve());
        }
    }

    private void aimAt(Vec3 point) {
        if (rotate.isOn()) {
            Vec3 eye = Core.entities().getSelf().getEyePosition();
            Core.rotations().request(this, eye.rotationTo(point), RotationPriority.HIGH, turn.getFloat());
        }
    }

    @Override protected void onDisable() {
        shown = null;
        faceHeld = false;
        Core.rotations().release(this);
    }

    /** Shown after the name in the module list: the damage of the last placement, and why. */
    @Override public String getDisplayInfo() {
        return shown == null ? "" : String.format("%.1f %s", shown.getDamage(), shown.getTrigger());
    }
}
```

### `CrystalSpawnEvent.java`

```java
package your.client.combat;

import dev.px.core.event.Event;

/** Posted by your adapter when the game adds an end crystal: Core has no event for entities appearing. */
public final class CrystalSpawnEvent extends Event {

    private final Object crystal;

    public CrystalSpawnEvent(Object crystal) {
        this.crystal = crystal;
    }

    public Object getCrystal() {
        return crystal;
    }
}
```

### Wiring it in

In your bootstrap, register the trackers before the module that uses them:

```java
Core core = Core.builder("LeapFrog", "2.0").platform(new MyPlatform()).build();

CombatSetup combat = new CombatSetup();
core.getEntityService().registerAll(combat.living, combat.crystals);
core.getModuleRegistry().registerAll(new CrystalAura(combat));

core.start();
Core.entities().setSource(new MyEntitySource());     // your EntitySource (Core §12)
```

In your mixins, where the game adds an entity to the world, on the game thread:

```java
if (entity instanceof EndCrystalEntity) {
    Core.bus().post(new CrystalSpawnEvent(entity));
}
```

Optionally, check the profile against the real game while you play and record
test vectors to replay later ([§6](#6-the-damage-monitor), [§7](#7-test-vectors)):

```java
VectorRecorder<LivingEntity> recorder = VectorRecorder.<LivingEntity>builder()
        .state(combat.capture)
        .blocks(combat.blocks)
        .build();

DamageMonitor<LivingEntity> monitor = DamageMonitor.<LivingEntity>builder()
        .model(combat.explosions)
        .blocks(combat.blocks)
        .vitals(combat.vitals)
        .targets(Core.entities(), combat.living)
        .recorder(recorder)
        .bus(Core.bus())
        .build();

// where the explosion packet is handled, on the game thread:
monitor.exploded(Vec3.of(packet.getX(), packet.getY(), packet.getZ()), combat.rules.getExplosive());
```

### What is whose

| Yours, per client | Yours, per game version | The library's |
|---|---|---|
| the settings and their defaults | `CombatSetup` and its `Profile` | the scan, the bounds, branch and bound |
| how you rotate, swap and swing | the `Game.…` wrappers | every threshold and trigger |
| what you render and log | which blocks are bases, the clearance number | inhibit, minimum age, spawn breaks |
| when to place and break, and in which order | the explosion and armour formulas | the one-explosion-per-tick rule |

---

## Contents

- [A full crystal aura](#a-full-crystal-aura)

1. [Your world](#1-your-world)
2. [Explosions](#2-explosions)
3. [Crystals](#3-crystals)
4. [Searching](#4-searching)
5. [Beds](#5-beds)
6. [The damage monitor](#6-the-damage-monitor)
7. [Test vectors](#7-test-vectors)
8. [When Mojang changes something](#8-when-mojang-changes-something)
9. [Package map](#9-package-map)
10. [Verifying](#10-verifying)

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

Combat sees blocks through one interface you implement per version:

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

A `BlockShape` is what stops an explosion's rays in a cell: `BlockShape.EMPTY`,
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

The same model serves crystals and beds now, and anchors later — they differ only
in their `Explosive`. A target beyond the falloff's range costs no raycasting at
all.

### Writing a profile for your version

Here is a profile written from the [Minecraft wiki](https://minecraft.wiki/w/Explosion)'s
description of current Java. **These numbers are your client's, not the
library's** — check them against your version with the [monitor](#6-the-damage-monitor)
and [test vectors](#7-test-vectors).

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
        .breakMinAge(ticksExisted::getInt)
        .inhibit(inhibitTicks::getInt)
        .build();

CrystalSearch<LivingEntity> search = CrystalSearch.<LivingEntity>builder()
        .rules(crystals)                                           // your CrystalRules
        .entities(Core.entities())                                 // you
        .targets(Core.targets(), enemies)                          // a TargetSelector: who counts
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
its score, and its `Trigger` — `MINIMUM`, `FACEPLACE`, `ARMOUR_BREAK` or `LETHAL`.
Draw the damage, log the reason, or skip a faceplace you do not want right now.

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
4. The **self-damage cap** refuses anything that hurts you more.

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
   wall range — one ray for visibility.
2. **Bound** each candidate against each target: the damage it would do with
   nothing in the way. That costs no raycasting at all. A candidate whose bound
   cannot meet any target's threshold is dropped there.
3. **Branch and bound.** Candidates are tried best bound first with exact,
   raycast estimates. Once the best option found scores higher than the next
   bound, nothing left can win, and the search stops.

With R the place range, T the targets, S the sample points an `Exposure` casts
from, L the cells a ray crosses (about √3 × its length), B the bases that pass
the scan, V the candidates still viable after bounding, and K the crystals in
break range:

| Step | Cost |
|---|---|
| scan | (2⌈R⌉ + 1)³ cells: distance and `canPlace` each, one ray each past the wall range |
| bound | O(B · T), no rays |
| order | O(V log V), one sort |
| exact estimates, worst case | O(V · (T + 1) · S · L) |
| exact estimates, typically | a handful: the bound stops the search early |
| break | O(g + K · (T + 1) · S · L), with g the grid buckets the break range covers |
| `spawned`, `attacked` | O((T + 1) · S · L): one crystal |

For a place range of 5 that is 1331 cells; with a player's hitbox sampled at
S = 45 and rays of about 20 cells, one exact estimate is around 900 cell tests —
which is why the bound matters more than anything else here. In the test arena,
67 viable placements are settled with 4 exact estimates.

Pruning assumes damage never falls as exposure rises, and that a score is never
more than the damage to the target. Both hold for everything shipped here; turn
pruning off with `.pruning(false)` for a model or score that breaks either. The
test suite checks branch and bound against a brute-force search in 30 random
layouts.

`getLastPlaceStats()` and `getLastBreakStats()` say what the last search did —
cells scanned, bases found, candidates pruned, estimates paid for — for profiling,
and for seeing why nothing was found.

### What it reuses from Core

| Need | Core piece |
|---|---|
| who the targets are, in range, filtered and sorted | `TargetService` with your `TargetSelector` |
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

**Your own explosive.** Respawn anchors, or whatever a version adds next, are a
device of yours: implement `PlaceDevice` and `UseDevice`, then call
`ExplosiveSearch.findPlace(device)` and `findUse(device)`. Every threshold, the
branch and bound and the timing come with it. `getEngine()` on either search
hands you an engine with the same settings.

---

## 5. Beds

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

---

## 6. The damage monitor

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

## 7. Test vectors

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

## 8. When Mojang changes something

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

## 9. Package map

| Package | Classes | What it is |
|---|---|---|
| `combat.world` | `BlockView`, `BlockShape`, `CellTest`, `Rays`, `Obstructions` | your world as combat needs it |
| `combat.explosion` | `ExplosionModel`, `Explosive`, `DamageEstimate` | how any explosion hurts a target, with the working shown |
| `combat.explosion.rule` | `Exposure`, `SampleGrid`, `Falloff`, `Mitigation` | the four rules a model is made of |
| `combat.explosion.state` | `TargetState`, `StateCapture`, `StateMitigation` | captured target values, so one mitigation works live and replayed |
| `combat.crystal` | `CrystalRules`, `CrystalBody`, `Placement` | where a crystal goes, where it sits, what it does |
| `combat.bed` | `BedRules`, `Bed`, `BedPart`, `BedPlacement`, `BedLookup` | where a bed goes, finding standing ones, what one does |
| `combat.monitor` | `DamageMonitor`, `Vitals`, `DamageSample`, `DamageReport`, `DamageDriftEvent`, `SampleRecorder` | checks predictions against the real game |
| `combat.vector` | `TestVector`, `VectorSet`, `BlockSnapshot` | real explosions, kept as test cases |
| `combat.vector.capture` | `VectorRecorder` | records vectors from the monitor while you play |
| `combat.vector.io` | `VectorJson` | one JSON file per set |
| `combat.vector.replay` | `VectorReplay`, `ReplayResult`, `VectorOutcome` | runs a profile against saved explosions |
| `combat.search` | `CrystalSearch`, `BedSearch` | where to place, what to break or use |
| `combat.search.engine` | `ExplosiveSearch`, `Device`, `PlaceDevice`, `UseDevice`, `Found`, `SearchStats` | the search every explosive shares, and what each search cost |
| `combat.search.rule` | `Thresholds`, `Reach`, `ReachPoint`, `Score` | your aura's settings: what is worth doing, how far, how it is ranked |
| `combat.search.option` | `Option`, `PlaceOption`, `BreakOption`, `BedPlaceOption`, `BedUseOption`, `Trigger` | what the search found, and why |
| `combat.search.timing` | `AttackLog` | inhibit, and what has been dealt this tick, shareable across searches |

---

## 10. Verifying

`dev.px.combat.test.CombatSmokeTest` runs **211 checks** in a plain JVM — no
Minecraft, no window. The "game" is a few plain classes and a block grid written
by the tests, with its own version profile written the way a client would, and
the checks prove the library does what any profile says.

```
sh gradlew :combat:compileTestJava
java -cp combat/build/classes/java/main:combat/build/classes/java/test:\
core/build/classes/java/main:core/build/classes/java/testFixtures:<gson.jar> \
     dev.px.combat.test.CombatSmokeTest
```

The suites share Core's test fixtures — `Checks`, `FakePlatform`, `RecordingLogger` —
through `testFixtures(project(':core'))`.

| Suite | Covers |
|---|---|
| `CrystalRulesTests` | block shapes and segment tests, including what counts as touching; rays through walls, over and through slabs, from inside a block; uniform grids; exposure in the open, behind a wall, partly covered; a model put together from its rules, with range culling, the formula's minimum, mitigation order and the measure point; placement by clearance, with one or two clear blocks and entities inside, touching and above; the crystal body; `CrystalRules` from a base and from an existing crystal, behind a wall, and builders naming what is missing |
| `DamageMonitorTests` | a matching model trusted; a wrong formula, wrong armour and wrong exposure each noticed and blamed on the right rule; several explosions in one tick; a health update handled before the explosion; recovering, hidden, untrusted, leaving and out-of-range targets; pops as lower bounds and unexpected pops; one disturbed sample not swaying the verdict; ticking from the bus, closing and clearing |
| `VectorTests` | target state; block snapshots at negative coordinates, palettes and rebuilding; recording from the monitor, with state, blocks and target, thrown-away samples, same-tick merging and capacity; JSON written and read back equal, other formats and newer versions refused; replaying the right profile from the saved file alone, and a wrong formula, wrong armour and wrong sampling each failing and blamed on the right rule; tolerance; popped vectors |
| `SearchTests` | placing on a floor of bases with the scan's counts; branch and bound picking what a brute-force search picks in 30 random layouts while pruning most candidates; place range, the nearest-point measure and wall range; minimum damage, the self-damage cap, anti-suicide; lethal, its multiplier and its self-cap override, never for hidden health; faceplacing by health and by keybind, armour breaking; picking the crystal to break, break range, minimum age; inhibit and the one-explosion-per-tick rule across ticks; breaking on spawn; balanced scoring; missing parts, no targets, ticking from the bus |
| `BedTests` | a bed's cells and equality by position; placement with room, walls, entities on either half, floating, and support under both halves; finding standing beds by their heads; the bed's own cells hiding its explosion, and predictions made with them empty; placing in the Nether, one spot per facing, narrowed facings, nothing in the Overworld; branch and bound against brute force in 20 layouts; using the nearer usable half, head-only versions, use range; inhibit by position; one explosion a tick, across a crystal search and a bed search sharing a log; the monitor and recorder told the bed is gone; builders naming what is missing |

---

## Not yet included

- **Placement facing and multiplace** — which face to click and whether a server
  will accept it ("strict direction"), and planning several placements a tick.
- **Respawn anchors** — a device on the shared engine: placing, charging with
  glowstone, and exploding outside the Nether are not written yet.
- **Melee, city, surround, holes** — later capabilities alongside the crystal search.
- **Reference profiles** — whether to ship an optional `combat-vanilla` module of
  tested profiles per version is still open.
