package dev.px.core.test.suite;

import com.google.gson.JsonElement;
import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.math.Range;
import dev.px.core.render.Color;
import dev.px.core.setting.Setting;
import dev.px.core.setting.SettingHolder;
import dev.px.core.setting.Settings;
import dev.px.core.setting.impl.BindSetting;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.setting.impl.EnumSetting;
import dev.px.core.setting.impl.GroupSetting;
import dev.px.core.setting.impl.MultiEnumSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.setting.impl.RangeSetting;
import dev.px.core.setting.impl.StringSetting;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;
import dev.px.core.test.example.ExampleKillAura;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Settings: declaration, coercion, visibility and persistence.
 *
 * <p>The headline is that {@link Fixture} below never calls a registration
 * method. Declaring the field is the registration, which is what removed the
 * {@code create(new Setting<>(...))} wrapper from every line of every module,
 * along with the bug class where forgetting it left a setting invisible and
 * unsaved.
 */
public final class SettingTests {

    private SettingTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Settings");

        Fixture fixture = new Fixture();

        // ---- discovery ------------------------------------------------------
        Checks.check("settings are discovered without any registration call",
                fixture.getSettings().size() > 0);
        Checks.check("declaration order is preserved",
                fixture.getSettings().get(0) == fixture.flag);
        Checks.check("a setting is findable by name",
                fixture.findSetting("amount").isPresent());
        Checks.check("lookup is case-insensitive",
                fixture.findSetting("AMOUNT").isPresent());

        // A group's children are nested under it, not repeated at the top level.
        Checks.check("group children are not duplicated at the top level",
                !fixture.getSettings().contains(fixture.nestedA));
        Checks.check("group children are still reachable in the flattened list",
                fixture.getAllSettings().contains(fixture.nestedA));

        // ---- numbers ---------------------------------------------------------
        fixture.amount.set(999f);
        Checks.checkEquals("a number clamps to its maximum", 10f, fixture.amount.getFloat());
        fixture.amount.set(-999f);
        Checks.checkEquals("a number clamps to its minimum", 1f, fixture.amount.getFloat());
        fixture.amount.set(5.27f);
        Checks.checkEquals("step snaps the value", 5.5f, fixture.amount.getFloat());
        fixture.amount.set(5.5f);
        Checks.checkEquals("progress reports the slider position", 0.5f, fixture.amount.progress());
        fixture.amount.setProgress(1f);
        Checks.checkEquals("setProgress drives from a slider", 10f, fixture.amount.getFloat());

        NumberSetting<Integer> counter = Settings.integer("Counter", 5, 0, 10);
        counter.set(7);
        Checks.checkEquals("an integer setting keeps whole numbers", 7f, counter.getInt());

        // ---- ranges ------------------------------------------------------------
        fixture.window.set(Range.of(18d, 4d));
        Checks.check("a range normalises inverted bounds",
                fixture.window.get().getLower() < fixture.window.get().getUpper());
        double sample = fixture.window.randomValue();
        Checks.check("a random value falls inside the range", fixture.window.get().contains(sample));

        // ---- enums ---------------------------------------------------------------
        Checks.check("an enum setting starts on its default", fixture.mode.is(Mode.SECOND));
        fixture.mode.cycle();
        Checks.check("cycle advances", fixture.mode.is(Mode.THIRD));
        fixture.mode.cycle();
        Checks.check("cycle reaches the last constant", fixture.mode.is(Mode.FOURTH_OPTION));
        fixture.mode.cycle();
        Checks.check("cycle wraps around to the first", fixture.mode.is(Mode.FIRST));
        fixture.mode.cycleBack();
        Checks.check("cycleBack wraps the other way", fixture.mode.is(Mode.FOURTH_OPTION));
        fixture.mode.set(Mode.THIRD);
        Checks.checkEquals("constants get a readable label", "Third", fixture.mode.displayValue());
        Checks.checkEquals("underscores become spaced words", "Fourth Option",
                Settings.enumOf("m", Mode.FOURTH_OPTION).displayValue());

        // ---- multi-select ----------------------------------------------------------
        Checks.check("multi-enum applies its defaults", fixture.flags.has(Mode.FIRST));
        Checks.check("and excludes the rest", !fixture.flags.has(Mode.THIRD));
        fixture.flags.toggle(Mode.THIRD);
        Checks.check("toggle adds", fixture.flags.has(Mode.THIRD));
        fixture.flags.toggle(Mode.THIRD);
        Checks.check("toggle removes", !fixture.flags.has(Mode.THIRD));
        Checks.check("hasAny matches on one", fixture.flags.hasAny(Mode.THIRD, Mode.FIRST));
        fixture.flags.selectAll();
        Checks.checkEquals("selectAll reads as All", "All", fixture.flags.displayValue());
        fixture.flags.clear();
        Checks.checkEquals("clear reads as None", "None", fixture.flags.displayValue());

        // ---- strings ------------------------------------------------------------------
        fixture.label.set("a-very-long-label-indeed");
        Checks.checkEquals("a string truncates to its maximum length", 8f, fixture.label.get().length());
        fixture.label.set("");
        Checks.checkEquals("a validator rejects bad input, keeping the old value",
                8f, fixture.label.get().length());

        // ---- colours --------------------------------------------------------------------
        fixture.tint.set(Color.of(10, 20, 30, 40));
        Checks.checkEquals("a colour round-trips through hex", "#280A141E", fixture.tint.get().toHex());
        fixture.tint.rainbow(true);
        Checks.checkEquals("rainbow mode is reported in the label", "Rainbow", fixture.tint.displayValue());
        Checks.check("resolve returns a live colour in rainbow mode",
                fixture.tint.resolve().getAlpha() == 40);
        fixture.tint.rainbow(false);

