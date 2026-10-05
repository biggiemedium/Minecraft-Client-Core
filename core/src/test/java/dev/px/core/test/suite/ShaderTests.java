package dev.px.core.test.suite;

import dev.px.core.render.Color;
import dev.px.core.shader.Shader;
import dev.px.core.shader.ShaderException;
import dev.px.core.shader.ShaderLoader;
import dev.px.core.shader.ShaderPreprocessor;
import dev.px.core.shader.ShaderService;
import dev.px.core.shader.ShaderSource;
import dev.px.core.shader.ShaderStage;
import dev.px.core.shader.Uniforms;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.FakeTexture;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.RecordingShaderBackend;
import dev.px.core.test.harness.TestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shader assembly, lifecycle and uniform delivery, with no OpenGL anywhere.
 *
 * <p>The whole point of the package is that only one class needs a context, so
 * everything else has to be provable without one: that {@code #include} is
 * inlined and applied once, that the version directive ends up first, that a
 * program is compiled on first use and not again, that binds and unbinds pair up
 * even when a draw throws, and that a GLSL error costs a log line rather than the
 * client.
 *
 * <p>Runs against a {@link ShaderService} of its own rather than the booted
 * client's, so the rest of the suite keeps running with no backend installed at
 * all &mdash; which is the state a client that ships no shaders is in.
 */
public final class ShaderTests {

    private ShaderTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Shaders");

        client.reset();

        preprocessor();
        sources();
        uniforms();
        service(client);
        inertWithoutBackend(client);
    }

    // ------------------------------------------------------- preprocessing

    private static void preprocessor() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("lib/noise.glsl", "float noise(vec2 p) { return 0.0; }");
        files.put("lib/common.glsl", "#version 150 core\n#include \"noise.glsl\"\nconst float PI = 3.14159;");
        files.put("glow.fsh", "#include \"lib/common.glsl\"\nvoid main() { }");
        ShaderLoader loader = ShaderLoader.of(files);

        String assembled = ShaderPreprocessor.process(files.get("glow.fsh"), null, loader, "glow.fsh");

        Checks.check("the version directive is hoisted to the first line",
                assembled.startsWith("#version 150 core\n"));
        Checks.check("an included file is inlined", assembled.contains("const float PI = 3.14159;"));
        Checks.check("an include inside an include is resolved too",
                assembled.contains("float noise(vec2 p)"));
        Checks.check("a sibling include resolves against the including file's folder",
                assembled.contains("lib/noise.glsl"));
        Checks.checkEquals("the version appears exactly once", 1, countOf(assembled, "#version"));

        // Include-once: two shaders pulling in the same header must not redeclare it.
        files.put("twice.fsh", "#include \"lib/noise.glsl\"\n#include \"lib/noise.glsl\"\nvoid main() { }");
        String once = ShaderPreprocessor.process(files.get("twice.fsh"), null,
                ShaderLoader.of(files), "twice.fsh");
        Checks.checkEquals("a header included twice is inlined once",
                1, countOf(once, "float noise(vec2 p)"));
        Checks.check("the second include is recorded as skipped",
                once.contains("already included"));

        Map<String, String> defines = new LinkedHashMap<>();
        defines.put("SAMPLES", "12");
        defines.put("HIGH_QUALITY", "");
        String defined = ShaderPreprocessor.process("#version 120\nvoid main() { }", defines, null, null);
        String[] lines = defined.split("\n");
        Checks.checkEquals("defines come after the version, never before",
                "#version 120", lines[0]);
        Checks.checkEquals("a define with a value is written out", "#define SAMPLES 12", lines[1]);
        Checks.checkEquals("a valueless define stays bare", "#define HIGH_QUALITY", lines[2]);

        Checks.checkThrows("a missing include is reported, not silently skipped",
                ShaderException.class,
                () -> ShaderPreprocessor.process("#include \"gone.glsl\"", null,
                        ShaderLoader.of(files), "glow.fsh"));
        Checks.checkThrows("an include with no loader says so",
                ShaderException.class,
                () -> ShaderPreprocessor.process("#include \"gone.glsl\"", null, null, null));
        Checks.checkThrows("a malformed include is reported",
                ShaderException.class,
                () -> ShaderPreprocessor.process("#include gone.glsl", null,
                        ShaderLoader.of(files), null));

        Checks.check("the numbered listing prefixes line numbers",
                ShaderPreprocessor.numbered("a\nb").startsWith("1 | a\n2 | b"));

        Checks.checkEquals("a loader falls back to the next one",
                "fallback",
                readQuietly(ShaderLoader.of(files).orElse(
                        ShaderLoader.of(single("only.glsl", "fallback"))), "only.glsl"));
    }

    // ------------------------------------------------------------- sources

    private static void sources() {
        ShaderSource fromFiles = ShaderSource.named("glow")
                .vertex("glow.vsh")
                .fragment("glow.fsh")
                .define("SAMPLES", 12)
                .define("STRENGTH", 1f)
                .define("HIGH_QUALITY")
                .build();

        Checks.checkEquals("a shader knows its stages", 2, fromFiles.getStages().size());
        Checks.checkEquals("a path-backed stage keeps its path",
                "glow.fsh", fromFiles.getPath(ShaderStage.FRAGMENT));
        Checks.check("a path-backed stage has no inline text",
                fromFiles.getText(ShaderStage.FRAGMENT) == null);
        Checks.check("a shader read from files is reloadable", fromFiles.isReloadable());
        Checks.checkEquals("an int define is written plainly",
                "12", fromFiles.getDefines().get("SAMPLES"));
        Checks.checkEquals("a float define gets the decimal point GLSL demands",
                "1.0", fromFiles.getDefines().get("STRENGTH"));
        Checks.checkEquals("a valueless define stores an empty value",
                "", fromFiles.getDefines().get("HIGH_QUALITY"));

        ShaderSource inline = ShaderSource.named("inline")
                .fragmentSource("void main() { }")
                .build();
        Checks.check("inline source is not reloadable", !inline.isReloadable());
        Checks.check("a fragment-only shader is legal", inline.has(ShaderStage.FRAGMENT));

        Checks.checkThrows("a shader with no stages is rejected at registration",
                IllegalArgumentException.class, () -> ShaderSource.named("empty").build());
        Checks.checkThrows("a shader with no name is rejected",
                IllegalArgumentException.class, () -> ShaderSource.named(" "));
    }

    // ------------------------------------------------------------ uniforms

    private static void uniforms() {
        RecordingShaderBackend sink = new RecordingShaderBackend();
        Uniforms values = new Uniforms();

        values.set("uFloat", 1.5f)
                .set("uVec2", 2f, 3f)
                .set("uVec3", 4f, 5f, 6f)
                .set("uVec4", 7f, 8f, 9f, 10f)
                .set("uInt", 3)
                .set("uBool", true)
                .set("uColour", Color.of(255, 0, 0, 255))
                .rgb("uTint", Color.of(0, 255, 0, 0));

        Checks.checkEquals("every value is recorded", 8, values.size());
        values.forEach(sink);
        Checks.checkEquals("a float arrives as one component", "1.5", sink.uniform("uFloat"));
        Checks.checkEquals("a vec2 arrives as two", "2.0,3.0", sink.uniform("uVec2"));
        Checks.checkEquals("a vec4 arrives as four", "7.0,8.0,9.0,10.0", sink.uniform("uVec4"));
        Checks.checkEquals("an int stays an int", "3", sink.uniform("uInt"));
        Checks.checkEquals("a bool becomes 0 or 1", "1", sink.uniform("uBool"));
        Checks.checkEquals("a colour is normalised to 0..1, not left at 0..255",
                "1.0,0.0,0.0,1.0", sink.uniform("uColour"));
        Checks.checkEquals("rgb drops the alpha", "0.0,1.0,0.0", sink.uniform("uTint"));

        values.matrix("uProjection", identity(4));
        sink.clearEvents();
        values.forEach(sink);
        Checks.check("a 16-float matrix is read as a mat4",
                sink.uniform("uProjection").startsWith("mat4("));
        Checks.checkThrows("a matrix that is not square is rejected",
                IllegalArgumentException.class, () -> new Uniforms().matrix("bad", new float[5]));

        FakeTexture texture = new FakeTexture("scene");
        values.sampler("uScene", texture, 1);
        sink.clearEvents();
        values.forEach(sink);
        Checks.checkEquals("a sampler carries its texture unit", "scene@1", sink.uniform("uScene"));

        texture.dispose();
        Uniforms afterDispose = new Uniforms().sampler("uScene", texture, 0);
        Checks.checkEquals("a disposed texture is dropped rather than bound", 0, afterDispose.size());

        // Reuse: the same instance, refilled, is the hot path every frame.
        values.clear();
        Checks.checkEquals("clear forgets everything", 0, values.size());
        Checks.check("clear forgets by name too", !values.has("uFloat"));
        values.set("uFloat", 9f);
        sink.clearEvents();
        values.forEach(sink);
        Checks.checkEquals("a refilled instance emits only what was set again", 1, values.size());
        Checks.checkEquals("and the new value, not the old one", "9.0", sink.uniform("uFloat"));
        Checks.check("a value from before the clear is not re-emitted", !sink.hasUniform("uVec2"));

        values.set("uGone", 1f).remove("uGone");
        Checks.check("remove drops one value", !values.has("uGone"));

        Uniforms copy = new Uniforms().setAll(values);
        Checks.checkEquals("setAll copies the live values", values.size(), copy.size());
        Checks.check("setAll copies by name", copy.has("uFloat"));
    }

    // ------------------------------------------------------------- service

    private static void service(TestClient client) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("glow.vsh", "#version 120\nvoid main() { }");
        files.put("glow.fsh", "#version 120\nuniform float uTime;\nvoid main() { }");
        files.put("outer.fsh", "void main() { }");
        files.put("inner.fsh", "void main() { }");

        RecordingLogger logger = new RecordingLogger();
        RecordingShaderBackend backend = new RecordingShaderBackend();
        ShaderService shaders = new ShaderService(logger, client.getPlatform());
        shaders.setBackend(backend);
        shaders.setLoader(ShaderLoader.of(files));
        shaders.start();

        shaders.register(ShaderSource.named("glow").vertex("glow.vsh").fragment("glow.fsh").build());
        shaders.registerAll(
                ShaderSource.named("outer").fragment("outer.fsh").build(),
                ShaderSource.named("inner").fragment("inner.fsh").build());

        Checks.checkEquals("registration compiles nothing", 0, backend.compileCount("glow"));

        // ---- compile on first use, once ------------------------------------
        List<String> drawn = new ArrayList<>();
        shaders.use("glow", () -> drawn.add("body"));
        Checks.checkEquals("the body runs", 1, drawn.size());
        Checks.checkEquals("the shader is compiled on first use", 1, backend.compileCount("glow"));
        shaders.use("glow", () -> drawn.add("body"));
        Checks.checkEquals("and never again", 1, backend.compileCount("glow"));
        Checks.check("the assembled fragment source reached the backend",
                backend.glslOf("glow", ShaderStage.FRAGMENT).contains("uniform float uTime;"));
        Checks.check("isReady reports a working shader", shaders.isReady("glow"));

        // ---- binding pairs up ----------------------------------------------
        backend.clearEvents();
        shaders.use("glow", () -> { });
        Checks.checkEquals("a pass binds then unbinds", "[bind:glow, unbind]", backend.getEvents().toString());
        Checks.check("nothing is left bound", shaders.getBound() == null);

        // ---- uniforms -------------------------------------------------------
        backend.clearEvents();
        client.getPlatform().setScreen(854f, 480f);
        client.getPlatform().setMouse(120f, 90f);
        shaders.use("glow", u -> u.set("uRadius", 6f).set("uColour", Color.of(0, 0, 255, 255)), () -> { });
        Checks.checkEquals("per-pass uniforms are uploaded", "6.0", backend.uniform("uRadius"));
        Checks.checkEquals("the screen size is supplied without asking",
                "854.0,480.0", backend.uniform(ShaderService.UNIFORM_RESOLUTION));
        Checks.checkEquals("so is the cursor", "120.0,90.0", backend.uniform(ShaderService.UNIFORM_MOUSE));
        Checks.check("so is the clock", backend.hasUniform(ShaderService.UNIFORM_TIME));
        Checks.check("the clock starts at zero and moves forward",
                Float.parseFloat(backend.uniform(ShaderService.UNIFORM_TIME)) >= 0f);

        backend.clearEvents();
        shaders.setBuiltinUniforms(false);
        shaders.use("glow", () -> { });
        Checks.check("builtins can be turned off for a client with its own names",
                !backend.hasUniform(ShaderService.UNIFORM_RESOLUTION));
        shaders.setBuiltinUniforms(true);

        backend.clearEvents();
        shaders.addGlobalUniforms(u -> u.set("uGamma", 2.2f).set("uRadius", 1f));
        shaders.use("glow", u -> u.set("uRadius", 6f), () -> { });
        Checks.checkEquals("a global provider contributes to every pass", "2.2", backend.uniform("uGamma"));
        Checks.checkEquals("and a pass's own value wins over a global one",
                "6.0", backend.uniform("uRadius"));
        Checks.checkEquals("globals are listed", 1, shaders.getGlobalUniforms().size());

        // ---- nesting --------------------------------------------------------
        backend.clearEvents();
        shaders.use("outer", () -> shaders.use("inner", () -> { }));
        Checks.checkEquals("a nested pass restores the outer program rather than dropping to none",
                "[bind:outer, bind:inner, bind:outer, unbind]", backend.getEvents().toString());

        // ---- a draw that throws still unbinds --------------------------------
        backend.clearEvents();
        try {
            shaders.use("glow", () -> {
                throw new IllegalStateException("the draw blew up");
            });
            Checks.check("a throwing body propagates", false);
        } catch (IllegalStateException expected) {
            Checks.check("a throwing body propagates", true);
        }
        Checks.checkEquals("and the shader is still unbound afterwards",
                "[bind:glow, unbind]", backend.getEvents().toString());
        Checks.check("with nothing left on the stack", shaders.getBound() == null);

        // ---- a broken shader is contained ------------------------------------
        files.put("broken.fsh", "void main() { this is not glsl }");
        shaders.setLoader(ShaderLoader.of(files));
        shaders.register(ShaderSource.named("broken").fragment("broken.fsh").build());
        logger.clear();
        backend.clearEvents();
        backend.failOn("broken");

        drawn.clear();
        shaders.use("broken", () -> drawn.add("body"));
        Checks.checkEquals("a shader that will not compile still draws its body, unshaded",
                1, drawn.size());
        Checks.check("nothing was bound", backend.getEvents().isEmpty());
        Checks.check("the failure is reported with the shader named",
                logger.loggedError("Shader 'broken' failed to compile"));
        Checks.check("and the service remembers it failed", shaders.isFailed("broken"));

        shaders.use("broken", () -> { });
        Checks.checkEquals("a failed shader is not retried every frame", 1, backend.compileCount("broken"));
        Checks.checkEquals("so it is not logged every frame either", 1, logger.errorCount());

        backend.succeedOn("broken");
        shaders.reload("broken");
        Checks.check("reload clears the failure", !shaders.isFailed("broken"));
        shaders.use("broken", () -> { });
        Checks.checkEquals("and the shader is built again", 2, backend.compileCount("broken"));

        // ---- an unsupported stage is named ------------------------------------
        logger.clear();
        shaders.register(ShaderSource.named("geo")
                .fragment("glow.fsh")
                .stage(ShaderStage.GEOMETRY, "glow.vsh")
                .build());
        backend.failOn("geo");
        shaders.use("geo", () -> { });
        Checks.check("a stage the backend cannot compile is called out by name",
                logger.loggedError("declares a geometry stage"));

        // ---- reload and lost contexts -----------------------------------------
        Shader before = shaders.get("glow");
        shaders.reload();
        Checks.check("reload disposes the old program", !before.isValid());
        shaders.use("glow", () -> { });
        Checks.checkEquals("and builds it again on the next use", 2, backend.compileCount("glow"));

        backend.programOf("glow").loseContext();
        shaders.use("glow", () -> { });
        Checks.checkEquals("a handle whose context died is rebuilt rather than bound",
                3, backend.compileCount("glow"));

        // ---- a name nobody registered -----------------------------------------
        logger.clear();
        drawn.clear();
        shaders.use("nosuch", () -> drawn.add("body"));
        shaders.use("nosuch", () -> drawn.add("body"));
        Checks.checkEquals("an unknown shader still draws its body", 2, drawn.size());
        Checks.check("and is reported", logger.loggedWarning("No shader registered under 'nosuch'"));
        Checks.checkEquals("once, not every frame", 1, logger.warningCount());

        // ---- names are case-insensitive, like every other registry -------------
        Checks.check("a shader is found whatever case it is asked for", shaders.isReady("GLOW"));

        // ---- unregister and shutdown --------------------------------------------
        Shader inner = shaders.get("inner");
        Checks.check("unregister removes a shader", shaders.unregister("inner"));
        Checks.check("and disposes its program", !inner.isValid());

        Shader live = shaders.get("glow");
        shaders.stop();
        Checks.check("shutdown disposes every remaining program", !live.isValid());
    }

    // -------------------------------------------------- inert by default

    private static void inertWithoutBackend(TestClient client) {
        ShaderService wired = client.getCore().getShaderService();
        Checks.check("Core wires a shader service in", wired != null);
        Checks.check("with no backend until the client installs one", wired.getBackend() == null);

        wired.register(ShaderSource.named("unused").fragmentSource("void main() { }").build());

        List<String> drawn = new ArrayList<>();
        Checks.checkSurvives("a pass with no backend at all is harmless",
                () -> wired.use("unused", u -> u.set("uTime", 1f), () -> drawn.add("body")));
        Checks.checkEquals("and still draws its body", 1, drawn.size());
        Checks.check("nothing reports itself ready", !wired.isReady("unused"));

        wired.unregister("unused");
    }

    // ------------------------------------------------------------- helpers

    private static Map<String, String> single(String path, String text) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(path, text);
        return map;
    }

    private static String readQuietly(ShaderLoader loader, String path) {
        try {
            return loader.read(path);
        } catch (Exception e) {
            return "<" + e.getMessage() + ">";
        }
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static float[] identity(int order) {
        float[] values = new float[order * order];
        for (int i = 0; i < order; i++) {
            values[i * order + i] = 1f;
        }
        return values;
    }
}
