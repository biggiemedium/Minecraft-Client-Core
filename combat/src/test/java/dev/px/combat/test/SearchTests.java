package dev.px.combat.test;

import dev.px.combat.crystal.CrystalBody;
import dev.px.combat.crystal.CrystalRules;
import dev.px.combat.crystal.Placement;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.explosion.rule.Falloff;
import dev.px.combat.explosion.rule.Mitigation;
import dev.px.combat.explosion.rule.SampleGrid;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.search.CrystalSearch;
import dev.px.combat.search.engine.SearchStats;
import dev.px.combat.search.option.BreakOption;
import dev.px.combat.search.option.PlaceOption;
import dev.px.combat.search.option.Trigger;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.rule.ReachPoint;
import dev.px.combat.search.rule.Score;
import dev.px.combat.search.rule.Thresholds;
import dev.px.combat.world.BlockShape;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Obstructions;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.Stage;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * The crystal search: placing and breaking, every threshold, reach, timing, and
 * branch and bound checked against a brute-force search of the same world.
 *
 * <p>The world is a floor of crystal bases with you, an enemy, and crystals on
 * it, behind Core's own entity and targeting services. The version profile is the
 * test's own, as before.
 */
public final class SearchTests {

    private static final Falloff WIKI = Falloff.of(
            power -> 2 * power,
            (distance, exposure, power) -> {
                double impact = (1 - distance / (2 * power)) * exposure;
                return 7 * power * (impact * impact + impact) + 1;
            });

    private SearchTests() {
    }

    public static void run() {
        Checks.section("Crystal search");

        placing();
        bruteForce();
        reach();
        thresholds();
        lethal();
        facePlaceAndArmour();
        breaking();
        timing();
        spawning();
        scoring();
        lifecycle();
    }

    // -------------------------------------------------------------- placing

