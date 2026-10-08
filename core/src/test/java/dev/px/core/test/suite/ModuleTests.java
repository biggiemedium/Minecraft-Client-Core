package dev.px.core.test.suite;

import dev.px.core.event.Stage;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.MouseEvent;
import dev.px.core.input.Bind;
import dev.px.core.input.BindMode;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleInfo;
import dev.px.core.module.ModuleRegistry;
import dev.px.core.module.toggle.ModuleToggleEvent;
import dev.px.core.setting.Setting;
import dev.px.core.setting.impl.BindSetting;
import dev.px.core.util.math.RotationMath;
import dev.px.core.test.example.ExampleCategories;
import dev.px.core.test.example.ExampleKillAura;
import dev.px.core.test.example.ExampleSprint;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Module identity, category resolution, toggle lifecycle, failed enables, and press and hold binds. */
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
        Checks.checkEquals("display info appends to the label",
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
        Checks.check("a bind given to toggledBy is the module's toggle bind",
                aura.getToggleBind() == aura.getKeybind());
        Checks.check("a module that opts out has no toggle bind", sprint.getToggleBind() == null);
        int sprintBinds = 0;
        for (Setting<?> setting : sprint.getAllSettings()) {
            if (setting instanceof BindSetting) {
                sprintBinds++;
            }
        }
        Checks.checkEquals("and no key setting to save or draw (" + sprint.getAllSettings() + ")",
                0f, sprintBinds);
        Checks.checkThrows("a second toggle bind fails at construction",
                IllegalStateException.class, TwiceBound::new);

        // The module without a bind goes first, so tripping over it would stop the
        // press reaching the one that has a bind.
        ModuleRegistry modules = client.getCore().getModuleRegistry();
        modules.sort(Comparator.comparingInt(module -> module == sprint ? 0 : 1));
        aura.getKeybind().set(Bind.of(Key.R, Modifier.SHIFT));
        boolean before = aura.isEnabled();
        boolean sprintBefore = sprint.isEnabled();
        client.getCore().getBus().post(new KeyEvent(Key.R, EnumSet.of(Modifier.SHIFT), true));
        Checks.check("a matching bind toggles the module, past one without a bind", aura.isEnabled() != before);
        Checks.checkEquals("and leaves a module without a bind alone", sprintBefore, sprint.isEnabled());
        modules.sort(Comparator.comparingInt(module -> module == aura ? 0 : 1));

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

        failures(client);
        holds(client);
    }

    /** An enable that throws or is refused never happened; a disable always does. */
    private static void failures(TestClient client) {
        Fragile fragile = new Fragile();
        List<Boolean> announced = new ArrayList<Boolean>();
        List<Boolean> runningWhenHeard = new ArrayList<Boolean>();
        client.getCore().getBus().on(ModuleToggleEvent.class, event -> {
            if (event.getModule() == fragile) {
                announced.add(event.isNowEnabled());
                runningWhenHeard.add(fragile.running);
            }
        });

        fragile.failOnEnable = true;
        Checks.checkThrows("an onEnable that throws reaches the caller",
                IllegalStateException.class, fragile::enable);
        Checks.check("and leaves the module off", !fragile.isEnabled());
        Checks.check("with its handlers quiet", !fragile.isListening());
        Checks.checkEquals("nothing is told it switched on (" + announced + ")", 0f, announced.size());
        Checks.checkEquals("and onDisable is not run for an enable that never happened",
                0f, fragile.disables);

        fragile.failOnEnable = false;
        fragile.enable();
        Checks.check("once it can start, enabling works", fragile.isEnabled());
        Checks.checkEquals("the switch on is announced once (" + announced + ")", "[true]", announced.toString());
        Checks.checkEquals("after onEnable has run, so a listener sees it running",
                "[true]", runningWhenHeard.toString());

        fragile.failOnDisable = true;
        Checks.checkThrows("an onDisable that throws reaches the caller",
                IllegalStateException.class, fragile::disable);
        Checks.check("and the module still ends off", !fragile.isEnabled());
        Checks.checkEquals("and the switch off is still announced (" + announced + ")",
                "[true, false]", announced.toString());
        fragile.failOnDisable = false;

        fragile.refuse = true;
        fragile.enable();
        Checks.check("an onEnable may refuse by calling disable", !fragile.isEnabled());
        Checks.checkEquals("and a refused enable announces nothing, nor the disable inside it ("
                + announced + ")", "[true, false]", announced.toString());
        fragile.refuse = false;
    }

    /** Hold binds: on while the key is down, off when it comes up. */
    private static void holds(TestClient client) {
        ExampleKillAura aura = client.getKillAura();
        aura.disable();
        aura.getKeybind().set(Bind.hold(Key.H));

        press(client, Key.H);
        Checks.check("a hold bind switches its module on while the key is down", aura.isEnabled());
        int enables = aura.getEnableCount();
        press(client, Key.H);
        Checks.check("a repeated press keeps it on", aura.isEnabled());
        Checks.checkEquals("without enabling it again", (float) enables, aura.getEnableCount());
        release(client, Key.H, EnumSet.noneOf(Modifier.class));
        Checks.check("and off when the key comes up", !aura.isEnabled());

        aura.getKeybind().set(Bind.hold(Key.H, Modifier.SHIFT));
        client.getCore().getBus().post(new KeyEvent(Key.H, EnumSet.of(Modifier.SHIFT), true));
        Checks.check("a hold bind with a modifier starts with it", aura.isEnabled());
        release(client, Key.H, EnumSet.noneOf(Modifier.class));
        Checks.check("and ends when its key comes up, whatever the modifiers by then", !aura.isEnabled());

        aura.getKeybind().set(Bind.hold(Key.H));
        KeyEvent cancelledPress = new KeyEvent(Key.H, EnumSet.noneOf(Modifier.class), true);
        cancelledPress.setCancelled(true);
        client.getCore().getBus().post(cancelledPress);
        Checks.check("a press something cancelled starts no hold", !aura.isEnabled());

        press(client, Key.H);
        KeyEvent cancelledRelease = new KeyEvent(Key.H, EnumSet.noneOf(Modifier.class), false);
        cancelledRelease.setCancelled(true);
        client.getCore().getBus().post(cancelledRelease);
        Checks.check("a release something cancelled still ends the hold", !aura.isEnabled());

        press(client, Key.H);
        client.getPlatform().setInGame(false);
        release(client, Key.H, EnumSet.noneOf(Modifier.class));
        Checks.check("a hold started in game ends though the key comes up in a menu", !aura.isEnabled());
        press(client, Key.H);
        Checks.check("while a press in a menu starts none", !aura.isEnabled());
        client.getPlatform().setInGame(true);

        press(client, Key.H);
        client.getCore().getInputService().releaseAll();
        Checks.check("releaseAll ends every hold", !aura.isEnabled());
        release(client, Key.H, EnumSet.noneOf(Modifier.class));

        aura.getKeybind().set(Bind.hold(MouseButton.BUTTON_4));
        client.getCore().getBus().post(new MouseEvent(MouseButton.BUTTON_4, EnumSet.noneOf(Modifier.class), true, 0f, 0f));
        Checks.check("a mouse button can be held too", aura.isEnabled());
        client.getCore().getBus().post(new MouseEvent(MouseButton.BUTTON_4, EnumSet.noneOf(Modifier.class), false, 0f, 0f));
        Checks.check("and lets go when the button comes up", !aura.isEnabled());
        aura.getKeybind().set(Bind.NONE);

        // ---- actions ---------------------------------------------------------------
        AtomicInteger presses = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        BindSetting zoom = client.getCore().getInputService()
                .register("Zoom", Bind.hold(Key.K), presses::incrementAndGet, releases::incrementAndGet);
        press(client, Key.K);
        Checks.check("a held action runs its press body (" + presses + ", " + releases + ")",
                presses.get() == 1 && releases.get() == 0);
        press(client, Key.K);
        Checks.checkEquals("a repeated press of a held key is not a second press", 1f, presses.get());
        release(client, Key.K, EnumSet.noneOf(Modifier.class));
        Checks.checkEquals("and its release body when the key comes up", 1f, releases.get());

        zoom.setMode(BindMode.PRESS);
        press(client, Key.K);
        release(client, Key.K, EnumSet.noneOf(Modifier.class));
        Checks.check("a press action never runs its release body (" + presses + ", " + releases + ")",
                presses.get() == 2 && releases.get() == 1);
        zoom.clear();
    }

    private static void press(TestClient client, Key key) {
        client.getCore().getBus().post(new KeyEvent(key, EnumSet.noneOf(Modifier.class), true));
    }

    private static void release(TestClient client, Key key, Set<Modifier> modifiers) {
        client.getCore().getBus().post(new KeyEvent(key, modifiers, false));
    }

    /** Missing its annotation on purpose, to prove the failure is immediate and clear. */
    static final class Unannotated extends Module {
    }

    /** A module whose hooks can be told to fail or refuse. */
    @ModuleInfo(name = "Fragile", category = "Combat")
    static final class Fragile extends Module {
        boolean failOnEnable;
        boolean failOnDisable;
        boolean refuse;
        boolean running;
        int disables;

        @Override
        protected void onEnable() {
            if (failOnEnable) {
                throw new IllegalStateException("deliberate enable failure");
            }
            if (refuse) {
                disable();
                return;
            }
            running = true;
        }

        @Override
        protected void onDisable() {
            disables++;
            running = false;
            if (failOnDisable) {
                throw new IllegalStateException("deliberate disable failure");
            }
        }
    }

    /** Asks for two toggle binds, which a module cannot have. */
    @ModuleInfo(name = "Twice Bound", category = "Combat")
    static final class TwiceBound extends Module {
        private final BindSetting first = toggledBy(bind("First"));
        private final BindSetting second = toggledBy(bind("Second"));
    }
}
