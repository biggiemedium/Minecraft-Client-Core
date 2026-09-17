package dev.px.core.test.example;

import dev.px.core.layout.Align;
import dev.px.core.hud.AbstractHudElement;
import dev.px.core.hud.Anchor;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudLayout;
import dev.px.core.render.Color;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

/**
 * The element every client has: a right-aligned list of enabled modules, each on
 * its own panel.
 *
 * <p>Worth having as an example because it is the case a bounding box gets
 * wrong. The rows are different widths, so the rectangle around them contains a
 * lot of space the element does not fill &mdash; to the left of the short ones.
 * Treated as a box, clicking that empty space would select the list and the
 * editor would outline a shape the element visibly does not occupy.
 *
 * <p>Nothing here says any of that. The rows have backgrounds, so they are what
 * the element fills, and Core works the region and the outline out from the same
 * description it measures.
 */
@Getter
public final class ExampleArrayList extends AbstractHudElement {

    private static final Color PANEL = Color.of(0, 0, 0, 120);
    private static final float ROW_PADDING = 3f;

    /** Stands in for the enabled modules, so the suite can control the rows. */
    @Setter
    private List<String> entries = Arrays.asList("Crystal Aura", "Kill Aura", "ESP");

    public ExampleArrayList() {
        super("arraylist", "ArrayList", HudLayout.at(Anchor.TOP_RIGHT, -4f, 4f));
    }

    @Override
    public void content(Content c) {
        // Right-aligned, so the rows form a staircase down the left.
        c.align(Align.END).gap(1f);
        for (String entry : entries) {
            c.row(row -> {
                row.background(PANEL).padding(ROW_PADDING, 1f);
                row.text(entry, Color.WHITE);
            });
        }
    }
}
