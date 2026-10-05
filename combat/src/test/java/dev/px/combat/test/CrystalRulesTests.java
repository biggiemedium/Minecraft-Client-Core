package dev.px.combat.test;

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
import dev.px.combat.world.BlockShape;
import dev.px.combat.world.BlockView;
import dev.px.combat.world.Obstructions;
import dev.px.combat.world.Rays;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The crystal and explosion rules: block shapes and rays, exposure, the
 * explosion model put together from its parts, placement, the crystal's body,
 * and {@link CrystalRules} over all of them.
 *
 * <p>The version profile here is the test's own, written from the wiki's
 * description of current Java the way a client would write it. The library
 * holds none of these numbers; the checks prove it does what any profile says.
 */
public final class CrystalRulesTests {

    // ---- the test client's version profile: its numbers, not the library's ----
    private static final double POWER = 6d;
    private static final Falloff WIKI_FALLOFF = Falloff.of(
            power -> 2 * power,
            (distance, exposure, power) -> {
                double impact = (1 - distance / (2 * power)) * exposure;
                return 7 * power * (impact * impact + impact) + 1;
            });

    private CrystalRulesTests() {
    }

    public static void run() {
        Checks.section("Crystal rules");

        shapes();
        rays();
        grids();
        exposure();
        model();
        placement();
        body();
        rules();
    }

    // ------------------------------------------------------------- blocks

