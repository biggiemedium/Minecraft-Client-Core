package dev.px.gui.test.visual;

import dev.px.core.Core;
import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.module.Module;
import dev.px.core.module.ModuleInfo;
import dev.px.core.render.Color;
import dev.px.core.render.Render;
import dev.px.core.setting.impl.BooleanSetting;
import dev.px.core.setting.impl.ColorSetting;
import dev.px.core.setting.impl.NumberSetting;
import dev.px.core.test.example.ExampleCategories;
import dev.px.core.test.visual.GlfwKeys;
import dev.px.core.test.visual.VisualWindow;
import dev.px.core.test.visual.WindowPlatform;
import dev.px.gui.Screen;
import dev.px.gui.legacy.Component;
import dev.px.gui.legacy.GuiService;
import dev.px.gui.legacy.click.CategoryWindow;
import dev.px.gui.legacy.click.ModuleButton;
import dev.px.gui.test.ExampleAura;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The GUI in a real window, with its layout and hit areas drawn over it.
 *
 * <pre>
 *   ./gradlew :gui:visual
 * </pre>
 *
 * <p>Two screens, picked with {@code --screen=}:
 *
 * <ul>
 *   <li>{@code demo} (the default): the {@link DemoScreen}, built from the new
 *       widgets with a look written in the harness, and the wireframe's fallback
 *       renderer for anything that look leaves out. Clicking presses a button.</li>
 *   <li>{@code clickgui}: the legacy click GUI, booted through its
 *       {@code GuiService} as a client would and routed the window's input.
 *       Left-click toggles a module, right-click opens its settings, a title bar
 *       drags.</li>
 * </ul>
 *
 * <p>The {@link Wireframe} inspector is drawn over either. Every colour and key
 * below is this file's choice; the library draws only through {@code Render2D}.
 *
 * <p><b>Controls.</b> The demo's input goes through the screen's own routing:
 * click, drag, scroll and type, with {@code Tab} and {@code Shift+Tab} moving
 * focus, {@code Enter} or {@code Space} activating, and {@code Escape} backing
 * out of a popup or focus before it quits. {@code F1} cycles the view,
 * {@code F2} turns class-name labels on and off, {@code F3} named parts.
 *
 * <p><b>Without a display:</b>
 *
 * <pre>
 *   ./gradlew :gui:visual --args="--frames=10 --screenshot=/tmp/gui.png
 *       --expand='Kill Aura' --mouse=60,40 --view=wireframe"
 * </pre>
 *
 * <p>{@code --view=} is one of {@code overlay}, {@code ghost}, {@code wireframe}
 * or {@code gui}; {@code --expand=} opens the click GUI's settings for the
 * modules named, comma separated; {@code --click=x,y} clicks the demo once,
 * after its first frame; {@code --scroll=n} turns the wheel down n notches
 * there, at the cursor; {@code --tooltip-delay=ms} sets the demo's tooltip
 * delay; {@code --no-labels} starts with class names off.
 */
public final class GuiVisualTest {

    private static final Color HELP = Color.of(255, 255, 255, 105);

    /** What is drawn: the GUI at some opacity, and whether the wireframe goes over it. */
    enum View {

        OVERLAY("GUI + wireframe", 1f, true),
        GHOST("faded GUI + wireframe", 0.25f, true),
        WIREFRAME("wireframe only", 0f, true),
        GUI("GUI only", 1f, false);

        private final String label;
        private final float guiAlpha;
        private final boolean wireframe;

        View(String label, float guiAlpha, boolean wireframe) {
            this.label = label;
            this.guiAlpha = guiAlpha;
            this.wireframe = wireframe;
        }

        View next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static View view = View.OVERLAY;

    private GuiVisualTest() {
    }

