package dev.px.gui.render;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.layout.Shape;
import dev.px.core.registry.Named;
import dev.px.gui.Widget;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * How one type of widget looks: the client's half of every widget.
 *
 * <pre>{@code
 * look.register(WidgetRenderer.of(Button.class, (button, c) -> {
 *     c.background(button.isHovered() ? HOVER : BASE, 3f).padding(6f, 4f).align(Align.CENTER);
 *     c.text(button.getLabel(), Color.WHITE);
 * }));
 * }</pre>
 *
 * <p>A renderer <b>describes</b> a widget with {@link Content}, reading its state
 * &mdash; hovered, enabled, a button's label &mdash; and the library measures,
 * lays out, hit tests and draws that one description, as it does a HUD element's.
 * The widget is as big as what is described, and looks like it, so size and
 * look cannot disagree. No widget describes itself and no look ships: every
 * pixel is the client's.
 *
 * <p>Name the parts a widget type reads, with
 * {@link Content#custom(String, float, float, dev.px.core.layout.Draw)}, and the
 * rectangle drawn is the rectangle clicked. A container's renderer marks where
 * its children go with {@link Content#slot()}.
 *
 * <p>The library sets the width of the box it hands in; use {@code min} for a
 * floor rather than {@code width}.
 *
 * @param <W> the widget type this describes
 */
public interface WidgetRenderer<W extends Widget> extends Named {

    /**
     * The widget class this renderer claims.
     *
     * <p>Matched with {@link Class#isInstance}, the most specific claim winning,
     * so a renderer for a subclass of {@code Button} takes over for that subclass
     * alone.
     */
    Class<?> getWidgetType();

    /**
     * Describes one widget, once a frame.
     *
     * @param widget the widget, to read its state from
     * @param content its root box, a column
     */
    void describe(W widget, Content content);

    /**
     * @return the widget's clickable region within its bounds, or null for the
     *         bounds themselves
     *
     * <p>For a widget whose look is not a rectangle, such as a round button.
     */
    default Shape shape(W widget, Bounds bounds) {
        return null;
    }

    /** Registry key. The widget type's name, which is unique by construction. */
    @Override
    default String getName() {
        return getWidgetType().getSimpleName();
    }

    // ------------------------------------------------------------ factories

    /**
     * The common case: a lambda over the widget and its content.
     *
     * <p>Takes a {@code Class<W>} so the lambda's parameter type is inferred and
     * {@code (button, c) -> ...} compiles without spelling it out.
     */
    static <W extends Widget> WidgetRenderer<W> of(Class<W> type, BiConsumer<W, Content> describe) {
        return of(type, describe, null);
    }

    /** As {@link #of(Class, BiConsumer)}, with a clickable shape of its own. */
    static <W extends Widget> WidgetRenderer<W> of(Class<W> type, BiConsumer<W, Content> describe,
                                                   BiFunction<W, Bounds, Shape> shape) {
        return new WidgetRenderer<W>() {

            @Override
            public Class<?> getWidgetType() {
                return type;
            }

            @Override
            public void describe(W widget, Content content) {
                describe.accept(widget, content);
            }

            @Override
            public Shape shape(W widget, Bounds bounds) {
                return shape == null ? null : shape.apply(widget, bounds);
            }
        };
    }
}
