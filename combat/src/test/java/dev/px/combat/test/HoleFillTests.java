package dev.px.combat.test;

import dev.px.combat.hole.FillOption;
import dev.px.combat.hole.HoleFill;
import dev.px.combat.hole.HoleFinder;
import dev.px.combat.hole.HoleRules;
import dev.px.combat.place.Clicks;
import dev.px.combat.place.FaceRule;
import dev.px.combat.search.rule.Reach;
import dev.px.core.math.Vec3;
import dev.px.core.math.Vec3i;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.movement.simulation.Simulation;
import dev.px.core.target.TargetSelector;
import dev.px.core.target.TargetService;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.GridCollisionSpace;
import dev.px.core.test.harness.MovementRig.Body;
import dev.px.core.test.harness.MovementRig.Mover;
import dev.px.core.test.harness.MovementRig;
import dev.px.core.world.BlockShape;
import dev.px.core.world.BlockView;
import dev.px.core.world.Obstructions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Auto-fill: which holes to fill before an enemy gets into them, in reach of
 * you, with your friends', your own and your escape holes kept.
 *
 * <p>The world is an obsidian ground with one-block pits cut into it. You stand
 * at the origin; enemies and friends are scripted bodies heading for a hole and
 * stopping in it, as a player going for one does.
 */
public final class HoleFillTests {

    private static final Script STILL = tick -> MovementInput.none(0f);

    private HoleFillTests() {
    }

    public static void run() {
        Checks.section("Auto-fill");

        filling();
        ranking();
        keeping();
        timing();
        pending();
        yourControl();
        lifecycle();
    }

    // ---------------------------------------------------------------- filling

    private static void filling() {
        Field field = new Field(4, 0, -4, 0, 0, 6);         // the last just past your reach
        Body enemy = field.enemy(Vec3.of(10.5d, 1d, 0.5d), field.towardHole(4, 0));
        field.run(12);
        HoleFill<Body> search = field.search(b -> b.minChance(() -> 0.3d));
        List<FillOption<Body>> fills = search.findFills(3);
        FillOption<Body> fill = fills.isEmpty() ? null : fills.get(0);
        Checks.check("a hole an enemy is heading for, in your reach, is offered to fill (" + fills + ", "
                        + search.getLastStats() + ")",
                fills.size() == 1 && fill.getHole().getCells().contains(Vec3i.of(4, 0, 0))
                        && fill.getEnemy().get() == enemy);
        Checks.check("with the cells to fill, how likely they are to get in, and when",
                fill.getCells().equals(fill.getHole().getCells()) && fill.getChance() >= 0.3d
                        && fill.getEarliestPossible() > 0 && fill.getArrivalTick() >= fill.getEarliestPossible());
        Checks.check("and your fill lands first: safe, with ticks to spare",
                fill.getTiming() == FillOption.Timing.SAFE && fill.getSlack() > 0);
        Checks.check("the hole nobody is heading for is not offered, nor the one just past your reach ("
                + search.getLastStats() + ")", search.getLastStats().getUnlikely() + search.getLastStats().getUnthreatened() >= 1
                && search.getLastStats().getFound() > search.getLastStats().getInReach());
        Checks.check("findFill is the best of them", search.findFill().getHole().equals(fill.getHole()));

        Checks.check("an enemy already in a hole is too late to fill it", entered());

        Field doubled = new Field(4, 0, 5, 0);
        doubled.enemy(Vec3.of(9.5d, 1d, 0.5d), doubled.towardHole(5, 0));
        doubled.run(12);
        FillOption<Body> two = doubled.search(b -> b.minChance(() -> 0.3d)).findFill();
        Checks.check("a double hole is filled whole, the cell the enemy comes to first first (" + two + ")",
                two != null && two.getCells().equals(java.util.Arrays.asList(Vec3i.of(5, 0, 0), Vec3i.of(4, 0, 0))));
    }

    private static boolean entered() {
        Field field = new Field(4, 0);
        field.enemy(Vec3.of(4.5d, 0d, 0.5d), STILL);
        field.run(10);
        HoleFill<Body> search = field.search(b -> b);
        return search.findFills(2).isEmpty() && search.getLastStats().getEntered() == 1;
    }

    private static void ranking() {
        Field field = new Field(4, 0, 0, 4, -4, 0);
        Body near = field.enemy(Vec3.of(8.5d, 1d, 0.5d), field.towardHole(4, 0));
        Body far = field.enemy(Vec3.of(0.5d, 1d, 10.5d), field.towardHole(0, 4));
        field.run(12);
        HoleFill<Body> search = field.search(b -> b.minChance(() -> 0.3d));
        List<FillOption<Body>> two = search.findFills(2);
        Checks.check("the best two holes to fill, the one an enemy would be in soonest first (" + two + ")",
                two.size() == 2 && two.get(0).getEnemy().get() == near && two.get(1).getEnemy().get() == far
                        && two.get(0).getArrivalTick() <= two.get(1).getArrivalTick());
        Checks.check("asking for one gives the first", search.findFills(1).size() == 1
                && search.findFills(1).get(0).getHole().equals(two.get(0).getHole()));
    }

