package dev.px.core.test.harness;

import dev.px.core.render.Texture;
import dev.px.core.shader.Shader;
import dev.px.core.shader.ShaderBackend;
import dev.px.core.shader.ShaderException;
import dev.px.core.shader.ShaderSource;
import dev.px.core.shader.ShaderStage;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A shader backend with no OpenGL behind it.
 *
 * <p>Everything {@link dev.px.core.shader.ShaderService} promises is about what
 * reaches the backend and when: that a shader is assembled correctly, compiled
 * once rather than per frame, bound and unbound in pairs even when a draw
 * throws, and given the uniforms it was promised. None of that needs a context,
 * and asserting it needs something on the other end of the seam.
 *
 * <p>The suite runs with this installed on a service of its own, never on the
 * booted client's, so the rest of the suite still runs with no backend at all.
 */
public final class RecordingShaderBackend implements ShaderBackend {

    /** How many times each shader has been compiled, keyed by name. */
    private final Map<String, Integer> compiles = new LinkedHashMap<>();

    /** The final GLSL each shader was last compiled from. */
    private final Map<String, Map<ShaderStage, String>> assembled = new LinkedHashMap<>();

    /** Binds and unbinds in order: {@code bind:glow}, {@code unbind}. */
    @Getter
    private final List<String> events = new ArrayList<>();

    /** The last value uploaded under each uniform name, formatted for comparison. */
    private final Map<String, String> uniforms = new LinkedHashMap<>();

    /** Handed out so a test can invalidate one the way a lost context would. */
    private final Map<String, FakeShader> programs = new LinkedHashMap<>();

    private final Set<String> failing = new HashSet<>();

    /** Whether {@link #supports} claims the geometry stage. */
    @Setter
    private boolean geometryCapable;

    /** Makes the named shader throw on its next compile, as a GLSL error would. */
    public void failOn(String name) {
        failing.add(name);
    }

    public void succeedOn(String name) {
        failing.remove(name);
    }

    public int compileCount(String name) {
        Integer count = compiles.get(name);
        return count == null ? 0 : count;
    }

    public String glslOf(String name, ShaderStage stage) {
        Map<ShaderStage, String> stages = assembled.get(name);
        return stages == null ? null : stages.get(stage);
    }

    public FakeShader programOf(String name) {
        return programs.get(name);
    }

    /** @return the last value uploaded under {@code name}, or null. */
    public String uniform(String name) {
        return uniforms.get(name);
    }

    public boolean hasUniform(String name) {
        return uniforms.containsKey(name);
    }

    /** @return the shader bound right now, or null. */
    public String boundName() {
        for (int i = events.size() - 1; i >= 0; i--) {
            String event = events.get(i);
            if (event.equals("unbind")) {
                return null;
            }
            if (event.startsWith("bind:")) {
                return event.substring("bind:".length());
            }
        }
        return null;
    }

    public void clearEvents() {
        events.clear();
        uniforms.clear();
    }

    // -------------------------------------------------------------- backend

    @Override
    public Shader compile(ShaderSource source, Map<ShaderStage, String> glsl) {
        String name = source.getName();
        compiles.put(name, compileCount(name) + 1);
        assembled.put(name, new EnumMap<>(glsl));
        if (failing.contains(name)) {
            throw new ShaderException("0(1) : error C0000: pretend syntax error");
        }
        FakeShader program = new FakeShader(name);
        programs.put(name, program);
        return program;
    }

    @Override
    public void bind(Shader shader) {
        events.add("bind:" + shader.getName());
    }

    @Override
    public void unbind() {
        events.add("unbind");
    }

    @Override
    public boolean supports(ShaderStage stage) {
        if (stage == ShaderStage.GEOMETRY) {
            return geometryCapable;
        }
        return stage == ShaderStage.VERTEX || stage == ShaderStage.FRAGMENT;
    }

    // ---------------------------------------------------------- uniform sink

    @Override
    public void floats(String name, float[] values, int count) {
        uniforms.put(name, join(values, count));
    }

    @Override
    public void ints(String name, int[] values, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(i == 0 ? "" : ",").append(values[i]);
        }
        uniforms.put(name, text.toString());
    }

    @Override
    public void matrix(String name, float[] values, int order) {
        uniforms.put(name, "mat" + order + "(" + join(values, order * order) + ")");
    }

    @Override
    public void sampler(String name, Texture texture, int unit) {
        uniforms.put(name, texture + "@" + unit);
    }

    private static String join(float[] values, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(i == 0 ? "" : ",").append(values[i]);
        }
        return text.toString();
    }

    /** A program handle that is nothing but a name and a validity flag. */
    @Getter
    public static final class FakeShader implements Shader {

        private final String name;

        private boolean valid = true;

        private FakeShader(String name) {
            this.name = name;
        }

        /** Simulates the context going away underneath a live handle. */
        public void loseContext() {
            valid = false;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void dispose() {
            valid = false;
        }
    }
}
