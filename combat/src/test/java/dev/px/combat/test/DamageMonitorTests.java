package dev.px.combat.test;

import dev.px.combat.explosion.ExplosionModel;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.rule.Exposure;
import dev.px.combat.explosion.rule.Falloff;
import dev.px.combat.explosion.rule.Mitigation;
import dev.px.combat.monitor.DamageDriftEvent;
import dev.px.combat.monitor.DamageMonitor;
import dev.px.combat.monitor.DamageReport;
import dev.px.combat.monitor.DamageSample;
import dev.px.combat.monitor.Vitals;
import dev.px.core.entity.EntityService;
import dev.px.core.entity.EntitySource;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.event.Stage;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.world.BlockView;

import java.util.ArrayList;
import java.util.List;

/**
 * The damage monitor, against a "game" whose real explosion rules the test
 * controls.
 *
 * <p>Each scenario has two models: the truth, which is what the test applies to
 * the fighters' health, and the client's, which the monitor checks. When they
 * agree the monitor must stay quiet; when one rule differs, it must notice, and
 * blame that rule.
 */
public final class DamageMonitorTests {

    private static final Explosive CRYSTAL = Explosive.of("end crystal", 6);

    /** The test's version formula, written as a client would; not the library's. */
    private static final Falloff WIKI = Falloff.of(
            power -> 2 * power,
            (distance, exposure, power) -> {
                double impact = (1 - distance / (2 * power)) * exposure;
                return 7 * power * (impact * impact + impact) + 1;
            });

    private DamageMonitorTests() {
    }

    public static void run() {
        Checks.section("Damage monitor");

        accurate();
        falloffOff();
        mitigationOff();
        exposureOff();
        sameTick();
        healthAhead();
        discards();
        pops();
        robustness();
        lifecycle();
    }

    // ------------------------------------------------------------ verdicts

    private static void accurate() {
        Arena arena = new Arena(1.0, 1.0, 0.5, 0.5);
        arena.play(12);
        DamageReport report = arena.monitor.report();
        Checks.check("a model that matches the game is trusted", report.isReliable() && report.hasEvidence());
        Checks.check("with nothing to blame", report.getSuspect() == DamageReport.Suspect.NONE);
        Checks.checkEquals("and no error", 0f, (float) report.getOverall().getMedianError());
        Checks.check("every rule was tested", report.get(DamageSample.Bucket.OPEN).getCount() > 0
                && report.get(DamageSample.Bucket.ARMOURED).getCount() > 0
                && report.get(DamageSample.Bucket.COVERED).getCount() > 0);
        Checks.check("the local player is sampled too", arena.monitor.getSamples().stream().anyMatch(DamageSample::isSelf));
        Checks.checkEquals("and nobody drifted", 0, arena.drifts.size());
    }

    private static void falloffOff() {
        Arena arena = new Arena(1.0, 0.7, 0.5, 0.5);           // the client's formula is 30% light
        arena.play(12);
        DamageReport report = arena.monitor.report();
        Checks.check("a wrong formula is noticed", !arena.monitor.isReliable());
        Checks.check("and blamed on the falloff (" + report + ")", report.getSuspect() == DamageReport.Suspect.FALLOFF);
        Checks.check("as under-predicting", report.getOverall().getMedianError() > 0d);
        Checks.check("announced once, as it happened",
                arena.drifts.size() == 1 && !arena.drifts.get(0).isReliable());
    }

    private static void mitigationOff() {
        Arena arena = new Arena(1.0, 1.0, 0.4, 0.6);           // armour is stronger than the client thinks
        arena.play(12);
        DamageReport report = arena.monitor.report();
        Checks.check("wrong armour is blamed on the mitigation (" + report + ")",
                report.getSuspect() == DamageReport.Suspect.MITIGATION);
        Checks.check("while the open targets are fine", !report.get(DamageSample.Bucket.OPEN).isOff());
        Checks.check("the client over-predicts armoured targets",
                report.get(DamageSample.Bucket.ARMOURED).getMedianError() < 0d);
    }

    private static void exposureOff() {
        Arena arena = new Arena(1.0, 1.0, 0.5, 0.5);
        arena.coverTruth = 0.2;                                 // walls hide more than the client thinks
        arena.coverClient = 0.6;
        arena.play(12);
        DamageReport report = arena.monitor.report();
        Checks.check("wrong exposure is blamed on the exposure (" + report + ")",
                report.getSuspect() == DamageReport.Suspect.EXPOSURE);
    }

