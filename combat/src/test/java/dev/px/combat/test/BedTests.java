package dev.px.combat.test;

import dev.px.combat.bed.Bed;
import dev.px.combat.bed.BedPart;
import dev.px.combat.bed.BedPlacement;
import dev.px.combat.bed.BedRules;
import dev.px.combat.crystal.CrystalBody;
import dev.px.combat.crystal.CrystalRules;
import dev.px.combat.crystal.Placement;
import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.explosion.rule.Falloff;
import dev.px.combat.explosion.rule.Mitigation;
import dev.px.combat.explosion.rule.SampleGrid;
import dev.px.combat.monitor.DamageMonitor;
import dev.px.combat.monitor.SampleRecorder;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.search.BedSearch;
import dev.px.combat.search.CrystalSearch;
import dev.px.combat.search.engine.SearchStats;
import dev.px.combat.search.option.BedPlaceOption;
import dev.px.combat.search.option.BedUseOption;
import dev.px.combat.search.option.Trigger;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.rule.Thresholds;
import dev.px.combat.search.timing.AttackLog;
import dev.px.combat.vector.capture.VectorRecorder;
import dev.px.combat.world.BlockShape;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Obstructions;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Direction;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
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
 * Beds: their rules as the wiki gives them for current Java, written as a client
 * would, and the bed search on the engine crystals share.
 *
 * <p>The world is a stone floor in "the Nether" &mdash; a flag the test flips
 * &mdash; with you, an enemy, and whatever beds and walls a check adds.
 */
public final class BedTests {

    private static final Explosive BED = Explosive.of("bed", 5);

    /** A bed's collision box, as the test's "game" has it. */
    private static final BlockShape BED_SHAPE = BlockShape.of(Box.of(0, 0, 0, 1, 0.5625, 1));

    private static final Falloff WIKI = Falloff.of(
            power -> 2 * power,
            (distance, exposure, power) -> {
                double impact = (1 - distance / (2 * power)) * exposure;
                return 7 * power * (impact * impact + impact) + 1;
            });

    private BedTests() {
    }

    public static void run() {
        Checks.section("Beds");

        geometry();
        placement();
        goneWhenFired();
        rulesBuild();

        placing();
        bruteForce();
        using();
        timing();
        sharedLog();
        searchBuild();
        monitor();
    }

    // ---------------------------------------------------------------- rules