    public static void main(String[] args) throws Exception {
        VisualWindow window = VisualWindow.open("Core GUI - wireframe harness", args);
        WindowPlatform platform = window.getPlatform();

        // ---- boot Core with the GUI, exactly as a client would -------------------
        Core core = Core.builder("GuiVisual", "1.0").platform(platform).build();
        core.getCategories().registerAll(ExampleCategories.class);
        core.getModuleRegistry().registerAll(new ExampleAura(), new Sprint(), new Esp());
        GuiService gui = GuiService.install(core);

        Render.install(window.getBackend());
        core.start();

        boolean legacy = "clickgui".equalsIgnoreCase(window.option("--screen="));
        // The legacy GUI's 13px rows were laid out for a font about 11px tall;
        // the demo's look has room for more.
        Render.setDefaultFont(window.loadFont(legacy ? 11f : 14f));

        Wireframe wireframe = new Wireframe();
        wireframe.setFont(window.loadFont(9f));
        wireframe.setLabels(!window.has("--no-labels"));

        String chosen = window.option("--view=");
        if (chosen != null) {
            view = View.valueOf(chosen.toUpperCase(Locale.ROOT));
        }

        DemoScreen demo = legacy ? null : new DemoScreen(core.getLogger(), window.loadFont(28f), wireframe,
                () -> glfwSetWindowShouldClose(window.getHandle(), true));
        if (legacy) {
            gui.openClickGui();
            String expand = window.option("--expand=");
            if (expand != null) {
                expand(gui, Arrays.asList(expand.split(",")));
            }
        }

        installInput(window.getHandle(), platform, gui, demo, wireframe);
        float[] click = point(window.option("--click="));
        String scrolled = window.option("--scroll=");
        String delay = window.option("--tooltip-delay=");
        if (demo != null && delay != null) {
            demo.getScreen().tooltipDelay(Long.parseLong(delay));
        }

        // ---- the frame ------------------------------------------------------------
        int frames = window.run((frame, seconds) -> {
            float mouseX = platform.getMouseX();
            float mouseY = platform.getMouseY();
            if (demo != null) {
                demo.getScreen().resize(platform.getScreenWidth(), platform.getScreenHeight());
                demo.getScreen().update(mouseX, mouseY);
                if (click != null && frame == 1) {
                    demo.click(click[0], click[1]);
                }
                if (scrolled != null && frame == 1) {
                    demo.getScreen().scroll(mouseX, mouseY, -Float.parseFloat(scrolled));
                }
            }

            boolean faded = view.guiAlpha < 1f;
            if (faded) {
                Render.pushAlpha(view.guiAlpha);
            }
            if (demo != null) {
                demo.getScreen().draw();
            } else {
                // Lays the screen out and draws it. Laid out even when it is drawn
                // invisibly, so the wireframe always has this frame's geometry.
                gui.renderFrame();
            }
            if (faded) {
                Render.popAlpha();
            }

            if (view.wireframe) {
                if (demo != null) {
                    wireframe.draw(demo.getScreen(), mouseX, mouseY);
                } else {
                    wireframe.draw(gui.getCurrent(), mouseX, mouseY);
                }
            }
            drawHelp(platform.getScreenWidth(), platform.getScreenHeight(), wireframe, demo != null);
        });

        core.stop();
        window.close();

        System.out.println("Rendered " + frames + " frames of the GUI with a real NanoVG backend.");
    }

    /** {@code "x,y"} as a point, or null. */
    private static float[] point(String value) {
        if (value == null) {
            return null;
        }
        String[] at = value.split(",");
        return new float[] { Float.parseFloat(at[0].trim()), Float.parseFloat(at[1].trim()) };
    }

    /** Opens the settings of every module named, so a screenshot can show them. */
    private static void expand(GuiService gui, List<String> names) {
        for (CategoryWindow categoryWindow : gui.getClickGui().getWindows()) {
            for (Component child : categoryWindow.getChildren()) {
                ModuleButton button = (ModuleButton) child;
                for (String name : names) {
                    if (button.getModule().getName().equalsIgnoreCase(name.trim())) {
                        button.setExpanded(true);
                    }
                }
            }
        }
    }

