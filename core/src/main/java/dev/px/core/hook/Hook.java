package dev.px.core.hook;

import dev.px.core.event.Event;
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

/**
 * One moment in the game that your adapter tells Core about, through
 * {@link GameHooks}.
 *
 * <p>Which of these something in your client actually needs is not fixed: it is
 * whoever is listening for the event right now &mdash; Core's own services, and
 * your modules. {@link GameHooks#report()} lists them.
 *
 * <p>A <b>steady</b> hook is one that keeps firing the whole time the player is
 * in a world &mdash; every tick, every frame, every packet &mdash; so its absence
 * is detectable, and {@link GameHooks} warns about it. The rest fire only when
 * the player does something, so their absence proves nothing; check those with
 * {@link GameHooks#verify(Hook...)} after doing it.
 */
public enum Hook {

    TICK(TickEvent.class, true, "tickStart() and tickEnd(), around every game tick"),
    WORLD(WorldEvent.class, true, "worldLoaded(address) on joining a world and worldUnloaded() on leaving"),
    PACKET_IN(PacketEvent.class, true, "packetReceived(packet) as each inbound packet is decoded"),
    PACKET_OUT(PacketEvent.class, true, "packetSent(packet) before each outbound packet is written"),
    MOTION(MotionUpdateEvent.class, true, "motionPre(...) and motionPost(...) around the client's movement report"),
    RENDER_2D(Render2DEvent.class, true, "render2D(...) every frame, over the screen"),
    RENDER_3D(Render3DEvent.class, true, "render3D(...) every frame, in the world"),
    KEY(KeyEvent.class, false, "key(...) on every key press and release"),
    MOUSE(MouseEvent.class, false, "mouse(...) on every button press and release"),
    SCROLL(ScrollEvent.class, false, "scroll(...) on every wheel movement"),
    CHAR_TYPED(CharTypedEvent.class, false, "charTyped(...) for every character typed"),
    CHAT_SEND(ChatSendEvent.class, false, "chatSend(message) before the player's chat is sent"),
    CHAT_RECEIVE(ChatReceiveEvent.class, false, "chatReceived(...) as each chat line arrives"),
    SCREEN(ScreenEvent.class, false, "screen(...) as each game screen opens and closes");

    private final Class<? extends Event> event;
    private final boolean steady;
    private final String call;

    Hook(Class<? extends Event> event, boolean steady, String call) {
        this.event = event;
        this.steady = steady;
        this.call = call;
    }

    /** @return the event this hook posts, which is what anything that needs it listens for */
    public Class<? extends Event> getEvent() {
        return event;
    }

    /** @return whether it fires the whole time the player is in a world, so its absence can be noticed */
    public boolean isSteady() {
        return steady;
    }

    /** @return the {@link GameHooks} method to call, and when */
    public String getCall() {
        return call;
    }
}
