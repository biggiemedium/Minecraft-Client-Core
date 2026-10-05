package dev.px.core.render.font;

/**
 * A measurable font handle owned by the active render backend.
 *
 * <p>Core measures text constantly, to size HUD elements, lay out GUI rows, and
 * centre labels, but it never rasterises. Measurement lives on the handle so a
 * caller can size a panel without a draw call.
 */
public interface Font {

    String getName();

    float getSize();

    /** @return the rendered width of {@code text} in scaled screen units. */
    float widthOf(String text);

    /** @return the height of a single line, ascender to descender. */
    float getHeight();

    /** @return the vertical distance between consecutive baselines. */
    float getLineHeight();

    /**
     * @return {@code text} truncated with an ellipsis so it fits {@code maxWidth}.
     *         Returns the input unchanged when it already fits.
     */
    default String truncate(String text, float maxWidth) {
        if (widthOf(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        float room = maxWidth - widthOf(ellipsis);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (widthOf(result.toString() + text.charAt(i)) > room) {
                break;
            }
            result.append(text.charAt(i));
        }
        return result + ellipsis;
    }
}
