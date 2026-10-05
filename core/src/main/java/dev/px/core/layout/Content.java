package dev.px.core.layout;

import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.render.Texture;
import dev.px.core.render.font.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What an element is made of: a box of parts, described once per frame.
 *
 * <p>This is the answer to the oldest annoyance in HUD code &mdash; working out
 * your size in one method and then working the same thing out again in another
 * to draw it, and watching the two drift apart the first time you change a
 * padding. Here you say it once:
 *
 * <pre>{@code
 * @Override
 * public void content(Content c) {
 *     c.background(PANEL, 3f).padding(4f).gap(2f);
 *     c.text("FPS", Color.GRAY);
 *     c.text(String.valueOf(fps), Color.WHITE);
 * }
 * }</pre>
 *
 * <p>Core measures that box to get the element's size, anchors and clamps it,
 * and then draws the same box at the position it resolved. One description, two
 * uses, no chance of disagreement.
 *
 * <h2>Boxes and parts</h2>
 *
 * <p>A box stacks its children along one axis: the element's root box is a
 * column, {@link #row} and {@link #column} nest another. Methods that take
 * content add a child; methods that describe the box &mdash; {@link #padding},
 * {@link #gap}, {@link #background}, {@link #align}, {@link #min} &mdash;
 * configure the box you call them on. Everything returns {@code this}, so both
 * kinds chain.
 *
 * <pre>{@code
 * c.background(PANEL, 3f).padding(5f);
 * c.row(r -> {
 *     r.gap(4f).align(Align.CENTER);
 *     r.texture(icon, 8f, 8f, Color.WHITE);
 *     r.text(name, Color.WHITE);
 * });
 * }</pre>
 *
 * <h2>When it is not enough</h2>
 *
 * <p>{@link #custom} takes a size and a drawing callback and does nothing else,
 * so an element the layout rules cannot express &mdash; a dial, a radar cone, a
 * chart &mdash; gives up none of its freedom and still takes part in
 * measurement, anchoring and hit testing:
 *
 * <pre>{@code
 * c.custom(Size.of(40f, 40f), (x, y, w, h) -> {
 *     Render.circle(x + w / 2f, y + h / 2f, w / 2f, PANEL);
 *     Render.line(x + w / 2f, y + h / 2f, x + w, y + h / 2f, 1.5f, Color.RED);
 * });
 * }</pre>
 *
 * <p>Sizes are in <b>natural, unscaled</b> units. The element's scale factor is a
 * transform applied around the whole box, so nothing here ever mentions scale.
 */
public final class Content {

    /** A leaf, or a nested box: something that measures itself and then draws. */
    private interface Part {

        Size measure();

        void draw(Bounds at);

        /** Only a nested box does anything here: it places its own children. */
        default void layout(Bounds at) {
        }

        /** Adds this part's outline to the element's silhouette, if it has one. */
        default void silhouette(List<Shape> out) {
        }

        /**
         * @return whether this part absorbs space left over on the main axis
         *
         * <p>Only meaningful in a box that was given a size rather than sized to
         * its content, which in practice means a GUI row rather than a HUD
         * element.
         */
        default boolean flexible() {
            return false;
        }

        /** @return the laid-out rectangle of the named part within this one, or null. */
        default Bounds find(String name) {
            return null;
        }
    }

    private final boolean horizontal;

    private final List<Part> parts = new ArrayList<>(4);

    /**
     * Each part's size, filled in by {@link #measure()} and read by
     * {@link #draw}.
     *
     * <p>This list <em>is</em> the measure-once guarantee. Drawing never asks a
     * part how big it is; it uses what measuring already found, so the box drawn
     * is always exactly the box that was measured.
     */
    private final List<Size> sizes = new ArrayList<>(4);

    /** Where each part ended up, filled in by {@link #layout}. */
    private final List<Bounds> places = new ArrayList<>(4);

    /** Where this box itself ended up. What its background and silhouette use. */
    private Bounds placed = Bounds.EMPTY;

    private float padLeft;
    private float padTop;
    private float padRight;
    private float padBottom;

    private float gap;
    private float minWidth;
    private float minHeight;

    /** Imposed size, ignoring what the content measures. Zero means "size to content". */
    private float fixedWidth;
    private float fixedHeight;

    /** Looked up after layout, so a click can be mapped to what was drawn. */
    private String name;

    /** Whether this box absorbs main-axis slack in its parent. See {@link #grow}. */
    private boolean grow;

    private Color background;
    private float radius;

    /** Arbitrary drawing behind this box's parts. See {@link #backdrop}. */
    private Draw backdrop;

    /** Whether parts are cut off at this box's edges. See {@link #clip}. */
    private boolean clip;

    private Align align = Align.START;

    private Size measured = Size.ZERO;

    Content(boolean horizontal) {
        this.horizontal = horizontal;
    }

    // ------------------------------------------------------------- factories

    /** A box whose children stack top to bottom. The shape of most things. */
    public static Content column() {
        return new Content(false);
    }

    /** A box whose children sit left to right. */
    public static Content row() {
        return new Content(true);
    }

    // ------------------------------------------------------------ the box

    /** Space inside the box, on all four sides. */
    public Content padding(float all) {
        return padding(all, all, all, all);
    }

    /** Horizontal and vertical padding. */
    public Content padding(float horizontal, float vertical) {
        return padding(horizontal, vertical, horizontal, vertical);
    }

    public Content padding(float left, float top, float right, float bottom) {
        this.padLeft = left;
        this.padTop = top;
        this.padRight = right;
        this.padBottom = bottom;
        return this;
    }

    /** Space between adjacent children. */
    public Content gap(float gap) {
        this.gap = gap;
        return this;
    }

    /** Fills the box behind its children. Drawn before them, so it never covers them. */
    public Content background(Color color) {
        return background(color, 0f);
    }

    public Content background(Color color, float radius) {
        this.background = color;
        this.radius = radius;
        return this;
    }

    /**
     * Cuts this box's parts off at its own edges.
     *
     * <p>Content is laid out, never shrunk: a label and a value that together
     * need more room than the box has will overflow it rather than being
     * compressed. Usually that is what you want and the overflow is invisible,
     * but a row inside a fixed-width window will spill outside the window, which
     * is not. Clipping bounds the damage to something that reads as truncation.
     */
    public Content clip() {
        this.clip = true;
        return this;
    }

    /**
     * Draws anything at all behind this box's parts, across its whole rectangle.
     *
     * <p>{@link #background} covers the common case of a flat fill. This is for
     * when the fill is not a colour &mdash; a gradient title bar, a texture, a
     * blur &mdash; and it matters because parts stack rather than overlap: adding
     * a gradient as a part would put it <em>above</em> the label rather than
     * behind it.
     *
     * <pre>{@code
     * c.height(h).backdrop((x, y, w, h) -> Render.roundGradient(x, y, w, h, r, a, b, b, a));
     * c.row(row -> row.text(title, GuiStyle.text()));
     * }</pre>
     */
    public Content backdrop(Draw draw) {
        this.backdrop = draw;
        return this;
    }

    /** Where children sit on the cross axis. See {@link Align}. */
    public Content align(Align align) {
        this.align = align == null ? Align.START : align;
        return this;
    }

    /**
     * A floor on the box's total size, padding included.
     *
     * <p>What stops an element collapsing to nothing when its content happens to
     * be empty &mdash; a module list with nothing enabled, a text element before
     * a font has loaded &mdash; which would otherwise leave it unclickable in the
     * editor.
     */
    public Content min(float width, float height) {
        this.minWidth = width;
        this.minHeight = height;
        return this;
    }

    /**
     * Fixes this box's height, whatever its content measures.
     *
     * <p>What a GUI row wants: every row in a panel is one row tall so they line
     * up, regardless of whether one of them happens to contain a taller widget.
     * A HUD element normally wants the opposite and leaves this alone.
     */
    public Content height(float height) {
        this.fixedHeight = height;
        return this;
    }

    /** Fixes this box's width. Rarely wanted: a box is usually as wide as it needs to be. */
    public Content width(float width) {
        this.fixedWidth = width;
        return this;
    }

    /**
     * Names this box so it can be found again after layout.
     *
     * <p>See {@link #find}. Naming is what lets a component map a click onto the
     * thing it drew, rather than recomputing the geometry a second time.
     */
    public Content name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Makes this box absorb the slack in its parent, the way {@link #fill} does.
     *
     * <p>For a box that should take the room left over rather than only what its
     * content needs &mdash; the label row of a slider, which wants everything the
     * row has except the few pixels the track sits in.
     */
    public Content grow() {
        this.grow = true;
        return this;
    }

    /**
     * Empty space that absorbs whatever is left over on the main axis.
     *
     * <p>How a row pushes its value to the right: label, fill, value. Only
     * meaningful when the box has been given a width rather than sizing to its
     * content &mdash; a GUI component is handed one, a HUD element is not, so in
     * a HUD element this simply contributes nothing.
     *
     * <p>Several fills share the slack evenly.
     */
    public Content fill() {
        return add(new FlexPart());
    }

    // --------------------------------------------------------- nested boxes

    /** A box whose children sit left to right. */
    public Content row(Consumer<Content> body) {
        return box(true, body);
    }

    /** A box whose children sit top to bottom. */
    public Content column(Consumer<Content> body) {
        return box(false, body);
    }

    private Content box(boolean horizontal, Consumer<Content> body) {
        Content child = new Content(horizontal);
        body.accept(child);
        // Wrapped rather than having Content implement Part directly: that
        // would force measure() and draw() public and put the engine's two
        // methods in front of everyone writing an element.
        parts.add(new BoxPart(child));
        return this;
    }

    // --------------------------------------------------------------- parts

    /** Text in the default font. */
    public Content text(String value, Color color) {
        return add(new TextPart(null, value, color, false, null));
    }

    public Content text(Font font, String value, Color color) {
        return add(new TextPart(font, value, color, false, null));
    }

    /** Text with a drop shadow, which is what most HUDs want over a busy game. */
    public Content textShadowed(String value, Color color) {
        return add(new TextPart(null, value, color, true, null));
    }

    public Content textShadowed(Font font, String value, Color color, Color shadow) {
        return add(new TextPart(font, value, color, true, shadow));
    }

    public Content rect(float width, float height, Color color) {
        return roundRect(width, height, 0f, color);
    }

    public Content roundRect(float width, float height, float radius, Color color) {
        Size size = Size.of(width, height);
        return add(size, (x, y, w, h) -> {
            if (radius > 0f) {
                Render.roundRect(x, y, w, h, radius, color);
            } else {
                Render.rect(x, y, w, h, color);
            }
        });
    }

    /** A horizontal progress bar. Common enough in HUD code to be worth a part. */
    public Content bar(float width, float height, float radius,
                          float progress, Color track, Color fill) {
        return add(Size.of(width, height),
                (x, y, w, h) -> Render.progressBar(x, y, w, h, radius, progress, track, fill));
    }

    public Content texture(Texture texture, float width, float height, Color tint) {
        return add(Size.of(width, height),
                (x, y, w, h) -> Render.texture(texture, x, y, w, h, tint));
    }

    /** Empty space, for pushing things apart without a gap on every child. */
    public Content space(float width, float height) {
        return add(Size.of(width, height), null);
    }

    /**
     * Anything at all, at a size you give.
     *
     * <p>The escape hatch. See the class documentation.
     */
    public Content custom(Size size, Draw draw) {
        return add(new FixedPart(size, draw, null));
    }

    public Content custom(float width, float height, Draw draw) {
        return custom(Size.of(width, height), draw);
    }

    /**
     * A custom part that can be found again by name.
     *
     * <p>The pairing that removes the oldest bug in widget code: the rectangle a
     * slider's track is drawn in and the rectangle a click is measured against
     * are the same rectangle, because there is only one of them.
     *
     * <pre>{@code
     * c.custom("track", width, 2f, this::drawTrack);
     * ...
     * Bounds track = content.find("track");
     * }</pre>
     */
    public Content custom(String name, float width, float height, Draw draw) {
        return add(new FixedPart(Size.of(width, height), draw, name));
    }

    private Content add(Size size, Draw draw) {
        return add(new FixedPart(size, draw, null));
    }

    private Content add(Part part) {
        parts.add(part);
        return this;
    }

    // -------------------------------------------------------------- engine

    /** @return the size measured by the last {@link #measure()}. */
    /**
     * @return the size found by the last {@link #measure()}
     *
     * <p>Engine method. Element and component authors describe content and never
     * call this; whatever is driving the layout does.
     */
    public Size size() {
        return measured;
    }

    /**
     * Measures this box and everything in it, remembering each part's size.
     *
     * <p>Called once a frame by {@link HudService#resolve}, and never again
     * before that frame is drawn.
     */
    /** Engine method: measures this box and remembers each part's size. */
    public Size measure() {
        sizes.clear();

        float main = 0f;
        float cross = 0f;
        for (Part part : parts) {
            Size size = part.measure();
            sizes.add(size);
            main += horizontal ? size.getWidth() : size.getHeight();
            cross = Math.max(cross, horizontal ? size.getHeight() : size.getWidth());
        }
        if (parts.size() > 1) {
            main += gap * (parts.size() - 1);
        }

        float width = (horizontal ? main : cross) + padLeft + padRight;
        float height = (horizontal ? cross : main) + padTop + padBottom;

        measured = Size.of(
                fixedWidth > 0f ? fixedWidth : Math.max(width, minWidth),
                fixedHeight > 0f ? fixedHeight : Math.max(height, minHeight));
        return measured;
    }

    /**
     * Places this box and everything in it, without drawing anything.
     *
     * <p>Split out from drawing because the editor needs to know where an
     * element's parts ended up in order to trace its outline, and asking for that
     * must not put pixels on the screen. {@link #draw} calls this itself, so a
     * caller that only wants to draw never has to think about it.
     *
     * <p>{@code width} and {@code height} are what the box was measured at rather
     * than being recomputed, so a box stretched by a {@link #min} lays its
     * children out inside the larger rectangle it actually occupies.
     */
    /** Engine method: places this box and everything in it, drawing nothing. */
    public void layout(float x, float y, float width, float height) {
        placed = Bounds.of(x, y, width, height);
        places.clear();

        float innerWidth = width - padLeft - padRight;
        float innerHeight = height - padTop - padBottom;
        float slack = slackFor(horizontal ? innerWidth : innerHeight);

        float cursorX = x + padLeft;
        float cursorY = y + padTop;

        for (int i = 0; i < parts.size() && i < sizes.size(); i++) {
            Part part = parts.get(i);
            Size size = sizes.get(i);

            // A flexible part has no size of its own; it is whatever is left over.
            float mainSize = part.flexible()
                    ? slack
                    : (horizontal ? size.getWidth() : size.getHeight());
            float crossSize = align.sizeIn(horizontal ? innerHeight : innerWidth,
                    horizontal ? size.getHeight() : size.getWidth());

            float partX;
            float partY;
            float partWidth;
            float partHeight;

            if (horizontal) {
                partWidth = mainSize;
                partHeight = crossSize;
                partX = cursorX;
                partY = cursorY + align.offsetIn(innerHeight, partHeight);
                cursorX += partWidth + gap;
            } else {
                partWidth = crossSize;
                partHeight = mainSize;
                partY = cursorY;
                partX = cursorX + align.offsetIn(innerWidth, partWidth);
                cursorY += partHeight + gap;
            }

            Bounds at = Bounds.of(partX, partY, partWidth, partHeight);
            places.add(at);
            part.layout(at);
        }
    }

    /**
     * @return how much main-axis space each flexible part gets
     *
     * <p>Zero when nothing is flexible, or when the box was sized to its content
     * and so has nothing spare. Shared evenly when several parts want it.
     */
    private float slackFor(float available) {
        int flexible = 0;
        float used = 0f;
        for (int i = 0; i < parts.size() && i < sizes.size(); i++) {
            if (parts.get(i).flexible()) {
                flexible++;
            } else {
                Size size = sizes.get(i);
                used += horizontal ? size.getWidth() : size.getHeight();
            }
        }
        if (flexible == 0) {
            return 0f;
        }
        if (parts.size() > 1) {
            used += gap * (parts.size() - 1);
        }
        return Math.max(0f, (available - used) / flexible);
    }

    /**
     * @return the laid-out rectangle of a named box or part, or null
     *
     * <p>Valid after {@link #layout}. This is how a component maps a click onto
     * something it drew without restating the geometry.
     */
    public Bounds find(String wanted) {
        if (wanted == null) {
            return null;
        }
        if (wanted.equals(name)) {
            return placed;
        }
        for (Part part : parts) {
            Bounds found = part.find(wanted);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Places this box and then draws it. */
    /** Engine method: places this box and then draws it. */
    public void draw(float x, float y, float width, float height) {
        layout(x, y, width, height);
        paint();
    }

    private void paint() {
        if (backdrop != null) {
            backdrop.draw(placed.getX(), placed.getY(), placed.getWidth(), placed.getHeight());
        }
        if (background != null) {
            if (radius > 0f) {
                Render.roundRect(placed.getX(), placed.getY(), placed.getWidth(), placed.getHeight(),
                        radius, background);
            } else {
                Render.rect(placed.getX(), placed.getY(), placed.getWidth(), placed.getHeight(),
                        background);
            }
        }
        if (clip) {
            Render.pushClip(placed.getX(), placed.getY(), placed.getWidth(), placed.getHeight());
        }
        for (int i = 0; i < parts.size() && i < places.size(); i++) {
            parts.get(i).draw(places.get(i));
        }
        if (clip) {
            Render.popClip();
        }
    }

    /**
     * Collects the outline of everything this box visibly fills.
     *
     * <p>Every box with a {@link #background} contributes its own rectangle, at
     * its own corner radius. That is what lets an element's clickable region and
     * its editor outline follow what it actually draws &mdash; a rounded panel
     * gets a rounded outline, and a list of separate rows gets an outline around
     * the rows rather than around the empty space beside the short ones &mdash;
     * without the author describing their shape a second time.
     *
     * <p>Call {@link #layout} first. A box with no background anywhere
     * contributes nothing, and the caller falls back to the whole rectangle.
     *
     * <p>Engine method, like {@link #measure} and {@link #layout}: whatever is
     * driving the layout asks, and the author describing content never does.
     */
    public void silhouette(List<Shape> out) {
        if (background != null) {
            out.add(radius > 0f ? Shape.roundRect(placed, radius) : Shape.rect(placed));
        }
        for (Part part : parts) {
            part.silhouette(out);
        }
    }

    // ------------------------------------------------------- part types

    @Override
    public String toString() {
        return (horizontal ? "row[" : "column[") + parts.size() + " parts, " + measured + "]";
    }

    /** Lets a box sit inside another box without widening this class's public API. */
    private static final class BoxPart implements Part {

        private final Content box;

        BoxPart(Content box) {
            this.box = box;
        }

        @Override
        public Size measure() {
            return box.measure();
        }

        @Override
        public void layout(Bounds at) {
            box.layout(at.getX(), at.getY(), at.getWidth(), at.getHeight());
        }

        @Override
        public void draw(Bounds at) {
            box.paint();
        }

        @Override
        public void silhouette(List<Shape> out) {
            box.silhouette(out);
        }

        @Override
        public boolean flexible() {
            return box.grow;
        }

        @Override
        public Bounds find(String wanted) {
            return box.find(wanted);
        }
    }

    private static final class FixedPart implements Part {

        private final Size size;
        private final Draw draw;
        private final String name;

        private Bounds placed = Bounds.EMPTY;

        FixedPart(Size size, Draw draw, String name) {
            this.size = size == null ? Size.ZERO : size;
            this.draw = draw;
            this.name = name;
        }

        @Override
        public Size measure() {
            return size;
        }

        @Override
        public void layout(Bounds at) {
            this.placed = at;
        }

        @Override
        public void draw(Bounds at) {
            if (draw != null) {
                draw.draw(at.getX(), at.getY(), at.getWidth(), at.getHeight());
            }
        }

        @Override
        public Bounds find(String wanted) {
            return wanted.equals(name) ? placed : null;
        }
    }

    /** Empty, sizeless, and takes whatever the box has spare. */
    private static final class FlexPart implements Part {

        @Override
        public Size measure() {
            return Size.ZERO;
        }

        @Override
        public void draw(Bounds at) {
        }

        @Override
        public boolean flexible() {
            return true;
        }
    }

    /**
     * Text, measured through the font rather than by a guess.
     *
     * <p>A null font means the default one, which is what
     * {@link Render#setDefaultFont} installed. With no font at all it measures
     * zero and draws nothing, which is why a text-only element usually wants a
     * {@link #min}.
     */
    private static final class TextPart implements Part {

        private final Font font;
        private final String value;
        private final Color color;
        private final boolean shadowed;
        private final Color shadow;

        TextPart(Font font, String value, Color color, boolean shadowed, Color shadow) {
            this.font = font;
            this.value = value == null ? "" : value;
            this.color = color;
            this.shadowed = shadowed;
            this.shadow = shadow;
        }

        @Override
        public Size measure() {
            if (font != null) {
                return Size.of(font.widthOf(value), font.getHeight());
            }
            return Size.of(Render.textWidth(value), Render.textHeight());
        }

        @Override
        public void draw(Bounds at) {
            float x = at.getX();
            float y = at.getY();
            if (shadowed) {
                if (font != null && shadow != null) {
                    Render.textShadowed(font, value, x, y, color, shadow);
                } else {
                    Render.textShadowed(value, x, y, color);
                }
            } else if (font != null) {
                Render.text(font, value, x, y, color);
            } else {
                Render.text(value, x, y, color);
            }
        }
    }
}
