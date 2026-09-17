package dev.px.core.test.suite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.px.core.event.impl.CharTypedEvent;
import dev.px.core.event.impl.KeyEvent;
import dev.px.core.event.impl.MouseEvent;
import dev.px.core.gui.Component;
import dev.px.core.gui.GuiService;
import dev.px.core.layout.Content;
import dev.px.core.gui.GuiStyle;
import dev.px.core.gui.Screen;
import dev.px.core.gui.SettingComponent;
import dev.px.core.gui.SettingRenderer;
import dev.px.core.gui.click.CategoryWindow;
import dev.px.core.gui.click.ClickGuiScreen;
import dev.px.core.gui.click.ModuleButton;
import dev.px.core.gui.setting.BindRow;
import dev.px.core.gui.setting.DefaultRenderers;
import dev.px.core.gui.setting.NumberRow;
import dev.px.core.input.Bind;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.module.Module;
import dev.px.core.gui.SettingRendererRegistry;
import dev.px.core.setting.Setting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.test.example.ExampleKillAura;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

import java.util.EnumSet;
import java.util.List;

/**
 * The GUI framework: renderer lookup, tree structure, visibility gating, hit
 * routing, setting round-trips and the input gate.
 *
 * <p>Runs with no render backend and no font, which is the point: every check
 * here is about what the tree <em>is</em> and what a click <em>does</em>, never
 * about what a pixel looks like. Coordinates only ever come from a component's
 * own resolved bounds, so moving a constant in {@code GuiStyle} cannot break a
 * single assertion.
 */
public final class GuiTests {

    private GuiTests() {
    }

    public static void run(TestClient client) {
        Checks.section("GUI");
        client.reset();

        GuiService gui = client.getCore().getGuiService();
        ExampleKillAura aura = client.getKillAura();

        renderers(gui, aura);
        lifecycle(gui);
        structure(client, gui, aura);
        gating(gui, aura);
        routing(client, gui, aura);
        settingRows(client, gui, aura);
        inputGate(client, gui, aura);
        persistence(gui);

        // Leave nothing swallowing input: the suites after this one post their own
        // mouse and key events, and an open screen would cancel every one.
        gui.close();
        client.reset();
    }

    // ------------------------------------------------------------- renderers

    private static void renderers(GuiService gui, ExampleKillAura aura) {
        Checks.checkEquals("a renderer ships for each of the nine setting types",
                9f, gui.getRenderers().size());

        for (Setting<?> setting : aura.getAllSettings()) {
            if (gui.getRenderers().rendererFor(setting) == null) {
                Checks.check("no renderer claims " + setting.getClass().getSimpleName(), false);
            }
        }
        Checks.check("every setting on a real module resolves to a renderer", true);

        Checks.check("a renderer reports the type it claims",
                gui.getRenderers().rendererFor(aura.getReach()).getSettingType()
                        == aura.getReach().getClass());

        // A setting type Core has never heard of.
        CountSetting custom = new CountSetting("Waypoints", 3);
        Checks.check("an unclaimed setting type has no renderer",
                gui.getRenderers().rendererFor(custom) == null);
        Checks.check("and yields no row rather than throwing",
                gui.getRenderers().create(custom) == null);

        gui.getRenderers().register(SettingRenderer.of(CountSetting.class,
                (CountSetting setting) -> new CountRow(setting)));
        Checks.check("registering a renderer for your own type makes it render",
                gui.getRenderers().create(custom) instanceof CountRow);
        Checks.checkEquals("and it joins the registry like anything else",
                10f, gui.getRenderers().size());

        gui.getRenderers().unregister(gui.getRenderers().rendererFor(custom));
        Checks.checkEquals("unregistering it again leaves the nine", 9f, gui.getRenderers().size());

        // A client registers before start(); the defaults are installed during it.
        // A default must therefore fill a gap without overruling a claim, or
        // replacing Core's slider would throw on the duplicate name at startup.
        SettingRendererRegistry fresh = new SettingRendererRegistry();
        fresh.register(SettingRenderer.of(NumberSetting.class,
                (NumberSetting<?> setting) -> new TestSlider(setting)));
        DefaultRenderers.installInto(fresh);
        Checks.checkEquals("installing the defaults fills every unclaimed type",
                9f, fresh.size());
        Checks.check("and leaves a type the client already claimed alone",
                fresh.create(aura.getReach()) instanceof TestSlider);

        // The same swap against the live registry, which needs a rebuild to reach
        // rows that were built at startup.
        gui.getRenderers().replace(SettingRenderer.of(NumberSetting.class,
                (NumberSetting<?> setting) -> new TestSlider(setting)));
        gui.rebuild();
        Checks.check("replacing a built-in renderer and rebuilding swaps the live rows",
                rowFor(buttonFor(gui, aura), aura.getReach()) instanceof TestSlider);

        gui.getRenderers().replace(SettingRenderer.of(NumberSetting.class,
                (NumberSetting<?> setting) -> new NumberRow(setting)));
        gui.rebuild();
        Checks.check("and putting Core's back restores them",
                rowFor(buttonFor(gui, aura), aura.getReach()) instanceof NumberRow);
    }

