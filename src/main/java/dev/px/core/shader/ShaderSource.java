package dev.px.core.shader;

import dev.px.core.registry.Named;
import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Where a shader's GLSL lives and what it should be compiled with.
 *
 * <p>A description, not a program: registering one costs no IO and no GL, which
 * is what lets a client declare every shader it owns at startup, long before a
 * context exists, and lets {@link ShaderService} compile each one the first time
 * something actually draws with it.
 *
 * <pre>{@code
 * shaders.register(ShaderSource.named("glow")
 *         .vertex("glow.vsh")
 *         .fragment("glow.fsh")
 *         .define("SAMPLES", 12)
 *         .define("HIGH_QUALITY")
 *         .build());
 * }</pre>
 *
 * <p>A stage is either a path the {@link ShaderLoader} reads or literal text
 * given inline. Paths are the reloadable kind &mdash;
 * {@link ShaderService#reload()} re-reads them, so a shader can be edited with
 * the game running &mdash; while inline source is fixed at the point it was
 * registered. {@link #isReloadable()} reports which.
 *
 * <p>Immutable once built, so the same instance can be registered, logged and
 * handed to a backend without anyone being able to change it underneath.
 */
public final class ShaderSource implements Named {

    private final String name;
    private final Map<ShaderStage, Stage> stages;
    private final Map<String, String> defines;

    private ShaderSource(Builder builder) {
        this.name = builder.name;
        this.stages = Collections.unmodifiableMap(new EnumMap<>(builder.stages));
        this.defines = Collections.unmodifiableMap(new LinkedHashMap<>(builder.defines));
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    @Override
    public String getName() {
        return name;
    }

    /** The stages this shader declares, in enum order. */
    public Set<ShaderStage> getStages() {
        return stages.keySet();
    }

    public boolean has(ShaderStage stage) {
        return stages.containsKey(stage);
    }

    /** @return the path this stage is read from, or null when it was given inline. */
    public String getPath(ShaderStage stage) {
        Stage entry = stages.get(stage);
        return entry == null ? null : entry.path;
    }

    /** @return this stage's inline source, or null when it is read from a path. */
    public String getText(ShaderStage stage) {
        Stage entry = stages.get(stage);
        return entry == null ? null : entry.text;
    }

    /** Compile-time constants, in the order they were declared. */
    public Map<String, String> getDefines() {
        return defines;
    }

    /**
     * @return whether any stage is read from a path, and so picks up edits on
     *         {@link ShaderService#reload()}.
     */
    public boolean isReloadable() {
        for (Stage stage : stages.values()) {
            if (stage.path != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("ShaderSource(").append(name);
        for (Map.Entry<ShaderStage, Stage> entry : stages.entrySet()) {
            text.append(", ").append(entry.getKey().getDisplayName()).append('=');
            text.append(entry.getValue().path != null ? entry.getValue().path : "<inline>");
        }
        if (!defines.isEmpty()) {
            text.append(", defines=").append(defines);
        }
        return text.append(')').toString();
    }

    /** One stage: exactly one of {@code path} and {@code text} is set. */
    private static final class Stage {

        private final String path;
        private final String text;

        private Stage(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }

    /**
     * Assembles a {@link ShaderSource}.
     *
     * <p>Setting the same stage twice replaces it, so a client can start from a
     * shared builder and swap one stage out.
     */
    public static final class Builder {

        private final String name;
        private final Map<ShaderStage, Stage> stages = new EnumMap<>(ShaderStage.class);
        private final Map<String, String> defines = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = Validate.notBlank(name, "shader name");
        }

        /** The vertex stage, read from {@code path} by the service's loader. */
        public Builder vertex(String path) {
            return stage(ShaderStage.VERTEX, path);
        }

        /** The vertex stage, given inline. */
        public Builder vertexSource(String glsl) {
            return stageSource(ShaderStage.VERTEX, glsl);
        }

        /** The fragment stage, read from {@code path} by the service's loader. */
        public Builder fragment(String path) {
            return stage(ShaderStage.FRAGMENT, path);
        }

        /** The fragment stage, given inline. */
        public Builder fragmentSource(String glsl) {
            return stageSource(ShaderStage.FRAGMENT, glsl);
        }

        public Builder stage(ShaderStage stage, String path) {
            Validate.notNull(stage, "stage");
            stages.put(stage, new Stage(Validate.notBlank(path, stage.getDisplayName() + " path"), null));
            return this;
        }

        public Builder stageSource(ShaderStage stage, String glsl) {
            Validate.notNull(stage, "stage");
            stages.put(stage, new Stage(null, Validate.notNull(glsl, stage.getDisplayName() + " source")));
            return this;
        }

        /** A {@code #define NAME VALUE} injected below the version directive. */
        public Builder define(String key, String value) {
            defines.put(Validate.notBlank(key, "define name"), value);
            return this;
        }

        public Builder define(String key, int value) {
            return define(key, String.valueOf(value));
        }

        /** Floats are written with a decimal point, which GLSL requires of a float literal. */
        public Builder define(String key, float value) {
            String text = String.valueOf(value);
            return define(key, text.indexOf('.') < 0 && text.indexOf('e') < 0 ? text + ".0" : text);
        }

        /**
         * A bare {@code #define NAME}, for a symbol tested with {@code #ifdef}.
         *
         * <p>A valueless define is not the same as {@code define(name, false)}:
         * {@code #ifdef} asks whether the symbol exists, and one defined as 0
         * still exists.
         */
        public Builder define(String key) {
            return define(key, "");
        }

        public ShaderSource build() {
            Validate.check(!stages.isEmpty(), "Shader " + name + " declares no stages");
            return new ShaderSource(this);
        }
    }
}