    // ---------------------------------------------------------------- keeping

    private static void keeping() {
        // An escape hole beside you, an enemy heading for it.
        Field near = new Field(2, 0);
        near.enemy(Vec3.of(8.5d, 1d, 0.5d), near.towardHole(2, 0));
        near.run(14);
        HoleFill<Body> anyHole = near.search(b -> b.minChance(() -> 0.3d));
        HoleFill<Body> keepNear = near.search(b -> b.minChance(() -> 0.3d).escapeRadius(() -> 3d));
        Checks.check("with no escape radius, a hole beside you is filled like any other", anyHole.findFills(1).size() == 1);
        boolean kept = keepNear.findFills(1).isEmpty();
        Checks.check("with one, it is kept for you to escape into (" + keepNear.getLastStats() + ")",
                kept && keepNear.getLastStats().getEscape() == 1);

        // You heading for a hole an enemy is heading for too.
        Field race = new Field(4, 0);
        race.you(race.towardHole(4, 0));
        race.enemy(Vec3.of(8.5d, 1d, 0.5d), race.towardHole(4, 0));
        race.run(8);
        HoleFill<Body> careless = race.search(b -> b.minChance(() -> 0.3d));
        HoleFill<Body> careful = race.search(b -> b.minChance(() -> 0.3d).protectOwn(() -> true));
        List<FillOption<Body>> carelessFills = careless.findFills(1);
        Checks.check("without protecting your own, the hole you are heading for is filled too (" + carelessFills + ", "
                + careless.getLastStats() + ")", carelessFills.size() == 1);
        boolean ownKept = careful.findFills(1).isEmpty();
        Checks.check("protecting it, it is kept (" + careful.getLastStats() + ")",
                ownKept && careful.getLastStats().getOwn() == 1);

        // A friend heading for a hole.
        Field friendly = new Field(4, 0);
        Body friend = friendly.friend(Vec3.of(8.5d, 1d, 0.5d), friendly.towardHole(4, 0));
        friendly.enemy(Vec3.of(4.5d, 1d, 7.5d), friendly.towardHole(4, 0));
        friendly.run(10);
        HoleFill<Body> sparing = friendly.search(b -> b.minChance(() -> 0.3d));
        boolean spared = sparing.findFills(1).isEmpty();
        Checks.check("a hole a friend is heading for is never filled, and the friend is never an enemy ("
                        + sparing.getLastStats() + ")",
                spared && sparing.getLastStats().getFriends() == 1 && friend != null);

        // A friend heading for a hole, though not surely enough to keep it for them: still never an enemy.
        Field unsure = new Field(4, 0);
        unsure.friend(Vec3.of(8.5d, 1d, 0.5d), unsure.towardHole(4, 0));
        unsure.run(10);
        HoleFill<Body> picky = unsure.search(b -> b.minChance(() -> 0.3d).protectChance(() -> 1.01d)
                .targets(unsure.targets, TargetSelector.from(unsure.rig.bodies).range(40).build()));   // every player
        boolean none = picky.findFills(1).isEmpty();
        Checks.check("a friend is never treated as an enemy, even when not sure enough of them to keep their hole ("
                + picky.getLastStats() + ")", none && picky.getLastStats().getFriends() == 0);

        // Someone else already in the hole.
        Field taken = new Field(4, 0);
        taken.enemy(Vec3.of(10.5d, 1d, 0.5d), taken.towardHole(4, 0));
        taken.bystander(Vec3.of(4.5d, 0d, 0.5d));
        taken.run(10);
        HoleFill<Body> occupied = taken.search(b -> b.obstructions(taken.everyone()));
        Checks.check("a hole someone else is in is not offered (" + occupied.findFills(1) + ", "
                + occupied.getLastStats() + ")", occupied.findFills(1).isEmpty());
    }

    // ----------------------------------------------------------------- timing

    private static void timing() {
        Field field = new Field(4, 0);
        field.enemy(Vec3.of(10.5d, 1d, 0.5d), field.towardHole(4, 0));
        field.run(12);
        FillOption<Body> safe = field.search(b -> b.fillDelay(() -> 1)).findFill();
        int possible = safe.getEarliestPossible();
        int arrives = safe.getArrivalTick();
        Checks.check("a fill landing before they could possibly be in is safe (possible " + possible + ", arrives "
                + arrives + ")", safe.getTiming() == FillOption.Timing.SAFE);
        FillOption<Body> race = field.search(b -> b.fillDelay(() -> possible)).findFill();
        Checks.check("one landing after they could be, but before they likely are, is a race (" + race + ")",
                race == null || race.getTiming() == FillOption.Timing.RACE || possible >= arrives);
        FillOption<Body> late = field.search(b -> b.fillDelay(() -> 19)).findFill();
        Checks.check("one landing after they are likely in is late, but still offered for you to judge (" + late + ")",
                late != null && late.getTiming() == FillOption.Timing.LATE && late.getSlack() < 0);
    }