    // ------------------------------------------------------------ measuring

    private static void sameTick() {
        Arena arena = new Arena(1.0, 1.0, 0.5, 0.5);
        for (int round = 0; round < 10; round++) {
            arena.heal();
            Vec3 near = Vec3.of(2, 0, 0.3);
            Vec3 far = Vec3.of(6, 0, 0.3);
            arena.monitor.exploded(far, CRYSTAL);
            arena.monitor.exploded(near, CRYSTAL);
            arena.landStrongest(near, far);
            arena.settle();
        }
        Checks.check("several explosions in one tick are judged by the strongest, as the game applies them",
                arena.monitor.isReliable() && Checks.eq((float) arena.monitor.report().getOverall().getMedianError(), 0f));
        Checks.checkEquals("one sample per target per tick, not one per explosion", 40, arena.monitor.getSamples().size());
    }

    private static void healthAhead() {
        Arena arena = new Arena(1.0, 1.0, 0.5, 0.5);
        for (int round = 0; round < 10; round++) {
            arena.heal();
            arena.monitor.tick();                               // end of a quiet tick: the "before"
            Vec3 origin = Vec3.of(3, 0, 0.3);
            arena.land(origin);                                 // the health update is handled first
            arena.monitor.exploded(origin, CRYSTAL);           // and only then the explosion
            arena.settle();
        }
        Checks.check("a health update handled before the explosion is still measured",
                arena.monitor.isReliable() && arena.monitor.report().getOverall().getCount() == 40
                        && Checks.eq((float) arena.monitor.report().getOverall().getMedianError(), 0f));
    }

    private static void discards() {
        Arena hurt = new Arena(1.0, 1.0, 0.5, 0.5);
        hurt.enemy.recentlyHurt = true;
        hurt.play(1);
        Checks.checkEquals("a target still recovering from a hit is thrown away", 1, hurt.monitor.getDiscarded());

        Arena hidden = new Arena(1.0, 1.0, 0.5, 0.5);
        hidden.enemy.trusted = false;
        hidden.armoured.health = Double.NaN;
        hidden.play(1);
        Checks.check("an untrusted or unknown health is not sampled at all",
                hidden.monitor.getSamples().size() == 2 && hidden.monitor.getDiscarded() == 0);

        Arena leaving = new Arena(1.0, 1.0, 0.5, 0.5);
        leaving.heal();
        leaving.monitor.exploded(Vec3.of(3, 0, 0.3), CRYSTAL);
        leaving.world.fighters.remove(leaving.enemy);
        leaving.world.service.refresh();
        leaving.settle();
        Checks.checkEquals("one that leaves before it settles is thrown away", 1, leaving.monitor.getDiscarded());

        Arena far = new Arena(1.0, 1.0, 0.5, 0.5);
        far.heal();
        far.monitor.exploded(Vec3.of(60, 0, 0), CRYSTAL);
        far.settle();
        Checks.check("nobody out of range is sampled", far.monitor.getSamples().isEmpty());
    }

    private static void pops() {
        Arena arena = new Arena(1.0, 1.0, 0.5, 0.5);
        arena.enemy.health = 2;
        arena.enemy.absorption = 0;
        arena.heal = false;
        Vec3 origin = Vec3.of(1, 0, 0.3);
        arena.monitor.exploded(origin, CRYSTAL);
        arena.land(origin);
        arena.settle();
        DamageSample popped = arena.find(arena.enemy);
        Checks.check("a pop is a lower bound, not a measurement", popped != null && popped.isPopped()
                && popped.getObserved() == popped.getBefore());
        Checks.checkEquals("and expected when the model said it would", 0, arena.monitor.report().getUnexpectedPops());

        Arena surprised = new Arena(1.0, 0.05, 0.5, 0.5);       // the client thinks crystals barely hurt
        surprised.heal = false;
        surprised.self.health = Double.NaN;                     // only the enemy is observed,
        surprised.armoured.trusted = false;                     // so pops are the only evidence
        surprised.covered.trusted = false;
        for (int round = 0; round < 4; round++) {
            surprised.enemy.health = 3;
            surprised.enemy.absorption = 0;
            surprised.monitor.tick();                           // a quiet tick: the "before"
            surprised.monitor.exploded(Vec3.of(1, 0, 0.3), CRYSTAL);
            surprised.landOn(surprised.enemy, Vec3.of(1, 0, 0.3));
            surprised.settle();
        }
        DamageReport report = surprised.monitor.report();
        Checks.check("pops the model said would not happen make it unreliable on their own (" + report + ")",
                report.getUnexpectedPops() == 4 && report.getOverall().getCount() == 0
                        && report.getSuspect() == DamageReport.Suspect.UNKNOWN);
    }

