package dev.px.combat.test;

import dev.px.combat.anchor.Anchor;
import dev.px.combat.anchor.AnchorLookup;
import dev.px.combat.anchor.AnchorPlacement;
import dev.px.combat.anchor.AnchorRules;
import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.explosion.rule.Falloff;
import dev.px.combat.explosion.rule.Mitigation;
import dev.px.combat.explosion.rule.SampleGrid;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.place.Click;
import dev.px.combat.place.Clicks;
import dev.px.combat.place.FaceRule;
import dev.px.combat.search.AnchorSearch;
import dev.px.combat.search.engine.SearchStats;
import dev.px.combat.search.option.AnchorPlaceOption;
import dev.px.combat.search.option.AnchorUseOption;
import dev.px.combat.search.option.Trigger;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.rule.Thresholds;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Respawn anchors: their rules as the wiki gives them for current Java, written
 * as a client would, and the anchor search on the engine crystals and beds share.
 *
 * <p>The world is a stone floor in "the Overworld" &mdash; a flag the test flips
 * &mdash; with you, an enemy, and whatever anchors and walls a check adds.
 */
public final class AnchorTests {

    /** The wiki: power 5. */
    private static final Explosive ANCHOR = Explosive.of("respawn anchor", 5);

    private static final Falloff WIKI = Falloff.of(
            power -> 2 * power,
            (distance, exposure, power) -> {
                double impact = (1 - distance / (2 * power)) * exposure;
                return 7 * power * (impact * impact + impact) + 1;
            });

    private AnchorTests() {
    }

    public static void run() {
        Checks.section("Respawn anchors");

        anchors();
        placement();
        goneWhenFired();
        rulesBuild();

        placing();
        bruteForce();
        using();
        timing();
        overSeveralTicks();
        strictPlacement();
        searchBuild();
    }

    // ---------------------------------------------------------------- rules

    private static void anchors() {
        Arena arena = new Arena();
        Checks.check("no anchor, none found", arena.anchors.anchorAt(2, 1, 2) == null);
        arena.blocks.anchor(2, 1, 2, 2);
        Anchor two = arena.anchors.anchorAt(2, 1, 2);
        Checks.check("an anchor is found with its charges, as your lookup reads them",
                two != null && two.getCharges() == 2 && two.isCharged() && two.getCell().equals(Vec3i.of(2, 1, 2)));
        arena.blocks.anchor(2, 1, 2, 0);
        Checks.check("an empty one holds no charge", !arena.anchors.anchorAt(2, 1, 2).isCharged());
        Checks.check("by value", Anchor.of(Vec3i.of(1, 1, 1), 3).equals(Anchor.of(Vec3i.of(1, 1, 1), 3))
                && !Anchor.of(Vec3i.of(1, 1, 1), 3).equals(Anchor.of(Vec3i.of(1, 1, 1), 2)));
        Checks.checkThrows("negative charges are refused", IllegalArgumentException.class,
                () -> Anchor.of(Vec3i.of(0, 0, 0), -1));
    }

    private static void placement() {
        Arena arena = new Arena();
        AnchorRules<Fighter> rules = arena.anchors;
        Checks.check("on the floor, with room, it goes", rules.canPlace(Vec3i.of(2, 1, 2)));
        Checks.check("not into the floor", !rules.canPlace(Vec3i.of(2, 0, 2)));
        Checks.check("not where someone stands, anywhere in the block", !rules.canPlace(Vec3i.of(3, 1, 0))
                && !rules.canPlace(Vec3i.of(3, 2, 0)));
        Checks.check("with clearance alone, mid air too: click rules say what can be clicked",
                rules.canPlace(Vec3i.of(2, 4, 2)));
        AnchorPlacement supported = AnchorPlacement.clearance(arena.blocks::isClear).nextTo(arena.blocks::isSolid);
        Obstructions entities = Obstructions.of(arena.world.service, arena.players);
        Checks.check("without them, next to something solid", supported.canPlace(Vec3i.of(2, 1, 2), entities)
                && !supported.canPlace(Vec3i.of(2, 4, 2), entities));
    }