    // ---------------------------------------------------------------- pending

    private static void pending() {
        Field field = new Field(4, 0);
        field.enemy(Vec3.of(10.5d, 1d, 0.5d), field.towardHole(4, 0));
        field.run(12);
        HoleFill<Body> search = field.search(b -> b.pendingTicks(() -> 3));
        FillOption<Body> fill = search.findFill();
        search.filled(fill);
        Checks.check("a hole you filled is not offered again while the server catches up ("
                + search.getLastStats() + ")", search.findFill() == null && search.getLastStats().getPending() == 1);
        for (int i = 0; i < 3; i++) {
            search.tick();
        }
        Checks.check("until its wait runs out, with the hole still there", search.findFill() != null);
    }

    // ------------------------------------------------------------ your control

    private static void yourControl() {
        Field field = new Field(4, 0, 0, 4);
        field.enemy(Vec3.of(8.5d, 1d, 0.5d), field.towardHole(4, 0));
        field.enemy(Vec3.of(0.5d, 1d, 10.5d), field.towardHole(0, 4));
        field.run(12);
        HoleFill<Body> filtered = field.search(b -> b.minChance(() -> 0.3d)
                .filter(fill -> !fill.getHole().getCells().contains(Vec3i.of(4, 0, 0))));
        List<FillOption<Body>> left = filtered.findFills(2);
        Checks.check("your filters have the last word", left.size() == 1 && filtered.getLastStats().getFiltered() == 1
                && left.get(0).getHole().getCells().contains(Vec3i.of(0, 0, 4)));
        HoleFill<Body> reversed = field.search(b -> b.minChance(() -> 0.3d)
                .rank((a, c) -> HoleFill.<Body>urgency().compare(c, a)));
        Checks.check("and your own ranking replaces the urgency",
                reversed.findFills(2).get(0).getHole().getCells().contains(Vec3i.of(0, 0, 4)));

        Clicks any = Clicks.builder().support(field.ground::solid).replaceable(field.ground::open).build();
        FillOption<Body> clicked = field.search(b -> b.minChance(() -> 0.3d).clicks(any)).findFill();
        Checks.check("with click rules, each cell comes with its click (" + clicked.getClicks() + ")",
                clicked.getClicks() != null && clicked.getClicks().size() == clicked.getCells().size()
                        && clicked.getClicks().get(0).getCell().equals(clicked.getCells().get(0)));
        Clicks never = Clicks.builder().support(field.ground::solid).replaceable(field.ground::open)
                .faces((eye, block, face, hit) -> false).build();
        HoleFill<Body> strict = field.search(b -> b.minChance(() -> 0.3d).clicks(never));
        Checks.check("and a hole your server would not let you fill is not offered",
                strict.findFills(2).isEmpty() && strict.getLastStats().getUnclickable() >= 2);

        Checks.check("a prior per enemy leans the chance toward holes", leaning());
    }

    /** Sprinting straight at a hole looks like sprinting over it: a prior for this enemy says they mean it. */
    private static boolean leaning() {
        Field field = new Field(4, 0);
        field.enemy(Vec3.of(-0.5d, 1d, 0.5d), field.sprintingAt(4, 0));
        field.run(6);
        FillOption<Body> plain = field.search(b -> b).findFill();
        FillOption<Body> leant = field.search(b -> b.prior(body -> 4d)).findFill();
        return plain != null && leant != null && leant.getChance() > plain.getChance();
    }

    private static void lifecycle() {
        String missing;
        try {
            HoleFill.<Body>builder().build();
            missing = null;
        } catch (IllegalStateException e) {
            missing = e.getMessage();
        }
        Checks.check("a fill search missing parts names them (" + missing + ")", missing != null
                && missing.contains("finder") && missing.contains("targets") && missing.contains("horizon")
                && missing.contains("placeReach"));
        Field empty = new Field(4, 0);
        empty.run(5);
        HoleFill<Body> search = empty.search(b -> b);
        Checks.check("with no enemy about, nothing to fill", search.findFills(3).isEmpty()
                && search.getLastStats().getUnthreatened() >= 1);
        Checks.checkThrows("and a count below one is refused", IllegalArgumentException.class, () -> search.findFills(0));
    }

    // ---------------------------------------------------------------- harness

