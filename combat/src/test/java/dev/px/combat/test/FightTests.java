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
import dev.px.combat.fight.Aim;
import dev.px.combat.fight.Attack;
import dev.px.combat.fight.Bout;
import dev.px.combat.fight.CrystalAttack;
import dev.px.combat.fight.Fight;
import dev.px.combat.fight.Footwork;
import dev.px.combat.fight.Strike;
import dev.px.combat.monitor.Vitals;
import dev.px.combat.search.CrystalSearch;
import dev.px.combat.search.rule.Reach;
import dev.px.combat.search.rule.Thresholds;
import dev.px.core.control.Click;
import dev.px.core.entity.EntityTracker;
import dev.px.core.entity.Tracked;
import dev.px.core.flow.Control;
import dev.px.core.flow.Flow;
import dev.px.core.flow.FlowHandle;
import dev.px.core.flow.Key;
import dev.px.core.flow.Span;
import dev.px.core.flow.Status;
import dev.px.core.flow.StopReason;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.rotation.RotationRequest;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.target.TargetSelector;
import dev.px.core.test.harness.Checks;
import dev.px.core.util.math.RotationMath;
import dev.px.core.world.Obstructions;
import dev.px.testkit.Sandbox;
import dev.px.testkit.SimEntity;
import dev.px.testkit.SimPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fight steps: an attack, an aim and footwork run as a step of Core's flows, in
 * the test kit's sandbox, and the crystal attack on a real crystal search.
 *
 * <p>The sandbox's player is the click, key and rotation sink, so what a fight
 * did is read off it: the clicks it made, where it faced, where it walked. The
 * parts are the test's own, standing in for a client's killaura, rotation logic
 * and strafe, and log what the step asked of them.
 */
public final class FightTests {

    private static final Falloff WIKI = Falloff.of(
            power -> 2 * power,
            (distance, exposure, power) -> {
                double impact = (1 - distance / (2 * power)) * exposure;
                return 7 * power * (impact * impact + impact) + 1;
            });

    private FightTests() {
    }

    public static void run() {
        Checks.section("Fight");

        swinging();
        facing();
        aiming();
        footwork();
        ending();
        driving();
        pausing();
        progress();
        missing();
        crystals();
        builders();
    }

    // ------------------------------------------------------------- the aura

    private static void swinging() {
        Arena arena = new Arena();
        Aura aura = new Aura(4);
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(aura).build();
        Fight.Round<SimEntity> round = fight.against(c -> arena.sandbox.tracked(arena.enemy));
        FlowHandle flow = arena.sandbox.flows().start("fight", round, 50);
        arena.sandbox.run(12);

        Checks.checkEquals("an answering attack's clicks go out through the flow, once each time it swings",
                Collections.nCopies(3, "click ATTACK"), arena.me.getClicks());
        Checks.check("and it hears each one went out (" + aura.struck + " heard, " + round.getStrikes() + " counted)",
                aura.struck == 3 && round.getStrikes() == 3);
        Vec2 wanted = arena.eyes().rotationTo(arena.target().getCenter());
        Checks.check("the head snaps to the aim by default (" + arena.me.getRotation() + ")",
                RotationMath.difference(arena.me.getRotation(), wanted) < 0.01f);
        Checks.check("claimed by the flow", arena.sandbox.rotations().getHolder() == flow);
        Checks.check("declaring the head and both buttons, and not the keys without footwork",
                round.getUses().contains(Control.ROTATION) && round.getUses().contains(Control.ATTACK)
                        && round.getUses().contains(Control.USE) && !round.getUses().contains(Control.MOVEMENT));
        Checks.check("leaving the keys alone", arena.sandbox.controls().getMovementHolder() == null);
        Checks.check("its parts are given the eyes of the player the host tracks",
                aura.lastEyes != null && aura.lastEyes.distanceTo(arena.eyes()) < 1.0E-9);
        Checks.checkEquals("named for its key-less target in the live view", "fight", round.getName());
    }