    private static void geometry() {
        Bed bed = Bed.of(Vec3i.of(0, 1, 0), Direction.NORTH);
        Checks.check("the head goes one block the way you face", bed.getHead().equals(Vec3i.of(0, 1, -1)));
        Bed again = Bed.ofHead(Vec3i.of(0, 1, -1), Direction.NORTH);
        Checks.check("found by its head, it is the same bed, by value",
                again.equals(bed) && again.hashCode() == bed.hashCode() && again != bed);
        Checks.check("facing the other way it is not", !Bed.of(Vec3i.of(0, 1, 0), Direction.SOUTH).equals(bed));
        boolean refused;
        try {
            Bed.of(Vec3i.of(0, 1, 0), Direction.UP);
            refused = false;
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        Checks.check("a bed only faces a compass direction", refused);
    }

    private static void placement() {
        Arena arena = new Arena();
        BedRules<Fighter> rules = arena.beds;
        Checks.check("on the floor, with room, it goes", rules.canPlace(2, 1, 2, Direction.EAST));
        Checks.check("not into the floor", !rules.canPlace(2, 0, 2, Direction.EAST));
        arena.blocks.set(3, 1, 2, BlockShape.FULL);
        Checks.check("not with its head in a wall", !rules.canPlace(2, 1, 2, Direction.EAST));
        Checks.check("but turned away from it, yes", rules.canPlace(2, 1, 2, Direction.WEST));
        Checks.check("no support needed in current Java: one can float", rules.canPlace(2, 4, 2, Direction.WEST));

        Bed onEnemy = Bed.of(Vec3i.of(3, 1, 0), Direction.SOUTH);
        Bed headOnEnemy = Bed.of(Vec3i.of(2, 1, 0), Direction.EAST);
        Checks.check("not with its foot where someone stands", !rules.canPlace(onEnemy));
        Checks.check("nor its head", !rules.canPlace(headOnEnemy));
        Obstructions entities = Obstructions.of(arena.world.service, arena.players);
        BedPlacement footOnly = BedPlacement.clearance(arena.blocks::isClear, 0.5625, 0);
        Checks.check("unless your version only checks the foot: the heights are yours",
                !footOnly.canPlace(onEnemy, entities) && footOnly.canPlace(headOnEnemy, entities));

        BedPlacement supported = BedPlacement.clearance(arena.blocks::isClear, 0.5625, 0.5625)
                .onlyAbove(arena.blocks::isSolid);
        Checks.check("a version that wants support refuses a floating bed",
                !supported.canPlace(Bed.of(Vec3i.of(-2, 4, -2), Direction.WEST), entities)
                        && supported.canPlace(Bed.of(Vec3i.of(-2, 1, -2), Direction.WEST), entities));
        arena.blocks.set(-3, 0, -2, BlockShape.EMPTY);
        Checks.check("under both halves", !supported.canPlace(Bed.of(Vec3i.of(-2, 1, -2), Direction.WEST), entities));

        Bed standing = Bed.of(Vec3i.of(-2, 1, 3), Direction.NORTH);
        arena.blocks.bed(standing);
        Checks.check("a standing bed is found by its head", standing.equals(rules.bedAt(-2, 1, 2)));
        Checks.check("and only by its head", rules.bedAt(-2, 1, 3) == null && rules.bedAt(0, 1, 0) == null);
        Checks.check("it explodes from where you said: the middle of its head",
                rules.origin(standing).equals(Vec3.of(-1.5, 1.5, 2.5)));
    }

    private static void goneWhenFired() {
        Arena arena = new Arena();
        Bed bed = Bed.of(Vec3i.of(1, 1, 0), Direction.EAST);          // head beside the enemy
        arena.blocks.bed(bed);
        Vec3 origin = arena.beds.origin(bed);
        DamageEstimate asItStands = arena.model.estimate(origin, BED, arena.enemyTracked(), arena.blocks);
        DamageEstimate asFired = arena.beds.estimate(bed, arena.enemyTracked());
        Checks.check("asked of the world as it stands, the bed hides its own explosion (exposure "
                + asItStands.getExposure() + ")", asItStands.getExposure() == 0d);
        Checks.check("so predictions are made with the bed gone (exposure " + asFired.getExposure() + ")",
                asFired.getExposure() > 0.5 && asFired.getDamage() > asItStands.getDamage() + 10);
        Checks.check("and only the bed: a wall still counts",
                arena.beds.blocksWhenFired(bed).shapeAt(2, 1, 0).isEmpty()
                        && arena.beds.blocksWhenFired(bed).shapeAt(1, 0, 0) == BlockShape.FULL);
    }

    private static void rulesBuild() {
        String missing;
        try {
            BedRules.<Fighter>builder().explosive(BED).build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("bed rules missing parts name them", missing != null && missing.contains("placement")
                && missing.contains("explodingAt") && missing.contains("usableFrom")
                && missing.contains("explodesWhen") && missing.contains("lookup") && !missing.contains("explosive"));
    }

    // --------------------------------------------------------------- search

    private static void placing() {
        Arena arena = new Arena();
        BedSearch<Fighter> search = arena.search(b -> b);
        BedPlaceOption<Fighter> spot = search.findPlace();
        Checks.check("a bed is found for the enemy", spot != null && spot.getTarget().get() == arena.enemy);
        Checks.check("one that can be placed, foot in reach",
                arena.beds.canPlace(spot.getBed()) && arena.eye().distanceTo(spot.getFoot().center()) <= 5);
        Checks.check("with why, and its explosion where the rules put it", spot.getTrigger() == Trigger.MINIMUM
                && spot.getOrigin().equals(arena.beds.origin(spot.getBed())) && spot.getDamage() > 0);
        SearchStats stats = search.getLastPlaceStats();
        Checks.check("up to four spots a cell, one per facing (" + stats + ")",
                stats.getPlaceable() > stats.getInReach() && stats.getPlaceable() <= 4 * stats.getInReach());
        Checks.check("estimating far fewer than it bounds", stats.getEvaluated() < stats.getBounds() / 4);

        BedSearch<Fighter> north = arena.search(b -> b.facings(() -> new Direction[] { Direction.NORTH }));
        BedPlaceOption<Fighter> facing = north.findPlace();
        Checks.check("narrowed to one facing, it keeps to it",
                facing != null && facing.getFacing() == Direction.NORTH
                        && north.getLastPlaceStats().getPlaceable() < stats.getPlaceable());

        arena.nether = false;
        Checks.check("where beds do not explode, nothing is worth placing",
                search.findPlace() == null && search.getLastPlaceStats().getCells() == 0);
    }

    private static void bruteForce() {
        Random random = new Random(7);
        int agree = 0;
        int pruned = 0;
        int layouts = 20;
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
                    arena.blocks.set(x, y, z, random.nextBoolean() ? BlockShape.FULL : BED_SHAPE);
                }
            }
            arena.refresh();
            Thresholds<Fighter> limits = Thresholds.<Fighter>builder().minDamage(() -> 4).maxSelfDamage(() -> 30).build();
            BedSearch<Fighter> fast = arena.search(x -> x.thresholds(limits));
            BedSearch<Fighter> slow = arena.search(x -> x.thresholds(limits).pruning(false));
            BedPlaceOption<Fighter> a = fast.findPlace();
            BedPlaceOption<Fighter> b = slow.findPlace();
            if (a == null ? b == null : b != null && a.getScore() == b.getScore() && a.getSelfDamage() == b.getSelfDamage()) {
                agree++;
            }
            pruned += fast.getLastPlaceStats().getPruned();
        }
        Checks.checkEquals("branch and bound picks what a brute-force search picks, for beds too, in all 20 layouts",
                layouts, agree);
        Checks.check("while skipping most spots (" + pruned + " pruned)", pruned > layouts * 10);
    }

    private static void using() {
        Arena arena = new Arena();
        Bed bed = Bed.of(Vec3i.of(1, 1, 1), Direction.EAST);
        arena.blocks.bed(bed);
        BedSearch<Fighter> search = arena.search(b -> b);
        BedUseOption<Fighter> use = search.findUse();
        Checks.check("a standing bed near the enemy is the one to use",
                use != null && use.getBed().equals(bed) && use.getTarget().get() == arena.enemy);
        Checks.check("clicking the nearer half", use.getPart() == BedPart.FOOT && use.getCell().equals(bed.getFoot()));
        Checks.check("with its damage predicted as fired", use.getDamage() == arena.beds.damage(bed, arena.enemyTracked()));
        Checks.checkEquals("(one bed seen)", 1, search.getLastUseStats().getExisting());

        Arena headOnly = new Arena(BedPart.HEAD);
        headOnly.blocks.bed(bed);
        BedUseOption<Fighter> head = headOnly.search(b -> b).findUse();
        Checks.check("a version that only takes the head is clicked on the head",
                head != null && head.getPart() == BedPart.HEAD);

        Checks.check("one out of use range is never chosen", arena.search(b -> b.useReach(Reach.of(1, 1))).findUse() == null);
        arena.nether = false;
        Checks.check("and in the Overworld, none is", search.findUse() == null);
    }

    private static void timing() {
        Arena arena = new Arena();
        Bed bed = Bed.of(Vec3i.of(1, 1, 1), Direction.EAST);
        arena.blocks.bed(bed);
        BedSearch<Fighter> search = arena.search(b -> b.thresholds(Thresholds.<Fighter>builder().inhibit(() -> 2).build()));
        search.used(Bed.of(Vec3i.of(1, 1, 1), Direction.EAST));       // a different object, the same bed
        Checks.check("a bed is inhibited by position, not by object",
                search.findUse() == null && search.getLastUseStats().getInhibited() == 1);
        double dealt = search.getLog().dealtTo(arena.enemy);
        Checks.check("and what it dealt this tick is recorded", dealt == arena.beds.damage(bed, arena.enemyTracked()));
        BedPlaceOption<Fighter> after = search.findPlace();
        Checks.check("a bed placed this tick goes off this tick, so only one that hits harder counts",
                after == null || after.getDamage() > dealt);
        search.tick();
        search.tick();
        Checks.check("after the inhibit window it is back", search.findUse() != null);
    }

    private static void sharedLog() {
        Arena arena = new Arena();
        Crystal crystal = arena.crystal(3, 0, 1);                      // right beside the enemy
        arena.refresh();
        AttackLog log = new AttackLog();
        CrystalSearch<Fighter> crystals = arena.crystalSearch(log);
        BedSearch<Fighter> beds = arena.search(b -> b.log(log));
        BedSearch<Fighter> alone = arena.search(b -> b);
        BedPlaceOption<Fighter> before = beds.findPlace();
        crystals.attacked(arena.crystals.get(crystal));
        double fromCrystal = log.dealtTo(arena.enemy);
        Checks.check("(the crystal hits harder than any bed could: " + fromCrystal + " vs " + before.getDamage() + ")",
                fromCrystal > before.getDamage());
        Checks.check("with one log, a crystal broken this tick stops a weaker bed: one explosion lands",
                beds.findPlace() == null && alone.findPlace() != null);
        log.newTick();
        Checks.check("next tick, the bed counts again", beds.findPlace() != null);
    }

    private static void searchBuild() {
        String missing;
        try {
            BedSearch.<Fighter>builder().placeReach(Reach.of(5, 5)).build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("a bed search missing parts names them", missing != null && missing.contains("rules")
                && missing.contains("useReach") && missing.contains("targets") && !missing.contains("placeReach"));
    }

    // -------------------------------------------------------------- monitor

    private static void monitor() {
        Arena arena = new Arena();
        Bed bed = Bed.of(Vec3i.of(1, 1, 0), Direction.EAST);
        arena.blocks.bed(bed);
        List<Double> predicted = new ArrayList<>();
        SampleRecorder<Fighter> recorder = new SampleRecorder<Fighter>() {
            @Override
            public Object begin(Vec3 origin, Explosive explosive, Tracked<? extends Fighter> target,
                                DamageEstimate estimate) {
                if (target.get() == arena.enemy) {
                    predicted.add(estimate.getDamage());
                }
                return null;
            }

            @Override
            public void finish(Object token, DamageEstimate estimate, double before, double observed, boolean popped) {
            }
        };
        DamageMonitor<Fighter> monitor = DamageMonitor.<Fighter>builder()
                .model(arena.model)
                .blocks(arena.blocks)
                .vitals(arena.vitals())
                .targets(arena.world.service, arena.players)
                .recorder(recorder)
                .build();
        Vec3 origin = arena.beds.origin(bed);
        monitor.exploded(origin, BED);
        monitor.tick();
        monitor.tick();
        monitor.tick();
        monitor.exploded(origin, BED, arena.beds.blocksWhenFired(bed));
        Checks.check("the monitor, told the bed is gone, predicts what it really did (" + predicted + ")",
                predicted.size() == 2 && predicted.get(1) > predicted.get(0) + 10);

        VectorRecorder<Fighter> vectors = VectorRecorder.<Fighter>builder().blocks(arena.blocks).build();
        Object token = vectors.begin(origin, BED, arena.enemyTracked(),
                arena.beds.estimate(bed, arena.enemyTracked()), arena.beds.blocksWhenFired(bed));
        vectors.finish(token, arena.beds.estimate(bed, arena.enemyTracked()), 36, 20, false);
        BlockView kept = vectors.getVectors().get(0).getBlocks();
        Checks.check("and a test vector keeps the world as the explosion found it: no bed, the floor",
                kept.shapeAt(2, 1, 0).isEmpty() && kept.shapeAt(1, 1, 0).isEmpty() && BlockShape.FULL.equals(kept.shapeAt(2, 0, 0)));
    }

    // ------------------------------------------------- the test's "game"

    /** A stone floor, you at the origin and an enemy three blocks east. */
    private static final class Arena {
        final World world = new World();
        final Blocks blocks = new Blocks();
        final Fighter self = world.add(new Fighter(0.5, 1, 0.5));
        final Fighter enemy = world.add(new Fighter(3.5, 1, 0.5));
        final EntityTracker<Fighter> players;
        final EntityTracker<Crystal> crystals;
        final TargetService targets;
        final ExplosionModel<Fighter> model;
        final BedRules<Fighter> beds;
        boolean nether = true;

        Arena() {
            this(BedPart.FOOT, BedPart.HEAD);
        }

        Arena(BedPart... usable) {
            for (int x = -7; x <= 7; x++) {
                for (int z = -7; z <= 7; z++) {
                    blocks.set(x, 0, z, BlockShape.FULL);
                }
            }
            world.self = self;
            players = world.service.register(EntityTracker.of(Fighter.class));
            crystals = world.service.register(EntityTracker.of(Crystal.class));
            targets = new TargetService(world.service);
            refresh();
            model = ExplosionModel.<Fighter>builder()
                    .measureFrom(Tracked::getPosition)
                    .exposure(Exposure.sampled(SampleGrid.uniform(3, 5, 3)))
                    .falloff(WIKI)
                    .mitigation(Mitigation.none())
                    .build();
            beds = BedRules.<Fighter>builder()
                    .placement(BedPlacement.clearance(blocks::isClear, 0.5625, 0.5625))
                    .explodingAt(0.5, 0.5, 0.5)
                    .usableFrom(usable)
                    .explodesWhen(() -> nether)
                    .lookup(blocks::bedHeadAt)
                    .explosive(BED)
                    .model(model)
                    .blocks(blocks)
                    .obstructions(Obstructions.of(world.service, players))
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

        Vec3 eye() {
            return world.service.getSelf().getEyePosition();
        }

        Tracked<Fighter> enemyTracked() {
            return players.get(enemy);
        }

        Vitals<Fighter> vitals() {
            return new Vitals<Fighter>() {
                @Override
                public double pool(Fighter fighter) {
                    return fighter.health;
                }

                @Override
                public boolean isTrusted(Fighter fighter) {
                    return true;
                }
            };
        }

        BedSearch<Fighter> search(Function<BedSearch.Builder<Fighter>, BedSearch.Builder<Fighter>> tweak) {
            BedSearch.Builder<Fighter> builder = BedSearch.<Fighter>builder()
                    .rules(beds)
                    .entities(world.service)
                    .targets(targets, TargetSelector.from(players).range(20).build())
                    .vitals(vitals())
                    .placeReach(Reach.of(5, 5))
                    .useReach(Reach.of(5, 5))
                    .thresholds(Thresholds.<Fighter>none());
            return tweak.apply(builder).build();
        }

        CrystalSearch<Fighter> crystalSearch(AttackLog log) {
            CrystalRules<Fighter> rules = CrystalRules.<Fighter>builder()
                    .placement(Placement.clearance(blocks::isSolid, 1, blocks::isClear, 2))
                    .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0))
                    .explosive(Explosive.of("end crystal", 6))
                    .model(model)
                    .blocks(blocks)
                    .obstructions(Obstructions.of(world.service, players, crystals))
                    .build();
            return CrystalSearch.<Fighter>builder()
                    .rules(rules)
                    .entities(world.service)
                    .targets(targets, TargetSelector.from(players).range(20).build())
                    .crystals(crystals)
                    .vitals(vitals())
                    .placeReach(Reach.of(5, 5))
                    .breakReach(Reach.of(5, 5))
                    .thresholds(Thresholds.<Fighter>none())
                    .log(log)
                    .build();
        }
    }

    private static final class Fighter {
        double x;
        double y;
        double z;
        double health = 36;

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

    /** Blocks by cell, and beds by head, as a client's lookup would read them from block states. */
    private static final class Blocks implements BlockView {
        private final Map<Long, BlockShape> shapes = new HashMap<>();
        private final Map<Long, Direction> heads = new HashMap<>();
        private final Set<Long> beds = new HashSet<>();

        void set(int x, int y, int z, BlockShape shape) {
            shapes.put(Vec3i.asLong(x, y, z), shape);
        }

        void bed(Bed bed) {
            for (BedPart part : BedPart.values()) {
                Vec3i cell = bed.get(part);
                set(cell.getX(), cell.getY(), cell.getZ(), BED_SHAPE);
                beds.add(cell.asLong());
            }
            heads.put(bed.getHead().asLong(), bed.getFacing());
        }

        boolean isClear(int x, int y, int z) {
            return shapeAt(x, y, z).isEmpty() && !beds.contains(Vec3i.asLong(x, y, z));
        }

        boolean isSolid(int x, int y, int z) {
            return shapeAt(x, y, z) == BlockShape.FULL;
        }

        Direction bedHeadAt(int x, int y, int z) {
            return heads.get(Vec3i.asLong(x, y, z));
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            BlockShape shape = shapes.get(Vec3i.asLong(x, y, z));
            return shape != null ? shape : BlockShape.EMPTY;
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
