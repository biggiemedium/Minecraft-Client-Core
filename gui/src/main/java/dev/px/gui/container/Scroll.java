package dev.px.gui.container;

import dev.px.core.input.MouseButton;
import dev.px.core.layout.Align;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Size;
import dev.px.core.util.Validate;
import dev.px.gui.Widget;
import lombok.Getter;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A column that scrolls: children stacked top to bottom, seen through a
 * viewport, and only the ones in view laid out and drawn.
 *
 * <pre>{@code
 * Scroll modules = window.add(new Scroll().gap(1f)).grow(1f);   // the rest of the window
 * for (Module module : core.getModuleRegistry().all()) {
 *     modules.add(new Button(module.getName()));
 * }
 *
 * look.register(WidgetRenderer.of(Scroll.class, (scroll, c) -> c.row(r -> {
 *     r.slot();                                                 // the viewport
 *     r.custom("track", 3f, 0f, (x, y, w, h) -> {
 *         Bounds thumb = scroll.thumbIn(Bounds.of(x, y, w, h), 12f);
 *         if (thumb != null) {
 *             Render.rect(thumb.getX(), thumb.getY(), thumb.getWidth(), thumb.getHeight(), THUMB);
 *         }
 *     });
 * })));
 * }</pre>
 *
 * <p>The viewport is the renderer's slot. It is as tall as the children, up to
 * {@link #maxHeight}, and shorter when the scroll is given less room &mdash;
 * which is what a {@linkplain Widget#grow growing} scroll in a window gets.
 * Children are clipped to it for drawing and for hit testing, so a row half out
 * of view is cut off at the edge and can't be clicked where it isn't shown.
 *
 * <p>Children outside the viewport are measured, to know where everything is,
 * but neither laid out nor drawn, so a long list costs little more than the
 * rows on screen.
 *
 * <p>{@link #scrollBy}, {@link #scrollTo} and {@link #scrollIntoView} move it,
 * clamped to the content. The wheel over it scrolls by {@link #wheelStep}, and a
 * press on the renderer's {@code "track"} part drags along it. Vertical only.
 *
 * <p>Game thread only.
 */
public class Scroll extends Column {

    /** How far down the content the viewport's top is. */
    private float offset;

    @Getter
    private float maxHeight = Float.POSITIVE_INFINITY;

    /** Where the viewport was at the last update. Empty before the first. */
    @Getter
    private Bounds viewport = Bounds.EMPTY;

    /** How tall the children are, gaps included, at the last update. */
    @Getter
    private float contentHeight;

    /** Each child's place within the content, from the content's top, at the last update. */
    private final Map<Widget, Bounds> content = new IdentityHashMap<>();

    /** A widget to bring into view at the next update. */
    private Widget reveal;

    /** Whether an update has laid this out, so there is content to clamp a scroll to. */
    private boolean arranged;

    /** How far one notch of the wheel scrolls. */
    @Getter
    private float wheelStep = 20f;

    /** Caps the viewport's height; the content scrolls beyond it. Unlimited by default. */
    public Scroll maxHeight(float height) {
        Validate.check(height >= 0f, "a scroll's maximum height cannot be negative");
        this.maxHeight = height;
        return this;
    }

    /**
     * Sets how far one notch of the wheel scrolls.
     *
     * <p>A tuning knob, twenty by default. Platforms report the wheel in notches
     * of different sizes, so a client may want its own.
     */
    public Scroll wheelStep(float distance) {
        Validate.check(distance >= 0f, "a wheel step cannot be negative");
        this.wheelStep = distance;
        return this;
    }

    // ---------------------------------------------------------------- input

    /** Scrolls by the wheel, unless there is nothing to scroll, which leaves it to an outer scroll. */
    @Override
    protected boolean mouseScrolled(float x, float y, float amount) {
        if (!canScroll()) {
            return false;
        }
        scrollBy(-amount * wheelStep);
        return true;
    }

    /** A left press on the renderer's {@code "track"} part jumps there and drags along it. */
    @Override
    protected boolean mousePressed(float x, float y, MouseButton button) {
        Bounds track = part("track");
        if (button != MouseButton.LEFT || track == null || !track.contains(x, y) || !canScroll()) {
            return false;
        }
        capture();
        scrollAlong(track, y);
        return true;
    }

    @Override
    protected void mouseDragged(float x, float y) {
        Bounds track = part("track");
        if (track != null) {
            scrollAlong(track, y);
        }
    }

    /** Scrolls as far down the content as the point is down the track. */
    private void scrollAlong(Bounds track, float y) {
        float along = track.getHeight() <= 0f ? 0f : (y - track.getY()) / track.getHeight();
        scrollTo(Math.max(0f, Math.min(1f, along)) * getMaxScroll());
    }

    // -------------------------------------------------------------- actions

    /** Scrolls by a distance; positive moves the content up, showing what is below. */
    public void scrollBy(float distance) {
        scrollTo(offset + distance);
    }

    /**
     * Scrolls so the viewport's top is this far down the content, clamped to it.
     *
     * <p>Before the first update there is no content to clamp to, so the
     * distance is kept and clamped then.
     */
    public void scrollTo(float distance) {
        this.offset = arranged ? clamp(distance) : Math.max(0f, distance);
    }

    /**
     * Scrolls the least distance that shows a widget, at the next update.
     *
     * @return whether the widget is inside this scroll at all
     */
    public boolean scrollIntoView(Widget widget) {
        Widget child = childHolding(widget);
        if (child == null) {
            return false;
        }
        this.reveal = child;
        return true;
    }

    /** @return how far down the content the viewport's top is. */
    public float getScrollY() {
        return offset;
    }

    /** @return the furthest {@link #scrollTo} can go: how much content the viewport cannot show. */
    public float getMaxScroll() {
        return Math.max(0f, contentHeight - viewport.getHeight());
    }

    /** @return whether there is more content than the viewport shows. */
    public boolean canScroll() {
        return getMaxScroll() > 0f;
    }

    /**
     * @return where a scroll bar's thumb goes within a track, or null when there
     *         is nothing to scroll
     *
     * <p>The thumb's length is the share of the content in view, at least
     * {@code minLength}, and it sits as far along the track as the viewport is
     * down the content. For a renderer drawing its scroll bar, so the bar always
     * matches the scroll.
     */
    public Bounds thumbIn(Bounds track, float minLength) {
        if (track == null || !canScroll()) {
            return null;
        }
        float length = Math.min(track.getHeight(),
                Math.max(minLength, track.getHeight() * viewport.getHeight() / contentHeight));
        float along = (track.getHeight() - length) * (offset / getMaxScroll());
        return Bounds.of(track.getX(), track.getY() + along, track.getWidth(), length);
    }

    // ------------------------------------------------------------ arranging

    @Override
    protected float childrenHeight(List<Widget> visible, float width) {
        return Math.min(super.childrenHeight(visible, width), maxHeight);
    }

    @Override
    protected Size childrenNatural(List<Widget> visible) {
        Size natural = super.childrenNatural(visible);
        return Size.of(natural.getWidth(), Math.min(natural.getHeight(), maxHeight));
    }

    @Override
    protected void arrange(List<Widget> visible, Bounds slot) {
        arranged = true;
        viewport = slot;
        contentHeight = super.childrenHeight(visible, slot.getWidth());

        // Laid out from the content's own top, at least as tall as the viewport so
        // growing rows still fill a short list.
        List<Bounds> places = stack(visible,
                Bounds.of(slot.getX(), 0f, slot.getWidth(), Math.max(contentHeight, slot.getHeight())));
        content.clear();
        for (int i = 0; i < visible.size(); i++) {
            content.put(visible.get(i), places.get(i));
        }

        if (reveal != null) {
            Bounds wanted = content.get(reveal);
            if (wanted != null) {
                if (wanted.getY() < offset) {
                    offset = wanted.getY();
                } else if (wanted.getBottom() > offset + slot.getHeight()) {
                    offset = wanted.getBottom() - slot.getHeight();
                }
            }
            reveal = null;
        }
        offset = clamp(offset);

        for (int i = 0; i < visible.size(); i++) {
            Bounds at = places.get(i);
            float top = slot.getY() + at.getY() - offset;
            if (top + at.getHeight() > slot.getY() && top < slot.getBottom()) {
                place(visible.get(i), Bounds.of(at.getX(), top, at.getWidth(), at.getHeight()));
            }
        }
    }

    @Override
    protected Bounds clipChildren() {
        return viewport;
    }

    private float clamp(float distance) {
        return Math.max(0f, Math.min(distance, getMaxScroll()));
    }

    /** The child of this scroll that holds a widget, or null if it is not inside. */
    private Widget childHolding(Widget widget) {
        for (Widget node = widget; node != null; node = node.getParent()) {
            if (node.getParent() == this) {
                return node;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- chaining

    @Override
    public Scroll gap(float spacing) {
        super.gap(spacing);
        return this;
    }

    @Override
    public Scroll align(Align across) {
        super.align(across);
        return this;
    }

    @Override
    public Scroll visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public Scroll enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public Scroll grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public Scroll tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public Scroll tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
