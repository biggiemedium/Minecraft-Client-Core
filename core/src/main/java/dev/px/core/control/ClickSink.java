package dev.px.core.control;

/**
 * Presses the buttons.
 *
 * <p>Two methods, because a button is either clicked once or held, and the game
 * treats the two differently: a click attacks or places once, a hold keeps
 * eating, drawing a bow or breaking a block for as long as it lasts.
 *
 * <pre>{@code
 * public final class PlayerClickSink implements ClickSink {
 *
 *     public void click(Click button) {
 *         if (button == Click.ATTACK) {
 *             mc.clickMouse();
 *         } else {
 *             mc.rightClickMouse();
 *         }
 *     }
 *
 *     public void setHeld(Click button, boolean held) {
 *         KeyBinding key = button == Click.ATTACK ? mc.gameSettings.keyBindAttack : mc.gameSettings.keyBindUseItem;
 *         KeyBinding.setKeyBindState(key.getKeyCode(), held);
 *     }
 * }
 * }</pre>
 *
 * <p>Called on the game thread and must not block. {@link #setHeld} is called
 * only when a hold starts or ends, never every tick, and a hold always ends with
 * {@code setHeld(button, false)} &mdash; including when its claim simply lapsed.
 */
public interface ClickSink {

    /** Presses and lets go of {@code button} once, this tick. */
    void click(Click button);

    /** Starts or ends holding {@code button} down. */
    void setHeld(Click button, boolean held);
}