    // ------------------------------------------------------------- lifecycle

    private static void lifecycle(GuiService gui) {
        Checks.check("nothing is open to begin with", !gui.isOpen());

        gui.openClickGui();
        Checks.check("opening the click GUI opens it", gui.isOpen());
        Checks.check("and it is the screen showing", gui.getCurrent() == gui.getClickGui());

        // No stack: opening a second screen replaces the first rather than layering.
        Screen other = new BareScreen();
        gui.open(other);
        Checks.check("opening another screen replaces it", gui.getCurrent() == other);
        Checks.check("the first screen was told it closed", ((BareScreen) other).opened);

        gui.close();
        Checks.check("closing leaves nothing open", !gui.isOpen());
        Checks.check("and the screen was told", ((BareScreen) other).closed);
    }

    // ------------------------------------------------------------- structure

    private static void structure(TestClient client, GuiService gui, ExampleKillAura aura) {
        ClickGuiScreen screen = gui.getClickGui();
        gui.openClickGui();
        gui.renderFrame();

        Checks.checkEquals("one window per registered category",
                (float) client.getCore().getCategories().size(), (float) screen.getChildren().size());

        Checks.check("windows follow Category.getOrder",
                ((CategoryWindow) screen.getChildren().get(0)).getCategory().getName().equals("Combat")
                        && ((CategoryWindow) screen.getChildren().get(2)).getCategory().getName().equals("Render"));

        CategoryWindow combat = screen.windowFor("Combat").orElse(null);
        Checks.check("a window is addressable by category name", combat != null);
        Checks.checkEquals("it holds a button per module in that category",
                1f, (float) combat.getChildren().size());

        ModuleButton button = (ModuleButton) combat.getChildren().get(0);
        Checks.check("and the button is that module", button.getModule() == aura);

        CategoryWindow render = screen.windowFor("Render").orElse(null);
        Checks.check("a category with no modules still gets a window", render != null);
        Checks.checkEquals("with no buttons in it", 0f, (float) render.getChildren().size());

        Checks.checkEquals("a row per top-level setting, group children nested rather than repeated",
                (float) aura.getSettings().size(), (float) button.getChildren().size());
        Checks.check("which is fewer than the flattened list",
                aura.getSettings().size() < aura.getAllSettings().size());

        SettingComponent<?> group = rowFor(button, aura.getBlocking());
        Checks.check("the group row exists", group != null);
        Checks.checkEquals("and holds a row for each of its children",
                (float) aura.getBlocking().getChildren().size(), (float) group.getChildren().size());
    }

    // -------------------------------------------------------------- gating

