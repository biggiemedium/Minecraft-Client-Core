package dev.px.core.hud;

import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.layout.Size;

import com.google.gson.JsonObject;
import dev.px.core.config.ConfigSection;
import dev.px.core.config.Json;
import dev.px.core.math.MathUtil;
import dev.px.core.platform.Platform;
import dev.px.core.registry.Registry;
import dev.px.core.render.Render;
import dev.px.core.service.Service;
import dev.px.core.setting.Setting;
import dev.px.core.setting.SettingHolder;
import dev.px.core.util.CoreLogger;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the HUD: which elements exist, where each one sits, and drawing them.
 *
 * <p>The layout engine is entirely here, which is what keeps
 * {@link HudElement} down to two mandatory methods. {@link #resolve} asks every
 * element its natural size, applies the element's scale, resolves the anchor
 * formula, clamps the result to the screen, and returns the results in z-order.
 * None of that is visible to an element.
 *
 * <p><b>Core does not draw the HUD.</b> It resolves geometry and stops. The
 * client drives its own frame, which is what lets the same layout engine sit
 * under any look:
 *
 * <pre>{@code
 * // once, at startup
 * Core.hud().getRenderers().register(HudRenderer.of(ClockElement.class, ClockLook::draw));
 *
 * // every frame, from the client's own render hook
 * List<Placement> placements = Core.hud().resolve(false);
 * Core.hud().drawAll(placements);
 * }</pre>
 *
 * <p>{@link #drawAll} is a convenience, not a requirement: it applies the scale
 * transform and contains a throwing element so one broken element cannot take
 * the rest of the HUD with it. A client that wants full control over the loop
 * iterates the placements itself and ignores it.
 */
@Getter
public final class HudService implements Service, ConfigSection {

    /**
     * How much of an element must remain on screen.
     *
     * <p>Elements may hang off an edge deliberately, but never entirely: an
     * element with nothing on screen cannot be found or grabbed in the editor.
     */
    public static final float MIN_VISIBLE = 8f;

    /**
     * Read by {@link AbstractHudElement#isEditing()}.
     *
     * <p>Static so an element can consult it without holding a service reference,
     * following the same injection pattern the setting and module packages use.
     */
    private static volatile boolean editingGlobally;

    private final CoreLogger logger;
    private final Platform platform;

    private final Registry<HudElement> elements = new Registry<HudElement>() {
        @Override
        protected String describe() {
            return "HUD element";
        }
    };

    /** Layout per element id. Kept apart from the element so it is plain saveable data. */
    private final Map<String, HudLayout> layouts = new LinkedHashMap<>();

    private final HudEditor editor;

    /**
     * How each element is drawn.
     *
     * <p>Empty until the client registers renderers. An element with no renderer
     * simply is not drawn by {@link #drawAll}, exactly as a setting with no
     * renderer produces no row.
     */
    private final HudRendererRegistry renderers = new HudRendererRegistry();

    /** Element ids already reported as broken, so a failure logs once rather than per frame. */
    private final Set<String> warned = new HashSet<>();

    public HudService(CoreLogger logger, Platform platform) {
        this.logger = logger;
        this.platform = platform;
        this.editor = new HudEditor(this, platform);
    }

    @Override
    public String getName() {
        return "HUD";
    }

    @Override
    public String getId() {
        return "hud";
    }

    /**
     * Nothing to wire up.
     *
     * <p>The HUD subscribes to no events. It used to draw on
     * {@link dev.px.core.event.impl.Render2DEvent} and the editor used to consume
     * input at {@link dev.px.core.event.Priority#HIGHEST}; both are the client's
     * now, because when the HUD is drawn and what input means are decisions Core
     * has no business making.
     */
    @Override
    public void start() {
    }

    @Override
    public void stop() {
        editor.close();
    }

    static boolean editingGlobally() {
        return editingGlobally;
    }

    // ------------------------------------------------------------ registration

    /**
     * Registers an element and gives it its default layout.
     *
     * @return the element, so it can be registered and kept in one expression
     */
    public <E extends HudElement> E register(E element) {
        elements.register(element);
        layouts.put(element.getId(), element.defaultLayout());
        return element;
    }

    @SafeVarargs
    public final void registerAll(HudElement... toRegister) {
        for (HudElement element : toRegister) {
            register(element);
        }
    }

    public Optional<HudElement> find(String id) {
        return elements.find(id);
    }

    /** @return the layout for an element id, or null if nothing is registered under it. */
    public HudLayout layoutOf(String id) {
        return layouts.get(id);
    }

    public HudLayout layoutOf(HudElement element) {
        return layouts.get(element.getId());
    }

    // --------------------------------------------------------------- geometry

    /**
     * Resolves every element's on-screen geometry for this frame.
     *
     * @param includeHidden whether hidden elements are included, which the editor
     *                      needs so they can be selected and unhidden again
     * @return placements in ascending z-order; equal z keeps registration order
     */
    public List<Placement> resolve(boolean includeHidden) {
        float screenWidth = platform.getScreenWidth();
        float screenHeight = platform.getScreenHeight();

        List<Placement> placements = new ArrayList<>(elements.size());
        for (HudElement element : elements) {
            HudLayout layout = layouts.get(element.getId());
            if (layout == null || (layout.isHidden() && !includeHidden)) {
                continue;
            }
            Content content = safeContent(element);
            if (content == null) {
                continue;
            }
            Size natural = content.size();
            placements.add(new Placement(element, layout, natural,
                    place(layout, natural, screenWidth, screenHeight), content));
        }
        // Stable, so equal z-order falls back to the order elements were registered.
        placements.sort(Comparator.comparingInt(placement -> placement.getLayout().getZOrder()));
        return placements;
    }

    /**
     * Applies the anchor formula, the scale factor and screen clamping.
     *
     * <p>Clamping is applied to the resolved position only and is never written
     * back to the layout. Temporarily shrinking the window must not permanently
     * move a user's HUD.
     */
    public Bounds place(HudLayout layout, Size natural, float screenWidth, float screenHeight) {
        float width = natural.getWidth() * layout.getScale();
        float height = natural.getHeight() * layout.getScale();

        Anchor anchor = layout.getAnchor();
        float x = anchor.resolveX(screenWidth, layout.getOffsetX(), width);
        float y = anchor.resolveY(screenHeight, layout.getOffsetY(), height);

        return Bounds.of(clampAxis(x, width, screenWidth), clampAxis(y, height, screenHeight), width, height);
    }

    /** Keeps at least {@link #MIN_VISIBLE} of the element on screen, or all of it if it is smaller. */
    private static float clampAxis(float position, float size, float screenSize) {
        float visible = Math.min(MIN_VISIBLE, size);
        return MathUtil.clamp(position, -(size - visible), screenSize - visible);
    }

    /**
     * @return the topmost placement whose {@link Shape} contains the point, or
     *         null. Iterated in reverse z-order, so what looks on top wins.
     */
    public Placement hitTest(List<Placement> placements, float x, float y) {
        for (int i = placements.size() - 1; i >= 0; i--) {
            Placement placement = placements.get(i);
            if (placement.hits(x, y)) {
                return placement;
            }
        }
        return null;
    }

    // -------------------------------------------------------------- drawing

    /**
     * Draws placements through their registered {@link HudRenderer}s.
     *
     * <p>Optional. Core neither subscribes to a render event nor decides when a
     * frame happens; the client calls this from its own hook, having resolved the
     * placements it wants. What it buys over an open-coded loop is the two things
     * that are easy to get wrong:
     *
     * <ul>
     *   <li><b>The scale transform.</b> An element is drawn at its natural size
     *       inside a transform about its top-left, which is why a renderer never
     *       has to account for scale.</li>
     *   <li><b>Containment.</b> An element whose renderer throws is logged once
     *       and skipped, rather than being allowed to take the rest of the HUD
     *       down with it.</li>
     * </ul>
     *
     * <p>An element with no registered renderer is skipped silently, so a client
     * can register elements before it has written every renderer.
     *
     * @param placements what to draw, in the order to draw it
     */
    public void drawAll(List<Placement> placements) {
        for (Placement placement : placements) {
            try {
                draw(placement);
            } catch (RuntimeException e) {
                reportOnce(placement.getId(), "render", e);
            }
        }
    }

    /** Resolves visible elements and draws them. The whole HUD in one call. */
    public void drawAll() {
        drawAll(resolve(editor.isActive()));
    }

    /**
     * Draws one placement, scale transform included.
     *
     * @return whether a renderer claimed it
     */
    public boolean draw(Placement placement) {
        Bounds bounds = placement.getBounds();
        float scale = placement.getLayout().getScale();

        if (scale == 1f) {
            return paint(placement);
        }
        // Scaled about the element's own top-left, so the transform never moves
        // it away from where the anchor put it.
        boolean[] drawn = new boolean[1];
        Render.scaled(bounds.getX(), bounds.getY(), scale, () -> drawn[0] = paint(placement));
        return drawn[0];
    }

    /**
     * Draws one placement: a registered renderer if the element's type has one,
     * otherwise the content the element described for itself.
     *
     * <p>Most elements need no renderer at all &mdash; describing their content
     * is enough, and that keeps an element to one class. Registering a
     * {@link HudRenderer} is how a client takes over an element it did not write,
     * or wants to look different from how its author drew it.
     */
    private boolean paint(Placement placement) {
        if (renderers.render(placement)) {
            return true;
        }
        Content content = placement.getContent();
        if (content == null) {
            return false;
        }
        Bounds bounds = placement.getBounds();
        Size natural = placement.getNatural();
        content.draw(bounds.getX(), bounds.getY(), natural.getWidth(), natural.getHeight());
        return true;
    }

    // ------------------------------------------------------- layout operations

    /**
     * Changes an element's anchor without moving it on screen.
     *
     * <p>Recomputes the offset so the element stays exactly where it is and only
     * its resize and resolution-change behaviour changes, which is what a user
     * picking a different anchor actually wants.
     */
    public void setAnchor(String id, Anchor anchor) {
        HudLayout layout = layouts.get(id);
        HudElement element = elements.get(id);
        if (layout == null || element == null) {
            return;
        }
        float screenWidth = platform.getScreenWidth();
        float screenHeight = platform.getScreenHeight();
        Content content = safeContent(element);
        if (content == null) {
            return;
        }
        Bounds current = place(layout, content.size(), screenWidth, screenHeight);
        layout.setAnchor(anchor);
        layout.setOffsetX(anchor.offsetXFor(screenWidth, current.getX(), current.getWidth()));
        layout.setOffsetY(anchor.offsetYFor(screenHeight, current.getY(), current.getHeight()));
    }

    /**
     * Moves an element so its top-left lands at an absolute position, clamped,
     * storing the result as an offset against its current anchor.
     */
    public void moveTo(Placement placement, float x, float y) {
        moveTo(placement.getLayout(), x, y,
                placement.getBounds().getWidth(), placement.getBounds().getHeight());
    }

    /**
     * As {@link #moveTo(Placement, float, float)}, but with the size given
     * explicitly.
     *
     * <p>Needed while resizing, where the scale has already changed and the
     * placement still carries the previous frame's dimensions.
     */
    public void moveTo(HudLayout layout, float x, float y, float width, float height) {
        float screenWidth = platform.getScreenWidth();
        float screenHeight = platform.getScreenHeight();

        float clampedX = clampAxis(x, width, screenWidth);
        float clampedY = clampAxis(y, height, screenHeight);

        Anchor anchor = layout.getAnchor();
        layout.setOffsetX(anchor.offsetXFor(screenWidth, clampedX, width));
        layout.setOffsetY(anchor.offsetYFor(screenHeight, clampedY, height));
    }

    public void resetLayout(String id) {
        HudElement element = elements.get(id);
        HudLayout layout = layouts.get(id);
        if (element != null && layout != null) {
            layout.copyFrom(element.defaultLayout());
        }
    }

    public void resetAll() {
        for (HudElement element : elements) {
            resetLayout(element.getId());
        }
    }

    /** Raises an element above every other, which is what "bring to front" means with sparse z values. */
    public void bringToFront(String id) {
        HudLayout layout = layouts.get(id);
        if (layout != null) {
            layout.setZOrder(highestZ() + 1);
        }
    }

    public void sendToBack(String id) {
        HudLayout layout = layouts.get(id);
        if (layout != null) {
            layout.setZOrder(lowestZ() - 1);
        }
    }

    private int highestZ() {
        int highest = 0;
        for (HudLayout layout : layouts.values()) {
            highest = Math.max(highest, layout.getZOrder());
        }
        return highest;
    }

    private int lowestZ() {
        int lowest = 0;
        for (HudLayout layout : layouts.values()) {
            lowest = Math.min(lowest, layout.getZOrder());
        }
        return lowest;
    }

    // ---------------------------------------------------------- editor access

    public boolean isEditing() {
        return editor.isActive();
    }

    public void openEditor() {
        editor.open();
    }

    public void closeEditor() {
        editor.close();
    }

    /** Called by the editor so {@link AbstractHudElement#isEditing()} stays in step. */
    void setEditingFlag(boolean editing) {
        editingGlobally = editing;
    }

    // ---------------------------------------------------------- persistence

    @Override
    public JsonObject save() {
        JsonObject entries = new JsonObject();
        for (HudElement element : elements) {
            HudLayout layout = layouts.get(element.getId());
            if (layout == null) {
                continue;
            }
            JsonObject node = layout.toJson();
            if (element instanceof SettingHolder) {
                JsonObject values = new JsonObject();
                for (Setting<?> setting : ((SettingHolder) element).getSettings()) {
                    values.add(setting.getName(), setting.toJson());
                }
                node.add("settings", values);
            }
            entries.add(element.getId(), node);
        }
        JsonObject json = new JsonObject();
        json.add("elements", entries);
        return json;
    }

    @Override
    public void load(JsonObject json) {
        JsonObject entries = Json.child(json, "elements");
        for (HudElement element : elements) {
            if (!entries.has(element.getId()) || !entries.get(element.getId()).isJsonObject()) {
                continue;
            }
            JsonObject node = entries.getAsJsonObject(element.getId());

            HudLayout layout = layouts.get(element.getId());
            if (layout != null) {
                layout.fromJson(node);
            }
            if (element instanceof SettingHolder) {
                JsonObject values = Json.child(node, "settings");
                for (Setting<?> setting : ((SettingHolder) element).getSettings()) {
                    if (values.has(setting.getName())) {
                        setting.fromJson(values.get(setting.getName()));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * Asks an element to describe itself, and measures the result.
     *
     * <p>Once per element per resolve, and the measured box travels on the
     * {@link Placement} so drawing never repeats the work. An element that throws
     * while describing itself is skipped for that frame rather than taking the
     * rest of the HUD with it.
     */
    private Content safeContent(HudElement element) {
        try {
            Content content = Content.column();
            element.content(content);
            content.measure();
            return content;
        } catch (RuntimeException e) {
            reportOnce(element.getId(), "content", e);
            return null;
        }
    }

    private void reportOnce(String id, String stage, RuntimeException failure) {
        if (warned.add(id)) {
            logger.error("HUD element " + id + " threw in " + stage
                    + "; it will be skipped when it fails again", failure);
        }
    }
}
