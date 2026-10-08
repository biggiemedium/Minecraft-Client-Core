package dev.px.gui.test;

import dev.px.core.input.MouseButton;
import dev.px.core.layout.Bounds;
import dev.px.core.layout.Content;
import dev.px.core.render.Color;
import dev.px.core.render.Render2D;
import dev.px.core.render.Render;
import dev.px.core.setting.Setting;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.RecordingRender2D;
import dev.px.gui.Widget;
import dev.px.gui.container.Column;
import dev.px.gui.legacy.Component;
import dev.px.gui.legacy.GuiService;
import dev.px.gui.legacy.Panel;
import dev.px.gui.legacy.Screen;
import dev.px.gui.legacy.SettingComponent;
import dev.px.gui.legacy.click.CategoryWindow;
import dev.px.gui.legacy.click.ModuleButton;
import dev.px.gui.render.WidgetRenderer;
import dev.px.gui.render.WidgetRendererRegistry;
import dev.px.gui.test.visual.Wireframe;
import dev.px.gui.widget.Button;
import dev.px.gui.widget.Label;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The wireframe the visual harness draws over a GUI tree, and the part listing
 * it reads.
 *
 * <p>Drawn into a {@link RecordingRender2D}, so what is checked is exactly what
 * reached the backend: an outline at each component's bounds, a name for each
 * class and part. Coordinates come from the tree's own resolved bounds, never
 * from literals.
 */
public final class WireframeTests {

    private WireframeTests() {
    }

    public static void run(GuiTestClient client) {
        Checks.section("Wireframe");
        client.reset();

        GuiService gui = client.getGui();
        ExampleAura aura = client.getKillAura();
        ModuleButton button = buttonFor(gui, aura);
        gui.openClickGui();
        button.setExpanded(true);
        gui.renderFrame();

        Screen screen = gui.getCurrent();
        Wireframe wireframe = new Wireframe();
        RecordingRender2D recorder = new RecordingRender2D();
        Render2D previous = Render.backend2D();
        Render.install(recorder);
        try {
            parts(button, aura);
            outlines(screen, wireframe, recorder);
            gating(gui, screen, button, aura, wireframe, recorder);
            toggles(screen, wireframe, recorder, aura);
            hover(screen, wireframe, recorder, aura);
            focusAndDrag(gui, screen, wireframe, recorder, aura);
            names(wireframe, recorder);
            widgets(recorder);
        } finally {
            Render.install(previous);
            gui.close();
            client.reset();
        }
    }

    // ----------------------------------------------------------------- parts

    private static void parts(ModuleButton button, ExampleAura aura) {
        Checks.check("a component lists the named parts its content described (" + button.parts().keySet() + ")",
                button.parts().containsKey("marker"));
        Bounds marker = button.parts().get("marker");
        Checks.check("each at the rectangle part() reports",
                marker != null && marker == button.part("marker"));

        SettingComponent<?> reach = rowFor(button, aura.getReach());
        Checks.check("a slider row lists its track (" + (reach == null ? "no row" : reach.parts().keySet()) + ")",
                reach != null && reach.parts().containsKey("track"));
        Checks.check("a component never laid out lists nothing",
                new Panel().parts().isEmpty());
    }

    // -------------------------------------------------------------- outlines

    private static void outlines(Screen screen, Wireframe wireframe, RecordingRender2D recorder) {
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);