    private static void gating(GuiService gui, ExampleKillAura aura) {
        ModuleButton button = buttonFor(gui, aura);
        button.setExpanded(false);
        gui.renderFrame();

        Checks.check("a collapsed module button shows no rows",
                button.visibleChildren().isEmpty());
        Checks.check("but still has them", !button.getChildren().isEmpty());

        float collapsedHeight = button.getBounds().getHeight();
        button.setExpanded(true);
        gui.renderFrame();
        Checks.check("expanding it reveals them", !button.visibleChildren().isEmpty());
        Checks.check("and makes it taller", button.getBounds().getHeight() > collapsedHeight);

        // visibleWhen: the rotation mode only exists while rotations are on.
        SettingComponent<?> mode = rowFor(button, aura.getRotationMode());
        Checks.check("a row for a conditional setting exists", mode != null);
        Checks.check("and is visible while its condition holds", mode.isVisible());

        aura.getRotations().set(false);
        gui.renderFrame();
        Checks.check("turning the condition off hides the row", !mode.isVisible());
        Checks.check("so it drops out of the parent's visible children",
                !button.visibleChildren().contains(mode));
        Checks.check("and out of hit testing entirely",
                gui.getClickGui().componentAt(centreX(mode), headerY(mode)) != mode);

        aura.getRotations().set(true);
        gui.renderFrame();
        Checks.check("turning it back on restores the row", mode.isVisible());

        // A collapsed group hides its children the same way.
        SettingComponent<?> group = rowFor(button, aura.getBlocking());
        aura.getBlocking().set(false);
        gui.renderFrame();
        Checks.check("a collapsed group shows no children", group.visibleChildren().isEmpty());

        aura.getBlocking().set(true);
        gui.renderFrame();
        Checks.check("an expanded one shows them", !group.visibleChildren().isEmpty());
    }

    // -------------------------------------------------------------- routing

    private static void routing(TestClient client, GuiService gui, ExampleKillAura aura) {
        ClickGuiScreen screen = gui.getClickGui();
        ModuleButton button = buttonFor(gui, aura);
        button.setExpanded(false);
        gui.renderFrame();

        CategoryWindow combat = screen.windowFor("Combat").orElse(null);

        Checks.check("a point on a window title hits the window",
                screen.componentAt(centreX(combat), titleY(combat)) == combat);
        Checks.check("a point on a module button hits the button",
                screen.componentAt(centreX(button), headerY(button)) == button);
        Checks.check("a point on empty screen hits only the screen",
                screen.componentAt(screen.getScreenWidth() - 1f, screen.getScreenHeight() - 1f) == screen);

        boolean before = aura.isEnabled();
        screen.mousePressed(centreX(button), headerY(button), MouseButton.LEFT);
        Checks.check("left-clicking a module button toggles the module", aura.isEnabled() != before);
        screen.mousePressed(centreX(button), headerY(button), MouseButton.LEFT);
        Checks.checkEquals("and again toggles it back", before, aura.isEnabled());

        screen.mousePressed(centreX(button), headerY(button), MouseButton.RIGHT);
        Checks.check("right-clicking expands its settings", button.isExpanded());
        Checks.checkEquals("without toggling the module", before, aura.isEnabled());

        // Bringing a window forward: last in the list draws last and is hit first.
        CategoryWindow movement = screen.windowFor("Movement").orElse(null);
        gui.renderFrame();
        screen.mousePressed(centreX(movement), titleY(movement), MouseButton.LEFT);
        List<Component> order = screen.getChildren();
        Checks.check("clicking a window brings it to the front",
                order.get(order.size() - 1) == movement);
        screen.mouseReleased(centreX(movement), titleY(movement));

        // Dragging is driven from the frame loop, so moving the mouse and drawing
        // is the whole gesture -- there is no move event to post.
        gui.renderFrame();
        float startX = movement.getX();
        screen.mousePressed(centreX(movement), titleY(movement), MouseButton.LEFT);
        Checks.check("pressing a title bar starts a drag", screen.isDragging());
        client.getPlatform().setMouse(centreX(movement) + 40f, titleY(movement) + 20f);
        gui.renderFrame();
        Checks.check("and the frame loop moves the window", movement.getX() > startX);

        screen.mouseReleased(0f, 0f);
        Checks.check("releasing ends the drag", !screen.isDragging());

        client.getPlatform().setMouse(0f, 0f);
        movement.setX(startX);
    }

    // ----------------------------------------------------------- setting rows

