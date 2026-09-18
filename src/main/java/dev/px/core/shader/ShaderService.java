package dev.px.core.shader;

import dev.px.core.platform.Platform;
import dev.px.core.registry.Registry;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shaders the client owns: what they are, when they are built, and what they
 * are given when something draws with them.
 *
 * <p><b>Core does not link OpenGL and never will.</b> One class does, the
 * {@link ShaderBackend} the client installs, and it is about a hundred lines of
 * ordinary GL. Everything around it is the part that is the same in every client
 * and tedious in all of them: reading the source, stitching {@code #include}s
 * together, hoisting the version directive, compiling once instead of every
 * frame, keeping the handle, uploading uniforms, unbinding on the way out even
 * when the draw threw, disposing it all at shutdown, and reporting a GLSL typo
 * as a log line rather than a crash.
 *
 * <pre>{@code
 * // once, at startup -- no GL context needed yet
 * ShaderService shaders = Core.shaders();
 * shaders.setBackend(new GlShaderBackend());
 * shaders.setLoader(ShaderLoader.classpath("assets/leapfrog/shaders"));
 * shaders.register(ShaderSource.named("glow")
 *         .vertex("glow.vsh")
 *         .fragment("glow.fsh")
 *         .define("SAMPLES", 12)
 *         .build());
 *
 * // every frame, from wherever you already draw
 * Core.shaders().use("glow", u -> u.set("uRadius", 6f).set("uColour", theme.getPrimary()),
 *         () -> Render.rect(x, y, width, height, Color.WHITE));
 * }</pre>
 *
 * <h2>Nothing is compiled until it is drawn</h2>
 *
 * <p>Registration is pure data, so it can happen in the same block as modules and
 * HUD elements, before a window exists. The first {@link #use} of a shader reads
 * it, assembles it and compiles it, on the thread that is rendering &mdash; which
 * is the only thread that may. A shader the client registers but never draws with
 * costs a map entry.
 *
 * <h2>A broken shader is not a broken client</h2>
 *
 * <p>A missing file, a bad {@code #include} or a GLSL error is logged once, with
 * the assembled source listed by line, and the shader is marked failed so the
 * next frame does not try again. {@link #use} then runs the body unshaded, the
 * same way {@link dev.px.core.render.Render} drops draw calls before a backend is
 * installed: the geometry still appears, just without the effect. That is also
 * what happens when no backend is installed at all, so a client that never wants
 * shaders is not paying for this class and a client whose user is on a driver
 * that hates one shader keeps the other nine.
 *
 * <p>Everything here happens on the render thread and none of it is synchronised,
 * which matches every other drawing path in the library.
 */
public final class ShaderService implements Service {

    /** Seconds since startup, as a float. Set on every pass unless builtins are off. */
    public static final String UNIFORM_TIME = "uTime";

    /** Screen width and height in the scaled units {@link Platform} reports. */
    public static final String UNIFORM_RESOLUTION = "uResolution";

    /** Cursor position, in the same space as {@link #UNIFORM_RESOLUTION}. */
    public static final String UNIFORM_MOUSE = "uMouse";

    /**
     * {@link #UNIFORM_TIME} wraps here.
     *
     * <p>A float holds about seven digits, so a client left running overnight
     * would be feeding its shaders a clock whose resolution had decayed to
     * several milliseconds &mdash; visible as judder in anything that scrolls.
     * Wrapping trades that for one discontinuity an hour, which almost nothing
     * notices and a shader that does can avoid by taking its own time.
     */
    private static final float TIME_WRAP_SECONDS = 3600f;

    private final CoreLogger logger;
    private final Platform platform;

    /** Compiled state per registered shader, keyed the same way the registry keys names. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** What the client registered. Iterable, so a debug screen can list them. */
    @Getter
    private final Registry<ShaderSource> sources = new Registry<ShaderSource>() {

        @Override
        protected String describe() {
            return "shader";
        }

        @Override
        protected void onRegistered(ShaderSource source) {
            entries.put(key(source.getName()), new Entry(source));
        }

        @Override
        protected void onUnregistered(ShaderSource source) {
            Entry removed = entries.remove(key(source.getName()));
            if (removed != null) {
                removed.invalidate();
            }
        }
    };

    private final List<UniformProvider> globalUniforms = new ArrayList<>();

    /**
     * Shaders bound and not yet unbound.
     *
     * <p>GL has one active program, so a pass drawn inside another pass would
     * otherwise leave the outer one unbound halfway through its own body. The
     * stack is what lets {@link #unbind()} put the outer program back instead of
     * dropping to the fixed pipeline.
     */
    private final Deque<Shader> bound = new ArrayDeque<>();

    /** Names already reported as unknown, so a typo logs once rather than per frame. */
    private final Set<String> warned = new HashSet<>();

    /**
     * The one class that touches OpenGL. Installed by the adapter before startup.
     *
     * <p>Null is a supported state: every pass then draws its body unshaded.
     */
    @Getter
    @Setter
    private ShaderBackend backend;

    /**
     * How path-backed stages and {@code #include}s are read.
     *
     * <p>Defaults to {@link ShaderLoader#none()} rather than the classpath,
     * because guessing a resource root would turn "you forgot to set the loader"
     * into "the file is missing" and send the reader looking in the wrong place.
     */
    @Getter
    @Setter
    private ShaderLoader loader = ShaderLoader.none();

    /**
     * Whether {@link #UNIFORM_TIME}, {@link #UNIFORM_RESOLUTION} and
     * {@link #UNIFORM_MOUSE} are set on every pass.
     *
     * <p>On by default: nearly every shader wants at least one of them, and a
     * program that declares none simply has three uniforms nobody asked about,
     * which costs the backend a failed location lookup it should be caching
     * anyway. Turn it off if the client names its uniforms differently and would
     * rather contribute its own through {@link #addGlobalUniforms}.
     */
    @Getter
    @Setter
    private boolean builtinUniforms = true;

    private long startedAt = System.nanoTime();

    public ShaderService(CoreLogger logger, Platform platform) {
        this.logger = Validate.notNull(logger, "logger");
        this.platform = Validate.notNull(platform, "platform");
    }

    @Override
    public String getName() {
        return "Shaders";
    }

    /**
     * Starts the clock behind {@link #UNIFORM_TIME}.
     *
     * <p>Says nothing when no backend is installed, unlike
     * {@link dev.px.core.render.font.FontService}, which warns. A client with no
     * fonts is misconfigured; a client with no shaders is simply a client that
     * does not use them, and this service is meant to be invisible to it.
     */
    @Override
    public void start() {
        startedAt = System.nanoTime();
        if (backend == null) {
            logger.debug("No ShaderBackend installed; shader passes will draw unshaded");
        }
    }

    /**
     * Disposes every compiled program.
     *
     * <p>Called on the shutdown path, which is not necessarily the render thread.
     * A backend whose GL objects must be deleted on the thread that made them
     * should queue the delete inside its own {@link Shader#dispose()} rather than
     * assume where this runs.
     */
    @Override
    public void stop() {
        bound.clear();
        for (Entry entry : entries.values()) {
            entry.invalidate();
        }
        warned.clear();
    }

    // ------------------------------------------------------- registration

    /** Registers a shader. Nothing is read or compiled here. */
    public ShaderSource register(ShaderSource source) {
        return sources.register(source);
    }

    public void registerAll(ShaderSource... shaders) {
        sources.registerAll(shaders);
    }

    /** Unregisters a shader and disposes its program if it had one. */
    public boolean unregister(String name) {
        return find(name).map(sources::unregister).orElse(false);
    }

    public Optional<ShaderSource> find(String name) {
        return sources.find(name);
    }

    // ------------------------------------------------------------ drawing

    /**
     * Draws {@code body} with the named shader bound.
     *
     * <p>The paired bind and unbind are handed the body for the same reason
     * {@link dev.px.core.render.Render#clipped} is: an unbalanced pair corrupts
     * every later draw in the frame, and a body that throws would leave it
     * unbalanced. Here it is impossible.
     *
     * <p>If the shader is unknown, failed, or there is no backend, the body still
     * runs &mdash; unshaded. Use {@link #isReady(String)} to branch instead.
     */
    public void use(String name, Runnable body) {
        use(name, null, body);
    }

    /**
     * Draws {@code body} with the named shader bound and {@code uniforms} applied
     * on top of the globals.
     *
     * @param uniforms filled immediately before upload; may be null
     */
    public void use(String name, UniformProvider uniforms, Runnable body) {
        Validate.notNull(body, "body");
        Entry entry = entryOf(name);
        Shader shader = entry == null ? null : programOf(entry);
        if (shader == null) {
            // Unshaded rather than invisible: a missing effect should cost the
            // effect, not the thing it was decorating.
            body.run();
            return;
        }
        Uniforms values = entry.borrow();
        try {
            applyGlobals(values);
            if (uniforms != null) {
                uniforms.contribute(values);
            }
            bind(shader, values);
            try {
                body.run();
            } finally {
                unbind();
            }
        } finally {
            entry.release(values);
        }
    }

    /**
     * Binds the named shader and uploads {@code uniforms}, for a client that owns
     * its own draw loop and cannot express it as a body.
     *
     * <p>Unlike {@link #use}, this applies exactly what it is given: globals are
     * not added, so call {@link #applyGlobals} first if they are wanted. Every
     * successful call must be matched by {@link #unbind()}.
     *
     * @return whether anything was bound. False means there is nothing to unbind
     */
    public boolean bind(String name, Uniforms uniforms) {
        Entry entry = entryOf(name);
        Shader shader = entry == null ? null : programOf(entry);
        if (shader == null) {
            return false;
        }
        bind(shader, uniforms);
        return true;
    }

    /** Uploads more uniforms to the currently bound shader. A no-op if none is. */
    public void upload(Uniforms uniforms) {
        if (backend != null && uniforms != null && !bound.isEmpty()) {
            uniforms.forEach(backend);
        }
    }

    /**
     * Ends the innermost pass, restoring the shader that was bound around it or
     * the game's own state when there was none.
     */
    public void unbind() {
        if (bound.isEmpty()) {
            return;
        }
        bound.pop();
        if (backend == null) {
            return;
        }
        Shader outer = bound.peek();
        if (outer != null && outer.isValid()) {
            backend.bind(outer);
        } else {
            backend.unbind();
        }
    }

    /** @return the shader currently bound, or null. */
    public Shader getBound() {
        return bound.peek();
    }

    /**
     * Fills in the values every pass gets: the builtins, then each registered
     * global provider, in registration order.
     */
    public void applyGlobals(Uniforms uniforms) {
        Validate.notNull(uniforms, "uniforms");
        if (builtinUniforms) {
            uniforms.set(UNIFORM_TIME, getTime())
                    .set(UNIFORM_RESOLUTION, platform.getScreenWidth(), platform.getScreenHeight())
                    .set(UNIFORM_MOUSE, platform.getMouseX(), platform.getMouseY());
        }
        for (int i = 0; i < globalUniforms.size(); i++) {
            globalUniforms.get(i).contribute(uniforms);
        }
    }

    /** @return seconds since startup, wrapped at {@link #TIME_WRAP_SECONDS}. */
    public float getTime() {
        double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0d;
        return (float) (seconds % TIME_WRAP_SECONDS);
    }

    // ------------------------------------------------------------ programs

    /**
     * @return the compiled program, building it if this is the first ask, or null
     *         if it is unknown, failed, or there is no backend
     */
    public Shader get(String name) {
        Entry entry = entryOf(name);
        return entry == null ? null : programOf(entry);
    }

    /** @return whether {@link #use} would actually shade. Compiles if it has not yet. */
    public boolean isReady(String name) {
        return get(name) != null;
    }

    /** @return whether this shader has been tried and failed. Cleared by {@link #reload()}. */
    public boolean isFailed(String name) {
        Entry entry = name == null ? null : entries.get(key(name));
        return entry != null && entry.failed;
    }

    /**
     * Throws away every compiled program, so each one is read and built again the
     * next time it is drawn &mdash; including the ones that failed.
     *
     * <p>Two uses. Editing GLSL with the game running, when the loader points at
     * a folder: save, reload, see it. And a resource reload on versions where
     * that destroys the GL context underneath every program Core is holding.
     */
    public void reload() {
        for (Entry entry : entries.values()) {
            entry.invalidate();
        }
        bound.clear();
        warned.clear();
        logger.info("Reloading " + entries.size() + (entries.size() == 1 ? " shader" : " shaders"));
    }

    /** Reloads one shader. @return whether it is registered. */
    public boolean reload(String name) {
        Entry entry = name == null ? null : entries.get(key(name));
        if (entry == null) {
            return false;
        }
        entry.invalidate();
        return true;
    }

    // ------------------------------------------------------------- globals

    /**
     * Adds values every pass receives, on top of the builtins.
     *
     * <p>Where a client's own conventions go: a projection matrix, a palette, the
     * partial-tick value, a gamma setting.
     */
    public void addGlobalUniforms(UniformProvider provider) {
        globalUniforms.add(Validate.notNull(provider, "provider"));
    }

    public boolean removeGlobalUniforms(UniformProvider provider) {
        return globalUniforms.remove(provider);
    }

    public List<UniformProvider> getGlobalUniforms() {
        return Collections.unmodifiableList(globalUniforms);
    }

    // ----------------------------------------------------------- internals

    private void bind(Shader shader, Uniforms uniforms) {
        backend.bind(shader);
        bound.push(shader);
        if (uniforms != null) {
            uniforms.forEach(backend);
        }
    }

    private Entry entryOf(String name) {
        if (name == null) {
            return null;
        }
        Entry entry = entries.get(key(name));
        if (entry == null && warned.add(key(name))) {
            logger.warn("No shader registered under '" + name + "'; that pass will draw unshaded");
        }
        return entry;
    }

    /** @return the entry's program, compiling or recompiling as needed. */
    private Shader programOf(Entry entry) {
        Shader existing = entry.shader;
        if (existing != null) {
            if (existing.isValid()) {
                return existing;
            }
            // The handle outlived its context, which a resource reload can do on
            // some versions. Build it again rather than binding a dead program.
            entry.shader = null;
        }
        if (entry.failed || backend == null) {
            return null;
        }
        return compile(entry);
    }

    private Shader compile(Entry entry) {
        ShaderSource source = entry.source;
        Map<ShaderStage, String> glsl = new EnumMap<>(ShaderStage.class);
        try {
            for (ShaderStage stage : source.getStages()) {
                String text = source.getText(stage);
                String path = source.getPath(stage);
                if (text == null) {
                    text = loader.read(path);
                }
                glsl.put(stage, ShaderPreprocessor.process(text, source.getDefines(), loader, path));
            }
            Shader shader = backend.compile(source, glsl);
            if (shader == null || !shader.isValid()) {
                throw new ShaderException("the backend returned "
                        + (shader == null ? "null" : "an invalid handle"));
            }
            entry.shader = shader;
            logger.debug("Compiled shader " + source.getName() + " " + source.getStages());
            return shader;
        } catch (Exception failure) {
            entry.failed = true;
            logger.error("Shader '" + source.getName() + "' failed to compile", failure);
            reportUnsupportedStages(source);
            // Debug rather than error: the listing is long, and it is only worth
            // reading once somebody has decided to look into the line above.
            for (Map.Entry<ShaderStage, String> stage : glsl.entrySet()) {
                logger.debug(source.getName() + " " + stage.getKey().getDisplayName() + " source:\n"
                        + ShaderPreprocessor.numbered(stage.getValue()));
            }
            return null;
        }
    }

    /** Names the likely cause when a stage the backend cannot do was declared. */
    private void reportUnsupportedStages(ShaderSource source) {
        for (ShaderStage stage : source.getStages()) {
            if (!backend.supports(stage)) {
                logger.error("  '" + source.getName() + "' declares a " + stage.getDisplayName()
                        + " stage, which the installed backend reports it cannot compile");
            }
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** A registered shader plus whatever has been built from it. */
    private static final class Entry {

        private final ShaderSource source;

        /**
         * Refilled and reused every frame, so a shader drawn continuously stops
         * allocating uniform storage after the first pass.
         */
        private final Uniforms scratch = new Uniforms();

        private Shader shader;

        /** Set once a compile has been attempted and failed. Cleared by a reload. */
        private boolean failed;

        private boolean busy;

        private Entry(ShaderSource source) {
            this.source = source;
        }

        /**
         * @return the scratch uniforms, or a fresh set when they are already in use.
         *
         * <p>Only a shader drawn inside its own body can hit the second case, which
         * is rare and legal, so it allocates rather than quietly corrupting the
         * outer pass's values.
         */
        private Uniforms borrow() {
            if (busy) {
                return new Uniforms();
            }
            busy = true;
            scratch.clear();
            return scratch;
        }

        private void release(Uniforms uniforms) {
            if (uniforms == scratch) {
                busy = false;
            }
        }

        private void invalidate() {
            if (shader != null) {
                shader.dispose();
                shader = null;
            }
            failed = false;
        }
    }
}
