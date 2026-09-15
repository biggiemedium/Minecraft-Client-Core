package dev.px.core.test.suite;

import dev.px.core.config.ConfigLoadEvent;
import dev.px.core.config.ConfigService;
import dev.px.core.hud.Anchor;
import dev.px.core.hud.HudLayout;
import dev.px.core.render.Color;
import dev.px.core.test.example.ExampleKillAura;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON config profiles.
 *
 * <p>Replaces the old scheme of five text files per profile parsed by splitting
 * on colons, which is why its loader carried a comment about not working. Every
 * section round-trips here, and a section that fails does not take the others
 * with it.
 */
public final class ConfigTests {

    private static final String PROFILE = "suite-profile";

    private ConfigTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Config");
        client.reset();

        ConfigService config = client.getCore().getConfigService();
        ExampleKillAura aura = client.getKillAura();
        HudLayout clockLayout = client.layout("clock");

        AtomicInteger loadEvents = new AtomicInteger();
        client.getCore().getBus().on(ConfigLoadEvent.class, event -> loadEvents.incrementAndGet());

        // ---- set a distinctive state across every section --------------------
        aura.getReach().set(5.5f);
        aura.getRotationMode().set(ExampleKillAura.RotationMode.LOCKED);
        aura.getTargets().toggle(ExampleKillAura.Target.ANIMALS);
        aura.getHitboxColour().set(Color.of(9, 8, 7, 6));
        aura.enable();
        client.getSprint().disable();

        client.getCore().getThemeService().setActive("Sunset");
        client.getCore().getSocialService().add("Notch");
        client.getCore().getNotificationService().getMaxVisible().set(9);

        clockLayout.setAnchor(Anchor.BOTTOM_RIGHT);
        clockLayout.setOffsetX(-21f);
        clockLayout.setOffsetY(-13f);
        clockLayout.setScale(1.75f);
        clockLayout.setZOrder(4);
        clockLayout.setLocked(true);
        client.getClock().getShowSeconds().set(false);

        config.save(PROFILE);
        Checks.check("the profile appears on disk", config.exists(PROFILE));
        Checks.check("and in the listing", config.listProfiles().contains(PROFILE));

        // ---- scramble everything -----------------------------------------------
        aura.getReach().set(3f);
        aura.getRotationMode().set(ExampleKillAura.RotationMode.INSTANT);
        aura.getTargets().clear();
        aura.getHitboxColour().set(Color.WHITE);
        aura.disable();
        client.getSprint().enable();

        client.getCore().getThemeService().setActive("Froggy");
        client.getCore().getSocialService().remove("Notch");
        client.getCore().getNotificationService().getMaxVisible().set(2);

        clockLayout.copyFrom(HudLayout.at(Anchor.TOP_LEFT, 0f, 0f));
        client.getClock().getShowSeconds().set(true);

        // ---- and load it back ---------------------------------------------------
        loadEvents.set(0);
        config.load(PROFILE);

        Checks.checkEquals("a module's number setting round-trips", 5.5f, aura.getReach().getFloat());
        Checks.check("its enum round-trips", aura.getRotationMode().is(ExampleKillAura.RotationMode.LOCKED));
        Checks.check("its multi-select round-trips", aura.getTargets().has(ExampleKillAura.Target.ANIMALS));
        Checks.checkEquals("its colour round-trips", "#06090807", aura.getHitboxColour().get().toHex());
        Checks.check("enabled state round-trips", aura.isEnabled());
        Checks.check("and a module switched off stays off", !client.getSprint().isEnabled());

        Checks.checkEquals("the theme round-trips",
                "Sunset", client.getCore().getThemeService().getActive().getName());
        Checks.check("friends round-trip", client.getCore().getSocialService().isFriend("notch"));
        Checks.checkEquals("service settings round-trip",
                9f, client.getCore().getNotificationService().getMaxVisible().getInt());

        Checks.check("a HUD anchor round-trips", clockLayout.getAnchor() == Anchor.BOTTOM_RIGHT);
        Checks.checkEquals("HUD offsets round-trip", -21f, clockLayout.getOffsetX());
        Checks.checkEquals("HUD scale round-trips", 1.75f, clockLayout.getScale());
        Checks.checkEquals("HUD z-order round-trips", 4f, clockLayout.getZOrder());
        Checks.check("the HUD lock flag round-trips", clockLayout.isLocked());
        Checks.check("an element's own settings round-trip", !client.getClock().getShowSeconds().isOn());

        Checks.checkEquals("the profile becomes active", PROFILE, config.getActiveProfile());
        Checks.checkEquals("a load event is posted so derived state can rebuild", 1f, loadEvents.get());

        // ---- a second load must not corrupt list-shaped sections ------------------
        // Regression: SocialService and AccountService replaced their lists by
        // iterating Registry.all(), a live view, and threw on any second load.
        Checks.checkSurvives("loading a second time does not throw", () -> config.load(PROFILE));
        Checks.check("and list sections survive it",
                client.getCore().getSocialService().isFriend("notch"));
        Checks.checkEquals("without duplicating entries",
                1f, client.getCore().getSocialService().getFriends().size());

        // ---- resilience ------------------------------------------------------------
        Checks.checkSurvives("loading a profile that does not exist keeps defaults",
                () -> config.load("no-such-profile"));

        Checks.check("deleting a profile reports success", config.delete(PROFILE));
        Checks.check("deleting it twice reports failure", !config.delete(PROFILE));

        // A profile name cannot escape the config directory.
        config.save("../../escape");
        Checks.check("a path-traversing profile name is sanitised",
                !config.listProfiles().contains("../../escape"));
        config.delete("../../escape");

        clockLayout.setLocked(false);
        config.load("default");
    }
}
