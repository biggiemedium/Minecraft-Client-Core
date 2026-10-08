package dev.px.gui.render;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.layout.Shape;
import dev.px.core.registry.Registry;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import dev.px.gui.Container;
import dev.px.gui.Widget;

import java.util.HashSet;
import java.util.Set;

/**
 * A client's look: the {@link WidgetRenderer}s it registered, and the lookup
 * that finds the one for a widget.
 *
 * <pre>{@code
 * WidgetRendererRegistry look = new WidgetRendererRegistry(core.getLogger());
 * look.register(WidgetRenderer.of(Button.class, (button, c) -> c.text(button.getLabel(), Color.WHITE)));
 * Screen title = new Screen(look, menu);
 * }</pre>
 *
 * <p>The most specific claim wins, as in the HUD's registry. One registry can
 * serve every screen, or a screen can be given a look of its own.
 *
 * <p>A missing or broken renderer never takes a screen down. A leaf widget
 * nothing claims is warned about once per type and lays out empty; a container
 * nothing claims is silent, and lays out as a bare slot. A renderer that throws
 * is logged once per type, and its widget lays out empty for that frame.
 *
 * <p>Game thread only.
 */
public final class WidgetRendererRegistry extends Registry<WidgetRenderer<?>> {

    private final CoreLogger logger;

    /** Types already reported, so a frame-rate problem is logged once rather than every frame. */
    private final Set<Class<?>> warnedMissing = new HashSet<>();
    private final Set<Class<?>> warnedBroken = new HashSet<>();

    public WidgetRendererRegistry(CoreLogger logger) {
        this.logger = Validate.notNull(logger, "logger");
    }

    @Override
    protected String describe() {
        return "widget renderer";
    }

    /**
     * Replaces the renderer for a widget type, if one is registered.
     *
     * <p>Plain {@link #register} rejects a duplicate, which is right when two
     * registrations collide by accident; this is the deliberate version.
     *
     * @return the renderer, so it can be registered and kept in one expression
     */
    public <R extends WidgetRenderer<?>> R replace(R renderer) {
        WidgetRenderer<?> existing = get(renderer.getName());
        if (existing != null) {
            unregister(existing);
        }
        return register(renderer);
    }

    /** @return the renderer that claims this widget, the most specific first, or null. */
    public WidgetRenderer<?> rendererFor(Widget widget) {
        if (widget == null) {
            return null;
        }
        WidgetRenderer<?> best = null;
        for (WidgetRenderer<?> candidate : all()) {
            if (!candidate.getWidgetType().isInstance(widget)) {
                continue;
            }
            if (best == null || best.getWidgetType().isAssignableFrom(candidate.getWidgetType())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Describes a widget with the renderer that claims it.
     *
     * <p>Engine method: a screen calls it once a frame for each widget.
     *
     * @return whether a renderer described it; false when none claims it or the
     *         one that does threw, in which case {@code content} may be half
     *         written and should be thrown away
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean describe(Widget widget, Content content) {
        WidgetRenderer renderer = rendererFor(widget);
        if (renderer == null) {
            if (!(widget instanceof Container) && warnedMissing.add(widget.getClass())) {
                logger.warn("No widget renderer claims " + widget.getClass().getName()
                        + ", so it lays out empty. Register a WidgetRenderer for it."
                        + " (Warned once per widget type.)");
            }
            return false;
        }
        try {
            renderer.describe(widget, content);
            return true;
        } catch (RuntimeException failure) {
            broken(widget, "describing it, so it lays out empty", failure);
            return false;
        }
    }

    /**
     * @return the clickable shape the claiming renderer gives a widget at its
     *         bounds, or null for the bounds themselves
     *
     * <p>Engine method, called once a widget is laid out.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Shape shapeOf(Widget widget, Bounds bounds) {
        WidgetRenderer renderer = rendererFor(widget);
        if (renderer == null) {
            return null;
        }
        try {
            return renderer.shape(widget, bounds);
        } catch (RuntimeException failure) {
            broken(widget, "giving its shape, so it is clicked by its bounds", failure);
            return null;
        }
    }

    private void broken(Widget widget, String consequence, RuntimeException failure) {
        if (warnedBroken.add(widget.getClass())) {
            logger.error("The widget renderer for " + widget.getClass().getName() + " threw while "
                    + consequence + " for as long as it throws. (Logged once per widget type.)", failure);
        }
    }
}