    private static void facing() {
        Arena arena = new Arena();
        arena.me.face(Vec2.rotation(90f, 0f));          // looking away, along -x
        Aura aura = new Aura(1);
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(aura)
                .aim(Aim.limited(RotationRequest.at(Vec2.ZERO).step(20f))).build();
        arena.sandbox.flows().start("fight", fight.against(c -> arena.sandbox.tracked(arena.enemy)), 50);
        arena.sandbox.run(3);
        Checks.check("a strike that names a box is not clicked while the head is still turning to it ("
                + arena.me.getRotation() + ")", arena.me.getClicks().isEmpty() && aura.struck == 0);
        arena.sandbox.run(12);
        Checks.check("and is, once the head looks at it (" + arena.me.getClicks().size() + " clicks)",
                !arena.me.getClicks().isEmpty() && aura.struck == arena.me.getClicks().size());

        Arena blind = new Arena();
        blind.me.face(Vec2.rotation(90f, 0f));
        Aura anyway = new Aura(1).facing(false);
        Fight<SimEntity> regardless = Fight.<SimEntity>builder().attack(anyway)
                .aim(Aim.limited(RotationRequest.at(Vec2.ZERO).step(20f))).build();
        blind.sandbox.flows().start("fight", regardless.against(c -> blind.sandbox.tracked(blind.enemy)), 50);
        blind.sandbox.run(1);
        Checks.checkEquals("a strike with no box is clicked regardless", 1, blind.me.getClicks().size());

        Arena holding = new Arena();
        Attack<SimEntity> hold = b -> Strike.at(b.target().getCenter()).hold(Click.USE);
        holding.sandbox.flows().start("fight",
                Fight.<SimEntity>builder().attack(hold).build().against(c -> holding.sandbox.tracked(holding.enemy)), 50);
        holding.sandbox.run(3);
        Checks.checkEquals("a held strike holds the button down rather than clicking it",
                Collections.singletonList("hold USE"), holding.me.getClicks());
    }

    private static void aiming() {
        Arena arena = new Arena();
        List<Vec3> asked = new ArrayList<>();
        Aim offset = (b, point, wanted) -> {
            asked.add(point);
            return RotationRequest.at(wanted.getYaw() + 5f, wanted.getPitch()).priority(1);
        };
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(new Aura(1000)).aim(offset).build();
        FlowHandle flow = arena.sandbox.flows().start("fight",
                fight.against(c -> arena.sandbox.tracked(arena.enemy)), 50);
        arena.sandbox.run(3);
        Vec2 wanted = arena.eyes().rotationTo(arena.target().getCenter());
        Checks.check("your aim is asked with the attack's point, and its rotation is the one applied ("
                + arena.me.getRotation() + ")", asked.size() == 3
                && Math.abs(arena.me.getRotation().getYaw() - (wanted.getYaw() + 5f)) < 0.01f);
        FlowHandle rival = arena.sandbox.flows().start("rival", Flow.step("look up").uses(Control.ROTATION)
                .tick(c -> {
                    c.look(Vec2.rotation(0f, -90f));
                    return Status.RUNNING;
                }), 40);
        arena.sandbox.run(2);
        Checks.check("filed at the flow's priority, not the request's own: a flow at 40 does not win the head",
                arena.sandbox.rotations().getHolder() == flow && rival.getState() == FlowHandle.State.RUNNING);

        Arena own = new Arena();
        final int[] turned = {0};
        Aim manager = new Aim() {
            @Override
            public RotationRequest turn(Bout<?> b, Vec3 point, Vec2 wanted) {
                turned[0]++;
                return RotationRequest.at(wanted);
            }

            @Override
            public boolean drives() {
                return true;
            }
        };
        Fight<SimEntity> driven = Fight.<SimEntity>builder().attack(new Aura(1000)).aim(manager).build();
        own.sandbox.flows().start("fight", driven.against(c -> own.sandbox.tracked(own.enemy)), 50);
        own.sandbox.run(3);
        Checks.check("an aim that drives is asked each tick, and the step files nothing for it",
                turned[0] == 3 && own.sandbox.rotations().getHolder() == null);
    }