    private static void settingRows(TestClient client, GuiService gui, ExampleKillAura aura) {
        ClickGuiScreen screen = gui.getClickGui();
        ModuleButton button = buttonFor(gui, aura);
        button.setExpanded(true);
        gui.renderFrame();

        // ---- boolean --------------------------------------------------------
        SettingComponent<?> autoBlock = rowFor(button, aura.getAutoBlock());
        boolean wasOn = aura.getAutoBlock().isOn();
        screen.mousePressed(centreX(autoBlock), headerY(autoBlock), MouseButton.LEFT);
        Checks.check("clicking a checkbox toggles the boolean", aura.getAutoBlock().isOn() != wasOn);
        screen.mousePressed(centreX(autoBlock), headerY(autoBlock), MouseButton.LEFT);

        // ---- number ---------------------------------------------------------
        SettingComponent<?> reach = rowFor(button, aura.getReach());
        dragAcross(screen, reach, 0f);
        Checks.checkEquals("dragging a slider to the far left gives the minimum",
                3f, aura.getReach().getFloat());
        dragAcross(screen, reach, 1f);
        Checks.checkEquals("and to the far right the maximum", 6f, aura.getReach().getFloat());
        dragAcross(screen, reach, 0.5f);
        Checks.check("a slider never leaves its bounds, wherever it is dragged",
                aura.getReach().getFloat() >= 3f && aura.getReach().getFloat() <= 6f);

        // ---- range ----------------------------------------------------------
        // Which handle a press grabs is whichever is nearer, so pressing at one end
        // of the track is how you address that end.
        SettingComponent<?> cps = rowFor(button, aura.getCps());
        dragFrom(screen, cps, 0f, 0f);
        Checks.checkEquals("pressing the left of a range grabs the lower handle",
                1f, (float) aura.getCps().get().getLower());
        dragFrom(screen, cps, 1f, 1f);
        Checks.checkEquals("and pressing the right grabs the upper", 20f,
                (float) aura.getCps().get().getUpper());

        // ---- enum -----------------------------------------------------------
        SettingComponent<?> mode = rowFor(button, aura.getRotationMode());
        Checks.check("a dropdown starts closed", mode.visibleChildren().isEmpty());
        screen.mousePressed(centreX(mode), headerY(mode), MouseButton.LEFT);
        gui.renderFrame();
        Checks.checkEquals("clicking it lists every option",
                (float) aura.getRotationMode().getOptions().size(),
                (float) mode.visibleChildren().size());

        Component option = mode.visibleChildren().get(0);
        screen.mousePressed(centreX(option), headerY(option), MouseButton.LEFT);
        Checks.check("clicking an option selects it",
                aura.getRotationMode().get() == aura.getRotationMode().getOptions().get(0));
        gui.renderFrame();
        Checks.check("and closes the dropdown", mode.visibleChildren().isEmpty());

        // ---- multi enum -----------------------------------------------------
        SettingComponent<?> targets = rowFor(button, aura.getTargets());
        screen.mousePressed(centreX(targets), headerY(targets), MouseButton.LEFT);
        gui.renderFrame();
        Component first = targets.visibleChildren().get(0);
        boolean had = aura.getTargets().has(ExampleKillAura.Target.PLAYERS);
        screen.mousePressed(centreX(first), headerY(first), MouseButton.LEFT);
        Checks.check("clicking an option in a checkbox list toggles just that option",
                aura.getTargets().has(ExampleKillAura.Target.PLAYERS) != had);
        gui.renderFrame();
        Checks.check("and the list stays open", !targets.visibleChildren().isEmpty());
        screen.mousePressed(centreX(targets), headerY(targets), MouseButton.LEFT);

        // ---- colour ---------------------------------------------------------
        SettingComponent<?> colour = rowFor(button, aura.getHitboxColour());
        screen.mousePressed(centreX(colour), headerY(colour), MouseButton.LEFT);
        gui.renderFrame();
        Checks.check("a colour row opens into a picker", !colour.visibleChildren().isEmpty());

        int controls = colour.visibleChildren().size();
        aura.getHitboxColour().rainbow(true);
        gui.renderFrame();
        Checks.check("a colour following the rainbow hides the controls it no longer drives",
                colour.visibleChildren().size() < controls);
        aura.getHitboxColour().rainbow(false);
        gui.renderFrame();
        Checks.checkEquals("turning it off brings them back",
                (float) controls, (float) colour.visibleChildren().size());
        screen.mousePressed(centreX(colour), headerY(colour), MouseButton.LEFT);

        // ---- text -----------------------------------------------------------
        SettingComponent<?> label = rowFor(button, aura.getLabel());
        gui.renderFrame();
        screen.mousePressed(centreX(label), headerY(label), MouseButton.LEFT);
        Checks.check("clicking a text field focuses it", label.isFocused());

        String original = aura.getLabel().get();
        gui.charTyped('z');
        Checks.checkEquals("a typed character reaches the focused field",
                original + "z", aura.getLabel().get());

        gui.keyPressed(Key.BACKSPACE, EnumSet.noneOf(Modifier.class));
        Checks.checkEquals("and backspace removes one", original, aura.getLabel().get());

        gui.keyPressed(Key.ENTER, EnumSet.noneOf(Modifier.class));
        Checks.check("enter gives up focus", !label.isFocused());

        // ---- keybind --------------------------------------------------------
        SettingComponent<?> keybind = rowFor(button, aura.getKeybind());
        gui.renderFrame();
        screen.mousePressed(centreX(keybind), headerY(keybind), MouseButton.LEFT);
        Checks.check("clicking a keybind button starts capturing", ((BindRow) keybind).isCapturing());

        gui.keyPressed(Key.J, EnumSet.of(Modifier.CTRL));
        Checks.checkEquals("the next key becomes the bind, modifiers included",
                Bind.of(Key.J, Modifier.CTRL), aura.getKeybind().get());
        Checks.check("and capturing stops", !((BindRow) keybind).isCapturing());

        screen.mousePressed(centreX(keybind), headerY(keybind), MouseButton.LEFT);
        boolean consumed = gui.keyPressed(Key.ESCAPE, EnumSet.noneOf(Modifier.class));
        Checks.checkEquals("escape clears the bind rather than binding escape",
                Bind.NONE, aura.getKeybind().get());
        Checks.check("and reports that it was consumed, so the host does not close the screen",
                consumed);
    }

