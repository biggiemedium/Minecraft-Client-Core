package dev.px.core.hud;

import com.google.gson.JsonObject;
import dev.px.core.math.MathUtil;
import lombok.Getter;
import lombok.Setter;

/**
 * Where an element sits and how it is presented: pure persisted data, held apart
 * from the element itself.
 *
 * <p>Splitting the two means an element is a renderer with no state to save, and
 * a layout is state with nothing to draw. The editor mutates layouts and never
 * touches elements; the config saves layouts and never asks an element anything.
 *
 * <p>Position is an {@link Anchor} plus an offset, never absolute coordinates.
 * See {@link Anchor} for why that one choice does most of the work.
 */
@Getter
@Setter
public final class HudLayout {

    public static final float MIN_SCALE = 0.25f;
    public static final float MAX_SCALE = 4f;

    private Anchor anchor = Anchor.TOP_LEFT;

    /** Distance from the anchor point, in scaled screen units. */
    private float offsetX;
    private float offsetY;

    private float scale = 1f;

    /** Draw order. Higher draws later, so it lands on top and wins hit tests. */
    private int zOrder;

    /** Locked elements render normally but cannot be selected or dragged. */
    private boolean locked;

    /** Hidden elements do not render in game, but stay visible and selectable in the editor. */
    private boolean hidden;

    public static HudLayout at(Anchor anchor, float offsetX, float offsetY) {
        HudLayout layout = new HudLayout();
        layout.anchor = anchor;
        layout.offsetX = offsetX;
        layout.offsetY = offsetY;
        return layout;
    }

    /** Scale is clamped on assignment, so no caller can produce an invisible or absurd element. */
    public void setScale(float scale) {
        this.scale = MathUtil.clamp(scale, MIN_SCALE, MAX_SCALE);
    }

    public void setAnchor(Anchor anchor) {
        this.anchor = anchor == null ? Anchor.TOP_LEFT : anchor;
    }

    public void toggleLocked() {
        this.locked = !this.locked;
    }

    public void toggleHidden() {
        this.hidden = !this.hidden;
    }

    public HudLayout copy() {
        HudLayout copy = new HudLayout();
        copy.anchor = anchor;
        copy.offsetX = offsetX;
        copy.offsetY = offsetY;
        copy.scale = scale;
        copy.zOrder = zOrder;
        copy.locked = locked;
        copy.hidden = hidden;
        return copy;
    }

    /** Overwrites this layout with another's values, keeping the same instance. */
    public void copyFrom(HudLayout other) {
        this.anchor = other.anchor;
        this.offsetX = other.offsetX;
        this.offsetY = other.offsetY;
        this.scale = other.scale;
        this.zOrder = other.zOrder;
        this.locked = other.locked;
        this.hidden = other.hidden;
    }

    // ---------------------------------------------------------- persistence

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("anchor", anchor.name());
        json.addProperty("offsetX", offsetX);
        json.addProperty("offsetY", offsetY);
        json.addProperty("scale", scale);
        json.addProperty("z", zOrder);
        json.addProperty("locked", locked);
        json.addProperty("hidden", hidden);
        return json;
    }

    /**
     * Applies persisted values.
     *
     * <p>Every field is optional. A config written by an older build, or edited by
     * hand, keeps its defaults for anything it does not mention rather than
     * failing the whole load.
     */
    public void fromJson(JsonObject json) {
        if (json == null) {
            return;
        }
        if (json.has("anchor")) {
            setAnchor(Anchor.byName(json.get("anchor").getAsString()));
        }
        if (json.has("offsetX")) {
            offsetX = json.get("offsetX").getAsFloat();
        }
        if (json.has("offsetY")) {
            offsetY = json.get("offsetY").getAsFloat();
        }
        if (json.has("scale")) {
            setScale(json.get("scale").getAsFloat());
        }
        if (json.has("z")) {
            zOrder = json.get("z").getAsInt();
        }
        if (json.has("locked")) {
            locked = json.get("locked").getAsBoolean();
        }
        if (json.has("hidden")) {
            hidden = json.get("hidden").getAsBoolean();
        }
    }

    @Override
    public String toString() {
        return "HudLayout[" + anchor + " +" + offsetX + "," + offsetY + " x" + scale + " z" + zOrder + "]";
    }
}