        // ---- binds -----------------------------------------------------------------------
        fixture.key.set(Bind.of(Key.R, Modifier.CTRL));
        Checks.checkEquals("a bind renders with its modifier", "Ctrl+R", fixture.key.displayValue());
        Bind roundTripped = Bind.deserialize(fixture.key.get().serialize());
        Checks.checkEquals("a bind survives serialisation", "Ctrl+R", roundTripped.getDisplay());
        Checks.check("an unknown key name degrades to unbound",
                !Bind.deserialize("KEY:NOT_A_KEY").isBound());

        // ---- visibility --------------------------------------------------------------------
        fixture.flag.set(true);
        Checks.check("a dependent setting is visible when its condition holds", fixture.dependent.isVisible());
        fixture.flag.set(false);
        Checks.check("and hidden when it does not", !fixture.dependent.isVisible());
        Checks.check("an unconditional setting is always visible", fixture.amount.isVisible());
        fixture.flag.set(true);

        // ---- change notification -------------------------------------------------------------
        AtomicInteger changes = new AtomicInteger();
        BooleanSetting watched = Settings.bool("Watched", false).onChange(value -> changes.incrementAndGet());
        watched.set(true);
        Checks.checkEquals("onChange fires on a real change", 1f, changes.get());
        watched.set(true);
        Checks.checkEquals("setting the same value changes nothing", 1f, changes.get());
        watched.setSilently(false);
        Checks.checkEquals("setSilently skips listeners, as a config load needs", 1f, changes.get());

        // ---- defaults and reset ----------------------------------------------------------------
        Checks.check("a modified setting knows it is off-default", !fixture.amount.isDefault());
        fixture.resetSettings();
        Checks.check("reset restores every setting", fixture.amount.isDefault() && fixture.mode.is(Mode.SECOND));

        // ---- persistence -------------------------------------------------------------------------
        roundTrip("boolean", Settings.bool("b", false), true);
        roundTrip("number", Settings.number("n", 1f, 0f, 10f), 7.5f);
        roundTrip("integer", Settings.integer("i", 1, 0, 10), 6);
        roundTrip("string", Settings.text("s", "a"), "hello");
        roundTrip("enum", Settings.enumOf("e", Mode.FIRST), Mode.THIRD);
        roundTrip("colour", Settings.color("c", Color.WHITE), Color.of(1, 2, 3, 4));
        roundTrip("bind", Settings.bind("k"), Bind.of(Key.F, Modifier.SHIFT));
        roundTrip("range", Settings.range("r", 2d, 8d, 0d, 10d), Range.of(3d, 6d));

        // Malformed input must leave the value alone rather than fail the load.
        NumberSetting<Float> resilient = Settings.number("n", 5f, 0f, 10f);
        resilient.fromJson(new com.google.gson.JsonObject());
        Checks.checkEquals("a malformed value leaves the setting untouched", 5f, resilient.getFloat());

        EnumSetting<Mode> removed = Settings.enumOf("e", Mode.FIRST);
        removed.fromJson(new com.google.gson.JsonPrimitive("A_CONSTANT_THAT_NO_LONGER_EXISTS"));
        Checks.check("an enum constant deleted since the config was written keeps the default",
                removed.is(Mode.FIRST));

        // ---- the real module ----------------------------------------------------------------------
        ExampleKillAura aura = client.getKillAura();
        Checks.check("the inherited keybind sorts above a module's own settings",
                aura.getSettings().get(0) == aura.getKeybind());
        Checks.check("a real module declares many settings with no registration calls",
                aura.getSettings().size() >= 8);
        Checks.check("a module's group nests its children",
                aura.getAllSettings().contains(aura.getAutoBlock())
                        && !aura.getSettings().contains(aura.getAutoBlock()));
    }

    /** Sets a value, serialises, resets, deserialises, and checks it came back. */
    private static <T> void roundTrip(String label, Setting<T> setting, T value) {
        setting.set(value);
        JsonElement json = setting.toJson();
        setting.reset();
        setting.fromJson(json);
        Checks.checkEquals("a " + label + " setting round-trips through JSON", value, setting.get());
    }

    public enum Mode { FIRST, SECOND, THIRD, FOURTH_OPTION }

    /**
     * A settings holder written the way a real one is: fields and nothing else.
     *
     * <p>Package-private fields so the suite can poke them; a real holder would
     * keep them private and expose behaviour.
     */
    static final class Fixture extends SettingHolder {

        final BooleanSetting flag = bool("Flag", true);
        final NumberSetting<Float> amount = number("Amount", 4f, 1f, 10f).step(0.5f);
        final RangeSetting window = range("Window", 4d, 18d, 0d, 20d);
        final EnumSetting<Mode> mode = enumOf("Mode", Mode.SECOND);
        final MultiEnumSetting<Mode> flags = multi("Flags", Mode.class, Mode.FIRST, Mode.SECOND);
        final StringSetting label = text("Label", "default")
                .maxLength(8)
                .validatedBy(text -> !text.isEmpty());
        final ColorSetting tint = color("Tint", Color.WHITE);
        final BindSetting key = bind("Key");
        final BooleanSetting dependent = bool("Dependent", true).visibleWhen(flag);

        final BooleanSetting nestedA = bool("Nested A", true);
        final NumberSetting<Integer> nestedB = integer("Nested B", 3, 0, 5);
        final GroupSetting group = group("Advanced").containing(nestedA, nestedB);

        @Override
        public String getName() {
            return "Fixture";
        }
    }
}
