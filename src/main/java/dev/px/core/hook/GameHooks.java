package dev.px.core.hook;

import dev.px.core.concurrent.ThreadService;
import dev.px.core.event.EventBus;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.CharTypedEvent;
import dev.px.core.event.impl.ChatReceiveEvent;
import dev.px.core.event.impl.ChatSendEvent;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.MotionUpdateEvent;
import dev.px.core.event.impl.MouseEvent;
import dev.px.core.event.impl.PacketEvent;
import dev.px.core.event.impl.Render2DEvent;
import dev.px.core.event.impl.Render3DEvent;
import dev.px.core.event.impl.ScreenEvent;
import dev.px.core.event.impl.ScrollEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.math.Vec2;
import dev.px.core.movement.simulation.MotionState;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.platform.Platform;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

/**
 * Everything your adapter tells Core about the game, in one place.
 *
 * <p>Core never hooks the game itself. Your mixins call these methods at the
 * moments they name, and each one posts the event Core's services and your
 * modules listen for. This class is the checklist: if a method here is never
 * called, whatever listens for its event goes idle.
 *
 * <pre>{@code
 * // in your mixins, one line each
 * Core.hooks().tickStart();                                    // head of the game tick
 * Core.hooks().tickEnd();                                      // tail of the game tick
 * Core.hooks().worldLoaded(serverAddress);
 * if (Core.hooks().packetSent(packet)) ci.cancel();            // returns whether it was cancelled
 * if (Core.hooks().key(keyOf(code), mods, true)) ci.cancel();     // keyOf: your game code -> Key table
 * }</pre>
 *
 * <p>Posting the events yourself still works: this class counts an event
 * however it was posted.
 *
 * <h2>Nothing fails silently</h2>
 *
 * <p>For the {@linkplain Hook#isSteady() steady} hooks &mdash; ticks, the world,
 * packets, motion, rendering &mdash; Core notices when one is needed and never
 * arrives: once the player has been in a world for {@link #setGracePeriodMillis
 * a few seconds}, it logs one warning per missing hook, naming the method to call
 * and everything left idle without it. Something is "needed" when something is
 * listening for it right now, so a hook nothing uses never warns.
 *
 * <p>The rest &mdash; keys, mouse, chat, screens &mdash; fire only when the player
 * does something, so their absence proves nothing. Check those, and everything
 * else, in your development build: press a key, click, send a chat message,
 * then call {@link #verify()}, which throws listing every hook something is
 * waiting on that has never fired. {@link #report()} gives the whole table.
 *
 * <p>Thread-safe: packets are hooked from the network thread, everything else
 * from the game thread.
 */
public final class GameHooks implements Service {

    /** In a world for this long before a steady hook nobody calls is warned about. A tuning knob. */
    public static final long DEFAULT_GRACE_PERIOD_MILLIS = 5000L;

    private static final long NONE = Long.MIN_VALUE;
    private static final String SELF = GameHooks.class.getSimpleName();

    private final CoreLogger logger;
    private final EventBus bus;
    private final Platform platform;
    private final ThreadService threads;
    private final LongSupplier clock;
    private final Observer observer = new Observer();
    private final AtomicLongArray fired = new AtomicLongArray(Hook.values().length);

    // Guarded by this: the self-check's state.
    private final Set<Hook> warned = EnumSet.noneOf(Hook.class);
    private final long[] neededSince = new long[Hook.values().length];
    private long inGameSince = NONE;
    private long gracePeriodNanos = DEFAULT_GRACE_PERIOD_MILLIS * 1_000_000L;
    private boolean warnedAboutPlatform;

    private ScheduledFuture<?> checking;

    public GameHooks(CoreLogger logger, EventBus bus, Platform platform, ThreadService threads) {
        this(logger, bus, platform, threads, System::nanoTime);
    }

