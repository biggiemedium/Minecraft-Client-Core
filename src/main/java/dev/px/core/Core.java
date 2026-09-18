package dev.px.core;

import dev.px.core.account.AccountService;
import dev.px.core.command.CommandRegistry;
import dev.px.core.concurrent.ThreadService;
import dev.px.core.config.ConfigService;
import dev.px.core.config.SettingsSection;
import dev.px.core.config.ToggleableSection;
import dev.px.core.event.EventBus;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.impl.ClientLifecycleEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.gui.GuiService;
import dev.px.core.hud.HudService;
import dev.px.core.input.InputService;
import dev.px.core.integration.IntegrationService;
import dev.px.core.module.CategoryRegistry;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleRegistry;
import dev.px.core.module.ThreadedModule;
import dev.px.core.notification.NotificationService;
import dev.px.core.platform.Platform;
import dev.px.core.render.Render;
import dev.px.core.render.font.FontService;
import dev.px.core.render.theme.ThemeService;
import dev.px.core.service.ServiceContainer;
import dev.px.core.shader.ShaderService;
import dev.px.core.setting.SettingChangeEvent;
import dev.px.core.social.SocialService;
import dev.px.core.util.ConsoleLogger;
import dev.px.core.util.CoreLogger;
import lombok.Getter;

/**
 * The client. One instance, reached through static accessors.
 *
 * <p>Replaces the old pattern of eighteen public static manager fields assigned
 * in a hand-ordered block whose constraints were documented in comments. Services
 * declare their dependencies and a {@link ServiceContainer} orders startup, so
 * adding one cannot silently break an invariant nobody remembers.
 *
 * <p>Typical bootstrap from a version adapter:
 *
 * <pre>{@code
 * Core core = Core.builder("LeapFrog", "2.0")
 *         .platform(new ForgePlatform())
 *         .logger(new Log4jLogger(LOGGER))
 *         .build();
 *
 * core.categories().registerAll(Categories.class);
 * core.themes().register(Theme.of("Froggy", Color.rgb(0xADF773), Color.rgb(0x80F393)));
 * core.modules().registerAll(new KillAura(), new Sprint(), new Fullbright());
 * core.commands().registerAll(new ConfigCommand(), new FriendCommand());
 *
 * Render.install(new NanoVGRender2D());
 * Render.install(new LegacyRender3D());
 *
 * core.start();
 * }</pre>
 *
 * <p>Registration happens between {@code build()} and {@code start()}: categories
 * must exist before modules resolve against them, and themes before a saved
 * selection is looked up.
 */
@Getter
public final class Core {

    private static Core instance;

    private final String clientName;
    private final String clientVersion;

    private final CoreLogger logger;
    private final Platform platform;
    private final EventBus bus;
    private final ServiceContainer services;

    private final CategoryRegistry categories = new CategoryRegistry();
    private final ThreadService threadService;
    private final ConfigService configService;
    private final ModuleRegistry moduleRegistry;
    private final CommandRegistry commandRegistry;
    private final InputService inputService;
    private final FontService fontService;
    private final ThemeService themeService;
    private final NotificationService notificationService;
    private final SocialService socialService;
    private final AccountService accountService;
    private final IntegrationService integrationService;
    private final HudService hudService;
    private final GuiService guiService;
    private final ShaderService shaderService;

    private boolean started;

    Core(CoreBuilder builder) {
        this.clientName = builder.clientName;
        this.clientVersion = builder.clientVersion;
        this.platform = builder.platform;
        this.logger = builder.logger != null ? builder.logger : new ConsoleLogger(builder.clientName);
        this.bus = new CoreEventBus(logger);
        this.services = new ServiceContainer(logger);

        // Statics the lower layers post through. Injected rather than reached via
        // this class, so the setting, module and render packages stay independently
        // testable and free of a dependency on Core itself.
        SettingChangeEvent.bindBus(bus);
        Module.bindBus(bus);

        this.threadService = services.register(new ThreadService(logger, builder.threadPoolSize));
        this.configService = services.register(new ConfigService(logger, bus, platform));
        this.fontService = services.register(new FontService(logger));
        this.themeService = services.register(new ThemeService());
        this.notificationService = services.register(new NotificationService());
        this.socialService = services.register(new SocialService());
        this.accountService = services.register(new AccountService(logger, threadService));
        this.integrationService = services.register(new IntegrationService(logger, threadService));
        this.moduleRegistry = new ModuleRegistry(categories, bus);
        this.commandRegistry = services.register(new CommandRegistry(bus, platform, logger));
        this.inputService = services.register(new InputService(bus, moduleRegistry, platform));
        this.hudService = services.register(new HudService(logger, platform));
        this.guiService = services.register(
                new GuiService(logger, platform, moduleRegistry, categories, themeService));
        // Inert until the client installs a ShaderBackend, and says nothing when it
        // does not, so a client that ships no GLSL never learns this service exists.
        this.shaderService = services.register(new ShaderService(logger, platform));

        ThreadedModule.bindThreadService(threadService);

        instance = this;
    }