    private static void footwork() {
        Arena arena = new Arena();
        Footwork<SimEntity> circle = b -> {
            float yaw = b.rotationTo(b.target().getCenter()).getYaw();
            return MovementInput.of(yaw, 0, 1);
        };
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(new Aura(1000)).footwork(circle).build();
        Fight.Round<SimEntity> round = fight.against(c -> arena.sandbox.tracked(arena.enemy));
        arena.sandbox.flows().start("fight", round, 50);
        arena.sandbox.run(10);
        Vec2 wanted = arena.eyes().rotationTo(arena.target().getCenter());
        double fromTarget = Math.hypot(arena.me.getPosition().getX() - 3.5, arena.me.getPosition().getZ() - 0.5);
        Checks.check("footwork's keys circle the player round the target (" + arena.me.getPosition()
                        + ", " + String.format("%.2f", fromTarget) + " blocks off)",
                Math.abs(arena.me.getPosition().getZ() - 0.5) > 1 && Math.abs(fromTarget - 3) < 0.3);
        // The head was turned before this tick's step, so it trails the feet by one tick's circling.
        Checks.check("while the head stays on the target (" + String.format("%.1f",
                RotationMath.difference(arena.me.getRotation(), wanted)) + " degrees behind)",
                RotationMath.difference(arena.me.getRotation(), wanted) < 10f);
        Checks.check("and the step declares the keys", round.getUses().contains(Control.MOVEMENT));

        Arena still = new Arena();
        Fight<SimEntity> quiet = Fight.<SimEntity>builder().attack(new Aura(1000))
                .footwork(b -> null).build();
        still.sandbox.flows().start("fight", quiet.against(c -> still.sandbox.tracked(still.enemy)), 50);
        still.sandbox.run(3);
        Checks.check("footwork answering null leaves the keys to anyone else",
                still.sandbox.controls().getMovementHolder() == null);

        Arena own = new Arena();
        final int[] asked = {0};
        Footwork<SimEntity> strafe = new Footwork<SimEntity>() {
            @Override
            public MovementInput keys(Bout<? extends SimEntity> b) {
                asked[0]++;
                return MovementInput.forward(0f);
            }

            @Override
            public boolean drives() {
                return true;
            }
        };
        own.sandbox.flows().start("fight", Fight.<SimEntity>builder().attack(new Aura(1000)).footwork(strafe).build()
                .against(c -> own.sandbox.tracked(own.enemy)), 50);
        own.sandbox.run(3);
        Checks.check("footwork that drives is asked each tick, and the step files no keys for it",
                asked[0] == 3 && own.sandbox.controls().getMovementHolder() == null);
    }

    // --------------------------------------------------------------- ending