    /**
     * @param threads runs the self-check once a second; null to run it only when
     *        {@link #checkNow()} is called
     * @param nanoClock monotonic nanoseconds; injectable so a test can run it by hand
     */
    public GameHooks(CoreLogger logger, EventBus bus, Platform platform, ThreadService threads, LongSupplier nanoClock) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        this.platform = Validate.notNull(platform, "platform");
        this.threads = threads;
        this.clock = Validate.notNull(nanoClock, "nanoClock");
        Arrays.fill(neededSince, NONE);
    }

    @Override
    public String getName() {
        return "Hooks";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Service>[] dependsOn() {
        return threads != null ? new Class[] { ThreadService.class } : new Class[0];
    }

    @Override
    public void start() {
        bus.subscribe(observer);
        if (threads != null) {
            checking = threads.repeat(this::checkNow, 1L, 1L, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        if (checking != null) {
            checking.cancel(false);
            checking = null;
        }
        bus.unsubscribe(observer);
    }

    // ---------------------------------------------------------------- ticks

    /** At the head of every game tick, before the game updates anything. */
    public void tickStart() {
        bus.post(new TickEvent(Stage.PRE));
    }

    /** At the tail of every game tick, after the game has updated. */
    public void tickEnd() {
        bus.post(new TickEvent(Stage.POST));
    }

    // ---------------------------------------------------------------- world

    /**
     * When the player joins a world, including a dimension change.
     *
     * @param serverAddress as the player typed it, such as {@code "play.example.net:25577"};
     *        empty or null in singleplayer
     */
    public void worldLoaded(String serverAddress) {
        bus.post(new WorldEvent(true, serverAddress == null ? "" : serverAddress));
    }

    /** When the player leaves the world: disconnecting, or quitting to the menu. */
    public void worldUnloaded() {
        bus.post(new WorldEvent(false, ""));
    }

    // -------------------------------------------------------------- packets

    /**
     * Before an outbound packet is written. Pass the game's own packet object; your
     * {@code PacketDescriber} is what reads it.
     *
     * @return whether a handler cancelled it, in which case do not send it
     */
    public boolean packetSent(Object packet) {
        return bus.post(PacketEvent.sent(packet)).isCancelled();
    }

    /**
     * As an inbound packet is decoded, on the network thread: its true arrival.
     *
     * @return whether a handler cancelled it, in which case do not handle it
     */
    public boolean packetReceived(Object packet) {
        return bus.post(PacketEvent.received(packet)).isCancelled();
    }

    /**
     * Optional: as that packet's handler runs on the game thread, with the
     * <b>same</b> packet instance as {@link #packetReceived}, so the two are linked.
     * A packet given only this counts as arriving here.
     *
     * @return whether a handler cancelled it
     */
    public boolean packetApplied(Object packet) {
        return bus.post(PacketEvent.applied(packet)).isCancelled();
    }

    // --------------------------------------------------------------- motion

    /** Before the client reports its movement to the server: what it is about to report. */
    public void motionPre(MotionState motion, MovementInput input, boolean sprinting, boolean sneaking,
                          Vec2 rotation, Vec2 previousRotation) {
        bus.post(new MotionUpdateEvent(Stage.PRE, motion, input, sprinting, sneaking, rotation, previousRotation));
    }

    /** After the client reported its movement. */
    public void motionPost(MotionState motion, MovementInput input, boolean sprinting, boolean sneaking,
                           Vec2 rotation, Vec2 previousRotation) {
        bus.post(new MotionUpdateEvent(Stage.POST, motion, input, sprinting, sneaking, rotation, previousRotation));
    }

    // ---------------------------------------------------------------- input

    /**
     * On every key press and release, while no game screen has the keyboard.
     * Module keybinds run off this.
     *
     * @param modifiers held modifiers; null for none
     * @return whether a handler cancelled it, in which case the game should ignore the key
     */
    public boolean key(Key key, Set<Modifier> modifiers, boolean pressed) {
        return bus.post(new KeyEvent(key, orNone(modifiers), pressed)).isCancelled();
    }

    /** On every mouse button press and release. @return whether a handler cancelled it */
    public boolean mouse(MouseButton button, Set<Modifier> modifiers, boolean pressed, float x, float y) {
        return bus.post(new MouseEvent(button, orNone(modifiers), pressed, x, y)).isCancelled();
    }

    /** On every scroll-wheel movement. @return whether a handler cancelled it */
    public boolean scroll(float amount, float x, float y) {
        return bus.post(new ScrollEvent(amount, x, y)).isCancelled();
    }

    /** For every character typed. @return whether a handler cancelled it */
    public boolean charTyped(char character) {
        return bus.post(new CharTypedEvent(character)).isCancelled();
    }

    // ----------------------------------------------------------------- chat

    /**
     * Before the player's chat message is sent. Commands run off this.
     *
     * @return the event: if cancelled, do not send; otherwise send
     *         {@link ChatSendEvent#getMessage()}, which a handler may have rewritten
     */
    public ChatSendEvent chatSend(String message) {
        return bus.post(new ChatSendEvent(message));
    }

    /**
     * As a chat line arrives.
     *
     * @return the event: if cancelled, do not show it; otherwise show
     *         {@link ChatReceiveEvent#getMessage()}, which a handler may have rewritten
     */
    public ChatReceiveEvent chatReceived(String message, String plainText) {
        return bus.post(new ChatReceiveEvent(message, plainText));
    }

    // -------------------------------------------------------------- screens

    /**
     * As each game screen opens or closes.
     *
     * @param screen the game's own screen object
     * @return whether a handler cancelled it
     */
    public boolean screen(Object screen, boolean opening) {
        return bus.post(new ScreenEvent(screen, opening)).isCancelled();
    }

    // ------------------------------------------------------------ rendering

    /**
     * Every frame, over the screen. For your modules' own drawing: it does
     * <b>not</b> draw the HUD, which you draw with {@code Core.hud().drawAll()}.
     */
    public void render2D(float partialTicks, float screenWidth, float screenHeight) {
        bus.post(new Render2DEvent(partialTicks, screenWidth, screenHeight));
    }

    /** Every frame, in the world, with the camera set up for world-space drawing. */
    public void render3D(float partialTicks) {
        bus.post(new Render3DEvent(partialTicks));
    }

    // ---------------------------------------------------------- diagnostics

    /** @return how many times the hook's event has been posted since Core started, however it was posted */
    public long timesFired(Hook hook) {
        return fired.get(hook.ordinal());
    }

    public boolean hasFired(Hook hook) {
        return timesFired(hook) > 0;
    }

    /** @return who is listening for the hook's event right now: what goes idle without it */
    public List<String> listenersOf(Hook hook) {
        List<String> listeners = new ArrayList<>(bus.listenersOf(hook.getEvent()));
        listeners.remove(SELF);
        return listeners;
    }

    /** @return every hook, how often it has fired, and who is waiting on it */
    public List<HookStatus> report() {
        List<HookStatus> report = new ArrayList<>();
        for (Hook hook : Hook.values()) {
            report.add(new HookStatus(hook, timesFired(hook), listenersOf(hook)));
        }
        return report;
    }

    /**
     * Fails if anything is waiting on a hook that has never fired.
     *
     * <p>For your development build or adapter test, never for players: play for a
     * few seconds, press a key, click, send a chat message, then call this. A hook
     * nothing listens for is not required.
     *
     * @throws IllegalStateException listing each missing hook, the method to call,
     *         and what is idle without it
     */
    public void verify() {
        List<HookStatus> missing = new ArrayList<>();
        for (HookStatus status : report()) {
            if (status.isMissing()) {
                missing.add(status);
            }
        }
        fail(missing);
    }

    /**
     * Fails unless every one of {@code hooks} has fired, whether or not anything
     * is listening for it yet.
     *
     * @throws IllegalStateException listing each that has not
     */
    public void verify(Hook... hooks) {
        List<HookStatus> missing = new ArrayList<>();
        for (Hook hook : hooks) {
            if (!hasFired(hook)) {
                missing.add(new HookStatus(hook, 0L, listenersOf(hook)));
            }
        }
        fail(missing);
    }

    /** @param millis how long the player must be in a world before a missing steady hook is warned about */
    public void setGracePeriodMillis(long millis) {
        Validate.check(millis >= 0L, "grace period must not be negative");
        synchronized (this) {
            this.gracePeriodNanos = millis * 1_000_000L;
        }
    }

    /**
     * Runs the self-check now. A timer does this once a second; call it yourself
     * only where there is no timer.
     *
     * <p>Warns once per steady hook that something has been waiting on for the
     * whole grace period while the player was in a world, and that has never
     * fired. Reads {@link Platform#isInGame()} from the calling thread.
     */
    public synchronized void checkNow() {
        long now = clock.getAsLong();
        if (!inGame()) {
            inGameSince = NONE;
            Arrays.fill(neededSince, NONE);
            return;
        }
        if (inGameSince == NONE) {
            inGameSince = now;
        }
        for (Hook hook : Hook.values()) {
            if (!hook.isSteady() || warned.contains(hook) || hasFired(hook)) {
                continue;
            }
            List<String> listeners = listenersOf(hook);
            if (listeners.isEmpty()) {
                neededSince[hook.ordinal()] = NONE;
                continue;
            }
            if (neededSince[hook.ordinal()] == NONE) {
                neededSince[hook.ordinal()] = now;
            }
            long waiting = now - Math.max(inGameSince, neededSince[hook.ordinal()]);
            if (waiting >= gracePeriodNanos) {
                warned.add(hook);
                logger.warn("No " + hook.getEvent().getSimpleName() + " after "
                        + (waiting / 1_000_000_000L) + "s in a world, so these are idle: "
                        + String.join(", ", listeners) + ". Call Core.hooks()." + hook.getCall()
                        + " from your adapter (or post " + hook.getEvent().getSimpleName() + " yourself).");
            }
        }
    }

    // ------------------------------------------------------------ internals

    private boolean inGame() {
        try {
            return platform.isInGame();
        } catch (RuntimeException e) {
            if (!warnedAboutPlatform) {
                warnedAboutPlatform = true;
                logger.error("Platform.isInGame() threw; the hook self-check is skipping"
                        + " (further failures are not logged)", e);
            }
            return false;
        }
    }

    private static Set<Modifier> orNone(Set<Modifier> modifiers) {
        return modifiers != null ? modifiers : Collections.<Modifier>emptySet();
    }

    private static void fail(List<HookStatus> missing) {
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(missing.size() == 1
                ? "1 game hook has never fired:"
                : missing.size() + " game hooks have never fired:");
        for (HookStatus status : missing) {
            message.append("\n  ").append(status.getHook())
                    .append(": call Core.hooks().").append(status.getHook().getCall());
            if (status.isNeeded()) {
                message.append(". Idle without it: ").append(String.join(", ", status.getListeners()));
            }
        }
        throw new IllegalStateException(message.toString());
    }

    private void count(Hook hook) {
        fired.incrementAndGet(hook.ordinal());
    }

    /**
     * Counts every event a hook posts, however it was posted, so an adapter that
     * posts events itself is not warned about. First in line and sees cancelled
     * events, so nothing a handler does hides one.
     */
    private final class Observer {

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onTick(TickEvent event) {
            count(Hook.TICK);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onWorld(WorldEvent event) {
            count(Hook.WORLD);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onPacket(PacketEvent event) {
            count(event.isInbound() ? Hook.PACKET_IN : Hook.PACKET_OUT);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onMotion(MotionUpdateEvent event) {
            count(Hook.MOTION);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onRender2D(Render2DEvent event) {
            count(Hook.RENDER_2D);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onRender3D(Render3DEvent event) {
            count(Hook.RENDER_3D);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onKey(KeyEvent event) {
            count(Hook.KEY);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onMouse(MouseEvent event) {
            count(Hook.MOUSE);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onScroll(ScrollEvent event) {
            count(Hook.SCROLL);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onChar(CharTypedEvent event) {
            count(Hook.CHAR_TYPED);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onChatSend(ChatSendEvent event) {
            count(Hook.CHAT_SEND);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onChatReceive(ChatReceiveEvent event) {
            count(Hook.CHAT_RECEIVE);
        }

        @Subscribe(priority = Integer.MAX_VALUE, receiveCancelled = true)
        private void onScreen(ScreenEvent event) {
            count(Hook.SCREEN);
        }
    }
}
