package dev.px.gui.test.visual;

import dev.px.core.layout.Bounds;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.font.Font;
import dev.px.gui.Container;
import dev.px.gui.Screen;
import dev.px.gui.Widget;
import dev.px.gui.legacy.Component;
import dev.px.gui.render.WidgetRenderer;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shows a GUI tree as outlines and labels, so layout and hit areas can be seen.
 *
 * <pre>{@code
 * Wireframe wireframe = new Wireframe();
 * look.register(wireframe.renderer());          // anything the look doesn't cover
 *
 * screen.update(mouseX, mouseY);
 * screen.draw();
 * wireframe.draw(screen, mouseX, mouseY);       // what the update found
 * }</pre>
 *
 * <p>Two jobs:
 *
 * <ul>
 *   <li>{@link #renderer()} is the renderer for {@code Widget.class}, so it
 *       claims every widget type nothing more specific does, and describes it
 *       as an outline with its type's name. That is what a headless widget with
 *       no renderer looks like in the harness.</li>
 *   <li>{@link #draw(Screen, float, float)} is the inspector drawn over a screen:
 *       every widget outlined at its bounds in a colour for its depth, its class
 *       name when {@link #isLabels() labels} are on, every named part outlined
 *       and named, and for the widget under the cursor its hit shape traced
 *       and the path down the tree to it.</li>
 * </ul>
 *
 * <p>Test tooling, never shipped, and the one place in the GUI's tests that picks
 * colours. It reads only what the tree already exposes &mdash; bounds, parts,
 * shapes &mdash; and draws only through {@link Render}, so it works on any
 * {@code Render2D}, including the recording one the suite checks it with.
 *
 * <p>{@link #draw(Component, float, float)} inspects the legacy click GUI's tree
 * the same way, with its focus and drag, and goes when that GUI does.
 */
public final class Wireframe {

    /** Outline colours by depth, so a child reads as distinct from its parent. */
    private static final Color[] DEPTH = {
            Color.of(120, 200, 255, 200),
            Color.of(140, 230, 160, 200),
            Color.of(250, 210, 110, 200),
            Color.of(240, 140, 200, 200),
            Color.of(170, 160, 255, 200),
    };

    private static final Color PART = Color.of(255, 120, 90, 230);
    private static final Color HIT = Color.of(255, 255, 255);
    private static final Color FOCUS = Color.of(80, 255, 220);
    private static final Color DRAG = Color.of(255, 230, 60);
    private static final Color LABEL = Color.of(225, 230, 240);
    private static final Color LABEL_BACK = Color.of(10, 12, 16, 200);
    private static final Color UNRENDERED = Color.of(200, 200, 210, 160);

    private static final float LABEL_PADDING = 2f;

    /** The font labels are drawn in, or null for the default one. */
    @Getter
    @Setter
    private Font font;

    /** Whether every node is labelled with its class name, not only the hovered one. */
    @Getter
    @Setter
    private boolean labels = true;

    /** Whether named parts are outlined and named. */
    @Getter
    @Setter
    private boolean parts = true;

    // -------------------------------------------------------------- widgets

    /**
     * @return a renderer for {@code Widget.class}: an outline and the type's
     *         name, for any leaf widget nothing more specific claims
     *
     * <p>A container it claims is left bare, a slot and nothing else, exactly as
     * the library leaves a container with no renderer: a plain layout row has no
     * look to stand in for.
     */
    public WidgetRenderer<Widget> renderer() {
        return WidgetRenderer.of(Widget.class, (widget, c) -> {
            if (widget instanceof Container) {
                c.slot();
                return;
            }
            c.backdrop((x, y, w, h) -> Render.rectOutline(x, y, w, h, 1f, UNRENDERED));
            c.padding(LABEL_PADDING);
            if (font == null) {
                c.text(nameOf(widget), UNRENDERED);
            } else {
                c.text(font, nameOf(widget), UNRENDERED);
            }
        });
    }

    /**
     * Draws a screen's tree as its last update laid it out.
     *
     * <p>Update the screen first; this measures nothing and changes nothing. A
     * hidden widget is not drawn, because it takes no part in layout or hit
     * testing either. Popups and the tooltip are outlined above the root, as
     * they are drawn.
     */
    public void draw(Screen screen, float mouseX, float mouseY) {
        if (screen == null) {
            return;
        }
        if (screen.getRoot().isVisible()) {
            outline(screen.getRoot(), 0);
        }
        for (Widget popup : screen.getPopups()) {
            outline(popup, 0);
        }
        if (screen.getTooltip() != null) {
            outline(screen.getTooltip(), 0);
        }

        Widget hovered = screen.widgetAt(mouseX, mouseY);
        if (hovered != null) {
            hovered.getShape().stroke(2f, HIT);
            List<String> path = new ArrayList<>();
            for (Widget node = hovered; node != null; node = node.getParent()) {
                path.add(0, nameOf(node));
            }
            describe(path, hovered.getBounds(), screen.getWidth(), mouseX, mouseY);
        }
    }

    private void outline(Widget widget, int depth) {
        box(widget.getBounds(), depth, nameOf(widget), widget.parts());
        List<Widget> children = widget instanceof Container
                ? ((Container) widget).getPlaced() : Collections.<Widget>emptyList();
        for (Widget child : children) {
            outline(child, depth + 1);
        }
    }

    // --------------------------------------------------------------- legacy

    /**
     * Draws the legacy click GUI's tree under {@code root} as it was last laid
     * out, with its focus and drag marked.
     */
    public void draw(Component root, float mouseX, float mouseY) {
        if (root == null || !root.isVisible()) {
            return;
        }
        outline(root, 0);

        dev.px.gui.legacy.Screen screen = root.getScreen();
        if (screen != null) {
            mark(screen.getFocused(), FOCUS, "focused");
            mark(screen.getDragging(), DRAG, "dragging");
        }

        Component hovered = root.componentAt(mouseX, mouseY);
        if (hovered != null) {
            hovered.resolvedShape().stroke(2f, HIT);
            List<String> path = new ArrayList<>();
            for (Component node = hovered; node != null; node = node.getParent()) {
                path.add(0, nameOf(node));
            }
            dev.px.gui.legacy.Screen host = hovered.getScreen();
            describe(path, hovered.getBounds(), host == null ? Float.MAX_VALUE : host.getScreenWidth(),
                    mouseX, mouseY);
        }
    }

    private void outline(Component component, int depth) {
        box(component.getBounds(), depth, nameOf(component), component.parts());
        for (Component child : component.visibleChildren()) {
            outline(child, depth + 1);
        }
    }

    /** A thicker outline and a word, for the one component holding focus or a drag. */
    private void mark(Component component, Color color, String what) {
        if (component == null || !component.isVisible()) {
            return;
        }
        Bounds at = component.getBounds();
        Render.rectOutline(at.getX(), at.getY(), at.getWidth(), at.getHeight(), 2f, color);
        label(what, at.getRight() - textWidth(what) - LABEL_PADDING * 2f, at.getY() + 1f);
    }

    // -------------------------------------------------------------- drawing

    /** One node: its outline, its name, and its named parts. */
    private void box(Bounds at, int depth, String name, Map<String, Bounds> named) {
        Color color = DEPTH[depth % DEPTH.length];
        Render.rectOutline(at.getX(), at.getY(), at.getWidth(), at.getHeight(), 1f, color);
        if (labels) {
            label(name, at.getX() + 1f, at.getY() + 1f);
        }
        if (parts) {
            for (Map.Entry<String, Bounds> part : named.entrySet()) {
                Bounds rect = part.getValue();
                Render.rectOutline(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), 1f, PART);
                label(part.getKey(), rect.getX(), rect.getBottom() + 1f);
            }
        }
    }

    /**
     * The path from the root to the hovered node and its bounds, beside the
     * cursor, flipped to stay on screen.
     */
    private void describe(List<String> path, Bounds at, float screenWidth, float mouseX, float mouseY) {
        String text = String.join(" > ", path) + "   " + format(at);
        float width = textWidth(text) + LABEL_PADDING * 2f;
        float x = mouseX + 12f;
        if (x + width > screenWidth) {
            x = Math.max(0f, mouseX - 12f - width);
        }
        label(text, x, mouseY + 12f);
    }

    private void label(String text, float x, float y) {
        float width = textWidth(text);
        float height = font == null ? Render.textHeight() : font.getHeight();
        Render.rect(x, y, width + LABEL_PADDING * 2f, height + LABEL_PADDING * 2f, LABEL_BACK);
        if (font == null) {
            Render.text(text, x + LABEL_PADDING, y + LABEL_PADDING, LABEL);
        } else {
            Render.text(font, text, x + LABEL_PADDING, y + LABEL_PADDING, LABEL);
        }
    }

    private float textWidth(String text) {
        return font == null ? Render.textWidth(text) : font.widthOf(text);
    }

    /** The class name, or the class it extends for an anonymous one. */
    static String nameOf(Object node) {
        Class<?> type = node.getClass();
        while (type.isAnonymousClass()) {
            type = type.getSuperclass();
        }
        return type.getSimpleName();
    }

    private static String format(Bounds at) {
        return Math.round(at.getX()) + "," + Math.round(at.getY())
                + "  " + Math.round(at.getWidth()) + "x" + Math.round(at.getHeight());
    }
}