    private static void ending() {
        Arena arena = new Arena();
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(new Aura(4)).build();
        FlowHandle flow = arena.sandbox.flows().start("fight", fight.against(c -> arena.sandbox.tracked(arena.enemy)), 50);
        arena.sandbox.run(5);
        arena.enemy.remove();
        arena.sandbox.run(3);
        Checks.checkEquals("the fight is done once its target is gone", FlowHandle.State.DONE, flow.getState());
        Checks.check("and lets go of the head", arena.sandbox.rotations().getHolder() == null);

        Arena keyed = new Arena();
        Key<Tracked<SimEntity>> target = Key.of("target");
        Fight.Round<SimEntity> round = fight.against(target);
        FlowHandle held = keyed.sandbox.flows().start("fight", Flow.sequence(
                Flow.action("pick", c -> c.put(target, keyed.sandbox.tracked(keyed.enemy))), round), 50);
        keyed.sandbox.run(3);
        keyed.enemy.remove();
        keyed.sandbox.run(3);
        Checks.check("a target held in a key is lost once it is no longer tracked, though the key still holds it ("
                + held.getState() + ")", held.getState() == FlowHandle.State.DONE && held.get(target) != null);
        Checks.checkEquals("named for its key in the live view", "fight target", round.getName());

        Arena away = new Arena();
        TargetSelector<SimEntity> near = TargetSelector.from(away.players).range(6).build();
        Fight<SimEntity> kept = Fight.<SimEntity>builder().attack(new Aura(4)).keep(near).build();
        FlowHandle walking = away.sandbox.flows().start("fight", kept.against(c -> away.players.get(away.enemy)), 50);
        away.sandbox.run(3);
        Checks.checkEquals("it fights while the target passes your selector", FlowHandle.State.RUNNING, walking.getState());
        away.enemy.teleport(Vec3.of(15.5, 64, 0.5));
        away.sandbox.run(2);
        Checks.checkEquals("and is done once it does not (out of range)", FlowHandle.State.DONE, walking.getState());

        Arena empty = new Arena();
        FlowHandle nobody = empty.sandbox.flows().start("fight", fight.against(c -> null), 50);
        empty.sandbox.run(2);
        Checks.check("no target as it starts fails it, saying so (" + nobody.getReason() + ")",
                nobody.getState() == FlowHandle.State.FAILED && nobody.getReason().contains("no target"));

        Arena giving = new Arena();
        Attack<SimEntity> out = b -> b.ticks() < 2 ? Strike.none() : Strike.failed("out of crystals");
        FlowHandle failed = giving.sandbox.flows().start("fight",
                Fight.<SimEntity>builder().attack(out).build().against(c -> giving.sandbox.tracked(giving.enemy)), 50);
        int ticks = giving.sandbox.runUntilDone(failed, 10);
        Checks.check("a failed strike fails the fight with its reason (" + failed.getReason() + " after " + ticks + ")",
                failed.getState() == FlowHandle.State.FAILED && failed.getReason().equals("out of crystals") && ticks == 3);
    }

    // -------------------------------------------------------- parts that drive

    private static void driving() {
        Arena arena = new Arena();
        Driver driver = new Driver();
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(driver).build();
        FlowHandle flow = arena.sandbox.flows().start("fight", fight.against(c -> arena.sandbox.tracked(arena.enemy)), 50);
        arena.sandbox.run(4);
        Checks.check("an attack that drives is started and asked each tick (" + driver.log + ")",
                !driver.log.isEmpty() && driver.log.get(0).equals("start") && driver.ticks == 4);
        Checks.check("and nothing is claimed for it: no clicks, no head",
                arena.me.getClicks().isEmpty() && arena.sandbox.rotations().getHolder() == null);
        flow.pause();
        arena.sandbox.tick();
        Checks.check("pausing the flow stops it, so it stops acting (" + driver.log + ")",
                driver.log.contains("stop PAUSED"));
        flow.resume();
        arena.sandbox.tick();
        Checks.check("resuming starts it again, saying it is resuming", driver.log.contains("start resuming"));
        arena.enemy.remove();
        arena.sandbox.run(2);
        Checks.check("and the fight ending stops it for good (" + driver.log + ")",
                driver.log.get(driver.log.size() - 1).equals("stop FINISHED"));
    }

