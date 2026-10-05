package dev.px.core.test.suite;

import dev.px.core.Core;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
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
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entity trackers and the targeting built on them.
 *
 * <p>The "game" here is a handful of plain Java classes &mdash; {@link Player},
 * {@link Monster}, {@link Crystal} &mdash; behind a small {@link EntitySource}, so
 * nothing needs Minecraft. Trackers are typed by those classes the way a client's
 * are typed by the game's, which proves that a tracker hands the game's own
 * object back with nothing cast and nothing copied into a vocabulary of Core's.
 *
 * <p>The spatial index is checked the only way worth trusting: hundreds of random
 * queries against a brute-force scan of the same world.
 */
public final class TargetingTests {

    private TargetingTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Entities and targeting");

        routing();
        snapshot();
        lifecycle();
        acceptance();
        betweenTicks();
        failures();
        rangeQueries();
        gridAgreesWithScan();
        selectors();
        lateTracker();
        filters();
        rangeAndFov();
        sorting();
        queries();
        ageAndOrigin();
        nesting();
        locks();
        geometry();
        bootedClient(client);
    }

    // -------------------------------------------------------------- routing

    private static void routing() {
        Rig rig = new Rig();
        EntityTracker<Mob> mobs = rig.entities.register(EntityTracker.of(Mob.class));
        EntityTracker<Hostile> hostile = rig.entities.register(EntityTracker.of(Hostile.class));
        EntityTracker<Crystal> crystals = rig.entities.register(EntityTracker.of(Crystal.class));
        Player player = rig.player("Enemy", 0, 64, 4);
        Monster monster = rig.add(new Monster("Zombie", 3, 64, 0));
        Boss boss = rig.add(new Boss("Wither", 6, 64, 0));
        Crystal crystal = rig.add(new Crystal(-2, 65, 0));
        rig.world.reads = 0;
        rig.tick();

        Checks.check("a tracker holds its type only", rig.players.size() == 1 && rig.players.get(player) != null);
        Checks.checkEquals("a supertype's tracker holds every subtype", 4, mobs.size());
        Checks.check("an interface works as the type, subclasses included",
                hostile.size() == 2 && hostile.get(monster) != null && hostile.get(boss) != null);
        Checks.check("trackers overlap freely", crystals.get(crystal) != null && mobs.get(crystal) != null);
        Checks.check("the local player is in no tracker, only getSelf()",
                rig.players.get(rig.world.self) == null && mobs.get(rig.world.self) == null
                        && rig.entities.getSelf().get() == rig.world.self);
        Checks.checkEquals("each entity is read once, however many trackers hold it", 5, rig.world.reads);
        Checks.check("overlapping trackers hold separate snapshots of one entity",
                (Object) mobs.get(player) != rig.players.get(player) && mobs.get(player).get() == rig.players.get(player).get());

        Checks.check("a subclassed tracker is found by its class", rig.entities.get(PlayerTracker.class) == rig.players);
        Checks.check("an unregistered class finds nothing", rig.entities.get(LateTracker.class) == null);
        Checks.checkThrows("require insists", IllegalStateException.class,
                () -> rig.entities.require(LateTracker.class));
        Checks.checkThrows("two trackers of one class are refused", IllegalArgumentException.class,
                () -> rig.entities.register(new PlayerTracker()));
        Checks.checkThrows("one tracker in two places is refused", IllegalArgumentException.class,
                () -> new Rig().entities.register(mobs));
        Checks.checkEquals("trackers made with of() need no class of their own", 4, rig.entities.getTrackers().size());

        Rig lean = new Rig();
        lean.add(new Monster("Ignored", 1, 64, 1));
        lean.player("Wanted", 2, 64, 2);
        lean.world.reads = 0;
        lean.tick();
        Checks.checkEquals("an entity no tracker wants is never read", 2, lean.world.reads);
    }

    // ------------------------------------------------------------ snapshot

    private static void snapshot() {
        Rig rig = new Rig();
        Player enemy = rig.player("Enemy", 0, 64, 4);
        enemy.yaw = 90f;
        rig.tick();

        Tracked<Player> e = rig.players.get(enemy);
        Player same = e.get();                         // typed: no cast
        Checks.check("the game's own object comes back, already typed", same == enemy);
        Checks.checkEquals("the box is centred on the position", -0.3f, (float) e.getMinX());
        Checks.checkEquals("and stands on it", 65.8f, (float) e.getMaxY());
        Checks.checkEquals("the eye height is the source's", 1.53f, (float) e.getEyeHeight());
        Checks.checkEquals("as is the facing", 90f, e.getYaw());
        Checks.check("positions are built once per tick", e.getPosition() == e.getPosition());

        enemy.eye = 2.5d;
        rig.tick();
        Checks.checkEquals("and reread every tick", 2.5f, (float) e.getEyeHeight());

        Rig bare = new Rig();
        bare.entities.setSource(new Bare(bare.world));
        Player nothing = bare.player("Nothing", 0, 64, 2);
        bare.tick();
        Tracked<Player> n = bare.players.get(nothing);
        Checks.check("a source that leaves the optional parts out reads as zero, not as any game's numbers",
                n.getEyeHeight() == 0d && n.getYaw() == 0f && n.getPitch() == 0f);
    }

    private static void lifecycle() {
        Rig rig = new Rig();
        Player walker = rig.player("Walker", 0, 64, 5);
        rig.tick();
        Tracked<Player> first = rig.players.get(walker);
        Checks.checkEquals("a new entity has no velocity yet", 0f, (float) first.getVelocityZ());
        Checks.checkEquals("and has been tracked for no ticks", 0, first.getTicksTracked());
        Checks.checkEquals("onTracked fires once", "[+Walker]", rig.players.events.toString());

        walker.z = 5.25d;
        rig.tick();
        Tracked<Player> second = rig.players.get(walker);
        Checks.check("the same object is updated in place", first == second);
        Checks.checkEquals("velocity is the move since last tick", 0.25f, (float) second.getVelocityZ());
        Checks.checkEquals("ticks tracked counts up", 1, second.getTicksTracked());
        Checks.checkEquals("extrapolation follows it", 5.75f, (float) second.extrapolate(2).getZ());
        Checks.checkEquals("and onTracked does not fire again", 1, rig.players.events.size());

        rig.world.entities.remove(walker);
        rig.tick();
        Checks.check("an entity that leaves is untracked", !first.isTracked());
        Checks.check("and gone from the tracker", rig.players.get(walker) == null && !rig.players.contains(first)
                && rig.players.isEmpty());
        Checks.checkEquals("keeping its last position", 5.25f, (float) first.getZ());
        Checks.checkEquals("onUntracked fires", "[+Walker, -Walker]", rig.players.events.toString());

        rig.world.entities.add(walker);
        rig.tick();
        Checks.check("coming back is a new snapshot, never a recycled one", rig.players.get(walker) != first);

        rig.bus.post(new WorldEvent(false, ""));
        Checks.check("leaving the world forgets everything", rig.players.isEmpty() && rig.entities.getSelf() == null);
        Checks.checkEquals("and says so to the tracker", "-Walker",
                rig.players.events.get(rig.players.events.size() - 1));

        rig.tick();
        Tracked<Player> back = rig.players.get(walker);
        Checks.check("the world is read again on the next tick", back != null);
        Checks.check("unregistering empties a tracker", rig.entities.unregister(rig.players)
                && rig.players.isEmpty() && !back.isTracked() && !rig.players.isRegistered());
        rig.tick();
        Checks.check("and it is no longer fed", rig.players.isEmpty());

        rig.entities.register(rig.players);
        rig.entities.setSource(null);
        rig.tick();
        Checks.check("with no source, refresh is a no-op", rig.players.isEmpty());
    }

    private static void acceptance() {
        Rig rig = new Rig();
        Player fighter = rig.player("Fighter", 0, 64, 3);
        rig.tick();
        Tracked<Player> alive = rig.players.get(fighter);

        fighter.dead = true;
        rig.tick();
        Checks.check("an entity the tracker stops accepting is let go", !alive.isTracked() && rig.players.isEmpty());
        Checks.check("though it is still in the world", rig.world.entities.contains(fighter));

        fighter.dead = false;
        rig.tick();
        Checks.check("and accepted again is a new snapshot", rig.players.get(fighter) != alive
                && rig.players.get(fighter).getTicksTracked() == 0);

        EntityTracker<Player> invisible = rig.entities.register(EntityTracker.of(Player.class, p -> p.invisible));
        fighter.invisible = true;
        rig.tick();
        Checks.check("of(type, predicate) filters without a subclass", invisible.size() == 1);
    }

    private static void betweenTicks() {
        Rig rig = new Rig();
        EntityTracker<Crystal> crystals = rig.entities.register(EntityTracker.of(Crystal.class));
        rig.tick();

        // The spawn packet: the game knows of it, the world list does not list it yet.
        Crystal spawned = new Crystal(1, 64, 2);
        Tracked<Crystal> now = crystals.track(spawned);
        Checks.check("track() holds an entity before the next tick", now != null && crystals.size() == 1
                && now.getTicksTracked() == 0);
        Checks.check("and queries find it straight away", crystals.nearest(6) == now);
        Checks.check("tracking it twice is the same snapshot", crystals.track(spawned) == now);

        rig.add(spawned);
        spawned.z = 2.5d;
        rig.tick();
        Checks.check("the next tick carries the same object on", crystals.get(spawned) == now
                && now.getTicksTracked() == 1);
        Checks.checkEquals("with a velocity measured from where it was tracked", 0.5f, (float) now.getVelocityZ());

        Checks.check("forget() lets go now", crystals.forget(spawned) && !now.isTracked() && crystals.isEmpty());
        Checks.check("and only once", !crystals.forget(spawned));
        rig.world.entities.remove(spawned);
        rig.tick();
        Checks.check("a destroyed entity stays gone", crystals.isEmpty());

        Player dead = new Player("Dead", 0, 64, 1);
        dead.dead = true;
        Checks.check("track() refuses what the tracker would not accept", rig.players.track(dead) == null);
        Checks.check("and the local player", rig.players.track(rig.world.self) == null);
        Checks.check("and does nothing for a tracker nobody feeds",
                EntityTracker.of(Crystal.class).track(new Crystal(0, 64, 0)) == null);

        // A hook that tracks something else in the middle of a refresh must not
        // disturb the reading the refresh is handing to the next tracker.
        Rig nested = new Rig();
        EntityTracker<Crystal> far = nested.entities.register(EntityTracker.of(Crystal.class));
        Crystal elsewhere = new Crystal(500, 10, 500);
        nested.entities.register(new EntityTracker<Player>(Player.class) {
            @Override
            protected void onTracked(Tracked<Player> entity) {
                far.track(elsewhere);
            }
        });
        EntityTracker<Player> after = nested.entities.register(EntityTracker.of(Player.class));
        Player watched = nested.player("Watched", 3, 64, 4);
        nested.tick();
        Checks.check("track() from a hook mid-refresh leaves the other trackers' reading alone",
                after.get(watched).getX() == 3d && far.get(elsewhere) != null);
    }

    private static void failures() {
        Rig rig = new Rig();
        Player good = rig.player("Good", 0, 64, 3);
        Player bad = rig.player("Bad", 0, 64, 4);
        rig.world.throwOn = bad;
        rig.tick();
        rig.tick();
        Checks.check("an entity the source cannot read is skipped", rig.players.get(bad) == null);
        Checks.check("without costing the others", rig.players.get(good) != null);
        Checks.checkEquals("and is logged once", 1, rig.logger.errorCount());

        rig.world.throwOn = null;
        rig.world.throwList = true;
        rig.tick();
        rig.tick();
        Checks.check("a source that cannot list keeps last tick's world", rig.players.get(good) != null);
        Checks.checkEquals("also logged once", 2, rig.logger.errorCount());

        rig.world.throwList = false;
        rig.players.throwOn = good;
        rig.tick();
        rig.tick();
        Checks.check("a tracker whose accepts throws skips that entity", rig.players.get(good) == null
                && rig.players.get(bad) != null);
        Checks.checkEquals("logged once", 3, rig.logger.errorCount());

        rig.players.throwOn = null;
        rig.players.throwInHooks = true;
        rig.tick();
        Checks.check("a hook that throws does not stop the tracking", rig.players.get(good) != null);
    }

    // --------------------------------------------------------- range queries

    private static void rangeQueries() {
        Rig rig = new Rig();
        EntityTracker<Monster> monsters = rig.entities.register(EntityTracker.of(Monster.class));
        Monster wide = rig.add(new Monster("Wide", 0, 64, 6));
        wide.width = 4d;                       // box edge at z = 4
        rig.add(new Monster("Far", 0, 64, 20));
        rig.tick();

        List<Tracked<Monster>> found = new ArrayList<>();
        monsters.forEachWithin(0, 64, 0, 4.5, found::add);
        Checks.check("range is to the box, not the position", found.size() == 1 && found.get(0).get() == wide);
        Checks.check("nearest finds the closest box", monsters.nearest(0, 64, 10, 50).get() == wide);
        Checks.check("and from the local player's eyes", monsters.nearest(30).get() == wide);
        Checks.checkEquals("infinite range is everything", 2,
                monsters.countWithin(0, 64, 0, Double.POSITIVE_INFINITY));
        Checks.check("a negative radius finds nothing", monsters.nearest(0, 64, 0, -1) == null);
    }

    private static void gridAgreesWithScan() {
        Rig rig = new Rig();
        EntityTracker<Mob> mobs = rig.entities.register(EntityTracker.of(Mob.class));
        Random random = new Random(1234);
        for (int i = 0; i < 600; i++) {
            Mob mob = rig.add(i % 3 == 0 ? new Crystal(0, 0, 0) : new Monster(null, 0, 0, 0));
            mob.x = random.nextDouble() * 200 - 100;
            mob.y = 60 + random.nextDouble() * 20;
            mob.z = random.nextDouble() * 200 - 100;
            mob.width = 0.25 + random.nextDouble() * 3;
            mob.height = 0.25 + random.nextDouble() * 3;
        }
        rig.tick();
        Checks.check("the tracker is big enough to be indexed", mobs.size() > EntityTracker.DEFAULT_SCAN_LIMIT);

        Checks.checkEquals("300 random range queries match a brute-force scan exactly", 0,
                mismatches(mobs, random, 300));
        mobs.setCellSize(2.5d);
        Checks.checkEquals("and still do with a different cell size", 0, mismatches(mobs, random, 150));
    }

    private static int mismatches(EntityTracker<Mob> mobs, Random random, int queries) {
        int mismatches = 0;
        for (int q = 0; q < queries; q++) {
            double x = random.nextDouble() * 220 - 110;
            double y = 55 + random.nextDouble() * 30;
            double z = random.nextDouble() * 220 - 110;
            double radius = random.nextDouble() * 24;

            Set<Tracked<Mob>> indexed = new HashSet<>();
            mobs.forEachWithin(x, y, z, radius, indexed::add);
            Set<Tracked<Mob>> scanned = new HashSet<>();
            for (Tracked<Mob> entity : mobs.getAll()) {
                if (entity.squaredDistanceToBox(x, y, z) <= radius * radius) {
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

    private static void selectors() {
        Rig rig = new Rig();
        EntityTracker<Mob> everything = rig.entities.register(EntityTracker.of(Mob.class));
        Player dead = rig.player("Dead", 0, 64, 2);
        dead.dead = true;
        Player buddy = rig.player("Buddy", 0, 64, 3);
        Monster monster = rig.add(new Monster("Zombie", 0, 64, 1));
        SocialService social = new SocialService();
        social.add("buddy");
        rig.tick();

        TargetSelector<Mob> anything = TargetSelector.from(everything).build();
        Checks.check("by default nothing is assumed: every entity in the tracker, nearest first",
                rig.targets.count(anything) == 3 && rig.targets.best(anything).get() == monster);
        Checks.check("the local player is never a candidate",
                !rig.targets.accepts(anything, rig.entities.<Mob>getSelf()));

        TargetSelector<Player> players = TargetSelector.from(PlayerTracker.class).build();
        Checks.check("the tracker's own accepts says what dead means", rig.targets.best(players).get() == buddy);
        Checks.check("friends are a filter on the game's object, like anything else", rig.targets.best(
                players.toBuilder().where(p -> !social.isFriend(p.name)).build()) == null);
    }

    private static void lateTracker() {
        Rig rig = new Rig();
        // Built in a module's field initialiser, before any tracker is registered.
        TargetSelector<Player> early = TargetSelector.from(LateTracker.class).range(10).build();
        rig.player("Someone", 0, 64, 3);
        rig.tick();
        Checks.check("a selector naming an unregistered tracker finds nothing, and does not throw",
                rig.targets.best(early) == null && rig.targets.count(early) == 0 && rig.targets.all(early).isEmpty());

        rig.entities.register(new LateTracker());
        rig.tick();
        Checks.check("and works as soon as one is registered", rig.targets.best(early) != null);
    }

    private static void filters() {
        Rig rig = new Rig();
        Player ghost = rig.player("Ghost", 0, 64, 2);
        ghost.invisible = true;
        Player runner = rig.player("Runner", 0, 64, 3);
        Player plain = rig.player("Plain", 0, 64, 5);
        plain.health = 4f;
        rig.tick();
        runner.x = 0.4d;
        rig.tick();

        TargetSelector<Player> all = TargetSelector.from(rig.players).build();
        Checks.checkEquals("where reads the game's object", "Plain",
                rig.targets.best(all.toBuilder().where(p -> p.health < 10).build()).get().name);
        Checks.checkEquals("whereTracked reads what Core measured", "Runner",
                rig.targets.best(all.toBuilder().whereTracked(t -> t.getHorizontalSpeed() > 0.1).build()).get().name);
        Checks.checkEquals("several filters are all required", "Plain", rig.targets.best(all.toBuilder()
                .where(p -> !p.invisible).where(p -> p != runner).build()).get().name);
    }

    private static void rangeAndFov() {
        Rig rig = new Rig();
        Player ahead = rig.player("Ahead", 0, 64, 3.2);     // box edge 2.9 away
        Player behind = rig.player("Behind", 0, 64, -2);
        rig.tick();

        double[] reach = {3d};
        TargetSelector<Player> inReach = TargetSelector.from(rig.players).range(() -> reach[0]).build();
        Checks.checkEquals("range counts to the box edge", 2, rig.targets.count(inReach));
        reach[0] = 2d;
        Checks.checkEquals("and is read on every query", 1, rig.targets.count(inReach));

        TargetSelector<Player> cone = TargetSelector.from(rig.players).fov(90).build();
        Checks.check("a 90 degree cone sees ahead only", rig.targets.count(cone) == 1
                && rig.targets.best(cone).get() == ahead);
        Checks.checkEquals("360 degrees is no limit", 2,
                rig.targets.count(TargetSelector.from(rig.players).fov(360).build()));

        rig.world.self.yaw = 180f;
        rig.tick();
        Checks.check("turning round swaps who is in view", rig.targets.best(cone).get() == behind);
    }

    private static void sorting() {
        Rig rig = new Rig();
        Player near = rig.player("Near", 0, 64, 2);
        near.health = 18f;
        near.armor = 20f;
        Player weak = rig.player("Weak", 3, 64, 3);
        weak.health = 4f;
        weak.armor = 10f;
        Player unarmored = rig.player("Unarmored", -4, 64, 4);
        unarmored.armor = null;
        Player tie = rig.player("Tie", 0, 64, 6);
        tie.health = 4f;
        tie.armor = 5f;
        rig.tick();

        TargetSelector.Builder<Player> base = TargetSelector.from(rig.players);
        Checks.check("distance is the default", best(rig, base.build()) == near);
        Checks.check("a getter on the game's object, lowest first, ties to the nearer",
                best(rig, base.sort(TargetSort.by(Player::getHealth)).build()) == weak);
        Checks.check("an entity the key has no number for ranks last",
                best(rig, base.sort(TargetSort.by(Player::armor)).build()) == tie);
        Checks.check("and stays last reversed",
                best(rig, base.sort(TargetSort.by(Player::armor).reversed()).build()) == near);
        Checks.check("angle picks the one nearest the crosshair", best(rig, base.sort(TargetSort.ANGLE).build()) == tie);
        Checks.check("reversed distance picks the furthest",
                best(rig, base.sort(TargetSort.DISTANCE.reversed()).build()) == tie);
        Checks.check("a sort over what Core measured", best(rig, base.sort(
                (entity, context) -> -entity.getX()).build()) == weak);
    }

    private static void queries() {
        Rig rig = new Rig();
        for (int i = 1; i <= 5; i++) {
            rig.player("P" + i, 0, 64, i * 2);
        }
        rig.tick();
        TargetSelector<Player> all = TargetSelector.from(rig.players).build();

        List<Tracked<Player>> three = rig.targets.all(all, 3);
        Checks.check("all(limit) returns the best few, in order", three.size() == 3
                && "P1".equals(three.get(0).get().name) && "P3".equals(three.get(2).get().name));
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
        EntityTracker<Crystal> crystals = rig.entities.register(EntityTracker.of(Crystal.class));
        Player enemy = rig.player("Enemy", 0, 64, 5);
        Crystal old = rig.add(new Crystal(1, 64, 4));
        rig.tick();
        rig.add(new Crystal(-1, 64, 1.5));
        rig.tick();

        TargetSelector<Crystal> settled = TargetSelector.from(crystals).range(6).minTicksTracked(1).build();
        Checks.check("one that appeared this tick is too new", rig.targets.count(settled) == 1
                && rig.targets.best(settled).get() == old);
        Checks.checkEquals("without the age limit both qualify", 2,
                rig.targets.count(settled.toBuilder().minTicksTracked(0).build()));
        Checks.check("newest first", rig.targets.best(settled.toBuilder().minTicksTracked(0)
                .sort(TargetSort.NEWEST).build()).get() != old);

        Tracked<Player> target = rig.players.get(enemy);
        TargetSelector<Crystal> nearEnemy = settled.toBuilder().minTicksTracked(0).range(2).build();
        Checks.check("measured from another entity instead of the player",
                rig.targets.best(nearEnemy, target.getPosition()).get() == old);
        Checks.check("accepts judges one entity", rig.targets.accepts(settled, crystals.get(old)));
    }

    private static void nesting() {
        Rig rig = new Rig();
        EntityTracker<Monster> monsterTracker = rig.entities.register(EntityTracker.of(Monster.class));
        rig.player("Lonely", 0, 64, 3);
        rig.player("Crowded", 20, 64, 20);
        rig.add(new Monster(null, 21, 64, 20));
        rig.add(new Monster(null, 20, 64, 21));
        rig.tick();

        TargetSelector<Monster> monsters = TargetSelector.from(monsterTracker).range(3).build();
        AtomicReference<TargetService> targets = new AtomicReference<>(rig.targets);
        TargetSelector<Player> crowded = TargetSelector.from(rig.players)
                .whereTracked(player -> targets.get().count(monsters, player.getPosition()) >= 2)
                .build();
        Tracked<Player> found = rig.targets.best(crowded);
        Checks.check("a filter may run a query of its own", found != null && "Crowded".equals(found.get().name));
        Checks.checkEquals("and the outer query is not disturbed by it", 1, rig.targets.count(crowded));
    }

    private static void locks() {
        Rig rig = new Rig();
        Player first = rig.player("First", 0, 64, 3);
        rig.tick();

        TargetLock<Player> lock = rig.targets.lock(TargetSelector.from(rig.players).range(6).build());
        Checks.check("a lock picks the best", lock.update().get() == first && lock.hasChanged());

        Player closer = rig.player("Closer", 0, 64, 1);
        rig.tick();
        Checks.check("and keeps it while it qualifies, though another is closer",
                lock.update().get() == first && !lock.hasChanged());

        first.z = 10;
        rig.tick();
        Checks.check("switching once it leaves range", lock.update().get() == closer && lock.hasChanged());

        rig.world.entities.remove(closer);
        rig.tick();
        Checks.check("or leaves the world", lock.update() == null && !lock.hasTarget());

        first.z = 4;
        rig.world.entities.add(closer);
        EntityTracker<Player> elsewhere = rig.entities.register(EntityTracker.of(Player.class));
        rig.tick();
        TargetLock<Player> greedy = rig.targets.lock(TargetSelector.from(rig.players).range(6).build()).setSticky(false);
        Checks.check("a non-sticky lock takes the best each time", greedy.update().get() == closer);
        Checks.check("lockOn holds a chosen target", greedy.setSticky(true).lockOn(rig.players.get(first))
                && greedy.update().get() == first);
        Checks.check("but refuses one from a tracker the selector does not read",
                !greedy.lockOn(elsewhere.get(closer)));
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
        Player diagonal = rig.player("Diagonal", 2.4, 64, 2.4);
        rig.tick();
        Tracked<Player> target = rig.players.get(diagonal);
        Vec3 aim = target.aimPoint(rig.entities.getSelf().getEyePosition(), 0.05);
        Checks.check("an aim point with an inset lands strictly inside the box",
                aim.getX() > target.getMinX() && aim.getZ() > target.getMinZ() && target.getBox().contains(aim));
    }

    // -------------------------------------------------------------- client

    private static void bootedClient(TestClient client) {
        Checks.check("the booted client has entities and targets", Core.entities() != null && Core.targets() != null);
        World world = new World();
        world.self = new Player("Me", 0, 64, 0);
        world.entities.add(world.self);
        world.entities.add(new Player("Them", 0, 64, 3));
        LateTracker players = Core.entities().register(new LateTracker());
        Core.entities().setSource(world);
        Core.bus().post(new TickEvent(Stage.PRE));
        Tracked<Player> target = Core.targets().best(TargetSelector.from(LateTracker.class).range(4).build());
        Checks.check("the tick refreshes every tracker ahead of modules",
                target != null && "Them".equals(target.get().name));
        Core.entities().unregister(players);
        Core.entities().setSource(null);
        Core.entities().clear();
    }

    // ------------------------------------------------------------- helpers

    private static Player best(Rig rig, TargetSelector<Player> selector) {
        Tracked<Player> best = rig.targets.best(selector);
        return best == null ? null : best.get();
    }

    // ------------------------------------------------- the test's "game"

    /**
     * The test's entities. Their defaults are the test game's, not Core's: a real
     * adapter reads them off the real game.
     */
    private abstract static class Mob {
        final String name;
        double x;
        double y;
        double z;
        double width = 0.6d;
        double height = 1.8d;
        Double eye;
        float yaw;

        Mob(String name, double x, double y, double z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + name + ")";
        }
    }

    private static final class Player extends Mob {
        float health = 20f;
        Float armor = 0f;
        boolean dead;
        boolean invisible;

        Player(String name, double x, double y, double z) {
            super(name, x, y, z);
        }

        float getHealth() {
            return health;
        }

        double armor() {
            return armor == null ? Double.NaN : armor;
        }
    }

    private interface Hostile {
    }

    private static class Monster extends Mob implements Hostile {
        Monster(String name, double x, double y, double z) {
            super(name, x, y, z);
        }
    }

    private static final class Boss extends Monster {
        Boss(String name, double x, double y, double z) {
            super(name, x, y, z);
        }
    }

    private static final class Crystal extends Mob {
        Crystal(double x, double y, double z) {
            super("crystal", x, y, z);
            width = 2d;
            height = 2d;
        }
    }

    /** The test adapter's EntitySource: every method written, the way a real one is. */
    private static final class World implements EntitySource<Mob> {
        final List<Mob> entities = new ArrayList<>();
        Player self;
        Mob throwOn;
        boolean throwList;
        int reads;

        @Override
        public Iterable<Mob> entities() {
            if (throwList) {
                throw new IllegalStateException("world bug");
            }
            return entities;
        }

        @Override
        public Mob self() {
            return self;
        }

        @Override
        public double x(Mob entity) {
            if (entity == throwOn) {
                throw new IllegalStateException("entity bug");
            }
            reads++;
            return entity.x;
        }

        @Override
        public double y(Mob entity) {
            return entity.y;
        }

        @Override
        public double z(Mob entity) {
            return entity.z;
        }

        @Override
        public double width(Mob entity) {
            return entity.width;
        }

        @Override
        public double height(Mob entity) {
            return entity.height;
        }

        @Override
        public double eyeHeight(Mob entity) {
            return entity.eye != null ? entity.eye : entity.height * 0.85d;
        }

        @Override
        public float yaw(Mob entity) {
            return entity.yaw;
        }
    }

    /** A source that writes only what it must. */
    private static final class Bare implements EntitySource<Mob> {
        private final World world;

        Bare(World world) {
            this.world = world;
        }

        @Override
        public Iterable<Mob> entities() {
            return world.entities;
        }

        @Override
        public Mob self() {
            return world.self;
        }

        @Override
        public double x(Mob entity) {
            return entity.x;
        }

        @Override
        public double y(Mob entity) {
            return entity.y;
        }

        @Override
        public double z(Mob entity) {
            return entity.z;
        }

        @Override
        public double width(Mob entity) {
            return entity.width;
        }

        @Override
        public double height(Mob entity) {
            return entity.height;
        }
    }

    /** What a client writes: a tracker of one type, with its own idea of who counts. */
    private static final class PlayerTracker extends EntityTracker<Player> {
        final List<String> events = new ArrayList<>();
        Player throwOn;
        boolean throwInHooks;

        PlayerTracker() {
            super(Player.class);
        }

        @Override
        protected boolean accepts(Player player) {
            if (player == throwOn) {
                throw new IllegalStateException("accepts bug");
            }
            return !player.dead;
        }

        @Override
        protected void onTracked(Tracked<Player> entity) {
            events.add("+" + entity.get().name);
            if (throwInHooks) {
                throw new IllegalStateException("hook bug");
            }
        }

        @Override
        protected void onUntracked(Tracked<Player> entity) {
            events.add("-" + entity.get().name);
        }
    }

    /** A tracker registered after the selectors that name it were built. */
    private static final class LateTracker extends EntityTracker<Player> {
        LateTracker() {
            super(Player.class);
        }
    }

    /** A bare bus, the local player at the origin looking along +Z, a player tracker, and targeting. */
    private static final class Rig {
        final RecordingLogger logger = new RecordingLogger();
        final CoreEventBus bus = new CoreEventBus(logger);
        final World world = new World();
        final EntityService entities = new EntityService(logger, bus);
        final PlayerTracker players = entities.register(new PlayerTracker());
        final TargetService targets = new TargetService(entities);

        Rig() {
            entities.start();
            entities.setSource(world);
            world.self = player("Me", 0, 64, 0);
        }

        <M extends Mob> M add(M mob) {
            world.entities.add(mob);
            return mob;
        }

        Player player(String name, double x, double y, double z) {
            return add(new Player(name, x, y, z));
        }

        void tick() {
            bus.post(new TickEvent(Stage.PRE));
            bus.post(new TickEvent(Stage.POST));
        }
    }
}