    @FunctionalInterface
    private interface Script extends MovementRig.Script {
    }

    /** You at the origin on obsidian, holes cut into it, and whoever the test adds. */
    private static final class Field {
        final Ground ground = new Ground();
        final MovementRig rig = new MovementRig(ground.space);
        final List<Body> enemies = new ArrayList<>();
        final List<Body> friends = new ArrayList<>();
        final List<Body> others = new ArrayList<>();
        final TargetService targets = new TargetService(rig.entities);

        /** @param holes x, z pairs of one-block holes */
        Field(int... holes) {
            for (int i = 0; i < holes.length; i += 2) {
                ground.cut(holes[i], holes[i + 1]);
            }
            rig.self(Vec3.of(0.5d, 1d, 0.5d), STILL);
        }

        void you(Mover mover) {
            Body self = rig.self(Vec3.of(0.5d, 1d, 0.5d), STILL);
            self.moving(mover);
        }

        Body enemy(Vec3 at, Object move) {
            Body body = add(at, move);
            enemies.add(body);
            return body;
        }

        Body friend(Vec3 at, Object move) {
            Body body = add(at, move);
            friends.add(body);
            return body;
        }

        Body bystander(Vec3 at) {
            Body body = add(at, STILL);
            others.add(body);
            return body;
        }

        private Body add(Vec3 at, Object move) {
            if (move instanceof Mover) {
                return rig.add(at, STILL).moving((Mover) move);
            }
            return rig.add(at, (MovementRig.Script) move);
        }

        void run(int ticks) {
            for (int i = 0; i < ticks; i++) {
                rig.tick();
            }
        }

        Obstructions everyone() {
            return region -> {
                for (Body body : rig.all) {
                    if (body.state.hitbox(0.6d, 1.8d).intersects(region)) {
                        return true;
                    }
                }
                return false;
            };
        }

        /** Walks for the hole at x, z and stops in it. */
        Mover towardHole(int x, int z) {
            return heading(x, z, false);
        }

        /** Sprints straight at the hole at x, z, as someone would sprinting over it, until right over it. */
        Mover sprintingAt(int x, int z) {
            return heading(x, z, true);
        }

        private Mover heading(int x, int z, boolean sprint) {
            Vec3 centre = Vec3.of(x + 0.5d, 0d, z + 0.5d);
            return (body, state, tick, world) -> {
                double dx = centre.getX() - state.getPosition().getX();
                double dz = centre.getZ() - state.getPosition().getZ();
                MovementInput keys = dx * dx + dz * dz < 0.05d * 0.05d
                        ? MovementInput.none(0f)
                        : MovementInput.forward((float) Math.toDegrees(Math.atan2(-dx, dz))).withSprint(sprint);
                body.yaw = keys.getYaw();
                return Simulation.step(MovementRig.VANILLA, state, keys, world);
            };
        }

        HoleFill<Body> search(Function<HoleFill.Builder<Body>, HoleFill.Builder<Body>> tweak) {
            HoleFill.Builder<Body> builder = HoleFill.<Body>builder()
                    .finder(new HoleFinder(ground.rules()))
                    .prediction(rig.prediction)
                    .entities(rig.entities)
                    .targets(targets, TargetSelector.from(rig.bodies).range(40).where(enemies::contains).build())
                    .protect(TargetSelector.from(rig.bodies).range(40).where(friends::contains).build())
                    .placeReach(Reach.of(5.5d, 5.5d))
                    .blocks(ground)
                    .horizon(() -> 20)
                    .fillDelay(() -> 2);
            return tweak.apply(builder).build();
        }
    }

    /** Obsidian two blocks thick, at y -1 and 0, with holes cut into the top layer. */
    private static final class Ground implements BlockView {
        final GridCollisionSpace space = new GridCollisionSpace();
        final Map<Long, Boolean> solid = new HashMap<>();

        Ground() {
            for (int x = -25; x <= 25; x++) {
                for (int z = -25; z <= 25; z++) {
                    set(x, -1, z);
                    set(x, 0, z);
                }
            }
        }

        void set(int x, int y, int z) {
            solid.put(key(x, y, z), Boolean.TRUE);
            space.solid(x, y, z);
        }

        void cut(int x, int z) {
            solid.remove(key(x, 0, z));
            space.remove(x, 0, z);
        }

        boolean solid(int x, int y, int z) {
            return solid.containsKey(key(x, y, z));
        }

        boolean open(int x, int y, int z) {
            return !solid(x, y, z);
        }

        HoleRules rules() {
            return HoleRules.builder().walls(this::solid).open(this::open).headroom(2).build();
        }

        @Override
        public BlockShape shapeAt(int x, int y, int z) {
            return solid(x, y, z) ? BlockShape.FULL : BlockShape.EMPTY;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }
    }
}