    private static void pausing() {
        Arena arena = new Arena();
        Aura aura = new Aura(1);
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(aura).build();
        FlowHandle flow = arena.sandbox.flows().start("fight", fight.against(c -> arena.sandbox.tracked(arena.enemy)), 50);
        final boolean[] hungry = {false};
        final int[] ate = {0};
        FlowHandle eat = arena.sandbox.flows().reflex("eat", c -> hungry[0], 90, Flow.step("eat").uses(Control.USE)
                .tick(c -> {
                    c.hold(Click.USE);
                    return ++ate[0] >= 6 ? Status.DONE : Status.RUNNING;
                }));
        arena.sandbox.run(3);
        hungry[0] = true;
        arena.sandbox.tick();
        hungry[0] = false;
        int swings = aura.struck;
        Checks.check("a reflex using the use button pauses the fight (" + flow.getReason() + ")",
                flow.getState() == FlowHandle.State.PAUSED);
        Checks.check("which lets go of the head at once", arena.sandbox.rotations().getHolder() != flow);
        arena.sandbox.run(2);
        Checks.checkEquals("and swings no more meanwhile", swings, aura.struck);
        arena.sandbox.run(8);
        Checks.check("then resumes and swings again (" + aura.struck + " swings)",
                flow.getState() == FlowHandle.State.RUNNING && aura.struck > swings && eat.getState() == FlowHandle.State.IDLE);
    }

    private static void progress() {
        Arena arena = new Arena();
        Map<SimEntity, Double> health = new HashMap<>();
        health.put(arena.enemy, 20d);
        Vitals<SimEntity> vitals = health::get;
        final boolean[] recovered = {false};
        Fight<SimEntity> missing = Fight.<SimEntity>builder().attack(new Aura(1)).vitals(vitals).build();
        arena.sandbox.flows().start("fight", missing.against(c -> arena.sandbox.tracked(arena.enemy))
                .stuckAfter(Span.ticks(10), Flow.action("reposition", c -> recovered[0] = true)), 50);
        arena.sandbox.run(15);
        Checks.check("given vitals, a fight that does not hurt the target is noticed by stuckAfter", recovered[0]);

        Arena landing = new Arena();
        Map<SimEntity, Double> hurt = new HashMap<>();
        hurt.put(landing.enemy, 100d);
        Aura hitting = new Aura(1);
        hitting.onStruck = () -> hurt.put(landing.enemy, hurt.get(landing.enemy) - 1);
        final boolean[] stuck = {false};
        Fight<SimEntity> fight = Fight.<SimEntity>builder().attack(hitting).vitals((Vitals<SimEntity>) hurt::get).build();
        landing.sandbox.flows().start("fight", fight.against(c -> landing.sandbox.tracked(landing.enemy))
                .stuckAfter(Span.ticks(10), Flow.action("reposition", c -> stuck[0] = true)), 50);
        landing.sandbox.run(30);
        Checks.check("and one that does is not (" + hurt.get(landing.enemy) + " health left)",
                !stuck[0] && hurt.get(landing.enemy) < 80);
    }

    private static void missing() {
        Arena arena = new Arena();
        Fight<SimEntity> blind = Fight.<SimEntity>builder().attack(new Aura(1)).eyes(() -> null).build();
        FlowHandle flow = arena.sandbox.flows().start("fight", blind.against(c -> arena.sandbox.tracked(arena.enemy)), 50);
        arena.sandbox.run(2);
        Checks.check("eyes that are unknown fail the fight, saying so (" + flow.getReason() + ")",
                flow.getState() == FlowHandle.State.FAILED && flow.getReason().contains("eyes"));

        Arena given = new Arena();
        Aura aura = new Aura(1000);
        Vec3 eyes = Vec3.of(0.5, 70, 0.5);
        given.sandbox.flows().start("fight",
                Fight.<SimEntity>builder().attack(aura).eyes(() -> eyes).build().against(c -> given.sandbox.tracked(given.enemy)), 50);
        given.sandbox.run(1);
        Checks.check("eyes you give are the ones its parts see", eyes.equals(aura.lastEyes));
    }

    // ------------------------------------------------------------- crystals