    // ------------------------------------------------------------ input gate

    private static void inputGate(TestClient client, GuiService gui, ExampleKillAura aura) {
        gui.openClickGui();
        gui.renderFrame();

        // Core no longer listens to the bus for the GUI at all. The game's own
        // screen owns the mouse and keyboard while it is showing, so there is
        // nothing to intercept and nothing to cancel.
        MouseEvent mouse = new MouseEvent(MouseButton.LEFT, EnumSet.noneOf(Modifier.class), true, 1f, 1f);
        client.getCore().getBus().post(mouse);
        Checks.check("an open GUI cancels no mouse event", !mouse.isCancelled());

        KeyEvent key = new KeyEvent(Key.Y, EnumSet.noneOf(Modifier.class), true);
        client.getCore().getBus().post(key);
        Checks.check("nor a key event", !key.isCancelled());

        CharTypedEvent typed = new CharTypedEvent('q');
        client.getCore().getBus().post(typed);
        Checks.check("nor a typed character", !typed.isCancelled());

        // Input arrives by being called, which is what the host screen does.
        SettingComponent<?> label = rowFor(buttonFor(gui, aura), aura.getLabel());
        gui.renderFrame();
        gui.mousePressed(centreX(label), headerY(label), MouseButton.LEFT);
        Checks.check("a press handed to the service reaches the component", label.isFocused());

        String original = aura.getLabel().get();
        Checks.check("and so does a character", gui.charTyped('k'));
        Checks.checkEquals("which the focused field receives",
                original + "k", aura.getLabel().get());
        gui.keyPressed(Key.BACKSPACE, EnumSet.noneOf(Modifier.class));
        gui.keyPressed(Key.ENTER, EnumSet.noneOf(Modifier.class));

        // Escape is reported, not acted on: whether it closes the screen is the
        // host's decision, and Core does not make it.
        Checks.check("escape is not consumed by an idle screen",
                !gui.keyPressed(Key.ESCAPE, EnumSet.noneOf(Modifier.class)));
        Checks.check("so the screen is still open until the host closes it", gui.isOpen());
        gui.close();
        Checks.check("and closing is an ordinary call", !gui.isOpen());

        Checks.check("input handed to a closed GUI is simply not consumed",
                !gui.mousePressed(1f, 1f, MouseButton.LEFT)
                        && !gui.charTyped('x')
                        && !gui.keyPressed(Key.Y, EnumSet.noneOf(Modifier.class)));
    }