    private static void shapes() {
        Checks.check("a full cell stops a line through it",
                BlockShape.FULL.intersects(0, 0, 0, -1, 0.5, 0.5, 2, 0.5, 0.5));
        Checks.check("and not one passing beside it", !BlockShape.FULL.intersects(0, 0, 0, -1, 1.5, 0.5, 2, 1.5, 0.5));
        Checks.check("an empty cell stops nothing", !BlockShape.EMPTY.intersects(0, 0, 0, -1, 0.5, 0.5, 2, 0.5, 0.5));

        BlockShape slab = BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1));
        Checks.check("a slab stops a line through its half", slab.intersects(5, 0, 5, 4, 0.25, 5.5, 7, 0.25, 5.5));
        Checks.check("and not one passing over it", !slab.intersects(5, 0, 5, 4, 0.75, 5.5, 7, 0.75, 5.5));
        Checks.check("its boxes are relative to whichever cell it is in",
                slab.intersects(-3, 10, 2, -4, 10.25, 2.5, -1, 10.25, 2.5));
        Checks.check("a line starting inside a shape is stopped", BlockShape.FULL.intersects(0, 0, 0, 0.5, 0.5, 0.5, 9, 9, 9));
        Checks.check("one along its top is not: a target is not hidden by the floor it stands on",
                !BlockShape.FULL.intersects(0, 0, 0, -1, 1, 0.5, 2, 1, 0.5)
                        && !BlockShape.FULL.intersects(0, 0, 0, 0.5, 1, 0.5, 0.5, 3, 0.5));
        Checks.check("but one along its bottom is: so the seam inside a solid wall is solid",
                BlockShape.FULL.intersects(0, 1, 0, -1, 1, 0.5, 2, 1, 0.5));
        Checks.check("touching only an edge is not", !BlockShape.FULL.intersects(0, 0, 0, 2, 0, 0.5, 0, 2, 0.5));
        Checks.check("shapes compare by their boxes",
                BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1)).equals(slab) && BlockShape.of().equals(BlockShape.EMPTY));
    }

    private static void rays() {
        Arena arena = new Arena();
        for (int y = 0; y < 4; y++) {
            arena.set(2, y, 0, BlockShape.FULL);
        }
        Checks.check("a wall stops a ray", !Rays.clear(Vec3.of(0.5, 1.5, 0.5), Vec3.of(4.5, 1.5, 0.5), arena));
        Checks.check("over it, the ray is clear", Rays.clear(Vec3.of(0.5, 4.5, 0.5), Vec3.of(4.5, 4.5, 0.5), arena));
        Checks.check("either way along it", !Rays.clear(Vec3.of(4.5, 1.5, 0.5), Vec3.of(0.5, 1.5, 0.5), arena));

        Arena low = new Arena();
        low.set(2, 0, 0, BlockShape.of(Box.of(0, 0, 0, 1, 0.5, 1)));
        Checks.check("a ray over a slab is clear", Rays.clear(Vec3.of(0.5, 0.9, 0.5), Vec3.of(4.5, 0.9, 0.5), low));
        Checks.check("one through it is not", !Rays.clear(Vec3.of(0.5, 0.3, 0.5), Vec3.of(4.5, 0.3, 0.5), low));
        Checks.check("a point inside a block sees nothing, even itself",
                !Rays.clear(Vec3.of(2.5, 1.5, 0.5), Vec3.of(2.5, 1.5, 0.5), arena));
    }

    private static void grids() {
        List<Vec3> points = new ArrayList<>();
        Box target = Box.of(0, 0, 0, 0.6, 1.8, 0.6);
        SampleGrid.uniform(3, 5, 3).forEach(target, (x, y, z) -> points.add(Vec3.of(x, y, z)));
        Checks.checkEquals("a uniform grid has every point it is asked for", 45, points.size());
        Checks.check("from corner to corner",
                points.contains(Vec3.of(0, 0, 0)) && points.contains(Vec3.of(0.6, 1.8, 0.6)));
        List<Vec3> one = new ArrayList<>();
        SampleGrid.uniform(1, 1, 1).forEach(target, (x, y, z) -> one.add(Vec3.of(x, y, z)));
        Checks.check("and one point is the centre", one.size() == 1 && one.get(0).distanceTo(Vec3.of(0.3, 0.9, 0.3)) < 1e-9);
        Checks.checkThrows("a count of zero is refused", IllegalArgumentException.class, () -> SampleGrid.uniform(0, 1, 1));
    }

    private static void exposure() {
        Exposure sampled = Exposure.sampled(SampleGrid.uniform(3, 5, 3));
        Vec3 origin = Vec3.of(0.5, 1, 0.5);
        Box target = Box.of(4.2, 0, 0.2, 4.8, 1.8, 0.8);

        Arena open = new Arena();
        Checks.checkEquals("in the open a target is fully exposed", 1f, (float) sampled.of(origin, target, open));

        Arena walled = new Arena();
        for (int y = -2; y < 5; y++) {
            for (int z = -3; z < 4; z++) {
                walled.set(2, y, z, BlockShape.FULL);
            }
        }
        Checks.checkEquals("behind a wall not at all", 0f, (float) sampled.of(origin, target, walled));

        Arena low = new Arena();
        for (int z = -3; z < 4; z++) {
            low.set(2, 0, z, BlockShape.FULL);
        }
        double partly = sampled.of(origin, target, low);
        Checks.check("behind a low wall, partly (" + partly + ")", partly > 0d && partly < 1d);
        Checks.checkEquals("FULL ignores terrain", 1f, (float) Exposure.FULL.of(origin, target, walled));
    }

    // -------------------------------------------------------------- model

    private static void model() {
        World world = new World();
        Mob target = world.add(new Mob(0, 0, 0));
        world.refresh();
        Tracked<Mob> tracked = world.mobs.get(target);

        ExplosionModel<Mob> plain = ExplosionModel.<Mob>builder()
                .measureFrom(Tracked::getPosition)
                .exposure(Exposure.FULL)
                .falloff(WIKI_FALLOFF)
                .mitigation(Mitigation.none())
                .build();
        Explosive crystal = Explosive.of("end crystal", POWER);
        Checks.checkEquals("the client's formula is what is used: point blank at power 6 is 85", 85f,
                (float) plain.damage(Vec3.of(0, 0, 0), crystal, tracked, BlockView.EMPTY));

        AtomicInteger exposureAsked = new AtomicInteger();
        ExplosionModel<Mob> counting = ExplosionModel.<Mob>builder()
                .measureFrom(Tracked::getPosition)
                .exposure((origin, box, blocks) -> {
                    exposureAsked.incrementAndGet();
                    return 1d;
                })
                .falloff(WIKI_FALLOFF)
                .mitigation(Mitigation.none())
                .build();
        DamageEstimate far = counting.estimate(Vec3.of(13, 0, 0), crystal, tracked, BlockView.EMPTY);
        Checks.check("beyond the falloff's range nothing is hurt", !far.isInRange() && far.getDamage() == 0d);
        Checks.checkEquals("and no rays are cast to find out", 0, exposureAsked.get());

        ExplosionModel<Mob> walled = ExplosionModel.<Mob>builder()
                .measureFrom(Tracked::getPosition)
                .exposure((origin, box, blocks) -> 0d)
                .falloff(WIKI_FALLOFF)
                .mitigation(Mitigation.none())
                .build();
        Checks.checkEquals("fully blocked, the client's formula still deals its minimum of 1", 1f,
                (float) walled.damage(Vec3.of(3, 0, 0), crystal, tracked, BlockView.EMPTY));

        Mitigation<Mob> halve = (damage, who) -> damage / 2;
        Mitigation<Mob> minusTen = (damage, who) -> damage - 10;
        double halveFirst = model(Mitigation.chain(halve, minusTen)).damage(Vec3.of(0, 0, 0), crystal, tracked, BlockView.EMPTY);
        double tenFirst = model(Mitigation.chain(minusTen, halve)).damage(Vec3.of(0, 0, 0), crystal, tracked, BlockView.EMPTY);
        Checks.check("mitigation runs in the order given (" + halveFirst + " vs " + tenFirst + ")",
                Checks.eq((float) halveFirst, 32.5f) && Checks.eq((float) tenFirst, 37.5f));
        Checks.checkEquals("and never leaves less than nothing", 0f,
                (float) model((damage, who) -> damage - 1000).damage(Vec3.of(0, 0, 0), crystal, tracked, BlockView.EMPTY));
        Checks.check("a step can read the game's own object", model((damage, who) -> damage * who.get().armourFactor)
                .damage(Vec3.of(0, 0, 0), crystal, tracked, BlockView.EMPTY) < 85d);

        ExplosionModel<Mob> fromEyes = ExplosionModel.<Mob>builder()
                .measureFrom(Tracked::getEyePosition)
                .exposure(Exposure.FULL)
                .falloff(WIKI_FALLOFF)
                .mitigation(Mitigation.none())
                .build();
        Checks.check("which point distance is measured to is the client's to say",
                fromEyes.estimate(Vec3.of(0, 0, 0), crystal, tracked, BlockView.EMPTY).getDistance() > 1d
                        && plain.estimate(Vec3.of(0, 0, 0), crystal, tracked, BlockView.EMPTY).getDistance() == 0d);

        String missing = thrown(() -> ExplosionModel.<Mob>builder().falloff(WIKI_FALLOFF).build());
        Checks.check("a model missing a rule names it",
                missing != null && missing.contains("measureFrom") && missing.contains("exposure")
                        && missing.contains("mitigation") && !missing.contains("falloff"));
    }

    private static ExplosionModel<Mob> model(Mitigation<Mob> mitigation) {
        return ExplosionModel.<Mob>builder()
                .measureFrom(Tracked::getPosition)
                .exposure(Exposure.FULL)
                .falloff(WIKI_FALLOFF)
                .mitigation(mitigation)
                .build();
    }

    // ---------------------------------------------------------- placement

    private static void placement() {
        Arena arena = new Arena();
        arena.base(0, 0, 0);
        arena.set(0, 2, 0, BlockShape.FULL);         // a block two above the base
        arena.base(5, 0, 0);

        Placement two = Placement.clearance(arena::isBase, 2, arena::isClear, 2d);
        Placement one = Placement.clearance(arena::isBase, 1, arena::isClear, 2d);
        Checks.check("only a base block holds a crystal", !two.canPlace(1, 0, 0, Obstructions.NONE));
        Checks.check("with two clear blocks required, a block two above refuses it",
                !two.canPlace(0, 0, 0, Obstructions.NONE));
        Checks.check("with one required, the same spot is fine: the version's number decides",
                one.canPlace(0, 0, 0, Obstructions.NONE));
        Checks.check("an open base takes one either way", two.canPlace(5, 0, 0, Obstructions.NONE));

        Obstructions inside = region -> region.intersects(Box.of(5.2, 1, 0.2, 5.8, 2.8, 0.8));
        Obstructions touching = region -> region.intersects(Box.of(6, 1, 0, 6.6, 2.8, 0.6));
        Obstructions above = region -> region.intersects(Box.of(5.2, 3, 0.2, 5.8, 4.8, 0.8));
        Checks.check("an entity in the space above refuses it", !two.canPlace(5, 0, 0, inside));
        Checks.check("one only touching its side does not", two.canPlace(5, 0, 0, touching));
        Checks.check("nor one above the space checked", two.canPlace(5, 0, 0, above));
        Checks.check("an entity height of zero ignores entities",
                Placement.clearance(arena::isBase, 2, arena::isClear, 0d).canPlace(5, 0, 0, inside));
    }

    private static void body() {
        CrystalBody body = CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0);
        Checks.check("a crystal sits where the body says, from the base block",
                body.position(3, 64, -7).distanceTo(Vec3.of(3.5, 65, -6.5)) < 1e-9);
        Box box = body.box(3, 64, -7);
        Checks.check("with the hitbox it says", box.getWidth() == 2d && box.getHeight() == 2d
                && box.getMin().getY() == 65d && box.getCenter().getX() == 3.5d);
        Checks.check("exploding where it says", body.explosion(3, 64, -7).distanceTo(Vec3.of(3.5, 65, -6.5)) < 1e-9
                && CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 1, 0).explosion(0, 0, 0).getY() == 2d);
        Checks.check("and a body missing a part is incomplete",
                !CrystalBody.at(0.5, 1, 0.5).size(2, 2).isComplete() && !CrystalBody.at(0, 0, 0).explodingAt(0, 0, 0).isComplete());
    }

    // -------------------------------------------------------------- rules

    private static void rules() {
        World world = new World();
        Mob enemy = world.add(new Mob(3.5, 1, 0.5));
        world.self = world.add(new Mob(-4.5, 1, 0.5));
        world.add(new Mob(10.5, 1, 0.5));          // standing on the second base
        Mob crystalEntity = world.add(new Mob(0.5, 1, 0.5));
        crystalEntity.width = 2;
        crystalEntity.height = 2;
        world.refresh();

        Arena arena = new Arena();
        arena.base(0, 0, 0);
        arena.base(10, 0, 0);
        CrystalRules<Mob> rules = rules(world, arena);

        Checks.check("an open base can take a crystal", rules.canPlace(0, 0, 0));
        Checks.check("one an entity stands on cannot", !rules.canPlace(10, 0, 0));
        Checks.check("its position, box and origin come from the body",
                rules.position(0, 0, 0).equals(Vec3.of(0.5, 1, 0.5)) && rules.box(0, 0, 0).getHeight() == 2d
                        && rules.origin(0, 0, 0).equals(Vec3.of(0.5, 1, 0.5)));
        Checks.checkEquals("its range is the client's falloff at the crystal's power", 12f, (float) rules.getRange());

        Tracked<Mob> them = world.mobs.get(enemy);
        Tracked<Mob> me = world.service.getSelf();
        double toThem = rules.damage(0, 0, 0, them);
        double toMe = rules.damage(0, 0, 0, me);
        Checks.check("the nearer target takes more (" + toThem + " vs " + toMe + ")", toThem > toMe && toMe > 0d);

        Tracked<Mob> existing = world.crystals.get(crystalEntity);
        Checks.check("a crystal already in the world is measured from where it is",
                existing != null && Checks.eq((float) rules.damage(existing, them), (float) toThem));

        for (int y = -1; y < 5; y++) {
            for (int z = -3; z < 4; z++) {
                arena.set(2, y, z, BlockShape.FULL);
            }
        }
        DamageEstimate behind = rules.estimate(0, 0, 0, them);
        Checks.check("behind a wall the target is not exposed and takes only the minimum",
                behind.getExposure() == 0d && Checks.eq((float) behind.getDamage(), 1f));

        String missing = thrown(() -> CrystalRules.<Mob>builder().explosive(Explosive.of("x", 1)).build());
        Checks.check("rules missing a part name every one",
                missing != null && missing.contains("placement") && missing.contains("body")
                        && missing.contains("model") && missing.contains("blocks") && missing.contains("obstructions"));
        String partial = thrown(() -> CrystalRules.<Mob>builder()
                .placement(Placement.clearance(arena::isBase, 2, arena::isClear, 2))
                .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2))
                .explosive(Explosive.of("x", 1)).model(model(Mitigation.none()))
                .blocks(arena).obstructions(Obstructions.NONE).build());
        Checks.check("including a body without its explosion point", partial != null && partial.contains("explodingAt"));
        Checks.checkThrows("an explosive needs power", IllegalArgumentException.class, () -> Explosive.of("dud", 0));
    }

    private static CrystalRules<Mob> rules(World world, Arena arena) {
        ExplosionModel<Mob> model = ExplosionModel.<Mob>builder()
                .measureFrom(Tracked::getPosition)
                .exposure(Exposure.sampled(SampleGrid.uniform(3, 5, 3)))
                .falloff(WIKI_FALLOFF)
                .mitigation(Mitigation.none())
                .build();
        return CrystalRules.<Mob>builder()
                .placement(Placement.clearance(arena::isBase, 2, arena::isClear, 2d))
                .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0))
                .explosive(Explosive.of("end crystal", POWER))
                .model(model)
                .blocks(arena)
                .obstructions(Obstructions.of(world.service, world.mobs))
                .build();
    }

    private static String thrown(Runnable body) {
        try {
            body.run();
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------- the test's "game"

    /** A block grid with some cells marked as crystal bases. */
    private static final class Arena implements BlockView {
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

    /** The test's entity: a position and a size. */
    private static final class Mob {
        double x;
        double y;
        double z;
        double width = 0.6;
        double height = 1.8;
        double armourFactor = 0.5;

        Mob(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A world of mobs behind Core's entity service, with a tracker for mobs and one for crystals. */
    private static final class World implements EntitySource<Mob> {
        final List<Mob> all = new ArrayList<>();
        final EntityService service;
        final Mobs mobs;
        final EntityTracker<Mob> crystals;
        Mob self;

        World() {
            RecordingLogger logger = new RecordingLogger();
            service = new EntityService(logger, new CoreEventBus(logger));
            mobs = service.register(new Mobs());
            crystals = service.register(EntityTracker.of(Mob.class, mob -> mob.width == 2));
            service.setSource(this);
        }

        Mob add(Mob mob) {
            all.add(mob);
            return mob;
        }

        void refresh() {
            service.refresh();
        }

        @Override
        public Iterable<Mob> entities() {
            return all;
        }

        @Override
        public Mob self() {
            return self;
        }

        @Override
        public double x(Mob mob) {
            return mob.x;
        }

        @Override
        public double y(Mob mob) {
            return mob.y;
        }

        @Override
        public double z(Mob mob) {
            return mob.z;
        }

        @Override
        public double width(Mob mob) {
            return mob.width;
        }

        @Override
        public double height(Mob mob) {
            return mob.height;
        }

        @Override
        public double eyeHeight(Mob mob) {
            return mob.height * 0.85;
        }
    }

    /** Living mobs: everything that is not a crystal. */
    private static final class Mobs extends EntityTracker<Mob> {
        Mobs() {
            super(Mob.class);
        }

        @Override
        protected boolean accepts(Mob mob) {
            return mob.width != 2;
        }
    }
}
