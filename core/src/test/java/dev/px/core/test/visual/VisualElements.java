package dev.px.core.test.visual;

import dev.px.core.hud.AbstractHudElement;
import dev.px.core.layout.Align;
import dev.px.core.hud.Anchor;
import dev.px.core.layout.Content;
import dev.px.core.hud.HudElement;
import dev.px.core.hud.HudLayout;
import dev.px.core.layout.Shape;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;

/**
 * Five elements, each written to test one thing the content system claims.
 *
 * <p>Every one of them is a single class, and not one of them mentions a
 * coordinate, a size, or the word scale.
 */
public final class VisualElements {

    static final Color PANEL = Color.of(16, 18, 24, 190);
    static final Color ACCENT = Color.of(120, 200, 255);
    static final Color DIM = Color.of(150, 158, 175);

    private VisualElements() {
    }

    /** The floor: a rounded panel and a word. */
    public static final class Watermark implements HudElement {

        @Override
        public String getId() {
            return "watermark";
        }

        @Override
        public String getDisplayName() {
            return "Watermark";
        }

        @Override
        public void content(Content c) {
            c.background(PANEL, 4f).padding(7f, 4f);
            c.row(r -> {
                r.gap(4f).align(Align.CENTER);
                r.text("px", ACCENT);
                r.text("Core", Color.WHITE);
            });
        }

        @Override
        public HudLayout defaultLayout() {
            return HudLayout.at(Anchor.TOP_LEFT, 8f, 8f);
        }
    }

    /**
     * Scenario 1: a rounded panel whose interaction region and editor outline are
     * rounded too, without ever declaring a shape.
     */
    public static final class FpsCounter extends AbstractHudElement {

        private final BooleanSetting showLabel = bool("Show Label", true);
        private final ColorSetting colour = color("Colour", Color.WHITE);

        @Setter
        private int fps;

        public FpsCounter() {
            super("fps", "FPS Counter", HudLayout.at(Anchor.TOP_RIGHT, -8f, 8f));
        }

        @Override
        public void content(Content c) {
            c.background(PANEL, 8f).padding(8f, 5f);
            c.row(r -> {
                r.gap(5f).align(Align.CENTER);
                if (showLabel.isOn()) {
                    r.text("FPS", DIM);
                }
                // A frozen sample while positioning, so the box does not twitch
                // under the cursor as the real number changes.
                r.text(isEditing() ? "240" : String.valueOf(fps), colour.resolve());
            });
        }
    }

    /**
     * Scenario 2: rows of different widths, right-aligned. The element is the
     * rows, not the rectangle around them.
     */
    public static final class ModuleList extends AbstractHudElement {

        @Setter
        private List<String> entries = Arrays.asList("Crystal Aura", "Kill Aura", "ESP");

        public ModuleList() {
            super("modulelist", "Module List", HudLayout.at(Anchor.TOP_RIGHT, -8f, 44f));
        }

        @Override
        public void content(Content c) {
            // Flush, with no gap, so the rows form one continuous block. The
            // editor then outlines the silhouette of that block -- a staircase
            // down the left -- rather than boxing each row separately.
            c.align(Align.END);
            for (String entry : entries) {
                c.row(row -> {
                    row.background(PANEL).padding(6f, 3f);
                    row.gap(5f).align(Align.CENTER);
                    row.rect(2f, 8f, ACCENT);
                    row.text(entry, Color.WHITE);
                });
            }
        }
    }

    /** Nesting: a column of labelled rows, all measured from the text. */
    public static final class Coordinates extends AbstractHudElement {

        @Setter
        private double x;
        @Setter
        private double y;
        @Setter
        private double z;

        public Coordinates() {
            super("coords", "Coordinates", HudLayout.at(Anchor.BOTTOM_LEFT, 8f, -8f));
        }

        @Override
        public void content(Content c) {
            c.background(PANEL, 4f).padding(7f, 5f).gap(3f);
            axis(c, "X", x);
            axis(c, "Y", y);
            axis(c, "Z", z);
        }

        private void axis(Content c, String label, double value) {
            c.row(r -> {
                r.gap(6f).align(Align.CENTER);
                r.text(label, ACCENT);
                r.text(String.format("%.1f", value), Color.WHITE);
            });
        }
    }

    /**
     * The escape hatch: something the box model cannot express, drawn by hand,
     * still measured, anchored, clamped, scaled and hit tested.
     */
    public static final class Dial extends AbstractHudElement {

        private static final float SIZE = 56f;

        @Setter
        private float progress = 0.6f;

        public Dial() {
            super("dial", "Dial", HudLayout.at(Anchor.BOTTOM_RIGHT, -8f, -8f));
        }

        @Override
        public void content(Content c) {
            c.custom(SIZE, SIZE, (x, y, w, h) -> {
                float radius = w / 2f;
                float cx = x + radius;
                float cy = y + radius;
                Render.circle(cx, cy, radius, PANEL);
                Render.circleOutline(cx, cy, radius - 2f, 2f, DIM.withAlpha(90));
                Render.arc(cx, cy, radius - 2f, -90f, -90f + 360f * progress, 3f, ACCENT);

                double angle = Math.toRadians(-90f + 360f * progress);
                Render.line(cx, cy,
                        cx + (float) Math.cos(angle) * (radius - 8f),
                        cy + (float) Math.sin(angle) * (radius - 8f),
                        2f, Color.WHITE);
            });
        }

        /** Core cannot see inside a custom callback, so this one does declare its shape. */
        @Override
        public Shape getShape(float x, float y, float w, float h) {
            return Shape.circle(x + w / 2f, y + h / 2f, w / 2f);
        }
    }
}