    public static CoreBuilder builder(String clientName, String clientVersion) {
        return new CoreBuilder(clientName, clientVersion);
    }

    /**
     * Starts every service, applies module defaults, and loads the active config.
     *
     * <p>Call after registering categories, themes, modules and commands.
     */
    public void start() {
        if (started) {
            throw new IllegalStateException(clientName + " is already started");
        }
        // Called by the adapter from the game thread, so this is where that thread
        // can be identified -- which is what lets ThreadService.sync() be checked
        // rather than merely documented.
        threadService.markGameThread();

        services.startAll();

        // Background work parks game-state changes on ThreadService; they are run
        // here, on whichever thread posts the tick. An adapter that posts TickEvent
        // needs nothing else. One that does not must call runPendingSync() itself
        // from its game loop, and ThreadService warns if nobody does either.
        bus.on(TickEvent.class, Priority.HIGHEST, tick -> {
            if (tick.getStage() == Stage.PRE) {
                threadService.runPendingSync();
            }
        });

        registerConfigSections();

        Render.setDefaultFont(fontService.getDefaultFont());

        // Defaults first, then the config, so a saved profile overrides them rather
        // than the other way round.
        moduleRegistry.applyDefaults();
        configService.load();

        started = true;
        logger.info(clientName + " " + clientVersion + " started on " + platform.getGameVersion()
                + " with " + moduleRegistry.size() + " modules");
        bus.post(new ClientLifecycleEvent(ClientLifecycleEvent.Phase.STARTED));
    }

    /** Saves the config and stops every service in reverse order. */
    public void stop() {
        if (!started) {
            return;
        }
        bus.post(new ClientLifecycleEvent(ClientLifecycleEvent.Phase.STOPPING));
        services.stopAll();
        bus.clear();
        started = false;
    }

    /** Registers a shutdown hook so an unclean exit still persists the config. */
    public void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, clientName + "-shutdown"));
    }

    private void registerConfigSections() {
        configService.register(new ToggleableSection<>("modules", moduleRegistry));
        configService.register(new SettingsSection("theme", themeService));
        configService.register(new SettingsSection("notifications", notificationService));
        configService.register(new SettingsSection("integrations", integrationService));
        configService.register(new SettingsSection("commands", commandRegistry.getPrefix()));
        configService.register(socialService);
        configService.register(accountService);
        configService.register(hudService);
        configService.register(guiService);
    }

    // ------------------------------------------------------- static access

    /** @throws IllegalStateException if Core has not been built yet. */
    public static Core get() {
        if (instance == null) {
            throw new IllegalStateException("Core has not been built; call Core.builder(...).build() first");
        }
        return instance;
    }

    public static EventBus bus() {
        return get().bus;
    }

    public static ModuleRegistry modules() {
        return get().moduleRegistry;
    }

    public static CategoryRegistry categories() {
        return get().categories;
    }

    public static CommandRegistry commands() {
        return get().commandRegistry;
    }

    public static ConfigService config() {
        return get().configService;
    }

    /**
     * The background threading and the game-thread queue.
     *
     * <p>Never null, and safe to call before startup or after shutdown: work
     * requested while it is down is dropped with a warning rather than throwing.
     */
    public static ThreadService threads() {
        return get().threadService;
    }

    public static NotificationService notifications() {
        return get().notificationService;
    }

    public static SocialService social() {
        return get().socialService;
    }

    public static AccountService accounts() {
        return get().accountService;
    }

    public static ThemeService themes() {
        return get().themeService;
    }

    public static FontService fonts() {
        return get().fontService;
    }

    public static InputService input() {
        return get().inputService;
    }

    public static IntegrationService integrations() {
        return get().integrationService;
    }

    public static HudService hud() {
        return get().hudService;
    }

    public static GuiService gui() {
        return get().guiService;
    }

    /**
     * Shader compilation, caching and uniform upload.
     *
     * <p>Does nothing at all until a {@link dev.px.core.shader.ShaderBackend} is
     * installed, and passes drawn through it run their body unshaded until one is.
     */
    public static ShaderService shaders() {
        return get().shaderService;
    }

    public static Platform platform() {
        return get().platform;
    }

    public static CoreLogger log() {
        return get().logger;
    }
}