    // ----------------------------------------------------------- persistence

    private static void persistence(GuiService gui) {
        ClickGuiScreen screen = gui.getClickGui();
        CategoryWindow combat = screen.windowFor("Combat").orElse(null);

        combat.setX(123f);
        combat.setY(45f);
        JsonObject saved = gui.save();

        combat.setX(0f);
        combat.setY(0f);
        gui.load(saved);
        Checks.checkEquals("a window position round-trips through the config", 123f, combat.getX());
        Checks.checkEquals("on both axes", 45f, combat.getY());

        Checks.checkSurvives("loading a config with no GUI section leaves the layout alone",
                () -> gui.load(new JsonObject()));
        Checks.checkEquals("literally alone", 123f, combat.getX());

        combat.setX(4f);
        combat.setY(4f);
    }

    // ---------------------------------------------------------------- helpers

    /** @return the row editing this exact setting, searched depth-first. */
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

    private static ModuleButton buttonFor(GuiService gui, Module module) {
        for (CategoryWindow window : gui.getClickGui().getWindows()) {
            for (Component child : window.getChildren()) {
                if (((ModuleButton) child).getModule() == module) {
                    return (ModuleButton) child;
                }
            }
        }
        return null;
    }

    // Aim points are read off a component's own resolved bounds, never written as
    // literal coordinates, so the layout constants are free to change.

    private static float centreX(Component component) {
        return component.getBounds().getCenterX();
    }

    private static float headerY(Component component) {
        return component.getBounds().getY() + GuiStyle.rowHeight() / 2f;
    }

    private static float titleY(Component component) {
        return component.getBounds().getY() + GuiStyle.titleHeight() / 2f;
    }

    /** Presses at the left of a row, drags to a fraction of its width, and releases. */
    private static void dragAcross(Screen screen, Component row, float fraction) {
        dragFrom(screen, row, 0f, fraction);
    }

    /**
     * Presses at one fraction across a row, drags to another, and releases.
     *
     * <p>Fractions of the row's own resolved width, so nothing here depends on
     * where the row happens to sit or how wide a window is.
     */
    private static void dragFrom(Screen screen, Component row, float from, float to) {
        float y = headerY(row);
        float left = row.getBounds().getX();
        float width = row.getBounds().getWidth();
        screen.mousePressed(left + width * from, y, MouseButton.LEFT);
        screen.updateDrag(left + width * to, y);
        screen.mouseReleased(left + width * to, y);
    }

    // ------------------------------------------------------------- test types

    /** A screen with nothing on it, to prove open and close are reported. */
    private static final class BareScreen extends Screen {

        boolean opened;
        boolean closed;

        BareScreen() {
            super("Bare");
        }

        @Override
        public void onOpen() {
            opened = true;
        }

        @Override
        public void onClose() {
            super.onClose();
            closed = true;
        }

        @Override
        public float getPreferredHeight(float width) {
            return getScreenHeight();
        }
    }

    /** A replacement for Core's slider, to prove a built-in renderer can be swapped. */
    private static final class TestSlider extends SettingComponent<NumberSetting<?>> {

        TestSlider(NumberSetting<?> setting) {
            super(setting);
        }

        @Override
        protected void content(Content c) {
            header(c, false);
        }
    }

    /** A setting type Core does not ship, to prove a client can add one. */
    private static final class CountSetting extends Setting<Integer> {

        CountSetting(String name, int defaultValue) {
            super(name, defaultValue);
        }

        @Override
        public String getTypeId() {
            return "count";
        }

        @Override
        public JsonElement toJson() {
            return new JsonPrimitive(get());
        }

        @Override
        public void fromJson(JsonElement json) {
            if (json != null && json.isJsonPrimitive()) {
                setSilently(json.getAsInt());
            }
        }
    }

    /** And the row that edits it, written against the same contract as the nine. */
    private static final class CountRow extends SettingComponent<CountSetting> {

        CountRow(CountSetting setting) {
            super(setting);
        }

        @Override
        protected void content(Content c) {
            header(c, false, row -> value(row, getSetting().displayValue()));
        }

        @Override
        protected boolean onClick(float x, float y, MouseButton button) {
            getSetting().set(getSetting().get() + 1);
            return true;
        }
    }
}
