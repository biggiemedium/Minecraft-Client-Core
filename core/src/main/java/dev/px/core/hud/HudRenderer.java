package dev.px.core.hud;

import dev.px.core.registry.Named;

import java.util.function.BiConsumer;

/**
 * Draws one kind of {@link HudElement}.
 *
 * <p>This is the seam that keeps the look of the HUD out of Core. An element
 * says how big it is and what shape it is; a renderer says what it looks like.
 * Core owns neither the colours nor the drawing calls &mdash; it resolves
 * geometry and then hands a placement to whichever renderer claims the element.
 *
 * <p>The arrangement is the one {@link dev.px.core.gui.SettingRenderer} already
 * uses for settings: a {@link dev.px.core.registry.Registry} of renderers keyed
 * by the type they handle, so a client that invents an element type registers a
 * renderer for it and nothing in this package changes.
 *
 * <pre>{@code
 * Core.hud().getRenderers().register(
 *         HudRenderer.of(ClockElement.class, (clock, placement) -> {
 *             Bounds bounds = placement.getBounds();
 *             Size natural = placement.getNatural();
 *             Render.roundRect(bounds.getX(), bounds.getY(),
 *                     natural.getWidth(), natural.getHeight(), 3f, GuiStyle.surface());
 *             Render.text(clock.text(), bounds.getX() + 4f, bounds.getY() + 3f, Color.WHITE);
 *         }));
 * }</pre>
 *
 * <p><b>Draw at the placement's position, but at its natural size.</b> The
 * element's scale factor is applied as a transform around the call by
 * {@link HudService#drawAll}, so a renderer that never mentions scale is
 * automatically scalable &mdash; the same promise the old element contract made.
 *
 * @param <E> the element type this renders
 */
public interface HudRenderer<E extends HudElement> extends Named {

    /**
     * The element class this renderer claims.
     *
     * <p>A {@code Class<?>} rather than a {@code Class<E>} for the same reason
     * {@link dev.px.core.gui.SettingRenderer} uses one: matching is by
     * {@link Class#isInstance}, so the token only has to name the type and a
     * parameterised element needs no cast to register.
     */
    Class<?> getElementType();

    /**
     * Draws one element.
     *
     * @param element the element to draw
     * @param placement where it landed this frame. Draw at
     *                  {@code placement.getBounds()} top-left, sized by
     *                  {@code placement.getNatural()}; the scale transform is
     *                  already applied around this call
     */
    void render(E element, Placement placement);

    /** Registry key. The element type's name, which is unique by construction. */
    @Override
    default String getName() {
        return getElementType().getSimpleName();
    }

    // ------------------------------------------------------------ factories

    /**
     * The common case: a lambda over the element and its placement.
     *
     * <p>Takes a {@code Class<E>} rather than the {@code Class<?>} its setting
     * counterpart uses, so the lambda's parameter type is inferred and
     * {@code (element, placement) -> ...} compiles without spelling the type out.
     * Settings cannot do this because the generic ones have only raw class
     * literals; element types do not have that problem. An element type that is
     * itself generic can implement this interface directly.
     */
    static <E extends HudElement> HudRenderer<E> of(Class<E> type, BiConsumer<E, Placement> body) {
        return new HudRenderer<E>() {

            @Override
            public Class<?> getElementType() {
                return type;
            }

            @Override
            public void render(E element, Placement placement) {
                body.accept(element, placement);
            }
        };
    }
}
