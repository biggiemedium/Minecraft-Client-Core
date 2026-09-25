package dev.px.core.test.suite;

import dev.px.core.Core;
import dev.px.core.entity.EntityAttribute;
import dev.px.core.entity.EntityCategory;
import dev.px.core.entity.EntityData;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTag;
import dev.px.core.entity.TagSet;
import dev.px.core.entity.TrackedEntity;
import dev.px.core.event.Stage;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.social.SocialService;
import dev.px.core.target.TargetLock;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.target.TargetSort;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The entity snapshot and the targeting built on it.
 *
 * <p>Two proofs in one file. The "world" is a list of plain Java objects behind a
 * small {@link EntitySource}, so neither layer needs a game. And every category,
 * tag and attribute used here is declared <em>by the test</em>, the way a client
 * declares its own: Core ships none, so nothing it does can depend on a game's
 * idea of what an entity is.
 *
 * <p>The spatial index is checked the only way worth trusting: hundreds of random
 * queries against a brute-force scan of the same world.
 */
public final class TargetingTests {

    // The client's own vocabulary. Nothing like this exists in Core.
    private enum Kinds implements EntityCategory { PLAYER, MONSTER, CRYSTAL, ITEM }

    private enum Tags implements EntityTag { INVISIBLE, TEAMMATE, DEAD }

    private enum Stats implements EntityAttribute { HEALTH, ARMOR }

    private TargetingTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Entities and targeting");