    private static void robustness() {
        Arena arena = new Arena(1.0, 1.0, 0.5, 0.5);
        arena.play(10);
        arena.heal();
        Vec3 origin = Vec3.of(3, 0, 0.3);
        arena.monitor.exploded(origin, CRYSTAL);
        arena.land(origin);
        arena.enemy.health -= 10;                               // a sword hit in the same moment
        arena.settle();
        Checks.check("one sample disturbed by something else does not sway the verdict", arena.monitor.isReliable());
    }

    private static void lifecycle() {
        String missing = thrown(() -> DamageMonitor.<Fighter>builder().vitals(f -> 0).build());
        Checks.check("a monitor missing parts names them", missing != null && missing.contains("model")
                && missing.contains("blocks") && missing.contains("targets") && !missing.contains("vitals"));

        Arena arena = new Arena(1.0, 0.7, 0.5, 0.5);
        arena.play(12);
        Checks.check("(drifted)", !arena.monitor.isReliable());
        arena.monitor.clear();
        Checks.check("clear forgets the evidence", arena.monitor.isReliable() && arena.monitor.getSamples().isEmpty());

        arena.heal();
        arena.monitor.exploded(Vec3.of(3, 0, 0.3), CRYSTAL);
        arena.land(Vec3.of(3, 0, 0.3));
        for (int i = 0; i < 3; i++) {
            arena.bus.post(new TickEvent(Stage.POST));
        }
        Checks.checkEquals("the bus's ticks drive it", 4, arena.monitor.getSamples().size());
        arena.monitor.close();
        arena.monitor.exploded(Vec3.of(3, 0, 0.3), CRYSTAL);
        for (int i = 0; i < 5; i++) {
            arena.bus.post(new TickEvent(Stage.POST));
        }
        Checks.checkEquals("until it is closed", 4, arena.monitor.getSamples().size());
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

    /**
     * Three fighters near the origin &mdash; the local player, an open enemy and an
     * armoured one &mdash; plus a covered one, the true rules and the client's.
     */
    private static final class Arena {
        final CoreEventBus bus = new CoreEventBus(new RecordingLogger());
        final World world = new World();
        final Fighter self = world.add(new Fighter(0, 0, 0));
        final Fighter enemy = world.add(new Fighter(0, 0, 4));
        final Fighter armoured = world.add(new Fighter(4, 0, 0));
        final Fighter covered = world.add(new Fighter(-4, 0, 0));
        final List<DamageDriftEvent> drifts = new ArrayList<>();
        final ExplosionModel<Fighter> truth;
        final DamageMonitor<Fighter> monitor;
        double coverTruth = 0.5;
        double coverClient = 0.5;
        boolean heal = true;

        Arena(double falloffTruth, double falloffClient, double armourTruth, double armourClient) {
            armoured.armoured = true;
            covered.covered = true;
            world.self = self;
            world.service.refresh();
            truth = model(falloffTruth, armourTruth, true);
            monitor = DamageMonitor.<Fighter>builder()
                    .model(model(falloffClient, armourClient, false))
                    .blocks(BlockView.EMPTY)
                    .vitals(new Vitals<Fighter>() {
                        @Override
                        public double pool(Fighter fighter) {
                            return fighter.health + fighter.absorption;
                        }

                        @Override
                        public boolean isTrusted(Fighter fighter) {
                            return fighter.trusted;
                        }

                        @Override
                        public boolean isRecentlyHurt(Fighter fighter) {
                            return fighter.recentlyHurt;
                        }
                    })
                    .targets(world.service, world.tracker)
                    .bus(bus)
                    .build();
            bus.on(DamageDriftEvent.class, drifts::add);
        }

        private ExplosionModel<Fighter> model(double falloffScale, double armour, boolean isTruth) {
            return ExplosionModel.<Fighter>builder()
                    .measureFrom(Tracked::getPosition)
                    .exposure((origin, box, blocks) -> fighterAt(box).covered ? (isTruth ? coverTruth : coverClient) : 1d)
                    .falloff(Falloff.of(WIKI::range, (d, e, p) -> WIKI.damage(d, e, p) * falloffScale))
                    .mitigation((Mitigation<Fighter>) (damage, target) -> target.get().armoured ? damage * armour : damage)
                    .build();
        }

        Fighter fighterAt(Box box) {
            for (Fighter fighter : world.fighters) {
                if (Math.abs(fighter.x - box.getCenter().getX()) < 1e-6 && Math.abs(fighter.z - box.getCenter().getZ()) < 1e-6) {
                    return fighter;
                }
            }
            throw new IllegalStateException("no fighter at " + box);
        }

        /** Rounds of explosions at varying distances, each settled. */
        void play(int rounds) {
            for (int round = 0; round < rounds; round++) {
                heal();
                Vec3 origin = Vec3.of(0.5 + (round % 5) * 0.7, 0, 0.3 + (round % 3) * 0.5);
                monitor.exploded(origin, CRYSTAL);
                land(origin);
                settle();
            }
        }

        void heal() {
            if (!heal) {
                return;
            }
            for (Fighter fighter : world.fighters) {
                if (!Double.isNaN(fighter.health)) {
                    fighter.health = 200;
                    fighter.absorption = 16;
                }
            }
        }

        /** What the game really does to everyone in range. */
        void land(Vec3 origin) {
            for (Fighter fighter : new ArrayList<>(world.fighters)) {
                landOn(fighter, origin);
            }
        }

        void landOn(Fighter fighter, Vec3 origin) {
            Tracked<Fighter> tracked = tracked(fighter);
            if (tracked == null || Double.isNaN(fighter.health)) {
                return;
            }
            hurt(fighter, truth.damage(origin, CRYSTAL, tracked, BlockView.EMPTY));
        }

        void landStrongest(Vec3... origins) {
            for (Fighter fighter : world.fighters) {
                Tracked<Fighter> tracked = tracked(fighter);
                double strongest = 0;
                for (Vec3 origin : origins) {
                    strongest = Math.max(strongest, truth.damage(origin, CRYSTAL, tracked, BlockView.EMPTY));
                }
                hurt(fighter, strongest);
            }
        }

        void hurt(Fighter fighter, double damage) {
            double pool = fighter.health + fighter.absorption;
            if (damage >= pool) {
                fighter.health = 1;                             // a totem
                fighter.absorption = 8;
                monitor.popped(fighter);
                return;
            }
            double fromAbsorption = Math.min(fighter.absorption, damage);
            fighter.absorption -= fromAbsorption;
            fighter.health -= damage - fromAbsorption;
        }

        void settle() {
            for (int i = 0; i < DamageMonitor.DEFAULT_SETTLE_TICKS; i++) {
                monitor.tick();
            }
        }

        Tracked<Fighter> tracked(Fighter fighter) {
            return fighter == world.self ? world.service.getSelf() : world.tracker.get(fighter);
        }

        DamageSample find(Fighter fighter) {
            for (DamageSample sample : monitor.getSamples()) {
                if (Math.abs(sample.getTarget().getX() - fighter.x) < 1e-6 && Math.abs(sample.getTarget().getZ() - fighter.z) < 1e-6) {
                    return sample;
                }
            }
            return null;
        }
    }

    private static final class Fighter {
        final double x;
        final double y;
        final double z;
        double health = 200;
        double absorption = 16;
        boolean armoured;
        boolean covered;
        boolean trusted = true;
        boolean recentlyHurt;

        Fighter(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class World implements EntitySource<Fighter> {
        final List<Fighter> fighters = new ArrayList<>();
        final EntityService service;
        final EntityTracker<Fighter> tracker;
        Fighter self;

        World() {
            RecordingLogger logger = new RecordingLogger();
            service = new EntityService(logger, new CoreEventBus(logger));
            tracker = service.register(EntityTracker.of(Fighter.class));
            service.setSource(this);
        }

        Fighter add(Fighter fighter) {
            fighters.add(fighter);
            return fighter;
        }

        @Override
        public Iterable<Fighter> entities() {
            return fighters;
        }

        @Override
        public Fighter self() {
            return self;
        }

        @Override
        public double x(Fighter fighter) {
            return fighter.x;
        }

        @Override
        public double y(Fighter fighter) {
            return fighter.y;
        }

        @Override
        public double z(Fighter fighter) {
            return fighter.z;
        }

        @Override
        public double width(Fighter fighter) {
            return 0.6;
        }

        @Override
        public double height(Fighter fighter) {
            return 1.8;
        }
    }
}
