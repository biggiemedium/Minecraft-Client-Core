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
import dev.px.combat.search.engine.Judgement;
import dev.px.combat.search.engine.SearchStats;
import dev.px.combat.search.option.BreakOption;
import dev.px.combat.search.option.Harm;
import dev.px.combat.search.option.PlaceOption;
import dev.px.combat.search.option.Proposal;
import dev.px.combat.search.option.Trigger;
import dev.px.combat.place.Clicks;
import dev.px.combat.place.FaceRule;
import dev.px.combat.search.rule.AimCost;
import dev.px.combat.search.rule.Lookahead;
import dev.px.combat.search.rule.OptionFilter;
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
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
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
 * The crystal search: placing and breaking, the best few places, every
 * threshold, protecting friends, your own filters, aiming, reach, timing, and
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
        ranked();
        rankedBruteForce();
        reach();
        thresholds();
        lethal();
        facePlaceAndArmour();
        protecting();
        protectingBruteForce();
        filters();
        breaking();
        timing();
        spawning();
        scoring();
        aiming();
        aimingBruteForce();
        aimingToBreak();
        lookingAhead();
        lookingAheadWithPrediction();
        pendingPlacements();
        planning();
        ownCrystals();
        listening();
        strictPlacement();
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

    private static void ranked() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> search = arena.search(b -> b);
        PlaceOption<Fighter> best = search.findPlace();
        List<PlaceOption<Fighter>> five = search.findPlaces(5);
        Checks.check("the best five places are five", five.size() == 5);
        Checks.check("the first is what findPlace finds",
                same(five.get(0), best) && five.get(0).getX() == best.getX() && five.get(0).getZ() == best.getZ());
        boolean ordered = true;
        Set<Long> bases = new HashSet<>();
        for (int i = 0; i < five.size(); i++) {
            PlaceOption<Fighter> option = five.get(i);
            bases.add(Blocks.key(option.getX(), option.getY(), option.getZ()));
            if (i > 0 && option.beats(five.get(i - 1))) {
                ordered = false;
            }
        }
        Checks.check("best first, each on its own base (" + five + ")", ordered && bases.size() == 5);
        int one = search.getLastPlaceStats().getEvaluated();
        search.findPlace();
        Checks.check("asking for more estimates more: pruning waits for the fifth best",
                one > search.getLastPlaceStats().getEvaluated());

        Thresholds<Fighter> strict = Thresholds.<Fighter>builder().minDamage(() -> 1000).build();
        Checks.check("nowhere worth it is an empty list", arena.search(b -> b.thresholds(strict)).findPlaces(3).isEmpty());
        Checks.checkThrows("and a count below one is refused", IllegalArgumentException.class, () -> search.findPlaces(0));
    }

    private static void rankedBruteForce() {
        Random random = new Random(11);
        int agree = 0;
        int pruned = 0;
        int layouts = 20;
        for (int layout = 0; layout < layouts; layout++) {
            Arena arena = randomArena(random);
            Thresholds<Fighter> limits = Thresholds.<Fighter>builder().minDamage(() -> 4).maxSelfDamage(() -> 30).build();
            CrystalSearch<Fighter> fast = arena.search(b -> b.thresholds(limits));
            CrystalSearch<Fighter> slow = arena.search(b -> b.thresholds(limits).pruning(false));
            List<PlaceOption<Fighter>> a = fast.findPlaces(4);
            List<PlaceOption<Fighter>> everything = slow.findPlaces(10000);
            List<PlaceOption<Fighter>> b = everything.subList(0, Math.min(4, everything.size()));
            if (sameRanking(a, b)) {
                agree++;
            }
            pruned += fast.getLastPlaceStats().getPruned();
        }
        Checks.checkEquals("the best four by branch and bound are the best four of every option, in all 20 layouts",
                layouts, agree);
        Checks.check("while still skipping spots (" + pruned + " pruned)", pruned > layouts * 5);
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

    // ------------------------------------------------------------ protecting

    private static void protecting() {
        Arena arena = new Arena();
        Fighter friend = arena.world.add(new Fighter(3.5, 1, 2.5));     // two blocks from the enemy
        arena.refresh();
        Tracked<Fighter> friendly = arena.players.get(friend);
        TargetSelector<Fighter> friends = TargetSelector.from(arena.players).range(20).where(f -> f == friend).build();

        PlaceOption<Fighter> free = arena.search(b -> b).findPlace();
        double freeHarm = arena.rules.damage(free.getX(), free.getY(), free.getZ(), friendly);
        Checks.check("(unprotected, the best place hurts the friend: " + freeHarm + ")", freeHarm > 5);

        double cap = freeHarm / 2;
        Thresholds<Fighter> capped = Thresholds.<Fighter>builder().maxProtectedDamage(() -> cap).build();
        CrystalSearch<Fighter> careful = arena.search(b -> b.protect(friends).thresholds(capped));
        PlaceOption<Fighter> safe = careful.findPlace();
        double safeHarm = arena.rules.damage(safe.getX(), safe.getY(), safe.getZ(), friendly);
        Checks.check("the protected cap is kept, at the cost of damage (" + safeHarm + ", " + careful.getLastPlaceStats() + ")",
                safeHarm <= cap && safe.getDamage() < free.getDamage() && safe.getTarget().get() == arena.enemy
                        && careful.getLastPlaceStats().getEndangering() > 0);
        Checks.check("and nothing is refused before a friend is named", arena.search(b -> b.thresholds(capped))
                .findPlace().getDamage() == free.getDamage());

        friend.health = cap + 4;
        Thresholds<Fighter> margin = Thresholds.<Fighter>builder().protectedMargin(() -> 4).build();
        PlaceOption<Fighter> spared = arena.search(b -> b.protect(friends).thresholds(margin)).findPlace();
        double sparedHarm = arena.rules.damage(spared.getX(), spared.getY(), spared.getZ(), friendly);
        Checks.check("the protected margin never leaves a friend near death (" + sparedHarm + ")",
                sparedHarm < cap && spared.getDamage() < free.getDamage());
        friend.trusted = false;
        PlaceOption<Fighter> hidden = arena.search(b -> b.protect(friends).thresholds(margin)).findPlace();
        Checks.check("a friend whose health is hidden gets no margin: only the cap protects them",
                hidden.getDamage() == free.getDamage());
        friend.trusted = true;

        arena.enemy.health = 3;
        Thresholds<Fighter> killing = Thresholds.<Fighter>builder().minDamage(() -> 1000).lethal(() -> 1, true)
                .maxProtectedDamage(() -> cap).build();
        PlaceOption<Fighter> kill = arena.search(b -> b.protect(friends).thresholds(killing)).findPlace();
        Checks.check("not even a kill may hurt a friend past the cap",
                kill != null && kill.getTrigger() == Trigger.LETHAL
                        && arena.rules.damage(kill.getX(), kill.getY(), kill.getZ(), friendly) <= cap);
        arena.enemy.health = 36;

        arena.world.fighters.remove(arena.enemy);
        arena.refresh();
        Checks.check("a friend is never a target, though the target selector would pick them",
                arena.search(b -> b).findPlace().getTarget().get() == friend
                        && arena.search(b -> b.protect(friends)).findPlace() == null);
        arena.world.fighters.add(arena.enemy);

        Fighter between = arena.world.add(new Fighter(1.5, 1, 0.5));    // nearer you than the enemy
        arena.refresh();
        TargetSelector<Fighter> near = TargetSelector.from(arena.players).range(20).where(f -> f == between).build();
        PlaceOption<Fighter> first = arena.search(b -> b.maxTargets(1).protect(near)).findPlace();
        Checks.check("friends are left out before the cut to maxTargets, so the nearest enemy still counts",
                first != null && first.getTarget().get() != between);
        arena.world.fighters.remove(between);
        arena.refresh();

        CrystalSearch<Fighter> loose = arena.search(b -> b.protect(friends).thresholds(Thresholds.<Fighter>builder()
                .maxProtectedDamage(() -> 1000).build()));
        loose.findPlace();
        Checks.check("a friend the no-ray bound clears is never raycast (" + loose.getLastPlaceStats() + ")",
                loose.getLastPlaceStats().getProtectedEvaluated() == 0);
        friend.x = 60;
        arena.refresh();
        CrystalSearch<Fighter> distant = arena.search(b -> b.protect(friends).thresholds(capped));
        distant.findPlace();
        Checks.check("nor one out of every explosion's reach", distant.getLastPlaceStats().getProtectedEvaluated() == 0);
        friend.x = 3.5;
        arena.refresh();

        Crystal crystal = arena.crystal(3, 0, 1);                       // between the enemy and the friend
        arena.refresh();
        Checks.check("breaking protects them too",
                arena.search(b -> b.protect(friends)).findBreak() != null
                        && arena.search(b -> b.protect(friends).thresholds(capped)).findBreak() == null
                        && crystal != null);
    }

    private static void protectingBruteForce() {
        Random random = new Random(23);
        int agree = 0;
        int cleared = 0;
        int layouts = 20;
        for (int layout = 0; layout < layouts; layout++) {
            Arena arena = randomArena(random);
            Fighter friend = arena.world.add(new Fighter(random.nextInt(9) - 4 + 0.5, 1, random.nextInt(9) - 4 + 0.5));
            arena.refresh();
            TargetSelector<Fighter> friends = TargetSelector.from(arena.players).range(30).where(f -> f == friend).build();
            Thresholds<Fighter> limits = Thresholds.<Fighter>builder().minDamage(() -> 4)
                    .maxProtectedDamage(() -> 12).build();
            CrystalSearch<Fighter> fast = arena.search(b -> b.protect(friends).thresholds(limits));
            CrystalSearch<Fighter> slow = arena.search(b -> b.protect(friends).thresholds(limits).pruning(false));
            if (sameRanking(fast.findPlaces(3), slow.findPlaces(3))) {
                agree++;
            }
            cleared += slow.getLastPlaceStats().getProtectedEvaluated() - fast.getLastPlaceStats().getProtectedEvaluated();
        }
        Checks.checkEquals("protection by bound refuses exactly what raycasting every friend refuses, in all 20 layouts",
                layouts, agree);
        Checks.check("while raycasting friends far less (" + cleared + " estimates saved)", cleared > layouts);
    }

    private static void filters() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> plain = arena.search(b -> b);
        List<PlaceOption<Fighter>> ranked = plain.findPlaces(2);
        PlaceOption<Fighter> best = ranked.get(0);

        CrystalSearch<Fighter> none = arena.search(b -> b.filter(p -> false));
        Checks.check("a filter refusing everything finds nothing, and says so",
                none.findPlace() == null && none.getLastPlaceStats().getFiltered() > 0);

        List<Proposal<Fighter>> seen = new ArrayList<>();
        arena.search(b -> b.filter(p -> seen.add(p))).findPlace();
        boolean honest = !seen.isEmpty();
        for (Proposal<Fighter> p : seen) {
            honest &= p.getTarget().get() == arena.enemy && p.getDamage() > 0 && p.getTrigger() == Trigger.MINIMUM
                    && p.getScore() == p.getDamage() && p.getProtected().isEmpty() && p.getMostProtectedDamage() == 0;
        }
        Checks.check("it sees the target, the damage to them and to you, and why (" + seen.size() + " asked)", honest);
        Checks.check("only about options that would be chosen: a handful, not every spot",
                seen.size() < plain.getLastPlaceStats().getViable());

        CrystalSearch<Fighter> notThere = arena.search(b -> b.filter(p -> !p.getOrigin().equals(best.getOrigin())));
        PlaceOption<Fighter> second = notThere.findPlace();
        Checks.check("refusing the best place gives the next best",
                second != null && second.getX() == ranked.get(1).getX() && second.getZ() == ranked.get(1).getZ()
                        && same(second, ranked.get(1)));
        Checks.check("and the best few all pass it", !containsOrigin(notThere.findPlaces(3), best.getOrigin()));

        boolean[] askedSecond = { false };
        arena.search(b -> b.filter(p -> false).filter(p -> askedSecond[0] = true)).findPlace();
        Checks.check("several filters are all required, in order: a refusal stops the rest", !askedSecond[0]);

        Fighter friend = arena.world.add(new Fighter(3.5, 1, 2.5));
        arena.refresh();
        Tracked<Fighter> friendly = arena.players.get(friend);
        TargetSelector<Fighter> friends = TargetSelector.from(arena.players).range(20).where(f -> f == friend).build();
        CrystalSearch<Fighter> lazy = arena.search(b -> b.protect(friends).filter(p -> p.getDamage() > 0));
        lazy.findPlace();
        Checks.check("damage to friends is not worked out unless a filter asks",
                lazy.getLastPlaceStats().getProtectedEvaluated() == 0);

        List<Harm<Fighter>> harms = new ArrayList<>();
        Vec3[] where = new Vec3[1];
        CrystalSearch<Fighter> asking = arena.search(b -> b.protect(friends).filter(p -> {
            harms.clear();
            harms.addAll(p.getProtected());
            where[0] = p.getOrigin();
            return true;
        }));
        PlaceOption<Fighter> chosen = asking.findPlace();
        double expected = arena.rules.damage(chosen.getX(), chosen.getY(), chosen.getZ(), friendly);
        Checks.check("asked, it is exact: what the explosion does to each friend (" + harms + ")",
                where[0].equals(chosen.getOrigin()) && harms.size() == 1 && harms.get(0).getEntity().get() == friend
                        && harms.get(0).getDamage() == expected && harms.get(0).getPool() == friend.health
                        && asking.getLastPlaceStats().getProtectedEvaluated() > 0);

        CrystalSearch<Fighter> gentle = arena.search(b -> b.protect(friends)
                .filter(p -> p.getMostProtectedDamage() < p.getDamage() * 0.8));
        PlaceOption<Fighter> kind = gentle.findPlace();
        Checks.check("so a filter can weigh friends against the target (" + kind + " over " + chosen + ")", kind != null
                && arena.rules.damage(kind.getX(), kind.getY(), kind.getZ(), friendly) < kind.getDamage() * 0.8
                && expected >= chosen.getDamage() * 0.8);

        friend.x = 60;
        arena.refresh();
        List<Harm<Fighter>> far = new ArrayList<>();
        arena.search(b -> b.protect(TargetSelector.from(arena.players).where(f -> f == friend).build())
                .filter(p -> far.addAll(p.getProtected()) || true)).findPlace();
        Checks.check("a friend out of the explosion's reach is not among those it would hurt", far.isEmpty());
        friend.x = 3.5;
        arena.world.fighters.remove(friend);

        Fighter other = arena.world.add(new Fighter(-5.5, 1, 0.5));      // a second enemy, further off the other side
        arena.refresh();
        List<PlaceOption<Fighter>> unfiltered = arena.search(b -> b).findPlaces(6);
        List<PlaceOption<Fighter>> passed = arena.search(b -> b.filter(p -> true)).findPlaces(6);
        boolean sameTargets = unfiltered.size() == passed.size();
        for (int i = 0; sameTargets && i < passed.size(); i++) {
            sameTargets = passed.get(i).getTarget().get() == unfiltered.get(i).getTarget().get();
        }
        Checks.check("a filter that accepts everything changes nothing, with two enemies to choose between",
                sameRanking(unfiltered, passed) && sameTargets);
        boolean bestTarget = !passed.isEmpty();
        for (PlaceOption<Fighter> option : passed) {
            for (Tracked<Fighter> enemy : arena.players.getAll()) {
                bestTarget &= option.getDamage() >= arena.rules.damage(option.getX(), option.getY(), option.getZ(), enemy);
            }
        }
        Checks.check("and each place is offered against the enemy it hurts most", bestTarget);
        Checks.check("so the best place is still the first enemy's best, though the second is weighed after",
                passed.get(0).getTarget().get() == arena.enemy && same(passed.get(0), best));
        arena.world.fighters.remove(other);
        arena.refresh();

        arena.crystal(2, 0, 0);
        arena.refresh();
        Checks.check("filters judge breaking too",
                arena.search(b -> b).findBreak() != null && arena.search(b -> b.filter(p -> false)).findBreak() == null);

        Random random = new Random(31);
        int agree = 0;
        for (int layout = 0; layout < 15; layout++) {
            Arena world = randomArena(random);
            OptionFilter<Fighter> oddOnly = p -> Math.floor(p.getOrigin().getX()) % 2 != 0;
            Thresholds<Fighter> limits = Thresholds.<Fighter>builder().minDamage(() -> 4).build();
            List<PlaceOption<Fighter>> a = world.search(b -> b.thresholds(limits).filter(oddOnly)).findPlaces(3);
            List<PlaceOption<Fighter>> c = world.search(b -> b.thresholds(limits).filter(oddOnly).pruning(false)).findPlaces(3);
            if (sameRanking(a, c)) {
                agree++;
            }
        }
        Checks.checkEquals("branch and bound stays exact under a filter, in all 15 layouts", 15, agree);
    }

    private static boolean same(PlaceOption<Fighter> a, PlaceOption<Fighter> b) {
        return a.getScore() == b.getScore() && a.getSelfDamage() == b.getSelfDamage() && a.getDamage() == b.getDamage();
    }

    /** Equal ranks in the same order; spots that tie may come in either order, so only the numbers count. */
    private static boolean sameRanking(List<PlaceOption<Fighter>> a, List<PlaceOption<Fighter>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getRank() != b.get(i).getRank() || a.get(i).getSelfDamage() != b.get(i).getSelfDamage()) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsOrigin(List<PlaceOption<Fighter>> options, Vec3 origin) {
        for (PlaceOption<Fighter> option : options) {
            if (option.getOrigin().equals(origin)) {
                return true;
            }
        }
        return false;
    }

    /** The arena with the enemy somewhere random and a few walls and slabs about. */
    private static Arena randomArena(Random random) {
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
        return arena;
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

    // ---------------------------------------------------------------- aiming

    private static void aiming() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> unaimed = arena.search(b -> b);
        PlaceOption<Fighter> free = unaimed.findPlace();
        Checks.check("every place says where to look: the centre of the base's top face",
                free.getAim().equals(Vec3.of(free.getX() + 0.5, free.getY() + 1, free.getZ() + 0.5)));
        Checks.check("and without an aim cost, aiming costs nothing and the rank is the score",
                free.getAimCost() == 0d && free.getRank() == free.getScore());

        // Turning anywhere east of you is out of the question: only the west is left.
        AimCost westOnly = (eye, at) -> at.getX() < eye.getX() ? 0d : Double.POSITIVE_INFINITY;
        CrystalSearch<Fighter> west = arena.search(b -> b.placeAimCost(westOnly));
        PlaceOption<Fighter> behind = west.findPlace();
        Checks.check("a spot you cannot turn to is never chosen, however strong (" + behind + " vs " + free + ")",
                behind != null && behind.getAim().getX() < arena.eye().getX() && behind.getDamage() < free.getDamage());
        SearchStats stats = west.getLastPlaceStats();
        Checks.check("and is dropped before anything is bounded or raycast (" + stats + ")",
                stats.getUnaimable() > 0 && stats.getBounds() < unaimed.getLastPlaceStats().getBounds());
        Checks.check("being the best of what is left",
                same(behind, arena.search(b -> b.placeAimCost(westOnly).pruning(false)).findPlace()));
        Checks.check("and when you can turn nowhere, nothing is found",
                arena.search(b -> b.placeAimCost((eye, at) -> Double.NaN)).findPlace() == null);

        // A trade: turning anywhere but one weaker spot costs just under, then just over, the best's lead on it.
        List<PlaceOption<Fighter>> ten = arena.search(b -> b).findPlaces(10);
        PlaceOption<Fighter> weaker = null;
        for (PlaceOption<Fighter> option : ten) {
            if (weaker == null && option.getScore() < ten.get(0).getScore()) {
                weaker = option;
            }
        }
        double lead = ten.get(0).getScore() - weaker.getScore();
        Vec3 facing = weaker.getAim();
        PlaceOption<Fighter> worthIt = arena.search(b -> b.placeAimCost(
                (eye, at) -> at.equals(facing) ? 0d : lead / 2)).findPlace();
        Checks.check("turning that costs less than the best's lead still leaves the best first (lead " + lead + ")",
                lead > 0 && worthIt.getScore() == ten.get(0).getScore() && worthIt.getAimCost() == lead / 2
                        && worthIt.getRank() == worthIt.getScore() - lead / 2);
        PlaceOption<Fighter> notWorthIt = arena.search(b -> b.placeAimCost(
                (eye, at) -> at.equals(facing) ? 0d : lead * 2)).findPlace();
        Checks.check("turning that costs more leaves the spot you face",
                notWorthIt.getAim().equals(facing) && notWorthIt.getRank() == weaker.getScore());

        // A bonus: the tenth best, already being turned toward, beats them all.
        PlaceOption<Fighter> tenth = ten.get(9);
        double bonus = ten.get(0).getScore() - tenth.getScore() + 1;
        CrystalSearch<Fighter> sticky = arena.search(b -> b.placeAimCost(
                (eye, at) -> at.equals(tenth.getAim()) ? -bonus : 0d));
        PlaceOption<Fighter> kept = sticky.findPlace();
        Checks.check("a negative cost is a bonus: the spot you are turning to stays first",
                kept.getAim().equals(tenth.getAim()) && kept.getRank() == tenth.getScore() + bonus);
        List<PlaceOption<Fighter>> ranked = sticky.findPlaces(3);
        Checks.check("and the best few are ranked by rank, not score (" + ranked + ")",
                ranked.get(0).getScore() < ranked.get(1).getScore() && !ranked.get(1).beats(ranked.get(0))
                        && !ranked.get(2).beats(ranked.get(1)));

        // Filters see where to look, and what it costs.
        List<Vec3> seen = new ArrayList<>();
        PlaceOption<Fighter> filtered = arena.search(b -> b.placeAimCost((eye, at) -> 1).filter(p -> {
            seen.add(p.getAim());
            return p.getAimCost() == 1 && p.getRank() == p.getScore() - 1;
        })).findPlace();
        Checks.check("a filter sees the aim and its cost",
                filtered != null && seen.contains(filtered.getAim()) && !seen.contains(null));
    }

    private static void aimingBruteForce() {
        Random random = new Random(23);
        int agree = 0;
        int pruned = 0;
        int unaimable = 0;
        int layouts = 40;
        for (int layout = 0; layout < layouts; layout++) {
            Arena arena = randomArena(random);
            Vec2 looking = Vec2.rotation(random.nextFloat() * 360 - 180, random.nextFloat() * 90 - 10);
            // Steep enough, some layouts, that a strong spot behind a wall costs more to turn to than it leads by.
            double perDegree = layout % 2 == 0 ? 0.02 + random.nextDouble() * 0.2 : 0.2 + random.nextDouble() * 0.8;
            double maxDegrees = 60 + random.nextDouble() * 90;
            AimCost angle = AimCost.angle(() -> looking, () -> perDegree, () -> maxDegrees);
            Map<Vec3, Double> bonuses = new HashMap<>();           // a few spots you are already turning toward
            for (int i = 0; i < 5; i++) {
                bonuses.put(Vec3.of(random.nextInt(11) - 5 + 0.5, 1, random.nextInt(11) - 5 + 0.5), random.nextDouble() * 15);
            }
            AimCost aim = (eye, at) -> angle.cost(eye, at) - bonuses.getOrDefault(at, 0d);
            Thresholds<Fighter> limits = Thresholds.<Fighter>builder().minDamage(() -> 4).maxSelfDamage(() -> 30).build();
            CrystalSearch<Fighter> fast = arena.search(b -> b.thresholds(limits).placeAimCost(aim));
            CrystalSearch<Fighter> slow = arena.search(b -> b.thresholds(limits).placeAimCost(aim).pruning(false));
            List<PlaceOption<Fighter>> a = fast.findPlaces(4);
            List<PlaceOption<Fighter>> everything = slow.findPlaces(10000);
            List<PlaceOption<Fighter>> b = everything.subList(0, Math.min(4, everything.size()));
            List<PlaceOption<Fighter>> one = fast.findPlaces(1);
            if (sameRanking(a, b) && sameRanking(one, everything.subList(0, Math.min(1, everything.size())))) {
                agree++;
            }
            pruned += fast.getLastPlaceStats().getPruned();
            unaimable += fast.getLastPlaceStats().getUnaimable();
        }
        Checks.checkEquals("with aim costs and bonuses, the best one and best four by branch and bound are those of "
                + "every option, in all 40 layouts", layouts, agree);
        Checks.check("while still skipping spots (" + pruned + " pruned, " + unaimable + " out of turning range)",
                pruned > layouts * 3 && unaimable > 0);

        AimCost ahead = AimCost.angle(() -> Vec2.rotation(0, 0), () -> 2, () -> 90);
        Vec3 origin = Vec3.of(0, 0, 0);
        Checks.check("an angle cost is free straight ahead",
                ahead.cost(origin, Vec3.of(0, 0, 5)) < 1e-4);
        Checks.check("costs per degree off it", Math.abs(ahead.cost(origin, Vec3.of(5, 0, 5)) - 90) < 1e-3);
        Checks.check("and is out of the question behind you",
                ahead.cost(origin, Vec3.of(0, 0, -5)) == Double.POSITIVE_INFINITY);
    }

    private static void aimingToBreak() {
        Arena arena = new Arena();
        Crystal strong = arena.crystal(2, 0, 0);
        Crystal weak = arena.crystal(4, 0, 1);
        arena.refresh();
        BreakOption<Fighter> free = arena.search(b -> b).findBreak();
        Checks.check("a crystal to break is aimed at its centre",
                free.getCrystal().get() == strong && free.getAim().equals(free.getCrystal().getCenter()));

        Vec3 strongCentre = arena.crystals.get(strong).getCenter();
        AimCost notThatOne = (eye, at) -> at.equals(strongCentre) ? Double.POSITIVE_INFINITY : 0d;
        CrystalSearch<Fighter> search = arena.search(b -> b.breakAimCost(notThatOne));
        BreakOption<Fighter> other = search.findBreak();
        Checks.check("one you cannot turn to is left for one you can",
                other != null && other.getCrystal().get() == weak && search.getLastBreakStats().getUnaimable() == 1);
        Checks.check("the place aim cost never touches breaking, nor the reverse",
                arena.search(b -> b.placeAimCost(notThatOne)).findBreak().getCrystal().get() == strong
                        && arena.search(b -> b.breakAimCost((eye, at) -> Double.NaN)).findPlace() != null);

        Crystal spawned = new Crystal(2.5, 1, 1.5);
        CrystalSearch<Fighter> blind = arena.search(b -> b.breakAimCost((eye, at) -> Double.POSITIVE_INFINITY));
        Checks.check("and one from its spawn packet you cannot turn to is not broken on spawn",
                blind.spawned(spawned) == null && blind.getLastBreakStats().getUnaimable() == 1);
    }

    // ----------------------------------------------------------- lookahead

    private static void lookingAhead() {
        Arena arena = new Arena();
        PlaceOption<Fighter> now = arena.search(b -> b).findPlace();

        // A lookahead that says the enemy will be three blocks further east, and everyone else where they are.
        int[] asked = { 0 };
        Lookahead<Fighter> east = (entity, ticks) -> {
            asked[0]++;
            return entity.get() == arena.enemy ? entity.projected(entity.getPosition().add(3d, 0d, 0d)) : entity;
        };
        PlaceOption<Fighter> idle = arena.search(b -> b.lookahead(east)).findPlace();
        Checks.check("a lookahead with no delay is never asked, and changes nothing",
                asked[0] == 0 && same(idle, now));

        CrystalSearch<Fighter> ahead = arena.search(b -> b.lookahead(east).placeDelay(() -> 4));
        PlaceOption<Fighter> there = ahead.findPlace();
        Tracked<Fighter> enemy = arena.players.get(arena.enemy);
        Tracked<Fighter> projected = enemy.projected(enemy.getPosition().add(3d, 0d, 0d));
        Checks.check("with a delay, damage is scored where the enemy will be (" + there + " vs " + now + ")",
                there.getDamage() == arena.rules.damage(there.getX(), there.getY(), there.getZ(), projected)
                        && there.getDamage() < now.getDamage());
        Checks.check("against the enemy itself, not a stand-in",
                there.getTarget() == enemy && there.getTarget().isTracked());
        Checks.check("asked once for each entity a search weighs", asked[0] > 0 && asked[0] <= 3);

        // You, looked ahead to somewhere safe: the same spots hurt you less.
        Lookahead<Fighter> retreat = (entity, ticks) -> entity.get() == arena.self
                ? entity.projected(entity.getPosition().add(-20d, 0d, 0d)) : entity;
        PlaceOption<Fighter> brave = arena.search(b -> b.lookahead(retreat).placeDelay(() -> 4)).findPlace();
        Checks.check("your own damage is scored where you will be (" + brave.getSelfDamage() + " vs "
                + now.getSelfDamage() + ")", brave.getSelfDamage() < now.getSelfDamage());

        // A friend walking into the blast is protected where they will be, not where they are.
        Fighter friend = arena.world.add(new Fighter(-15.5, 1, 0.5));
        arena.refresh();
        TargetSelector<Fighter> friends = TargetSelector.from(arena.players).range(40).where(f -> f == friend).build();
        Lookahead<Fighter> arriving = (entity, ticks) -> entity.get() == friend
                ? entity.projected(Vec3.of(3.5d, 1d, 2.5d)) : entity;
        Thresholds<Fighter> gentle = Thresholds.<Fighter>builder().maxProtectedDamage(() -> 5).build();
        PlaceOption<Fighter> unaware = arena.search(b -> b.protect(friends).thresholds(gentle)).findPlace();
        PlaceOption<Fighter> aware = arena.search(b -> b.protect(friends).thresholds(gentle)
                .lookahead(arriving).placeDelay(() -> 4)).findPlace();
        Checks.check("a friend about to walk into the blast is spared: next to the enemy, nothing is worth it ("
                + aware + " vs " + unaware + ")", unaware != null && aware == null);

        // Breaking looks ahead by its own delay.
        Arena crystals = new Arena();
        crystals.crystal(-2, 0, 0);
        crystals.crystal(4, 0, 0);
        crystals.refresh();
        // The enemy, by the crystal at 4 now, will be past you by the one at -2.
        Lookahead<Fighter> away = (entity, ticks) -> entity.get() == crystals.enemy
                ? entity.projected(entity.getPosition().add(-4.5d, 0d, 0d)) : entity;
        BreakOption<Fighter> nowBreak = crystals.search(b -> b.lookahead(away)).findBreak();
        BreakOption<Fighter> laterBreak = crystals.search(b -> b.lookahead(away).useDelay(() -> 3)).findBreak();
        Checks.check("breaking looks ahead by the use delay: the crystal by where they will be is chosen ("
                        + nowBreak + " now, " + laterBreak + " ahead)",
                nowBreak.getCrystal().getX() > 4d && laterBreak.getCrystal().getX() < 0d);
    }

    private static void lookingAheadWithPrediction() {
        Arena arena = new Arena();
        dev.px.core.movement.simulation.SimulationService simulation =
                new dev.px.core.movement.simulation.SimulationService(new RecordingLogger(),
                        new CoreEventBus(new RecordingLogger()));
        simulation.setCollisionSpace(region -> {
            List<dev.px.core.math.Box> boxes = new ArrayList<>();
            for (int x = (int) Math.floor(region.getMinX()); x <= (int) Math.floor(region.getMaxX()); x++) {
                for (int y = (int) Math.floor(region.getMinY()); y <= (int) Math.floor(region.getMaxY()); y++) {
                    for (int z = (int) Math.floor(region.getMinZ()); z <= (int) Math.floor(region.getMaxZ()); z++) {
                        if (!arena.blocks.isClear(x, y, z)) {
                            boxes.add(dev.px.core.math.Box.block(x, y, z));
                        }
                    }
                }
            }
            return boxes;
        });
        dev.px.core.movement.prediction.PredictionService prediction =
                new dev.px.core.movement.prediction.PredictionService(simulation);
        // The enemy glides east at a steady 0.15 a tick for a second and a half: a strafe, as far as the rules go.
        for (int tick = 0; tick < 30; tick++) {
            arena.enemy.x += 0.15d;
            arena.refresh();
        }
        Tracked<Fighter> enemy = arena.players.get(arena.enemy);
        Tracked<? extends Fighter> soon = Lookahead.<Fighter>predicted(prediction).at(enemy, 6);
        Checks.check("Core's prediction can be the lookahead: six ticks on, it is 0.9 further east ("
                        + soon.getPosition() + " from " + enemy.getPosition() + ")",
                Math.abs(soon.getX() - (enemy.getX() + 0.9d)) < 1e-6 && !soon.isTracked());

        // Somebody it cannot make sense of stays where they are.
        Random random = new Random(3);
        for (int tick = 0; tick < 30; tick++) {
            arena.enemy.x += random.nextDouble() * 2 - 1;
            arena.enemy.z += random.nextDouble() * 2 - 1;
            arena.refresh();
        }
        enemy = arena.players.get(arena.enemy);
        Checks.check("while an unreliable prediction leaves them where they are",
                Lookahead.<Fighter>predicted(prediction).at(enemy, 6) == enemy
                        && Lookahead.<Fighter>predicted(prediction, true).at(enemy, 6) != enemy);
        Checks.check("and a straight line is a straight line", Math.abs(Lookahead.<Fighter>extrapolated().at(enemy, 4)
                .getX() - enemy.extrapolate(4).getX()) < 1e-12);
    }

    // ------------------------------------------------- place, then break

    private static void pendingPlacements() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> search = arena.search(b -> b.pendingTicks(() -> 3));
        PlaceOption<Fighter> first = search.findPlace();
        search.placed(first);
        PlaceOption<Fighter> second = search.findPlace();
        Checks.check("a crystal placed and not yet shown keeps the next placement off its base (" + first + ", then "
                        + second + ")",
                second != null && !(second.getX() == first.getX() && second.getZ() == first.getZ())
                        && search.getLastPlaceStats().getPending() > 0);
        Checks.check("and off every base its crystal would stand in the way of",
                Math.abs(second.getX() - first.getX()) >= 2 || Math.abs(second.getZ() - first.getZ()) >= 2);
        Checks.checkEquals("one placement is waiting", 1, search.getLog().getPendingCount());

        for (int i = 0; i < 3; i++) {
            search.tick();
        }
        PlaceOption<Fighter> again = search.findPlace();
        Checks.check("until its wait runs out: it never showed up, so the base is free again",
                search.getLog().getPendingCount() == 0 && again.getX() == first.getX() && again.getZ() == first.getZ());

        CrystalSearch<Fighter> other = arena.search(b -> b.log(search.getLog()));
        search.placed(first);
        PlaceOption<Fighter> shared = other.findPlace();
        Checks.check("searches sharing a log share what is waiting",
                !(shared.getX() == first.getX() && shared.getZ() == first.getZ()));
    }

    private static void planning() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> search = arena.search(b -> b);
        List<PlaceOption<Fighter>> plan = search.planPlaces(3);
        PlaceOption<Fighter> best = search.findPlace();
        boolean apart = true;
        for (int i = 0; i < plan.size(); i++) {
            for (int j = 0; j < plan.size(); j++) {
                if (i != j) {
                    PlaceOption<Fighter> a = plan.get(i);
                    PlaceOption<Fighter> b = plan.get(j);
                    dev.px.core.math.Box crystal = arena.rules.box(a.getX(), a.getY(), a.getZ());
                    dev.px.core.math.Box column = dev.px.core.math.Box.of(b.getX(), b.getY() + 1, b.getZ(),
                            b.getX() + 1, b.getY() + 3, b.getZ() + 1);
                    apart &= !crystal.intersects(column);
                }
            }
        }
        Checks.check("a plan of three places, none in another's way (" + plan + ")",
                plan.size() == 3 && apart);
        Checks.check("the first is the best place there is", same(plan.get(0), best)
                && plan.get(0).getX() == best.getX() && plan.get(0).getZ() == best.getZ());
        Checks.check("each after it the best of what is left, so no better than the one before",
                !plan.get(1).beats(plan.get(0)) && !plan.get(2).beats(plan.get(1)));
        Checks.check("planning leaves nothing waiting: only placing does", search.getLog().getPendingCount() == 0);
        Thresholds<Fighter> strict = Thresholds.<Fighter>builder().minDamage(() -> 1000).build();
        Checks.check("and where nothing is worth it, the plan is empty",
                arena.search(b -> b.thresholds(strict)).planPlaces(2).isEmpty());
    }

    private static void ownCrystals() {
        Arena arena = new Arena();
        CrystalSearch<Fighter> search = arena.search(b -> b);
        PlaceOption<Fighter> spot = search.findPlace();
        search.placed(spot);
        Crystal mine = arena.crystal(spot.getX(), spot.getY(), spot.getZ());
        Crystal theirs = arena.crystal(-2, 0, 3);
        arena.refresh();
        BreakOption<Fighter> hit = search.findBreak();
        Checks.check("a crystal that shows up where you placed one is yours (" + hit + ")",
                hit != null && hit.getCrystal().get() == mine && hit.isOwn());
        Checks.checkEquals("and is no longer waited for", 0, search.getLog().getPendingCount());
        Checks.check("one that someone else placed is not",
                !search.getLog().isOwn(theirs) && search.getLog().isOwn(mine));

        Arena spawning = new Arena();
        CrystalSearch<Fighter> instant = spawning.search(b -> b);
        PlaceOption<Fighter> at = instant.findPlace();
        instant.placed(at);
        Crystal fresh = new Crystal(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        BreakOption<Fighter> now = instant.spawned(fresh);
        Checks.check("from its spawn packet too, before the world lists it", now != null && now.isOwn());
        Checks.check("and a place to put one is never yours", !instant.findPlace().isOwn());
    }

    private static void listening() {
        Arena arena = new Arena();
        List<Judgement<Fighter>> heard = new ArrayList<>();
        CrystalSearch<Fighter> search = arena.search(b -> b.listener(heard::add));
        PlaceOption<Fighter> best = search.findPlace();
        boolean chosenHeard = false;
        for (Judgement<Fighter> judgement : heard) {
            chosenHeard |= judgement.getVerdict() == Judgement.Verdict.PASSED && judgement.isPlacing()
                    && judgement.getDamage() == best.getDamage();
        }
        Checks.checkEquals("a listener hears every estimate the search paid for", search.getLastPlaceStats().getEvaluated(),
                heard.size());
        Checks.check("the option it chose among them, passed (" + heard.size() + " heard)", chosenHeard);

        // After a stronger crystal this tick, a weaker one is estimated and does nothing more: too weak.
        Arena tick = new Arena();
        tick.crystal(2, 0, 0);
        Crystal weak = tick.crystal(4, 0, 1);
        tick.refresh();
        heard.clear();
        CrystalSearch<Fighter> breaking = tick.search(b -> b.listener(heard::add));
        breaking.attacked(breaking.findBreak().getCrystal());
        heard.clear();
        BreakOption<Fighter> none = breaking.findBreak();
        boolean allWeak = !heard.isEmpty() && none == null;
        for (Judgement<Fighter> judgement : heard) {
            allWeak &= judgement.getVerdict() == Judgement.Verdict.TOO_WEAK && Double.isNaN(judgement.getSelfDamage());
        }
        Checks.check("and why nothing was chosen: too weak, with no need to work out your damage (" + heard + ")",
                allWeak && weak != null);

        Checks.check("each refusal by its own verdict: the self cap",
                heardVerdict(arena, b -> b.thresholds(Thresholds.<Fighter>builder().maxSelfDamage(() -> 45).build()),
                        Judgement.Verdict.SELF_CAP));
        arena.self.health = 6;
        Checks.check("anti-suicide", heardVerdict(arena,
                b -> b.thresholds(Thresholds.<Fighter>builder().antiSuicide(() -> 1).build()), Judgement.Verdict.SUICIDAL));
        arena.self.health = 36;
        Checks.check("your own filters", heardVerdict(arena, b -> b.filter(p -> false), Judgement.Verdict.FILTERED));
        Fighter friend = arena.world.add(new Fighter(3.5, 1, 2.5));
        arena.refresh();
        TargetSelector<Fighter> friends = TargetSelector.from(arena.players).range(20).where(f -> f == friend).build();
        Checks.check("and protecting a friend", heardVerdict(arena, b -> b.protect(friends)
                .thresholds(Thresholds.<Fighter>builder().maxProtectedDamage(() -> 1).build()), Judgement.Verdict.ENDANGERS));

        Arena crystals = new Arena();
        crystals.crystal(2, 0, 0);
        crystals.refresh();
        List<Judgement<Fighter>> breaks = new ArrayList<>();
        crystals.search(b -> b.listener(breaks::add)).findBreak();
        Checks.check("breaking is heard too, as not placing", !breaks.isEmpty() && !breaks.get(0).isPlacing());
    }

    private static void strictPlacement() {
        Arena arena = new Arena();
        PlaceOption<Fighter> loose = arena.search(b -> b).findPlace();
        Checks.check("without click rules, an option has no click", loose.getClick() == null);

        Clicks strict = Clicks.builder().support(arena.blocks::isBase).replaceable(arena.blocks::isClear)
                .faces(FaceRule.facingEye()).build();
        CrystalSearch<Fighter> search = arena.search(b -> b.clicks(strict));
        PlaceOption<Fighter> spot = search.findPlace();
        dev.px.core.math.Vec3 eye = arena.eye();
        Checks.check("with them, it says which face of the base to click, one turned toward you (" + spot.getClick() + ")",
                spot.getClick() != null && spot.getClick().getBlock().equals(dev.px.core.math.Vec3i.of(spot.getX(),
                        spot.getY(), spot.getZ()))
                        && FaceRule.facingEye().allows(eye, spot.getClick().getBlock(), spot.getClick().getFace(),
                        spot.getClick().getHit()));
        Checks.check("and the aim is its hit point", spot.getAim().equals(spot.getClick().getHit()));
        Checks.check("on the same base the loose search chose: any face puts a crystal on top",
                spot.getX() == loose.getX() && spot.getZ() == loose.getZ() && spot.getDamage() == loose.getDamage());

        // A base above your eyes: its top cannot be seen, so a server that wants the top refuses it.
        arena.blocks.base(1, 3, 1);
        FaceRule topOnly = FaceRule.facingEye().and((e, block, face, hit) -> face == dev.px.core.math.Direction.UP);
        CrystalSearch<Fighter> tops = arena.search(b -> b.clicks(Clicks.builder().support(arena.blocks::isBase)
                .replaceable(arena.blocks::isClear).faces(topOnly).build()));
        tops.findPlace();
        CrystalSearch<Fighter> sides = arena.search(b -> b.clicks(strict));
        sides.findPlace();
        Checks.check("a base whose top is above your eyes is offered by a server that takes any face you can see, "
                        + "and not by one that wants the top (" + tops.getLastPlaceStats().getUnclickable() + " refused)",
                tops.getLastPlaceStats().getUnclickable() == 1 && sides.getLastPlaceStats().getUnclickable() == 0);

        List<PlaceOption<Fighter>> plan = search.planPlaces(2);
        Checks.check("planned places carry their clicks too", plan.size() == 2 && plan.get(1).getClick() != null);
    }

    private static boolean heardVerdict(Arena arena,
                                        Function<CrystalSearch.Builder<Fighter>, CrystalSearch.Builder<Fighter>> tweak,
                                        Judgement.Verdict verdict) {
        List<Judgement<Fighter>> heard = new ArrayList<>();
        arena.search(b -> tweak.apply(b).listener(heard::add)).findPlace();
        for (Judgement<Fighter> judgement : heard) {
            if (judgement.getVerdict() == verdict) {
                return true;
            }
        }
        return false;
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
