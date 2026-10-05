package dev.px.core.hud;

import dev.px.core.registry.Registry;

/**
 * The registered {@link HudRenderer}s, and the lookup that turns an element into
 * a drawn thing.
 *
 * <p>A plain {@link Registry} keyed by name already gives duplicate detection,
 * ordering and iteration; the only thing it cannot do is find an entry by the
 * type it <em>handles</em> rather than the type it <em>is</em>. That is the one
 * method added here, exactly as
 * {@link dev.px.core.gui.SettingRendererRegistry} adds it for settings.
 */
public final class HudRendererRegistry extends Registry<HudRenderer<?>> {

    @Override
    protected String describe() {
        return "HUD renderer";
    }

    /**
     * Replaces the renderer for an element type, if one is registered.
     *
     * <p>Plain {@link #register} rejects a duplicate, which is right when two
     * registrations collide by accident and wrong when a client deliberately
     * wants its own look for an element Core's examples already cover. This is
     * the deliberate version.
     *
     * @return the renderer, so it can be registered and kept in one expression
     */
    public <R extends HudRenderer<?>> R replace(R renderer) {
        HudRenderer<?> existing = get(renderer.getName());
        if (existing != null) {
            unregister(existing);
        }
        return register(renderer);
    }

    /**
     * @return the renderer that claims this element, or null
     *
     * <p>The most specific match wins, so registering a renderer for a subclass
     * of an existing element overrides it for that subclass alone and leaves the
     * base type alone.
     */
    public HudRenderer<?> rendererFor(HudElement element) {
        if (element == null) {
            return null;
        }
        HudRenderer<?> best = null;
        for (HudRenderer<?> candidate : all()) {
            if (!candidate.getElementType().isInstance(element)) {
                continue;
            }
            if (best == null || best.getElementType().isAssignableFrom(candidate.getElementType())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Draws one placement through whichever renderer claims its element.
     *
     * @return whether anything claimed it
     *
     * <p>Returning false rather than throwing is deliberate, and matches the
     * setting registry: a client that registers an element before writing its
     * renderer should see that one element is missing, not lose the whole HUD.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean render(Placement placement) {
        if (placement == null) {
            return false;
        }
        HudRenderer renderer = rendererFor(placement.getElement());
        if (renderer == null) {
            return false;
        }
        renderer.render(placement.getElement(), placement);
        return true;
    }
}