    private static void crystals() {
        Arena arena = new Arena();
        final boolean[] holding = {true};
        CrystalAttack<Object> attack = CrystalAttack.<Object>builder()
                .search(arena.search()).placing(() -> holding[0]).build();
        Fight<Object> fight = Fight.<Object>builder().attack(attack).build();
        Fight.Round<Object> round = fight.against(c -> arena.foes.get(arena.enemy));
        arena.sandbox.flows().start("crystals", round, 50);
        arena.sandbox.tick();
        Checks.check("with crystals in hand and none to break, it places: a use, facing the block clicked ("
                + round.getLastStrike() + ")", arena.me.getClicks().equals(Collections.singletonList("click USE"))
                && attack.getPlaces() == 1 && round.getLastStrike().getButton() == Click.USE);

        dev.px.core.math.Box first = round.getLastStrike().getFacing();
        arena.sandbox.tick();
        Checks.check("the search hears where it went, so the next place keeps off that base until a crystal shows up ("
                        + attack.getPlaces() + " placed)",
                attack.getPlaces() == 2 && !round.getLastStrike().getFacing().getCenter().equals(first.getCenter()));

        Vec3 base = first.getCenter();
        SimEntity crystal = arena.sandbox.entity("crystal", Vec3.of(base.getX(), 64, base.getZ()), tick -> MovementInput.none(0f))
                .tag("crystal");
        holding[0] = false;
        arena.sandbox.run(2);
        Checks.check("once one is there, it breaks it: an attack facing the crystal (" + round.getLastStrike() + ")",
                arena.me.getClicks().contains("click ATTACK") && attack.getBreaks() >= 1
                        && round.getLastStrike().getButton() == Click.ATTACK);
        crystal.remove();
        arena.sandbox.run(2);
        int clicks = arena.me.getClicks().size();
        arena.sandbox.run(3);
        Checks.check("with nothing to break and no crystals in hand, it does nothing",
                arena.me.getClicks().size() == clicks && round.getLastStrike() == Strike.none());

        Arena blocked = new Arena();
        CrystalAttack<Object> denied = CrystalAttack.<Object>builder()
                .search(blocked.search()).placing(() -> true).build();
        blocked.sandbox.flows().start("crystals",
                Fight.<Object>builder().attack(denied).build().against(c -> blocked.foes.get(blocked.enemy)), 50);
        blocked.sandbox.flows().start("look up", Flow.step("look up").uses(Control.ROTATION).tick(c -> {
            c.look(Vec2.rotation(0f, -90f));
            return Status.RUNNING;
        }), 80);
        blocked.sandbox.run(3);
        Checks.check("a place whose click a higher claim kept from going out is never told to the search",
                blocked.me.getClicks().isEmpty() && denied.getPlaces() == 0);
    }

    private static void builders() {
        Checks.checkThrows("a Fight without an attack is refused", IllegalStateException.class,
                () -> Fight.<SimEntity>builder().build());
        try {
            CrystalAttack.<SimEntity>builder().build();
        } catch (IllegalStateException refused) {
            Checks.checkEquals("a CrystalAttack names every missing part", "a CrystalAttack needs: search, placing",
                    refused.getMessage());
        }
        Checks.checkEquals("strikes describe themselves", "Strike(at (1.00, 2.00, 3.00), click ATTACK, when facing)",
                Strike.at(Vec3.of(1, 2, 3)).click(Click.ATTACK)
                        .whenFacing(dev.px.core.math.Box.of(0, 0, 0, 1, 1, 1)).toString());
    }

    // ---------------------------------------------------------------- parts

    /** A killaura's brain: aims at the target's centre and swings every {@code every} ticks once heard. */
    private static final class Aura implements Attack<SimEntity> {

        private final int every;
        private boolean facing = true;
        int struck;
        int sinceSwing;
        Vec3 lastEyes;
        Runnable onStruck = () -> { };

        Aura(int every) {
            this.every = every;
            this.sinceSwing = every;
        }

        Aura facing(boolean facing) {
            this.facing = facing;
            return this;
        }

        @Override
        public Strike tick(Bout<? extends SimEntity> b) {
            lastEyes = b.eyes();
            Strike look = Strike.at(b.target().getCenter());
            if (++sinceSwing < every) {
                return look;
            }
            Strike swing = look.click(Click.ATTACK);
            return facing ? swing.whenFacing(b.target().getBox()) : swing;
        }