        vocabulary();
        noAssumedValues();
        snapshot();
        lifecycle();
        failures();
        rangeQueries();
        gridAgreesWithScan();
        defaults();
        tagsAndAttributes();
        rangeAndFov();
        sorting();
        queries();
        ageAndOrigin();
        nesting();
        locks();
        geometry();
        bootedClient(client);
    }

    // ---------------------------------------------------------- vocabulary

    private static void vocabulary() {
        Checks.checkEquals("an enum is a category with no body to write", "CRYSTAL", Kinds.CRYSTAL.getName());

        // Far more tags than fit in one word, to prove there is no cap.
        List<EntityTag> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new Numbered(i));
        }
        // Declared together, they take consecutive slots, so the last ones are well past the first word.
        TagSet.of(many);
        Rig rig = new Rig();
        Mob mob = rig.add(Kinds.MONSTER, null, 0, 64, 2);
        mob.extraTags.add(many.get(3));
        mob.extraTags.add(many.get(140));
        rig.tick();
        TrackedEntity e = rig.entities.get(mob);
        Checks.check("tags past the sixty-fourth work like the first",
                e.has(many.get(140)) && e.has(many.get(3)) && !e.has(many.get(139)));
        Checks.check("a set spanning several words matches any", TagSet.of(many.get(100), many.get(140)).matchesAny(e));
        Checks.check("and all", TagSet.of(many.get(3), many.get(140)).matchesAll(e)
                && !TagSet.of(many.get(3), many.get(141)).matchesAll(e));
        Checks.check("a set naming tags the entity was never given matches none",
                TagSet.of(many.get(149)).matchesNone(e));
        Checks.check("value-equal tags are the same tag", e.has(new Numbered(140)));
    }

    private static void noAssumedValues() {
        Rig rig = new Rig();
        Bare bare = new Bare();
        rig.world.bare.add(bare);
        rig.tick();
        TrackedEntity e = rig.entities.get(bare);
        Checks.check("with nothing written, the entity is uncategorised", e.is(EntityCategory.UNCATEGORIZED));
        Checks.check("its box has no volume", e.getWidth() == 0d && e.getHeight() == 0d);
        Checks.checkEquals("its eyes are at its position", 0f, (float) e.getEyeHeight());
        Checks.check("an attribute never set is NaN, not zero", Double.isNaN(e.get(Stats.HEALTH)) && !e.has(Stats.HEALTH));
        Checks.checkEquals("with a fallback when asked for one", -1f, (float) e.get(Stats.HEALTH, -1d));
        Checks.check("and it has no tags", !e.has(Tags.DEAD));
        Checks.check("it is still indexed and queryable",
                rig.entities.ofCategory(EntityCategory.UNCATEGORIZED).contains(e));
    }

    // ------------------------------------------------------------ snapshot

    private static void snapshot() {
        Rig rig = new Rig();
        Mob enemy = rig.add(Kinds.PLAYER, "Enemy", 0, 64, 4);
        Mob monster = rig.add(Kinds.MONSTER, null, 3, 64, 0);
        Mob crystal = rig.add(Kinds.CRYSTAL, null, -2, 65, 0);
        crystal.health = null;
        crystal.width = 2d;
        crystal.height = 2d;
        rig.tick();

        EntityService entities = rig.entities;
        Checks.checkEquals("every entity is read, the local player included", 4, entities.size());
        Checks.check("the local player is known", entities.getSelf() != null && entities.getSelf().isSelf());
        Checks.checkEquals("categories are indexed", 1, entities.count(Kinds.CRYSTAL));
        Checks.checkEquals("including the player's own", 2, entities.ofCategory(Kinds.PLAYER).size());
        Checks.checkEquals("an unused category is empty, not an error", 0, entities.count(Kinds.ITEM));

        TrackedEntity e = entities.get(enemy);
        Checks.check("the game's object comes back out", e.<Mob>getHandle() == enemy);
        Checks.checkEquals("size() centres the box on the position", -0.3f, (float) e.getMinX());
        Checks.checkEquals("and stands it on it", 65.8f, (float) e.getMaxY());
        Checks.checkEquals("an attribute reads back", 20f, (float) e.get(Stats.HEALTH));
        Checks.check("positions are built once per tick", e.getPosition() == e.getPosition());

        TrackedEntity c = entities.get(crystal);
        Checks.check("an attribute the source skipped for one entity is absent for it only",
                !c.has(Stats.HEALTH) && e.has(Stats.HEALTH));

        monster.box = Box.of(2, 64, -1, 4, 67, 1);
        monster.eye = 2.5d;
        rig.tick();
        TrackedEntity m = entities.get(monster);
        Checks.check("an explicit box is kept as given", m.getMinX() == 2d && m.getMaxY() == 67d);
        Checks.checkEquals("as is the eye height", 2.5f, (float) m.getEyeHeight());

        enemy.invisible = true;
        enemy.health = null;
        rig.tick();
        Checks.check("tags and attributes are rewritten each tick, not accumulated",
                e.has(Tags.INVISIBLE) && !e.has(Stats.HEALTH));
        enemy.invisible = false;
        rig.tick();
        Checks.check("a tag not given this tick is gone", !e.has(Tags.INVISIBLE));
    }

    private static void lifecycle() {
        Rig rig = new Rig();
        Mob walker = rig.add(Kinds.PLAYER, "Walker", 0, 64, 5);
        rig.tick();
        TrackedEntity first = rig.entities.get(walker);
        Checks.checkEquals("a new entity has no velocity yet", 0f, (float) first.getVelocityZ());
        Checks.checkEquals("and has been seen for no ticks", 0, first.getTicksTracked());

        walker.z = 5.25d;
        rig.tick();
        TrackedEntity second = rig.entities.get(walker);
        Checks.check("the same object is updated in place", first == second);
        Checks.checkEquals("velocity is the move since last tick", 0.25f, (float) second.getVelocityZ());
        Checks.checkEquals("ticks tracked counts up", 1, second.getTicksTracked());
        Checks.checkEquals("extrapolation follows it", 5.75f, (float) second.extrapolate(2).getZ());

        rig.world.entities.remove(walker);
        rig.tick();
        Checks.check("an entity that leaves is untracked", !first.isTracked());
        Checks.check("and gone from every index", rig.entities.get(walker) == null
                && !rig.entities.ofCategory(Kinds.PLAYER).contains(first));
        Checks.checkEquals("keeping its last position", 5.25f, (float) first.getZ());

        rig.world.entities.add(walker);
        rig.tick();
        Checks.check("coming back is a new snapshot, never a recycled one", rig.entities.get(walker) != first);

        rig.bus.post(new WorldEvent(false, ""));
        Checks.check("leaving the world forgets everything", rig.entities.size() == 0 && rig.entities.getSelf() == null);

        rig.entities.setSource(null);
        rig.tick();
        Checks.checkEquals("with no source, refresh is a no-op", 0, rig.entities.size());
    }

    private static void failures() {
        Rig rig = new Rig();
        Mob good = rig.add(Kinds.PLAYER, "Good", 0, 64, 3);
        Mob bad = rig.add(Kinds.PLAYER, "Bad", 0, 64, 4);
        rig.world.throwOn = bad;
        rig.tick();
        rig.tick();
        Checks.check("an entity the source cannot read is skipped", rig.entities.get(bad) == null);
        Checks.check("without costing the others", rig.entities.get(good) != null);
        Checks.checkEquals("and is logged once", 1, rig.logger.errorCount());

        rig.world.throwOn = null;
        rig.world.throwList = true;
        rig.tick();
        rig.tick();
        Checks.check("a source that cannot list keeps last tick's world", rig.entities.get(good) != null);
        Checks.checkEquals("also logged once", 2, rig.logger.errorCount());
    }

    // --------------------------------------------------------- range queries

    private static void rangeQueries() {
        Rig rig = new Rig();
        Mob wide = rig.add(Kinds.MONSTER, null, 0, 64, 6);
        wide.width = 4d;                       // box edge at z = 4
        rig.add(Kinds.MONSTER, null, 0, 64, 20);
        rig.tick();

        List<TrackedEntity> found = new ArrayList<>();
        rig.entities.forEachWithin(Kinds.MONSTER, 0, 64, 0, 4.5, found::add);
        Checks.check("range is to the box, not the position", found.size() == 1 && found.get(0).<Mob>getHandle() == wide);
        found.clear();
        rig.entities.forEachWithin(Kinds.CRYSTAL, 0, 64, 0, 100, found::add);
        Checks.check("an unselected category is never visited", found.isEmpty());
        Checks.check("nearest finds the closest box",
                rig.entities.nearest(Kinds.MONSTER, 0, 64, 10, 50).<Mob>getHandle() == wide);
        found.clear();
        rig.entities.forEachWithin(0, 64, 0, 30, found::add);
        Checks.checkEquals("every category at once, the player's own box included", 3, found.size());
        Checks.checkEquals("infinite range is everything in the category", 2,
                rig.entities.countWithin(Kinds.MONSTER, 0, 64, 0, Double.POSITIVE_INFINITY));
    }

    private static void gridAgreesWithScan() {
        Rig rig = new Rig();
        Random random = new Random(1234);
        for (int i = 0; i < 600; i++) {
            Mob mob = rig.add(i % 3 == 0 ? Kinds.ITEM : Kinds.MONSTER, null,
                    random.nextDouble() * 200 - 100, 60 + random.nextDouble() * 20, random.nextDouble() * 200 - 100);
            mob.width = 0.25 + random.nextDouble() * 3;
            mob.height = 0.25 + random.nextDouble() * 3;
        }
        rig.tick();
        Checks.check("the categories are big enough to be indexed",
                rig.entities.count(Kinds.MONSTER) > EntityService.DEFAULT_SCAN_LIMIT);

        Checks.checkEquals("300 random range queries match a brute-force scan exactly", 0,
                mismatches(rig, random, 300));
        rig.entities.setCellSize(2.5d);
        Checks.checkEquals("and still do with a different cell size", 0, mismatches(rig, random, 150));
    }

    private static int mismatches(Rig rig, Random random, int queries) {
        int mismatches = 0;
        for (int q = 0; q < queries; q++) {
            double x = random.nextDouble() * 220 - 110;
            double y = 55 + random.nextDouble() * 30;
            double z = random.nextDouble() * 220 - 110;
            double radius = random.nextDouble() * 24;
            Set<EntityCategory> wanted = q % 2 == 0
                    ? new HashSet<EntityCategory>(Arrays.asList(Kinds.MONSTER))
                    : new HashSet<EntityCategory>(Arrays.asList(Kinds.MONSTER, Kinds.ITEM));

            Set<TrackedEntity> indexed = new HashSet<>();
            rig.entities.forEachWithin(wanted, x, y, z, radius, indexed::add);
            Set<TrackedEntity> scanned = new HashSet<>();
            for (TrackedEntity entity : rig.entities.getAll()) {
                if (wanted.contains(entity.getCategory()) && entity.squaredDistanceToBox(x, y, z) <= radius * radius) {
                    scanned.add(entity);
                }
            }
            if (!indexed.equals(scanned)) {
                mismatches++;
            }
        }
        return mismatches;
    }

    // ------------------------------------------------------------ selectors

    private static void defaults() {
        Rig rig = new Rig();
        Mob dead = rig.add(Kinds.PLAYER, "Dead", 0, 64, 2);
        dead.dead = true;
        Mob friend = rig.add(Kinds.PLAYER, "Buddy", 0, 64, 3);
        Mob monster = rig.add(Kinds.MONSTER, null, 0, 64, 1);
        rig.social.add("buddy");
        rig.tick();

        TargetSelector anything = TargetSelector.builder().build();
        Checks.check("by default nothing is assumed: every category, dead or friend",
                rig.targets.count(anything) == 3 && rig.targets.best(anything).<Mob>getHandle() == monster);
        Checks.check("only the local player is left out unasked", !rig.targets.accepts(anything, rig.entities.getSelf()));
        Checks.check("your own tag says what dead means", best(rig, TargetSelector.builder()
                .categories(Kinds.PLAYER).without(Tags.DEAD).build()) == friend);
        Checks.check("friends are left out only when asked", rig.targets.count(TargetSelector.builder()
                .categories(Kinds.PLAYER).excludeFriends().build()) == 1);
    }

    private static void tagsAndAttributes() {
        Rig rig = new Rig();
        Mob mate = rig.add(Kinds.PLAYER, "Mate", 0, 64, 2);
        mate.teammate = true;
        Mob ghost = rig.add(Kinds.PLAYER, "Ghost", 0, 64, 3);
        ghost.invisible = true;
        Mob both = rig.add(Kinds.PLAYER, "Both", 0, 64, 4);
        both.invisible = true;
        both.teammate = true;
        Mob plain = rig.add(Kinds.PLAYER, "Plain", 0, 64, 5);
        plain.health = 4f;
        rig.tick();

        Checks.checkEquals("without drops any listed tag", "Plain",
                rig.targets.best(TargetSelector.builder().without(Tags.INVISIBLE, Tags.TEAMMATE).build()).getName());
        Checks.checkEquals("withAll needs every one", "Both",
                rig.targets.best(TargetSelector.builder().withAll(Tags.INVISIBLE, Tags.TEAMMATE).build()).getName());
        Checks.checkEquals("withAny needs one", 3,
                rig.targets.count(TargetSelector.builder().withAny(Tags.INVISIBLE, Tags.TEAMMATE).build()));
        Checks.checkEquals("an attribute filter", "Plain",
                rig.targets.best(TargetSelector.builder().where(Stats.HEALTH, h -> h < 10).build()).getName());

        TargetSelector sameAsAbove = TargetSelector.builder().without(Tags.INVISIBLE).build()
                .toBuilder().without(Tags.TEAMMATE).build();
        Checks.checkEquals("toBuilder keeps what it had and adds to it", "Plain",
                rig.targets.best(sameAsAbove).getName());
    }

    private static void rangeAndFov() {
        Rig rig = new Rig();
        Mob ahead = rig.add(Kinds.PLAYER, "Ahead", 0, 64, 3.2);     // box edge 2.9 away
        Mob behind = rig.add(Kinds.PLAYER, "Behind", 0, 64, -2);
        rig.tick();

        double[] reach = {3d};
        TargetSelector inReach = TargetSelector.builder().range(() -> reach[0]).build();
        Checks.checkEquals("range counts to the box edge", 2, rig.targets.count(inReach));
        reach[0] = 2d;
        Checks.checkEquals("and is read on every query", 1, rig.targets.count(inReach));

        TargetSelector cone = TargetSelector.builder().fov(90).build();
        Checks.check("a 90 degree cone sees ahead only", rig.targets.count(cone) == 1
                && rig.targets.best(cone).<Mob>getHandle() == ahead);
        Checks.checkEquals("360 degrees is no limit", 2, rig.targets.count(TargetSelector.builder().fov(360).build()));

        rig.world.self.yaw = 180f;
        rig.tick();
        Checks.check("turning round swaps who is in view", rig.targets.best(cone).<Mob>getHandle() == behind);
    }

    private static void sorting() {
        Rig rig = new Rig();
        Mob near = rig.add(Kinds.PLAYER, "Near", 0, 64, 2);
        near.health = 18f;
        near.armor = 20;
        Mob weak = rig.add(Kinds.PLAYER, "Weak", 3, 64, 3);
        weak.health = 4f;
        weak.armor = 10;
        Mob unarmored = rig.add(Kinds.PLAYER, "Unarmored", -4, 64, 4);
        unarmored.armor = null;
        Mob tie = rig.add(Kinds.PLAYER, "Tie", 0, 64, 6);
        tie.health = 4f;
        tie.armor = 5;
        rig.tick();

        Checks.check("distance is the default", best(rig, TargetSelector.builder().build()) == near);
        Checks.check("your attribute, lowest first, ties to the nearer",
                best(rig, TargetSelector.builder().sort(TargetSort.by(Stats.HEALTH)).build()) == weak);
        Checks.check("an entity without the attribute ranks last",
                best(rig, TargetSelector.builder().sort(TargetSort.by(Stats.ARMOR)).build()) == tie);
        Checks.check("and stays last reversed", best(rig, TargetSelector.builder()
                .sort(TargetSort.by(Stats.ARMOR).reversed()).build()) == near);
        Checks.check("angle picks the one nearest the crosshair",
                best(rig, TargetSelector.builder().sort(TargetSort.ANGLE).build()) == tie);
        Checks.check("reversed distance picks the furthest",
                best(rig, TargetSelector.builder().sort(TargetSort.DISTANCE.reversed()).build()) == tie);
        Checks.check("a key computed from the entity", best(rig, TargetSelector.builder()
                .sort(TargetSort.by(e -> -e.getX())).build()) == weak);
    }

    private static void queries() {
        Rig rig = new Rig();
        for (int i = 1; i <= 5; i++) {
            rig.add(Kinds.PLAYER, "P" + i, 0, 64, i * 2);
        }
        rig.tick();
        TargetSelector all = TargetSelector.builder().build();

        List<TrackedEntity> three = rig.targets.all(all, 3);
        Checks.check("all(limit) returns the best few, in order", three.size() == 3
                && "P1".equals(three.get(0).getName()) && "P3".equals(three.get(2).getName()));
        Checks.checkEquals("all() returns every one", 5, rig.targets.all(all).size());
        Checks.checkEquals("and a second call agrees", 5, rig.targets.all(all).size());
        int[] visited = {0};
        rig.targets.forEach(all, e -> visited[0]++);
        Checks.checkEquals("forEach visits every one", 5, visited[0]);
        Checks.check("any", rig.targets.any(all));

        rig.world.self = null;
        rig.tick();
        Checks.check("with no local player and no origin, there is nowhere to look from",
                rig.targets.best(all) == null && rig.targets.all(all).isEmpty() && rig.targets.count(all) == 0);
    }

    private static void ageAndOrigin() {
        Rig rig = new Rig();
        Mob enemy = rig.add(Kinds.PLAYER, "Enemy", 0, 64, 5);
        Mob old = rig.add(Kinds.CRYSTAL, null, 1, 64, 4);
        old.width = 2d;
        old.height = 2d;
        rig.tick();
        rig.add(Kinds.CRYSTAL, null, -1, 64, 1.5);
        rig.tick();

        TargetSelector settled = TargetSelector.builder()
                .categories(Kinds.CRYSTAL)
                .range(6)
                .minTicksTracked(1)
                .build();
        Checks.check("one that appeared this tick is too new", rig.targets.count(settled) == 1
                && rig.targets.best(settled).<Mob>getHandle() == old);
        Checks.checkEquals("without the age limit both qualify", 2,
                rig.targets.count(settled.toBuilder().minTicksTracked(0).build()));
        Checks.check("newest first", rig.targets.best(settled.toBuilder().minTicksTracked(0)
                .sort(TargetSort.NEWEST).build()).<Mob>getHandle() != old);

        TrackedEntity target = rig.entities.get(enemy);
        TargetSelector nearEnemy = settled.toBuilder().minTicksTracked(0).range(2).build();
        Checks.check("measured from another entity instead of the player",
                rig.targets.best(nearEnemy, target.getPosition()).<Mob>getHandle() == old);
        Checks.check("accepts judges one entity", rig.targets.accepts(settled, rig.entities.get(old)));
    }

    private static void nesting() {
        Rig rig = new Rig();
        rig.add(Kinds.PLAYER, "Lonely", 0, 64, 3);
        rig.add(Kinds.PLAYER, "Crowded", 20, 64, 20);
        rig.add(Kinds.MONSTER, null, 21, 64, 20);
        rig.add(Kinds.MONSTER, null, 20, 64, 21);
        rig.tick();

        TargetSelector monsters = TargetSelector.builder().categories(Kinds.MONSTER).range(3).build();
        AtomicReference<TargetService> targets = new AtomicReference<>(rig.targets);
        TargetSelector crowded = TargetSelector.builder()
                .categories(Kinds.PLAYER)
                .where(player -> targets.get().count(monsters, player.getPosition()) >= 2)
                .build();
        TrackedEntity found = rig.targets.best(crowded);
        Checks.check("a filter may run a query of its own", found != null && "Crowded".equals(found.getName()));
        Checks.checkEquals("and the outer query is not disturbed by it", 1, rig.targets.count(crowded));
    }

    private static void locks() {
        Rig rig = new Rig();
        Mob first = rig.add(Kinds.PLAYER, "First", 0, 64, 3);
        rig.tick();

        TargetLock lock = rig.targets.lock(TargetSelector.builder().range(6).build());
        Checks.check("a lock picks the best", lock.update().<Mob>getHandle() == first && lock.hasChanged());

        Mob closer = rig.add(Kinds.PLAYER, "Closer", 0, 64, 1);
        rig.tick();
        Checks.check("and keeps it while it qualifies, though another is closer",
                lock.update().<Mob>getHandle() == first && !lock.hasChanged());

        first.z = 10;
        rig.tick();
        Checks.check("switching once it leaves range", lock.update().<Mob>getHandle() == closer && lock.hasChanged());

        rig.world.entities.remove(closer);
        rig.tick();
        Checks.check("or leaves the world", lock.update() == null && !lock.hasTarget());

        first.z = 4;
        rig.world.entities.add(closer);
        rig.tick();
        TargetLock greedy = rig.targets.lock(TargetSelector.builder().range(6).build()).setSticky(false);
        Checks.check("a non-sticky lock takes the best each time", greedy.update().<Mob>getHandle() == closer);
        Checks.check("lockOn holds a chosen target", greedy.setSticky(true).lockOn(rig.entities.get(first))
                && greedy.update().<Mob>getHandle() == first);
        Checks.check("but refuses one the selector would not", !greedy.lockOn(rig.entities.getSelf()));
        greedy.release();
        Checks.check("release drops it", !greedy.hasTarget() && greedy.hasChanged());
    }

    // ------------------------------------------------------------- geometry

    private static void geometry() {
        Box box = Box.of(2.1, 64, 2.1, 2.7, 65.8, 2.7);
        Vec3 eye = Vec3.of(0, 65, 0);
        Checks.checkEquals("distance to a box is to its nearest point", (float) Math.sqrt(2.1 * 2.1 * 2),
                (float) box.distanceTo(eye));
        Checks.checkEquals("and zero inside it", 0f, (float) box.distanceTo(Vec3.of(2.4, 65, 2.4)));
        Checks.check("the closest point is on the near corner edge", box.closestPoint(eye).getX() == 2.1
                && box.closestPoint(eye).getY() == 65d);
        Box inner = box.inset(0.05);
        Checks.check("inset shrinks every side", inner.getMinX() > box.getMinX() && inner.getMaxY() < box.getMaxY());
        Box collapsed = box.inset(5);
        Checks.check("an inset larger than the box collapses to its centre, never inside out",
                collapsed.getMinX() == collapsed.getMaxX() && Checks.eq((float) collapsed.getMinX(), 2.4f));

        Rig rig = new Rig();
        Mob diagonal = rig.add(Kinds.PLAYER, "Diagonal", 2.4, 64, 2.4);
        rig.tick();
        TrackedEntity target = rig.entities.get(diagonal);
        Vec3 aim = target.aimPoint(rig.entities.getSelf().getEyePosition(), 0.05);
        Checks.check("an aim point with an inset lands strictly inside the box",
                aim.getX() > target.getMinX() && aim.getZ() > target.getMinZ() && target.getBox().contains(aim));
    }

    // -------------------------------------------------------------- client

    private static void bootedClient(TestClient client) {
        Checks.check("the booted client has entities and targets", Core.entities() != null && Core.targets() != null);
        World world = new World();
        world.self = new Mob(Kinds.PLAYER, "Me", 0, 64, 0);
        world.entities.add(world.self);
        world.entities.add(new Mob(Kinds.PLAYER, "Them", 0, 64, 3));
        Core.entities().setSource(world);
        Core.bus().post(new TickEvent(Stage.PRE));
        TrackedEntity target = Core.targets().best(TargetSelector.builder().range(4).build());
        Checks.check("the tick refreshes the world ahead of modules", target != null && "Them".equals(target.getName()));
        Core.entities().setSource(null);
        Core.entities().clear();
    }

    // ------------------------------------------------------------- helpers

    private static Mob best(Rig rig, TargetSelector selector) {
        TrackedEntity best = rig.targets.best(selector);
        return best == null ? null : best.<Mob>getHandle();
    }

    /** A tag type that is not an enum: any object with equality works. */
    private static final class Numbered implements EntityTag {
        private final int number;

        Numbered(int number) {
            this.number = number;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Numbered && ((Numbered) other).number == number;
        }

        @Override
        public int hashCode() {
            return number;
        }
    }

    /**
     * The test's entity. Its defaults are the test adapter's choices, not Core's:
     * a real adapter reads them off the game.
     */
    private static final class Mob {
        final EntityCategory kind;
        final String name;
        double x;
        double y;
        double z;
        double width = 0.6d;
        double height = 1.8d;
        Box box;
        Double eye;
        float yaw;
        Float health = 20f;
        Integer armor = 0;
        boolean dead;
        boolean invisible;
        boolean teammate;
        final Set<EntityTag> extraTags = new LinkedHashSet<>();

        Mob(EntityCategory kind, String name, double x, double y, double z) {
            this.kind = kind;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** An entity the source says nothing about at all. */
    private static final class Bare {
    }

    /** The test adapter's EntitySource. */
    private static final class World implements EntitySource<Object> {
        final List<Mob> entities = new ArrayList<>();
        final List<Bare> bare = new ArrayList<>();
        Mob self;
        Mob throwOn;
        boolean throwList;

        @Override
        public Iterable<Object> entities() {
            if (throwList) {
                throw new IllegalStateException("world bug");
            }
            List<Object> all = new ArrayList<>(entities);
            all.addAll(bare);
            return all;
        }

        @Override
        public Object self() {
            return self;
        }

        @Override
        public void read(Object entity, EntityData out) {
            if (!(entity instanceof Mob)) {
                return;
            }
            Mob mob = (Mob) entity;
            if (mob == throwOn) {
                throw new IllegalStateException("entity bug");
            }
            out.category(mob.kind).name(mob.name)
                    .position(mob.x, mob.y, mob.z)
                    .size(mob.width, mob.height)
                    .eyeHeight(mob.eye != null ? mob.eye : mob.height * 0.85d)
                    .rotation(mob.yaw, 0f)
                    .tag(Tags.DEAD, mob.dead)
                    .tag(Tags.INVISIBLE, mob.invisible)
                    .tag(Tags.TEAMMATE, mob.teammate);
            for (EntityTag tag : mob.extraTags) {
                out.tag(tag);
            }
            if (mob.health != null) {
                out.set(Stats.HEALTH, mob.health);
            }
            if (mob.armor != null) {
                out.set(Stats.ARMOR, mob.armor);
            }
            if (mob.box != null) {
                Box b = mob.box;
                out.box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
            }
        }
    }

    /** A bare bus, the local player at the origin looking along +Z, and both services. */
    private static final class Rig {
        final RecordingLogger logger = new RecordingLogger();
        final CoreEventBus bus = new CoreEventBus(logger);
        final World world = new World();
        final EntityService entities = new EntityService(logger, bus);
        final SocialService social = new SocialService();
        final TargetService targets = new TargetService(entities, social);

        Rig() {
            entities.start();
            entities.setSource(world);
            world.self = add(Kinds.PLAYER, "Me", 0, 64, 0);
        }

        Mob add(EntityCategory kind, String name, double x, double y, double z) {
            Mob mob = new Mob(kind, name, x, y, z);
            world.entities.add(mob);
            return mob;
        }

        void tick() {
            bus.post(new TickEvent(Stage.PRE));
            bus.post(new TickEvent(Stage.POST));
        }
    }
}
