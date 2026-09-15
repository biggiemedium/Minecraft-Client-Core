package dev.px.core;

import dev.px.core.command.Command;
import dev.px.core.command.CommandContext;
import dev.px.core.command.CommandInfo;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.ChatSendEvent;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.module.Category;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleInfo;
import dev.px.core.module.ModuleToggleEvent;
import dev.px.core.platform.Platform;
import dev.px.core.render.Color;
import dev.px.core.render.theme.Theme;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.EnumSetting;
import dev.px.core.setting.impl.MultiEnumSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.setting.impl.RangeSetting;

import java.io.File;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end exercise of Core with no game attached.
 *
 * <p>That this runs at all is the point: it proves Core boots, registers,
 * toggles, persists and dispatches without Minecraft anywhere on the classpath.
 */
public final class CoreSmokeTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        File dataDir = Files.createTempDirectory("core-smoke").toFile();
        dataDir.deleteOnExit();

        Core core = Core.builder("SmokeClient", "1.0")
                .platform(new FakePlatform(dataDir))
                .build();

        core.getCategories().registerAll(Categories.class);
        core.getThemeService().register(Theme.of("Froggy", Color.rgb(0xADF773), Color.rgb(0x80F393)));
        core.getThemeService().register(Theme.of("Sunset", Color.rgb(0xFD9115), Color.rgb(0xF56AE6)));

        AimAssist aim = core.getModuleRegistry().register(new AimAssist());
        Sprint sprint = core.getModuleRegistry().register(new Sprint());
        core.getCommandRegistry().register(new EchoCommand());

        AtomicInteger toggles = new AtomicInteger();
        core.getBus().on(ModuleToggleEvent.class, event -> toggles.incrementAndGet());

        core.start();

        // ---------------------------------------------------------- registry
        check("categories registered", core.getCategories().size() == 3);
        check("module resolves category", aim.getCategory() == Categories.COMBAT);
        check("category inferred from annotation", sprint.getCategory() == Categories.MOVEMENT);
        check("lookup by class", core.getModuleRegistry().get(AimAssist.class) == aim);
        check("lookup by name is case-insensitive",
                core.getModuleRegistry().get("aim assist") == aim);
        check("in-category filter", core.getModuleRegistry().inCategory(Categories.COMBAT).size() == 1);

        // ----------------------------------------------------------- settings
        check("settings auto-discovered", aim.getSettings().size() >= 6);
        check("inherited keybind is first", aim.getSettings().get(0) == aim.getKeybind());
        check("number clamps above max", clamped(aim.fov, 400f) == 180f);
        check("number clamps below min", clamped(aim.fov, -50f) == 1f);
        check("integer step snaps", stepped(aim.smoothing, 3) == 3);
        check("range keeps bounds ordered", aim.cps.get().getLower() <= aim.cps.get().getUpper());
        check("multi-enum defaults applied", aim.targets.has(TargetKind.PLAYERS));
        check("multi-enum excludes others", !aim.targets.has(TargetKind.ANIMALS));
        check("visibility predicate honoured", aim.mode.isVisible());
        aim.enabledToggle.set(false);
        check("dependent setting hides", !aim.mode.isVisible());
        aim.enabledToggle.set(true);

        // -------------------------------------------------------------- events
        check("module starts disabled", !aim.isEnabled());
        core.getBus().post(new TickEvent(Stage.PRE));
        check("disabled module receives nothing", aim.preTicks == 0);
        check("ignoreListening handler still runs", aim.alwaysTicks == 1);

        aim.enable();
        check("toggle event posted", toggles.get() == 1);
        check("onEnable ran", aim.enableCount == 1);

        core.getBus().post(new TickEvent(Stage.PRE));
        core.getBus().post(new TickEvent(Stage.POST));
        check("stage filter: PRE handler got one", aim.preTicks == 1);
        check("stage filter: POST handler got one", aim.postTicks == 1);
        check("unfiltered handler got both", aim.alwaysTicks == 3);
        check("priority order respected", aim.firstHandlerRanFirst);

        // ------------------------------------------------------------- keybind
        aim.getKeybind().set(Bind.of(Key.R, Modifier.SHIFT));
        core.getBus().post(new KeyEvent(Key.R, EnumSet.of(Modifier.SHIFT), true));
        check("bind with modifier toggles module", !aim.isEnabled());
        core.getBus().post(new KeyEvent(Key.R, EnumSet.noneOf(Modifier.class), true));
        check("bind requires its modifier", !aim.isEnabled());
        aim.enable();

        // ------------------------------------------------------------ commands
        ChatSendEvent chat = core.getBus().post(new ChatSendEvent(".echo hello world"));
        check("command consumed the chat line", chat.isCancelled());
        check("command received its arguments", "hello world".equals(EchoCommand.lastEcho));

        ChatSendEvent normal = core.getBus().post(new ChatSendEvent("just talking"));
        check("plain chat passes through", !normal.isCancelled());

        // -------------------------------------------------------------- config
        aim.fov.set(42f);
        aim.targets.toggle(TargetKind.ANIMALS);
        core.getThemeService().setActive("Sunset");
        sprint.enable();
        core.getSocialService().add("Notch");
        core.getConfigService().save("test-profile");

        aim.fov.set(1f);
        aim.targets.clear();
        sprint.disable();
        core.getSocialService().remove("Notch");
        core.getThemeService().setActive("Froggy");

        core.getConfigService().load("test-profile");
        check("number round-tripped", aim.fov.getFloat() == 42f);
        check("multi-enum round-tripped", aim.targets.has(TargetKind.ANIMALS));
        check("enabled state round-tripped", sprint.isEnabled());
        check("friends round-tripped", core.getSocialService().isFriend("notch"));
        check("theme round-tripped", "Sunset".equals(core.getThemeService().getActive().getName()));
        check("profile became active", "test-profile".equals(core.getConfigService().getActiveProfile()));
        check("profile listed on disk", core.getConfigService().listProfiles().contains("test-profile"));

        core.stop();
        check("services stopped", !core.getServices().isRunning());

        System.out.println();
        System.out.println(checks - failures + "/" + checks + " checks passed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static float clamped(NumberSetting<Float> setting, float attempt) {
        float previous = setting.getFloat();
        setting.set(attempt);
        float result = setting.getFloat();
        setting.set(previous);
        return result;
    }

    private static int stepped(NumberSetting<Integer> setting, int attempt) {
        setting.set(attempt);
        return setting.getInt();
    }

    private static void check(String description, boolean passed) {
        checks++;
        if (!passed) {
            failures++;
        }
        System.out.println((passed ? "  ok   " : "  FAIL ") + description);
    }

    // ------------------------------------------------------------- fixtures

    enum Categories implements Category {
        COMBAT("Combat"), MOVEMENT("Movement"), RENDER("Render");

        private final String name;

        Categories(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    enum TargetKind { PLAYERS, MOBS, ANIMALS }

    enum RotationMode { INSTANT, SMOOTH }

    @ModuleInfo(name = "Aim Assist", description = "Assists aim", category = "Combat")
    public static final class AimAssist extends Module {

        final BooleanSetting enabledToggle = bool("Rotations", true);
        final EnumSetting<RotationMode> mode = enumOf("Rotation Mode", RotationMode.SMOOTH)
                .visibleWhen(() -> enabledToggle.isOn());
        final NumberSetting<Float> fov = number("FOV", 90f, 1f, 180f);
        final NumberSetting<Integer> smoothing = integer("Smoothing", 5, 1, 10);
        final RangeSetting cps = range("CPS", 8, 14, 1, 20);
        final MultiEnumSetting<TargetKind> targets =
                multi("Targets", TargetKind.class, TargetKind.PLAYERS, TargetKind.MOBS);

        int preTicks;
        int postTicks;
        int alwaysTicks;
        int enableCount;
        boolean firstHandlerRanFirst;

        private boolean highPriorityRan;

        @Override
        protected void onEnable() {
            enableCount++;
        }

        @Subscribe(stage = Stage.PRE, priority = Priority.HIGH)
        private void onPreTick(TickEvent event) {
            highPriorityRan = true;
            preTicks++;
        }

        @Subscribe(stage = Stage.POST)
        private void onPostTick(TickEvent event) {
            postTicks++;
        }

        /** Runs regardless of toggle state, and after the high-priority handler. */
        @Subscribe(ignoreListening = true, priority = Priority.LOW)
        private void onAnyTick(TickEvent event) {
            if (highPriorityRan) {
                firstHandlerRanFirst = true;
            }
            alwaysTicks++;
        }
    }

    @ModuleInfo(name = "Sprint", category = "Movement")
    public static final class Sprint extends Module {

        final BooleanSetting omni = bool("Omnidirectional", false);
    }

    @CommandInfo(name = "echo", aliases = {"say"}, usage = "echo <text>")
    public static final class EchoCommand extends Command {

        static String lastEcho;

        @Override
        public void execute(CommandContext context) {
            lastEcho = context.rest(0);
            context.reply(lastEcho);
        }
    }

    /** A Platform that answers with constants. Enough for Core to run headless. */
    static final class FakePlatform implements Platform {

        private final File dataDirectory;
        private String clipboard = "";

        FakePlatform(File dataDirectory) {
            this.dataDirectory = dataDirectory;
        }

        @Override public String getGameVersion() { return "Headless"; }
        @Override public File getDataDirectory() { return dataDirectory; }
        @Override public String getUsername() { return "Tester"; }
        @Override public boolean isInGame() { return true; }
        @Override public float getScreenWidth() { return 854f; }
        @Override public float getScreenHeight() { return 480f; }
        @Override public float getScreenScale() { return 2f; }
        @Override public float getMouseX() { return 0f; }
        @Override public float getMouseY() { return 0f; }
        @Override public void printMessage(String message) { }
        @Override public void sendChatMessage(String message) { }
        @Override public void setClipboard(String text) { this.clipboard = text; }
        @Override public String getClipboard() { return clipboard; }
    }
}
