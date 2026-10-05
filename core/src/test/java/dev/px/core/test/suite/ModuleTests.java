package dev.px.core.test.suite;

import dev.px.core.event.Stage;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleToggleEvent;
import dev.px.core.util.math.RotationMath;
import dev.px.core.test.example.ExampleCategories;
import dev.px.core.test.example.ExampleKillAura;
import dev.px.core.test.example.ExampleSprint;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

/** Module identity, category resolution, toggle lifecycle and keybinds. */
public final class ModuleTests {

    private ModuleTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Modules");
        client.reset();

        ExampleKillAura aura = client.getKillAura();
        ExampleSprint sprint = client.getSprint();

        // ---- identity from the annotation ------------------------------------
        Checks.checkEquals("name comes from @ModuleInfo", "Kill Aura", aura.getName());
        Checks.checkEquals("so does the description",
                "Attacks nearby targets", aura.getDescription());
        Checks.check("category resolves against the registry",
                aura.getCategory() == ExampleCategories.COMBAT);
        Checks.check("a second module resolves its own category",
                sprint.getCategory() == ExampleCategories.MOVEMENT);
        Checks.checkEquals("display info appends to the ArrayList label",
                "Kill Aura Smooth", aura.getDisplayName());

        // ---- registry lookups -------------------------------------------------
        Checks.check("lookup by class", client.getCore().getModuleRegistry().get(ExampleKillAura.class) == aura);
        Checks.check("lookup by name ignores case",
                client.getCore().getModuleRegistry().get("kill aura") == aura);
        Checks.checkEquals("filtering by category", 1f,
                client.getCore().getModuleRegistry().inCategory(ExampleCategories.COMBAT).size());
        Checks.checkThrows("a module without @ModuleInfo fails loudly at construction",
                IllegalStateException.class, Unannotated::new);

        // ---- defaults ----------------------------------------------------------
        Checks.check("a module marked enabled starts on", sprint.isEnabled());
        Checks.check("others start off", !aura.isEnabled());
        Checks.check("the default is readable", sprint.isEnabledByDefault());

        // ---- toggle lifecycle ----------------------------------------------------
        AtomicInteger toggles = new AtomicInteger();
        client.getCore().getBus().on(ModuleToggleEvent.class, event -> toggles.incrementAndGet());

        int enablesBefore = aura.getEnableCount();
        aura.enable();
        Checks.check("enabling flips the flag", aura.isEnabled());
        Checks.checkEquals("onEnable ran once", enablesBefore + 1f, aura.getEnableCount());
        Checks.checkEquals("a toggle event was posted", 1f, toggles.get());

        aura.enable();
        Checks.checkEquals("enabling an enabled module does nothing",
                enablesBefore + 1f, aura.getEnableCount());
        Checks.checkEquals("and posts nothing", 1f, toggles.get());

        int disablesBefore = aura.getDisableCount();
        aura.toggle();
        Checks.check("toggle flips the other way", !aura.isEnabled());
        Checks.checkEquals("onDisable ran", disablesBefore + 1f, aura.getDisableCount());

        // ---- subscription is permanent -------------------------------------------
        aura.resetCounters();
        aura.disable();
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.checkEquals("a disabled module is quiet", 0f, aura.getPreTicks());

        aura.enable();
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.checkEquals("an enabled module hears events again without re-subscribing",
                1f, aura.getPreTicks());
        Checks.check("the module is subscribed throughout",
                client.getCore().getBus().isSubscribed(aura));

        // ---- the decision a module actually computes ---------------------------------
        // Core has no entity, so the target is handed in. Everything after that
        // is real: a reach gate, a solved rotation, and a turn traced over ticks.
        aura.resetCounters();
        aura.enable();

        Vec3 eye = Vec3.of(0d, 0d, 0d);
        Vec3 behind = Vec3.of(0d, 0d, -3d);          // directly north, 180 degrees away
        Vec2 wanted = eye.rotationTo(behind);