    private static void drawHelp(float width, float height, Wireframe wireframe, boolean demo) {
        String text = "F1 view: " + view.label
                + "    F2 labels " + (wireframe.isLabels() ? "on" : "off")
                + "    F3 parts " + (wireframe.isParts() ? "on" : "off")
                + (demo ? "    click a button" : "    right-click a module for its settings")
                + "    ESC quit";
        Render.text(text, (width - Render.textWidth(text)) / 2f, height - 22f, HELP);
    }

    // --------------------------------------------------------------------- input

    /**
     * The window's input, handed to the GUI in Core's own vocabulary.
     *
     * <p>What a client's game screen does. Keys the GUI does not consume fall
     * through to the harness's own controls.
     */
    private static void installInput(long handle, WindowPlatform platform, GuiService gui,
                                     DemoScreen demo, Wireframe wireframe) {
        Screen screen = demo == null ? null : demo.getScreen();
        if (screen != null) {
            screen.clipboard(platform::getClipboard, platform::setClipboard);
        }

        glfwSetMouseButtonCallback(handle, (window, button, action, mods) -> {
            float x = platform.getMouseX();
            float y = platform.getMouseY();
            MouseButton pressed = GlfwKeys.button(button);
            if (screen != null) {
                if (action == GLFW_PRESS) {
                    screen.press(x, y, pressed);
                } else if (action == GLFW_RELEASE) {
                    screen.release(x, y, pressed);
                }
            } else if (action == GLFW_PRESS) {
                gui.mousePressed(x, y, pressed);
            } else if (action == GLFW_RELEASE) {
                gui.mouseReleased(x, y);
            }
        });

        glfwSetScrollCallback(handle, (window, xOffset, yOffset) -> {
            if (screen != null) {
                screen.scroll(platform.getMouseX(), platform.getMouseY(), (float) yOffset);
            } else {
                gui.scrolled((float) yOffset, platform.getMouseX(), platform.getMouseY());
            }
        });

        glfwSetCharCallback(handle, (window, codepoint) -> {
            if (!Character.isBmpCodePoint(codepoint)) {
                return;
            }
            if (screen != null) {
                screen.typed((char) codepoint);
            } else {
                gui.charTyped((char) codepoint);
            }
        });

        // The keys a client binds itself: whatever the focused widget doesn't take.
        glfwSetKeyCallback(handle, (window, key, scancode, action, mods) -> {
            if (action != GLFW_PRESS && action != GLFW_REPEAT) {
                return;
            }
            Key resolved = GlfwKeys.key(key);
            Set<Modifier> modifiers = GlfwKeys.modifiers(mods);
            boolean taken = screen != null ? screen.key(resolved, modifiers) : gui.keyPressed(resolved, modifiers);
            if (taken) {
                return;
            }
            switch (resolved) {
                case TAB:
                    if (screen != null) {
                        if (modifiers.contains(Modifier.SHIFT)) {
                            screen.focusPrevious();
                        } else {
                            screen.focusNext();
                        }
                    }
                    break;
                case ENTER:
                case SPACE:
                    if (screen != null) {
                        screen.activate();
                    }
                    break;
                case ESCAPE:
                    if (screen == null || !screen.cancel()) {
                        glfwSetWindowShouldClose(window, true);
                    }
                    break;
                case F1: view = view.next(); break;
                case F2: wireframe.setLabels(!wireframe.isLabels()); break;
                case F3: wireframe.setParts(!wireframe.isParts()); break;
                default: break;
            }
        });
    }

    // ------------------------------------------------------------------- modules

    /** Something for the Movement window to hold. */
    @ModuleInfo(name = "Sprint", description = "Keeps sprinting", category = "Movement")
    public static final class Sprint extends Module {

        private final BooleanSetting omni = bool("Omni", false);
    }

    /** Something for the Render window to hold. */
    @ModuleInfo(name = "ESP", description = "Outlines entities", category = "Render")
    public static final class Esp extends Module {

        private final ColorSetting colour = color("Colour", Color.of(120, 200, 255));
        private final NumberSetting<Float> width = number("Line Width", 1.5f, 0.5f, 4f).step(0.5f);
    }
}
