package dev.px.core.hud;

import com.google.gson.JsonObject;
import dev.px.core.config.ConfigSection;
import dev.px.core.config.Json;
import dev.px.core.event.EventBus;
import dev.px.core.event.Subscribe;
import dev.px.core.event.impl.Render2DEvent;
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
 * {@link HudElement} down to two mandatory methods. Each frame it asks every
 * element its natural size, applies the element's scale, resolves the anchor
 * formula, clamps the result to the screen, and draws in z-order. None of that
 * is visible to an element.
 *
 * <p>An element that throws is logged once and skipped for that frame rather
 * than being allowed to kill the overlay: one broken HUD element must not take
 * the rest of the HUD with it.
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
    private final EventBus bus;
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

    /** Element ids already reported as broken, so a failure logs once rather than per frame. */
    private final Set<String> warned = new HashSet<>();

    public HudService(CoreLogger logger, EventBus bus, Platform platform) {
        this.logger = logger;
        this.bus = bus;
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

    @Override
    public void start() {
        bus.subscribe(this);
        bus.subscribe(editor);
    }

    @Override
    public void stop() {
        editor.close();
        bus.unsubscribe(editor);
        bus.unsubscribe(this);
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
            Size natural = safeSize(element);
            if (natural == null) {
                continue;
            }
            placements.add(new Placement(element, layout, natural,
                    place(layout, natural, screenWidth, screenHeight)));
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

    // -------------------------------------------------------------- rendering

    /**
     * Draws the HUD, then the editor overlay if it is open.
     *
     * <p>Subscribed to {@link Render2DEvent}, which is the normal path. An adapter
     * whose editor screen suppresses that event can call this directly instead,
     * but must not do both in the same frame.
     */
    public void renderFrame() {
        boolean editing = editor.isActive();
        if (editing) {
            // Apply an in-flight drag before resolving, so the element is drawn
            // where the cursor is now rather than one frame behind it.
            editor.beforeResolve();
        }
        List<Placement> placements = resolve(editing);
        if (editing) {
            editor.renderBackdrop();
        }

        for (Placement placement : placements) {
            // Hidden elements are only reachable here while editing; drawn faint so
            // they read as hidden while still being selectable to unhide.
            boolean faint = placement.getLayout().isHidden();
            if (faint) {
                Render.pushAlpha(0.35f);
            }
            try {
                draw(placement);
            } catch (RuntimeException e) {
                reportOnce(placement.getId(), "render", e);
            } finally {
                if (faint) {
                    Render.popAlpha();
                }
            }
        }

        if (editing) {
            editor.renderOverlay(placements);
        }
    }

    private void draw(Placement placement) {
        Bounds bounds = placement.getBounds();
        Size natural = placement.getNatural();
        float scale = placement.getLayout().getScale();
        HudElement element = placement.getElement();

        if (scale == 1f) {
            element.render(bounds.getX(), bounds.getY(), natural.getWidth(), natural.getHeight());
        } else {
            // Scaled about the element's own top-left, so the transform never moves
            // it away from where the anchor put it.
            Render.scaled(bounds.getX(), bounds.getY(), scale,
                    () -> element.render(bounds.getX(), bounds.getY(),
                            natural.getWidth(), natural.getHeight()));
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        renderFrame();
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
        Size natural = safeSize(element);
        if (natural == null) {
            return;
        }
        Bounds current = place(layout, natural, screenWidth, screenHeight);
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

    private Size safeSize(HudElement element) {
        try {
            Size size = element.getPreferredSize();
            return size == null ? Size.ZERO : size;
        } catch (RuntimeException e) {
            reportOnce(element.getId(), "getPreferredSize", e);
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
