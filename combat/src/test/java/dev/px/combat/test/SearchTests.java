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
import dev.px.combat.search.option.Harm;
import dev.px.combat.search.option.PlaceOption;
import dev.px.combat.search.option.Proposal;
import dev.px.combat.search.option.Trigger;
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
 * threshold, protecting friends, your own filters, reach, timing, and branch and
 * bound checked against a brute-force search of the same world.
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

    /** Equal scores in the same order; spots that tie may come in either order, so only the numbers count. */
    private static boolean sameRanking(List<PlaceOption<Fighter>> a, List<PlaceOption<Fighter>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getScore() != b.get(i).getScore() || a.get(i).getSelfDamage() != b.get(i).getSelfDamage()) {
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