        @Override
        public void struck(Bout<? extends SimEntity> b, Strike strike) {
            struck++;
            sinceSwing = 0;
            onStruck.run();
        }
    }

    /** An aura tied to its client's own code: it acts itself and logs what the step told it. */
    private static final class Driver implements Attack<SimEntity> {

        final List<String> log = new ArrayList<>();
        int ticks;

        @Override
        public boolean drives() {
            return true;
        }

        @Override
        public void start(Bout<? extends SimEntity> b) {
            log.add(b.context().isResuming() ? "start resuming" : "start");
        }

        @Override
        public Strike tick(Bout<? extends SimEntity> b) {
            ticks++;
            return Strike.at(b.target().getCenter()).click(Click.ATTACK);
        }

        @Override
        public void stop(Bout<? extends SimEntity> b, StopReason why) {
            log.add("stop " + why);
        }
    }

    // ---------------------------------------------------------------- arena

    /** A sandbox with a floor whose top is at y = 64, you at the origin and an enemy three blocks along x. */
    private static final class Arena {

        final Sandbox sandbox = Sandbox.create();
        final SimPlayer me;
        final SimEntity enemy;
        final EntityTracker<SimEntity> players;
        final EntityTracker<SimEntity> crystals;
        /** The enemy again, typed as the crystal search needs: its player is a SimPlayer, so the search is over Object. */
        final EntityTracker<Object> foes;

        Arena() {
            sandbox.world().floor(64, -12, 12);
            me = sandbox.player(Vec3.of(0.5, 64, 0.5)).eyeHeight(1.5);
            enemy = sandbox.entity("enemy", Vec3.of(3.5, 64, 0.5), tick -> MovementInput.none(0f)).tag("enemy");
            players = sandbox.entities().register(EntityTracker.of(SimEntity.class, e -> "enemy".equals(e.getTag())));
            crystals = sandbox.entities().register(EntityTracker.of(SimEntity.class, e -> "crystal".equals(e.getTag())));
            foes = sandbox.entities().register(EntityTracker.of(Object.class,
                    e -> e instanceof SimEntity && "enemy".equals(((SimEntity) e).getTag())));
        }

        Vec3 eyes() {
            return me.getPosition().add(0, me.getEyeHeight(), 0);
        }

        Tracked<SimEntity> target() {
            return sandbox.tracked(enemy);
        }

        /** A crystal search over the sandbox's world, by the test's own rules. */
        CrystalSearch<Object> search() {
            ExplosionModel<Object> model = ExplosionModel.<Object>builder()
                    .measureFrom(Tracked::getPosition)
                    .exposure(Exposure.sampled(SampleGrid.uniform(3, 5, 3)))
                    .falloff(WIKI)
                    .mitigation(Mitigation.none())
                    .build();
            CrystalRules<Object> rules = CrystalRules.<Object>builder()
                    .placement(Placement.clearance((x, y, z) -> y == 63 && sandbox.world().isSolid(x, y, z), 1,
                            (x, y, z) -> !sandbox.world().isSolid(x, y, z), 2))
                    .body(CrystalBody.at(0.5, 1, 0.5).size(2, 2).explodingAt(0, 0, 0))
                    .explosive(Explosive.of("end crystal", 6))
                    .model(model)
                    .blocks(sandbox.world())
                    .obstructions(Obstructions.of(sandbox.entities(), foes, crystals))
                    .build();
            return CrystalSearch.<Object>builder()
                    .rules(rules)
                    .entities(sandbox.entities())
                    .targets(sandbox.targets(), TargetSelector.from(foes).range(20).build())
                    .crystals(crystals)
                    .vitals(e -> 36)
                    .placeReach(Reach.of(5, 5))
                    .breakReach(Reach.of(5, 5))
                    .thresholds(Thresholds.<Object>none())
                    .bus(sandbox.bus())
                    .build();
        }
    }
}
