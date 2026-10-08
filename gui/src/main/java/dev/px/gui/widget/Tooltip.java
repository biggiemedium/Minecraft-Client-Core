package dev.px.gui.widget;

/**
 * The text a screen shows beside the cursor once it rests on a widget with a
 * {@linkplain dev.px.gui.Widget#tooltip(String) tooltip}.
 *
 * <pre>{@code
 * look.register(WidgetRenderer.of(Tooltip.class, (tip, c) -> {
 *     c.background(PANEL, 3f).padding(4f, 3f);
 *     c.text(tip.getText(), Color.WHITE);
 * }));
 * }</pre>
 *
 * <p>The screen makes and places it; the client only renders it. A look with no
 * renderer for it renders it as the {@link Label} it is.
 *
 * <p>Game thread only.
 */
public final class Tooltip extends Label {

    public Tooltip() {
        super("");
    }
}