        aura.getRotationMode().set(ExampleKillAura.RotationMode.SMOOTH);
        aura.aimAt(eye, behind);
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        // 180 degrees away, so one 30-degree step must leave exactly 150 to go.
        // Snapping straight onto the target is the bug this pins down.
        Checks.checkEquals("a smooth turn moves one step per tick, no more", 150f,
                RotationMath.difference(aura.getAim(), wanted));

        for (int tick = 0; tick < 12; tick++) {
            client.getCore().getBus().post(new TickEvent(Stage.PRE));
        }
        Checks.check("a smooth turn converges on the target",
                RotationMath.difference(aura.getAim(), wanted) < 1f);

        // Out of reach: the head must not move, however close the aim already is.
        aura.resetCounters();
        aura.aimAt(eye, Vec3.of(0d, 0d, -50d));
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.checkEquals("a target beyond reach moves nothing",
                Vec2.rotation(0f, 0f), aura.getAim());
        Checks.checkEquals("and is never attacked", 0, aura.getAttacks());

        // INSTANT is the other half of that distinction: one tick, fully turned.
        aura.resetCounters();
        aura.getRotationMode().set(ExampleKillAura.RotationMode.INSTANT);
        aura.aimAt(eye, behind);
        client.getCore().getBus().post(new TickEvent(Stage.PRE));
        Checks.checkEquals("an instant turn arrives in one tick", 0f,
                RotationMath.difference(aura.getAim(), wanted));
        aura.getRotationMode().set(ExampleKillAura.RotationMode.SMOOTH);

        // The timer paces the attacks; a burst of ticks is not a burst of swings.
        aura.resetCounters();
        aura.aimAt(eye, behind);
        for (int tick = 0; tick < 50; tick++) {
            client.getCore().getBus().post(new TickEvent(Stage.PRE));
        }
        Checks.check("attacks are paced by CPS, not fired once per tick (" + aura.getAttacks() + ")",
                aura.getAttacks() < 5);

        aura.disable();
        Checks.check("disabling drops the target", aura.getTargetPosition() == null);
        aura.resetCounters();

        // ---- keybinds ---------------------------------------------------------------
        aura.getKeybind().set(Bind.of(Key.R, Modifier.SHIFT));
        boolean before = aura.isEnabled();
        client.getCore().getBus().post(new KeyEvent(Key.R, EnumSet.of(Modifier.SHIFT), true));
        Checks.check("a matching bind toggles the module", aura.isEnabled() != before);

        boolean after = aura.isEnabled();
        client.getCore().getBus().post(new KeyEvent(Key.R, EnumSet.noneOf(Modifier.class), true));
        Checks.checkEquals("the same key without the modifier does not", after, aura.isEnabled());

        client.getCore().getBus().post(new KeyEvent(Key.LEFT_SHIFT, EnumSet.of(Modifier.SHIFT), true));
        Checks.checkEquals("a modifier key alone never fires a bind", after, aura.isEnabled());

        // Binds only act in game, so a keystroke in a menu cannot silently flip state.
        client.getPlatform().setInGame(false);
        boolean inMenu = aura.isEnabled();
        client.getCore().getBus().post(new KeyEvent(Key.R, EnumSet.of(Modifier.SHIFT), true));
        Checks.checkEquals("binds are inert outside the game", inMenu, aura.isEnabled());
        client.getPlatform().setInGame(true);

        aura.getKeybind().set(Bind.NONE);

        // ---- bulk operations -----------------------------------------------------------
        aura.enable();
        sprint.enable();
        client.getCore().getModuleRegistry().disableAll();
        Checks.check("disableAll switches everything off",
                client.getCore().getModuleRegistry().enabled().isEmpty());

        aura.enable();
        Checks.check("the ArrayList lists enabled, visible modules",
                !client.getCore().getModuleRegistry().arrayListEntries().isEmpty());
        aura.getVisible().set(false);
        Checks.check("an element hidden from the ArrayList is excluded",
                client.getCore().getModuleRegistry().arrayListEntries().isEmpty());
        aura.getVisible().set(true);
        aura.disable();
    }

    /** Missing its annotation on purpose, to prove the failure is immediate and clear. */
    static final class Unannotated extends Module {
    }
}