    private static void placing() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> search = arena.search(b -> b);
        PlaceOption<Fighter> spot = search.findPlace();
        Checks.check("a place is found for the enemy", spot != null && spot.getTarget().get() == arena.enemy);
        Checks.check("on a base a crystal can go on, in reach",
                arena.rules.canPlace(spot.getX(), spot.getY(), spot.getZ())
                        && arena.eye().distanceTo(dev.px.core.math.Vec3.of(spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5)) <= 5);
        Checks.check("with its damage, self-damage and why", spot.getDamage() > 0 && spot.getSelfDamage() >= 0
                && spot.getTrigger() == Trigger.MINIMUM && spot.getScore() == spot.getDamage());
        Checks.check("nothing under anyone's feet",
                !(spot.getX() == 0 && spot.getZ() == 0) && !(spot.getX() == 3 && spot.getZ() == 0));
        SearchStats stats = search.getLastPlaceStats();
        Checks.check("the scan visits the cube, cheapest tests first (" + stats + ")",
                stats.getCells() == 11 * 11 * 11 && stats.getInReach() < stats.getCells()
                        && stats.getPlaceable() < stats.getInReach());
        Checks.check("and estimates far fewer than it bounds", stats.getEvaluated() < stats.getBounds() / 4);
    }

    private static void bruteForce() {
        Random random = new Random(42);
        int agree = 0;
        int pruned = 0;
        int savedEstimates = 0;
        int layouts = 30;
        for (int layout = 0; layout < layouts; layout++) {
            Arena arena = new Arena();
            arena.enemy.x = random.nextInt(9) - 4 + 0.5;
            arena.enemy.z = random.nextInt(9) - 4 + 0.5;
            if (arena.enemy.x == 0.5 && arena.enemy.z == 0.5) {
                arena.enemy.x = 3.5;
            }
            for (int wall = 0; wall < 6; wall++) {
                int x = random.nextInt(11) - 5;
                int z = random.nextInt(11) - 5;
                for (int y = 1; y < 3; y++) {
                    arena.blocks.set(x, y, z, random.nextBoolean() ? BlockShape.FULL
                            : BlockShape.of(dev.px.core.math.Box.of(0, 0, 0, 1, 0.5, 1)));
                }
            }
            arena.refresh();
            Thresholds<Fighter> limits = Thresholds.<Fighter>builder().minDamage(() -> 4).maxSelfDamage(() -> 30).build();
            CrystalSearch<Fighter> fast = arena.search(b -> b.thresholds(limits));
            CrystalSearch<Fighter> slow = arena.search(b -> b.thresholds(limits).pruning(false));
            PlaceOption<Fighter> a = fast.findPlace();
            PlaceOption<Fighter> b = slow.findPlace();
            boolean same = a == null ? b == null : b != null && a.getScore() == b.getScore()
                    && a.getSelfDamage() == b.getSelfDamage();
            if (same) {
                agree++;
            }
            pruned += fast.getLastPlaceStats().getPruned();
            savedEstimates += slow.getLastPlaceStats().getEvaluated() - fast.getLastPlaceStats().getEvaluated();
        }
        Checks.checkEquals("branch and bound picks what a brute-force search picks, in every one of 30 layouts",
                layouts, agree);
        Checks.check("while skipping most candidates (" + pruned + " pruned, " + savedEstimates + " estimates saved)",
                pruned > layouts * 10 && savedEstimates > 0);
    }

    private static void reach() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> near = arena.search(b -> b.placeReach(Reach.of(2, 2)));
        near.findPlace();
        CrystalSearch<Fighter> far = arena.search(b -> b.placeReach(Reach.of(5, 5)));
        far.findPlace();
        Checks.check("a shorter range sees fewer bases",
                near.getLastPlaceStats().getInReach() < far.getLastPlaceStats().getInReach());
        CrystalSearch<Fighter> nearest = arena.search(b -> b.placeReach(Reach.of(2, 2).measuredTo(ReachPoint.NEAREST)));
        nearest.findPlace();
        Checks.check("measured to the nearest point, more are in reach than to the centre",
                nearest.getLastPlaceStats().getInReach() > near.getLastPlaceStats().getInReach());

        for (int z = -6; z <= 6; z++) {
            for (int y = 1; y < 6; y++) {
                arena.blocks.set(-2, y, z, BlockShape.FULL);      // a wall at your side
            }
        }
        CrystalSearch<Fighter> walled = arena.search(b -> b.placeReach(Reach.of(5, 1)));
        walled.findPlace();
        CrystalSearch<Fighter> open = arena.search(b -> b.placeReach(Reach.of(5, 5)));
        open.findPlace();
        Checks.check("past the wall range, only what you can see counts",
                walled.getLastPlaceStats().getVisible() < open.getLastPlaceStats().getVisible());
    }

    // ------------------------------------------------------------ thresholds

    private static void thresholds() {
        Arena arena = new Arena();
        PlaceOption<Fighter> free = arena.search(b -> b).findPlace();
        double most = free.getDamage();

        Checks.check("a minimum no option reaches finds nothing", arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .minDamage(() -> most + 1).build())).findPlace() == null);
        Checks.check("a minimum the best reaches still finds it", arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .minDamage(() -> most).build())).findPlace() != null);

        PlaceOption<Fighter> capped = arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .maxSelfDamage(() -> 45).build())).findPlace();
        Checks.check("the self-damage cap is kept, at the cost of damage (" + capped + " vs " + free + ")",
                capped != null && capped.getSelfDamage() <= 45 && free.getSelfDamage() > 45
                        && capped.getDamage() < free.getDamage());
        Checks.check("and a cap nothing in reach meets finds nothing", arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .maxSelfDamage(() -> 10).build())).findPlace() == null);

        arena.self.health = 6;
        PlaceOption<Fighter> safe = arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .antiSuicide(() -> 1).build())).findPlace();
        Checks.check("anti-suicide never leaves you within its margin (" + safe + ")",
                safe == null || safe.getSelfDamage() < 5);
        arena.self.health = 0.5;
        Checks.check("and with nothing to spare, nothing is safe", arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .antiSuicide(() -> 1).build())).findPlace() == null);
    }

    private static void lethal() {
        Arena arena = new Arena();
        arena.enemy.health = 3;
        Thresholds<Fighter> lethal = Thresholds.<Fighter>builder()
                .minDamage(() -> 1000).lethal(() -> 1, false).build();
        PlaceOption<Fighter> kill = arena.search(b -> b.thresholds(lethal)).findPlace();
        Checks.check("a kill needs no minimum damage", kill != null && kill.getTrigger() == Trigger.LETHAL && kill.getDamage() >= 3);

        Thresholds<Fighter> multiplied = Thresholds.<Fighter>builder()
                .minDamage(() -> 1000).lethal(() -> 1000, false).build();
        arena.enemy.health = 100;
        Checks.check("the multiplier is yours: a large one calls more things lethal",
                arena.search(b -> b.thresholds(multiplied)).findPlace() != null);

        arena.enemy.health = 3;
        Thresholds<Fighter> strict = Thresholds.<Fighter>builder().minDamage(() -> 1000)
                .maxSelfDamage(() -> 0.5).lethal(() -> 1, false).build();
        Thresholds<Fighter> override = Thresholds.<Fighter>builder().minDamage(() -> 1000)
                .maxSelfDamage(() -> 0.5).lethal(() -> 1, true).build();
        Checks.check("a kill respects the self-damage cap unless told not to",
                arena.search(b -> b.thresholds(strict)).findPlace() == null
                        && arena.search(b -> b.thresholds(override)).findPlace() != null);

        arena.enemy.trusted = false;
        Checks.check("an enemy whose health is hidden is never called lethal",
                arena.search(b -> b.thresholds(lethal)).findPlace() == null);
    }

    private static void facePlaceAndArmour() {
        Arena arena = new Arena();
        arena.enemy.health = 8;
        Thresholds<Fighter> face = Thresholds.<Fighter>builder()
                .minDamage(() -> 1000).facePlace(() -> 10, () -> 2).build();
        PlaceOption<Fighter> low = arena.search(b -> b.thresholds(face)).findPlace();
        Checks.check("faceplacing a low target lowers the minimum", low != null && low.getTrigger() == Trigger.FACEPLACE);
        arena.enemy.health = 30;
        Checks.check("not for a healthy one", arena.search(b -> b.thresholds(face)).findPlace() == null);
        boolean[] key = { true };
        Thresholds<Fighter> forced = Thresholds.<Fighter>builder()
                .minDamage(() -> 1000).facePlace(() -> 10, () -> 2).facePlaceWhen(() -> key[0]).build();
        Checks.check("unless faceplacing is forced", arena.search(b -> b.thresholds(forced)).findPlace() != null);
        key[0] = false;
        Checks.check("read live, like every setting", arena.search(b -> b.thresholds(forced)).findPlace() == null);

        arena.enemy.wear = 0.1;
        Thresholds<Fighter> armour = Thresholds.<Fighter>builder()
                .minDamage(() -> 1000).armourBreak(f -> f.wear, () -> 0.2, () -> 2).build();
        PlaceOption<Fighter> breaking = arena.search(b -> b.thresholds(armour)).findPlace();
        Checks.check("worn armour lowers the minimum too", breaking != null && breaking.getTrigger() == Trigger.ARMOUR_BREAK);
        arena.enemy.wear = 0.9;
        Checks.check("not for armour in good shape", arena.search(b -> b.thresholds(armour)).findPlace() == null);
    }

    // -------------------------------------------------------------- breaking

    private static void breaking() {
        Arena arena = new Arena();
        Crystal close = arena.crystal(2, 0, 0);
        Crystal far = arena.crystal(-3, 0, 2);
        arena.refresh();
        CrystalSearch<Fighter> search = arena.search(b -> b);
        BreakOption<Fighter> hit = search.findBreak();
        Checks.check("the crystal that hurts the enemy most is the one to break",
                hit != null && hit.getCrystal().get() == close && hit.getTarget().get() == arena.enemy);
        Checks.check("among those in break range (" + search.getLastBreakStats() + ")",
                search.getLastBreakStats().getExisting() == 2 && far != null);

        CrystalSearch<Fighter> shortReach = arena.search(b -> b.breakReach(Reach.of(1, 1)));
        Checks.check("one out of break range is never chosen", shortReach.findBreak() == null);

        CrystalSearch<Fighter> aged = arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .breakMinAge(() -> 2).build()));
        Checks.check("a crystal younger than the minimum age is left alone",
                aged.findBreak() == null && aged.getLastBreakStats().getTooYoung() == 2);
        arena.refresh();
        arena.refresh();
        Checks.check("until it is old enough", aged.findBreak() != null);
    }

    private static void timing() {
        Arena arena = new Arena();
        Crystal strong = arena.crystal(2, 0, 0);
        Crystal weak = arena.crystal(4, 0, 1);
        arena.refresh();
        CrystalSearch<Fighter> search = arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .inhibit(() -> 3).build()));
        search.tick();

        BreakOption<Fighter> first = search.findBreak();
        Checks.check("(the strong crystal first)", first != null && first.getCrystal().get() == strong);
        search.attacked(first.getCrystal());
        BreakOption<Fighter> again = search.findBreak();
        Checks.check("once one crystal is attacked this tick, a weaker one does nothing more: only the highest lands",
                again == null);
        Checks.check("and the attacked one is inhibited", search.getLastBreakStats().getInhibited() == 1);

        search.tick();
        BreakOption<Fighter> next = search.findBreak();
        Checks.check("next tick the weaker one counts again, while the attacked one is still inhibited",
                next != null && next.getCrystal().get() == weak);
        search.tick();
        search.tick();
        Checks.check("and after the inhibit window, the attacked one is back",
                search.findBreak().getCrystal().get() == strong);
        Checks.check("what was dealt is recorded against the target", search.getLog().dealtTo(arena.enemy) == 0d);
        search.attacked(search.findBreak().getCrystal());
        Checks.check("for the rest of the tick", search.getLog().dealtTo(arena.enemy) > 0d);
    }

    private static void spawning() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> search = arena.search(b -> b.thresholds(Thresholds.<Fighter>builder()
                .breakMinAge(() -> 5).build()));
        Crystal spawned = new Crystal(2.5, 1, 0.5);
        BreakOption<Fighter> now = search.spawned(spawned);
        Checks.check("a crystal from its spawn packet can be broken at once, before the world lists it",
                now != null && now.getCrystal().get() == spawned);
        Checks.check("ignoring the minimum age, which is the point", now.getCrystal().getTicksTracked() == 0);
        Checks.check("something that is not a crystal is not", search.spawned(new Fighter(1, 1, 1)) == null);
    }

    private static void scoring() {
        Arena arena = new Arena();
        PlaceOption<Fighter> greedy = arena.search(b -> b).findPlace();
        PlaceOption<Fighter> careful = arena.search(b -> b.score(Score.balanced(2))).findPlace();
        Checks.check("a balanced score trades damage for safety",
                careful.getSelfDamage() <= greedy.getSelfDamage() && careful.getDamage() <= greedy.getDamage());
    }

    private static void lifecycle() {
        String missing;
        try {
            CrystalSearch.<Fighter>builder().score(Score.DAMAGE).build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("a search missing parts names them", missing != null && missing.contains("rules")
                && missing.contains("targets") && missing.contains("placeReach") && missing.contains("thresholds"));

        Arena empty = new Arena();
        empty.world.fighters.remove(empty.enemy);
        empty.refresh();
        Checks.check("with no target, nothing is worth doing",
                empty.search(b -> b).findPlace() == null && empty.search(b -> b).findBreak() == null);

        Arena arena = new Arena();
        CoreEventBus bus = new CoreEventBus(new RecordingLogger());
        Crystal crystal = arena.crystal(2, 0, 0);
        arena.refresh();
        CrystalSearch<Fighter> search = arena.search(b -> b.bus(bus).thresholds(Thresholds.<Fighter>builder()
                .inhibit(() -> 1).build()));
        search.attacked(arena.crystals.get(crystal));
        Checks.check("(inhibited)", search.findBreak() == null);
        bus.post(new TickEvent(Stage.PRE));
        Checks.check("the bus's ticks drive it", search.findBreak() != null);
        search.close();
    }

    // ------------------------------------------------- the test's "game"

    /** A floor of crystal bases, you at the origin and an enemy three blocks east. */
    private static final class Arena {
        final World world = new World();
        final Blocks blocks = new Blocks();
        final Fighter self = world.add(new Fighter(0.5, 1, 0.5));
        final Fighter enemy = world.add(new Fighter(3.5, 1, 0.5));
        final EntityTracker<Fighter> players;
        final EntityTracker<Crystal> crystals;
        final TargetService targets;
        final CrystalRules<Fighter> rules;

        Arena() {
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    blocks.base(x, 0, z);
                }
            }
            world.self = self;
            players = world.service.register(EntityTracker.of(Fighter.class));
            crystals = world.service.register(EntityTracker.of(Crystal.class));
            targets = new TargetService(world.service);
            refresh();
            ExplosionModel<Fighter> model = ExplosionModel.<Fighter>builder()
                    .measureFrom(Tracked::getPosition)
                    .exposure(Exposure.sampled(SampleGrid.uniform(3, 5, 3)))
                    .falloff(WIKI)
                    .mitigation(Mitigation.none())
                    .build();
            rules = CrystalRules.<Fighter>builder()
                    .placement(Placement.clearance(blocks::isBase, 1, blocks::isClear, 2))
                    .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0))
                    .explosive(Explosive.of("end crystal", 6))
                    .model(model)
                    .blocks(blocks)
                    .obstructions(Obstructions.of(world.service, players, crystals))
                    .build();
        }

        Crystal crystal(int baseX, int baseY, int baseZ) {
            Crystal crystal = new Crystal(baseX + 0.5, baseY + 1, baseZ + 0.5);
            world.crystals.add(crystal);
            return crystal;
        }

        void refresh() {
            world.service.refresh();
        }

        dev.px.core.math.Vec3 eye() {
            return world.service.getSelf().getEyePosition();
        }

        CrystalSearch<Fighter> search(Function<CrystalSearch.Builder<Fighter>, CrystalSearch.Builder<Fighter>> tweak) {
            CrystalSearch.Builder<Fighter> builder = CrystalSearch.<Fighter>builder()
                    .rules(rules)
                    .entities(world.service)
                    .targets(targets, TargetSelector.from(players).range(20).build())
                    .crystals(crystals)
                    .vitals(new Vitals<Fighter>() {
                        @Override
                        public double pool(Fighter fighter) {
                            return fighter.health;
                        }

                        @Override
                        public boolean isTrusted(Fighter fighter) {
                            return fighter.trusted;
                        }
                    })
                    .placeReach(Reach.of(5, 5))
                    .breakReach(Reach.of(5, 5))
                    .thresholds(Thresholds.<Fighter>none());
            return tweak.apply(builder).build();
        }
    }

    private static final class Fighter {
        double x;
        double y;
        double z;
        double health = 36;
        double wear = Double.NaN;
        boolean trusted = true;

        Fighter(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Crystal {
        final double x;
        final double y;
        final double z;

        Crystal(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Blocks implements BlockView {
        private final Map<Long, BlockShape> shapes = new HashMap<>();
        private final Set<Long> bases = new HashSet<>();

        void set(int x, int y, int z, BlockShape shape) {
            shapes.put(key(x, y, z), shape);
        }

        void base(int x, int y, int z) {
            set(x, y, z, BlockShape.FULL);
            bases.add(key(x, y, z));
        }

        boolean isBase(int x, int y, int z) {
            return bases.contains(key(x, y, z));
        }

        boolean isClear(int x, int y, int z) {
            return shapeAt(x, y, z).isEmpty();
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            BlockShape shape = shapes.get(key(x, y, z));
            return shape != null ? shape : BlockShape.EMPTY;
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
        }
    }

    private static final class World implements EntitySource<Object> {
        final List<Fighter> fighters = new ArrayList<>();
        final List<Crystal> crystals = new ArrayList<>();
        final EntityService service;
        Fighter self;

        World() {
            RecordingLogger logger = new RecordingLogger();
            service = new EntityService(logger, new CoreEventBus(logger));
            service.setSource(this);
        }

        Fighter add(Fighter fighter) {
            fighters.add(fighter);
            return fighter;
        }

        @Override
        public Iterable<Object> entities() {
            List<Object> all = new ArrayList<Object>(fighters);
            all.addAll(crystals);
            return all;
        }

        @Override
        public Object self() {
            return self;
        }

        @Override
        public double x(Object entity) {
            return entity instanceof Fighter ? ((Fighter) entity).x : ((Crystal) entity).x;
        }

        @Override
        public double y(Object entity) {
            return entity instanceof Fighter ? ((Fighter) entity).y : ((Crystal) entity).y;
        }

        @Override
        public double z(Object entity) {
            return entity instanceof Fighter ? ((Fighter) entity).z : ((Crystal) entity).z;
        }

        @Override
        public double width(Object entity) {
            return entity instanceof Fighter ? 0.6 : 2;
        }

        @Override
        public double height(Object entity) {
            return entity instanceof Fighter ? 1.8 : 2;
        }

        @Override
        public double eyeHeight(Object entity) {
            return entity instanceof Fighter ? 1.62 : 0;
        }
    }
}