        List<Component> visible = new ArrayList<>();
        collectVisible(screen, visible);
        List<String> missing = new ArrayList<>();
        for (Component component : visible) {
            Bounds at = component.getBounds();
            if (!recorder.hasOutline(at.getX(), at.getY(), at.getWidth(), at.getHeight())) {
                missing.add(component.getClass().getSimpleName());
            }
        }
        Checks.check("every component taking part is outlined at its bounds ("
                + visible.size() + " components, missing " + missing + ")", missing.isEmpty());
        Checks.check("and labelled with its class name",
                recorder.getTexts().contains("ClickGuiScreen") && recorder.getTexts().contains("CategoryWindow")
                        && recorder.getTexts().contains("ModuleButton") && recorder.getTexts().contains("NumberRow"));
        Checks.check("a cursor outside everything adds no path label (" + recorder.getTexts().size() + " labels)",
                !containsFragment(recorder.getTexts(), " > "));
    }

    // ---------------------------------------------------------------- gating

    private static void gating(GuiService gui, Screen screen, ModuleButton button, ExampleAura aura,
                               Wireframe wireframe, RecordingRender2D recorder) {
        // The only EnumSetting on the module, so its row's label is countable.
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("a visible enum row is drawn", recorder.getTexts().contains("EnumRow"));

        aura.getRotations().set(false);
        gui.renderFrame();
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("a row hidden by visibleWhen is not drawn, as it takes no part in hit testing",
                !recorder.getTexts().contains("EnumRow"));
        aura.getRotations().set(true);

        button.setExpanded(false);
        gui.renderFrame();
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("nor are the rows of a collapsed module",
                !recorder.getTexts().contains("NumberRow") && recorder.getTexts().contains("ModuleButton"));

        button.setExpanded(true);
        gui.renderFrame();
    }

    // --------------------------------------------------------------- toggles

    private static void toggles(Screen screen, Wireframe wireframe, RecordingRender2D recorder, ExampleAura aura) {
        SettingComponent<?> reach = rowFor(screen, aura.getReach());
        Bounds track = reach == null ? null : reach.part("track");

        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("a named part is outlined at the rectangle it was laid out in",
                track != null && recorder.hasOutline(track.getX(), track.getY(), track.getWidth(), track.getHeight()));
        Checks.check("and named", recorder.getTexts().contains("track"));

        wireframe.setParts(false);
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("with parts off, no part is named",
                !recorder.getTexts().contains("track") && !recorder.getTexts().contains("marker"));
        wireframe.setParts(true);

        wireframe.setLabels(false);
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("with labels off, no class is named but the parts still are",
                !recorder.getTexts().contains("NumberRow") && recorder.getTexts().contains("track"));
        wireframe.setLabels(true);
    }

    // ----------------------------------------------------------------- hover

    private static void hover(Screen screen, Wireframe wireframe, RecordingRender2D recorder, ExampleAura aura) {
        SettingComponent<?> reach = rowFor(screen, aura.getReach());
        Bounds at = reach.getBounds();
        float x = at.getCenterX();
        float y = at.getY() + 2f;

        recorder.clear();
        wireframe.draw(screen, x, y);
        String path = fragment(recorder.getTexts(), " > ");
        Checks.check("the hovered component is named with its path down the tree (" + path + ")",
                path != null && path.startsWith("ClickGuiScreen > CategoryWindow > ModuleButton > NumberRow"));
        Checks.check("and its bounds (" + path + ")",
                path != null && path.endsWith(Math.round(at.getWidth()) + "x" + Math.round(at.getHeight())));

        int traced = 0;
        for (float[] outline : recorder.getOutlines()) {
            if (Checks.eq(outline[0], at.getX()) && Checks.eq(outline[1], at.getY())
                    && Checks.eq(outline[2], at.getWidth()) && Checks.eq(outline[3], at.getHeight())) {
                traced++;
            }
        }
        Checks.checkEquals("its hit shape is traced over its outline", 2f, traced);
    }

    // --------------------------------------------------------- focus and drag

    private static void focusAndDrag(GuiService gui, Screen screen, Wireframe wireframe,
                                     RecordingRender2D recorder, ExampleAura aura) {
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("nothing is marked focused or dragging while idle",
                !recorder.getTexts().contains("focused") && !recorder.getTexts().contains("dragging"));

        SettingComponent<?> label = rowFor(screen, aura.getLabel());
        Bounds row = label.getBounds();
        screen.mousePressed(row.getCenterX(), row.getY() + 2f, MouseButton.LEFT);
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("the focused text field is marked", screen.getFocused() == label
                && recorder.getTexts().contains("focused"));
        screen.clearFocus();

        CategoryWindow combat = gui.getClickGui().windowFor("Combat").orElse(null);
        Bounds window = combat.getBounds();
        screen.mousePressed(window.getX() + 2f, window.getY() + 2f, MouseButton.LEFT);
        recorder.clear();
        wireframe.draw(screen, -100f, -100f);
        Checks.check("a window being dragged is marked", screen.isDragging()
                && recorder.getTexts().contains("dragging"));
        screen.mouseReleased(window.getX() + 2f, window.getY() + 2f);
    }

    // ----------------------------------------------------------------- names

    private static void names(Wireframe wireframe, RecordingRender2D recorder) {
        Panel anonymous = new Panel() {
            @Override
            protected void content(Content c) {
                c.custom("knob", 4f, 4f, (x, y, w, h) -> { });
            }
        };
        anonymous.layout(0f, 0f, 50f);
        recorder.clear();
        wireframe.draw(anonymous, -100f, -100f);
        Checks.check("an anonymous component is named by the class it extends (" + recorder.getTexts() + ")",
                recorder.getTexts().contains("Panel"));
        Checks.check("an unattached tree still draws, with its parts",
                recorder.getTexts().contains("knob"));

        recorder.clear();
        wireframe.draw((Component) null, 0f, 0f);
        Checks.check("no tree draws nothing", recorder.getOutlines().isEmpty() && recorder.getTexts().isEmpty());
    }

    // --------------------------------------------------------------- widgets

    private static void widgets(RecordingRender2D recorder) {
        Wireframe wireframe = new Wireframe();
        WidgetRendererRegistry look = new WidgetRendererRegistry(new RecordingLogger());
        look.register(wireframe.renderer());
        look.register(WidgetRenderer.of(Button.class, (button, c) -> c.padding(2f).row(r -> {
            r.text(button.getLabel(), Color.WHITE);
            r.custom("knob", 4f, 4f, (x, y, w, h) -> { });
        })));

        Column root = new Column();
        Column menu = root.add(new Column());
        Button go = menu.add(new Button("go"));
        Label plain = root.add(new Label("x"));
        dev.px.gui.Screen screen = new dev.px.gui.Screen(look, root);
        screen.resize(200f, 100f);
        screen.update(0f, 0f);

        recorder.clear();
        screen.draw();
        Checks.check("the fallback renderer names every leaf type the look leaves out ("
                + recorder.getTexts() + ")", recorder.getTexts().contains("Label")
                && recorder.getTexts().contains("go") && !recorder.getTexts().contains("Button"));
        Checks.check("and gives it a size, so it can be seen and hit (" + plain.getBounds() + ")",
                Checks.eq(plain.getBounds().getHeight(), 13f));
        Checks.check("a container it claims is left bare, as the library leaves one with no renderer ("
                + menu.getBounds() + ", " + go.getBounds() + ")", !recorder.getTexts().contains("Column")
                && Checks.eq(go.getBounds().getY(), menu.getBounds().getY()));

        Bounds at = go.getBounds();
        recorder.clear();
        wireframe.draw(screen, at.getCenterX(), at.getCenterY());
        List<String> missing = new ArrayList<>();
        for (Widget widget : Arrays.asList(root, menu, go, plain)) {
            Bounds b = widget.getBounds();
            if (!recorder.hasOutline(b.getX(), b.getY(), b.getWidth(), b.getHeight())) {
                missing.add(widget.getClass().getSimpleName());
            }
        }
        Checks.check("the inspector outlines every widget the update placed (missing " + missing + ")",
                missing.isEmpty());
        Bounds knob = go.part("knob");
        Checks.check("and every named part, by name", knob != null && recorder.getTexts().contains("knob")
                && recorder.hasOutline(knob.getX(), knob.getY(), knob.getWidth(), knob.getHeight()));
        String path = fragment(recorder.getTexts(), " > ");
        Checks.check("and the path to the widget under the cursor (" + path + ")",
                path != null && path.startsWith("Column > Column > Button"));

        recorder.clear();
        wireframe.draw(screen, -5f, -5f);
        Checks.check("off every widget there is no path", !containsFragment(recorder.getTexts(), " > "));

        Label popup = screen.popup(new Label("popped"), dev.px.gui.Popup.below(go));
        screen.update(0f, 0f);
        recorder.clear();
        wireframe.draw(screen, -5f, -5f);
        Bounds popped = popup.getBounds();
        Checks.check("popups are outlined above the root (" + popped + ")",
                recorder.hasOutline(popped.getX(), popped.getY(), popped.getWidth(), popped.getHeight()));
        screen.closePopups();

        root.visibleWhen(() -> false);
        screen.update(0f, 0f);
        recorder.clear();
        wireframe.draw(screen, 0f, 0f);
        Checks.check("a root hidden at the last update shows nothing", recorder.getOutlines().isEmpty());
    }

    // --------------------------------------------------------------- helpers

    private static void collectVisible(Component component, List<Component> out) {
        out.add(component);
        for (Component child : component.visibleChildren()) {
            collectVisible(child, out);
        }
    }

    private static SettingComponent<?> rowFor(Component root, Setting<?> setting) {
        if (root instanceof SettingComponent && ((SettingComponent<?>) root).getSetting() == setting) {
            return (SettingComponent<?>) root;
        }
        for (Component child : root.getChildren()) {
            SettingComponent<?> found = rowFor(child, setting);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ModuleButton buttonFor(GuiService gui, ExampleAura aura) {
        for (CategoryWindow window : gui.getClickGui().getWindows()) {
            for (Component child : window.getChildren()) {
                if (((ModuleButton) child).getModule() == aura) {
                    return (ModuleButton) child;
                }
            }
        }
        return null;
    }

    private static boolean containsFragment(List<String> texts, String fragment) {
        return fragment(texts, fragment) != null;
    }

    private static String fragment(List<String> texts, String fragment) {
        for (String text : texts) {
            if (text.contains(fragment)) {
                return text;
            }
        }
        return null;
    }
}