    private static void goneWhenFired() {
        Arena arena = new Arena();
        Vec3i cell = Vec3i.of(2, 1, 0);                                 // beside the enemy
        arena.blocks.anchor(2, 1, 0, 1);
        Vec3 origin = arena.anchors.origin(cell);
        DamageEstimate asItStands = arena.model.estimate(origin, ANCHOR, arena.enemyTracked(), arena.blocks);
        DamageEstimate asFired = arena.anchors.estimate(cell, arena.enemyTracked());
        Checks.check("asked of the world as it stands, the anchor hides its own explosion (exposure "
                + asItStands.getExposure() + ")", asItStands.getExposure() == 0d);
        Checks.check("so predictions are made with the anchor gone (exposure " + asFired.getExposure() + ")",
                asFired.getExposure() > 0.5 && asFired.getDamage() > asItStands.getDamage() + 10);
        Checks.check("and only the anchor: the floor still counts",
                arena.anchors.blocksWhenFired(cell).shapeAt(2, 1, 0).isEmpty()
                        && arena.anchors.blocksWhenFired(cell).shapeAt(2, 0, 0) == BlockShape.FULL);
        Checks.check("from where your rules put it", origin.equals(Vec3.of(2.5, 1.5, 0.5)));
    }

    private static void rulesBuild() {
        String missing;
        try {
            AnchorRules.<Fighter>builder().explosive(ANCHOR).build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("anchor rules missing parts name them (" + missing + ")", missing != null
                && missing.contains("placement") && missing.contains("explodingAt") && missing.contains("explodesWhen")
                && missing.contains("lookup") && missing.contains("model") && !missing.contains("explosive"));
    }

    // --------------------------------------------------------------- search

    private static void placing() {
        Arena arena = new Arena();
        AnchorSearch<Fighter> search = arena.search(b -> b);
        AnchorPlaceOption<Fighter> spot = search.findPlace();
        Checks.check("an anchor is found for the enemy (" + spot + ")",
                spot != null && spot.getTarget().get() == arena.enemy);
        Checks.check("one that can be placed, in reach",
                arena.anchors.canPlace(spot.getCell()) && arena.eye().distanceTo(spot.getCell().center()) <= 5);
        Checks.check("with why, and its explosion where the rules put it", spot.getTrigger() == Trigger.MINIMUM
                && spot.getOrigin().equals(arena.anchors.origin(spot.getCell())) && spot.getDamage() > 0
                && spot.getAim().equals(spot.getCell().center()));
        SearchStats stats = search.getLastPlaceStats();
        Checks.check("one spot a cell (" + stats + ")",
                stats.getPlaceable() > 0 && stats.getPlaceable() <= stats.getInReach());
        Checks.check("estimating far fewer than it bounds", stats.getEvaluated() < stats.getBounds() / 4);
        List<AnchorPlaceOption<Fighter>> three = search.findPlaces(3);
        Checks.check("the best three, best first, the first what findPlace finds (" + three + ")",
                three.size() == 3 && three.get(0).getCell().equals(spot.getCell())
                        && !three.get(1).beats(three.get(0)) && !three.get(2).beats(three.get(1))
                        && !three.get(1).getCell().equals(three.get(0).getCell()));

        arena.overworld = false;
        Checks.check("where anchors do not explode, nothing is worth placing",
                search.findPlace() == null && search.getLastPlaceStats().getCells() == 0);
    }

    private static void bruteForce() {
        Random random = new Random(11);
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
                    arena.blocks.set(x, y, z, BlockShape.FULL);
                }
            }
            arena.refresh();
            Thresholds<Fighter> limits = Thresholds.<Fighter>builder().minDamage(() -> 4).maxSelfDamage(() -> 30).build();
            AnchorSearch<Fighter> fast = arena.search(x -> x.thresholds(limits));
            AnchorSearch<Fighter> slow = arena.search(x -> x.thresholds(limits).pruning(false));
            AnchorPlaceOption<Fighter> a = fast.findPlace();
            AnchorPlaceOption<Fighter> b = slow.findPlace();
            if (a == null ? b == null : b != null && a.getScore() == b.getScore() && a.getSelfDamage() == b.getSelfDamage()) {
                agree++;
            }
            pruned += fast.getLastPlaceStats().getPruned();
        }
        Checks.checkEquals("branch and bound picks what a brute-force search picks, for anchors too, in all 20 layouts",
                layouts, agree);
        Checks.check("while skipping most spots (" + pruned + " pruned)", pruned > layouts * 10);
    }

    private static void using() {
        Arena arena = new Arena();
        arena.blocks.anchor(2, 1, 1, 3);
        AnchorSearch<Fighter> search = arena.search(b -> b);
        AnchorUseOption<Fighter> use = search.findUse();
        Vec3i cell = Vec3i.of(2, 1, 1);
        Checks.check("a charged anchor near the enemy is the one to set off (" + use + ")",
                use != null && use.getCell().equals(cell) && use.getTarget().get() == arena.enemy
                        && use.getChargesNeeded() == 0 && use.getAnchor().getCharges() == 3);
        Checks.check("with its damage predicted as fired, aimed at its centre",
                use.getDamage() == arena.anchors.damage(cell, arena.enemyTracked()) && use.getAim().equals(cell.center()));
        Checks.checkEquals("(one anchor seen)", 1, search.getLastUseStats().getExisting());

        arena.blocks.anchor(2, 1, 1, 0);
        AnchorUseOption<Fighter> empty = search.findUse();
        Checks.check("an empty one is offered too, needing a charge first",
                empty != null && empty.getChargesNeeded() == 1);
        AnchorSearch<Fighter> chargedOnly = arena.search(b -> b.useEmpty(() -> false));
        Checks.check("unless you only want charged ones", chargedOnly.findUse() == null
                && chargedOnly.getLastUseStats().getExisting() == 0);

        Checks.check("one out of use range is never chosen",
                arena.search(b -> b.useReach(Reach.of(1, 1))).findUse() == null);
        arena.blocks.anchor(2, 1, 1, 3);
        AnchorSearch<Fighter> nearWalls = arena.search(b -> b.useReach(Reach.of(5, 1)));
        Checks.check("past the wall range, one in plain sight is used: it does not hide itself",
                nearWalls.findUse() != null);
        for (int y = 1; y <= 2; y++) {
            arena.blocks.set(1, y, 0, BlockShape.FULL);
            arena.blocks.set(1, y, 1, BlockShape.FULL);
        }
        Checks.check("but one behind a wall is not", nearWalls.findUse() == null);
        Checks.check("unless your wall range reaches it", arena.search(b -> b).findUse() != null);
        arena.blocks.set(1, 1, 0, BlockShape.EMPTY);
        arena.blocks.set(1, 2, 0, BlockShape.EMPTY);
        arena.blocks.set(1, 1, 1, BlockShape.EMPTY);
        arena.blocks.set(1, 2, 1, BlockShape.EMPTY);
        arena.overworld = false;
        Checks.check("and in the Nether, none is", search.findUse() == null);
    }

    private static void timing() {
        Arena arena = new Arena();
        arena.blocks.anchor(2, 1, 1, 2);
        Vec3i cell = Vec3i.of(2, 1, 1);
        AnchorSearch<Fighter> search = arena.search(b -> b.thresholds(Thresholds.<Fighter>builder().inhibit(() -> 2).build()));
        search.used(Anchor.of(cell, 4));                                // the same cell, other charges
        Checks.check("an anchor is inhibited by its cell, whatever its charges",
                search.findUse() == null && search.getLastUseStats().getInhibited() == 1);
        double dealt = search.getLog().dealtTo(arena.enemy);
        Checks.check("and what it dealt this tick is recorded", dealt == arena.anchors.damage(cell, arena.enemyTracked()));
        AnchorPlaceOption<Fighter> after = search.findPlace();
        Checks.check("an anchor placed this tick goes off this tick, so only one that hits harder counts",
                after == null || after.getDamage() > dealt);
        search.tick();
        search.tick();
        Checks.check("after the inhibit window it is back", search.findUse() != null);
    }

    private static void overSeveralTicks() {
        Arena arena = new Arena();
        AnchorSearch<Fighter> search = arena.search(b -> b.oneTick(() -> false));
        AnchorPlaceOption<Fighter> spot = search.findPlace();
        search.placed(spot);
        AnchorPlaceOption<Fighter> next = search.findPlace();
        Checks.check("placed to set off later, its cell is kept while it is pending (" + next + ")",
                next != null && !next.getCell().equals(spot.getCell()));
        Vec3i cell = spot.getCell();
        arena.blocks.anchor(cell.getX(), cell.getY(), cell.getZ(), 0);
        AnchorUseOption<Fighter> mine = search.findUse();
        Checks.check("when it shows up, empty, it is yours, to charge and set off ("
                + mine + ")", mine != null && mine.getCell().equals(cell) && mine.isOwn() && mine.getChargesNeeded() == 1);

        AnchorSearch<Fighter> atOnce = arena.search(b -> b);
        AnchorPlaceOption<Fighter> other = atOnce.findPlace();
        atOnce.placed(other);
        Checks.check("placing in one tick, nothing is kept pending: it is gone the same tick",
                atOnce.getLog().isPendingIn(other.getCell().toBox()) == false);
    }

    private static void strictPlacement() {
        Arena arena = new Arena();
        Clicks clicks = Clicks.builder().support(arena.blocks::isSolid).replaceable(arena.blocks::isClear)
                .faces(FaceRule.facingEye()).build();
        AnchorSearch<Fighter> search = arena.search(b -> b.clicks(clicks));
        List<AnchorPlaceOption<Fighter>> spots = search.findPlaces(4);
        Vec3 eye = arena.eye();
        boolean clicked = !spots.isEmpty();
        for (AnchorPlaceOption<Fighter> spot : spots) {
            Click place = spot.getClick();
            Click use = spot.getUseClick();
            clicked &= place != null && place.getCell().equals(spot.getCell()) && spot.getAim().equals(place.getHit())
                    && use != null && use.getBlock().equals(spot.getCell());
        }
        Checks.check("with click rules, an anchor comes with a click to place it and one on it to charge and set it "
                + "off (" + spots + ")", clicked);
        Checks.check("without them, no clicks", arena.search(b -> b).findPlace().getClick() == null);
        AnchorPlaceOption<Fighter> later = arena.search(b -> b.clicks(clicks).oneTick(() -> false)).findPlace();
        Checks.check("placing now to set off later, only the placing click: the anchor is clicked once it stands",
                later.getClick() != null && later.getUseClick() == null);
        AnchorSearch<Fighter> never = arena.search(b -> b.clicks(Clicks.builder().support(arena.blocks::isSolid)
                .replaceable(arena.blocks::isClear).faces((e, block, face, hit) -> false).build()));
        Checks.check("and nowhere your server would not take a click is offered", never.findPlace() == null);
        Clicks onlyStanding = Clicks.builder().support(arena.blocks::isSolid).replaceable(arena.blocks::isClear)
                .faces((e, block, face, hit) -> arena.blocks.isSolid(block.getX(), block.getY(), block.getZ())).build();
        Checks.check("placing in one tick, a spot whose anchor could not then be clicked is not offered",
                arena.search(b -> b.clicks(onlyStanding)).findPlace() == null
                        && arena.search(b -> b.clicks(onlyStanding).oneTick(() -> false)).findPlace() != null);
        AnchorSearch<Fighter> walled = arena.search(b -> b.placeReach(Reach.of(5, 1)));
        AnchorPlaceOption<Fighter> seen = walled.findPlace();
        Checks.check("past the wall range, a cell in plain sight can be placed into (" + walled.getLastPlaceStats() + ")",
                seen != null);

        arena.blocks.anchor(2, 1, 1, 1);
        AnchorUseOption<Fighter> use = arena.search(b -> b.clicks(clicks)).findUse();
        Checks.check("a standing anchor is set off by clicking it (" + (use == null ? null : use.getClick()) + ")",
                use != null && use.getClick() != null && use.getClick().getBlock().equals(use.getCell())
                        && use.getAim().equals(use.getClick().getHit()));
    }

    private static void searchBuild() {
        String missing;
        try {
            AnchorSearch.<Fighter>builder().placeReach(Reach.of(5, 5)).build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("an anchor search missing parts names them (" + missing + ")", missing != null
                && missing.contains("rules") && missing.contains("useReach") && missing.contains("targets")
                && !missing.contains("placeReach"));
    }

    // ------------------------------------------------- the test's "game"

    /** A stone floor, you at the origin and an enemy three blocks east. */
    private static final class Arena {
        final World world = new World();
        final Blocks blocks = new Blocks();
        final Fighter self = world.add(new Fighter(0.5, 1, 0.5));
        final Fighter enemy = world.add(new Fighter(3.5, 1, 0.5));
        final EntityTracker<Fighter> players;
        final TargetService targets;
        final ExplosionModel<Fighter> model;
        final AnchorRules<Fighter> anchors;
        boolean overworld = true;

        Arena() {
            for (int x = -7; x <= 7; x++) {
                for (int z = -7; z <= 7; z++) {
                    blocks.set(x, 0, z, BlockShape.FULL);
                }
            }
            world.self = self;
            players = world.service.register(EntityTracker.of(Fighter.class));
            targets = new TargetService(world.service);
            refresh();
            model = ExplosionModel.<Fighter>builder()
                    .measureFrom(Tracked::getPosition)
                    .exposure(Exposure.sampled(SampleGrid.uniform(3, 5, 3)))
                    .falloff(WIKI)
                    .mitigation(Mitigation.none())
                    .build();
            anchors = AnchorRules.<Fighter>builder()
                    .placement(AnchorPlacement.clearance(blocks::isClear))
                    .explodingAt(0.5, 0.5, 0.5)
                    .explodesWhen(() -> overworld)
                    .lookup(blocks::chargesAt)
                    .explosive(ANCHOR)
                    .model(model)
                    .blocks(blocks)
                    .obstructions(Obstructions.of(world.service, players))
                    .build();
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

        AnchorSearch<Fighter> search(Function<AnchorSearch.Builder<Fighter>, AnchorSearch.Builder<Fighter>> tweak) {
            AnchorSearch.Builder<Fighter> builder = AnchorSearch.<Fighter>builder()
                    .rules(anchors)
                    .entities(world.service)
                    .targets(targets, TargetSelector.from(players).range(20).build())
                    .vitals(vitals())
                    .placeReach(Reach.of(5, 5))
                    .useReach(Reach.of(5, 5))
                    .thresholds(Thresholds.<Fighter>none());
            return tweak.apply(builder).build();
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

    /** Blocks by cell, and anchors with their charges, as a client's lookup would read them from block states. */
    private static final class Blocks implements BlockView {
        private final Map<Long, BlockShape> shapes = new HashMap<>();
        private final Map<Long, Integer> anchors = new HashMap<>();

        void set(int x, int y, int z, BlockShape shape) {
            shapes.put(Vec3i.asLong(x, y, z), shape);
        }

        /** An anchor is a whole block. */
        void anchor(int x, int y, int z, int charges) {
            set(x, y, z, BlockShape.FULL);
            anchors.put(Vec3i.asLong(x, y, z), charges);
        }

        boolean isClear(int x, int y, int z) {
            return shapeAt(x, y, z).isEmpty();
        }

        boolean isSolid(int x, int y, int z) {
            return shapeAt(x, y, z) == BlockShape.FULL;
        }

        int chargesAt(int x, int y, int z) {
            Integer charges = anchors.get(Vec3i.asLong(x, y, z));
            return charges != null ? charges : AnchorLookup.NONE;
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            BlockShape shape = shapes.get(Vec3i.asLong(x, y, z));
            return shape != null ? shape : BlockShape.EMPTY;
        }
    }

    private static final class World implements EntitySource<Object> {
        final List<Fighter> fighters = new ArrayList<>();
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
            return new ArrayList<Object>(fighters);
        }

        @Override
        public Object self() {
            return self;
        }

        @Override
        public double x(Object entity) {
            return ((Fighter) entity).x;
        }

        @Override
        public double y(Object entity) {
            return ((Fighter) entity).y;
        }

        @Override
        public double z(Object entity) {
            return ((Fighter) entity).z;
        }

        @Override
        public double width(Object entity) {
            return 0.6;
        }

        @Override
        public double height(Object entity) {
            return 1.8;
        }

        @Override
        public double eyeHeight(Object entity) {
            return 1.62;
        }
    }
}
