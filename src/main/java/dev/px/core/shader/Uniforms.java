package dev.px.core.shader;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.render.Color;
import dev.px.core.render.Texture;
import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The values to hand a shader, recorded without touching GL.
 *
 * <p>A shader's inputs are described here and executed by the backend through
 * {@link UniformSink}, the same split the rest of the library uses: Core says
 * what, the backend says how. Nothing in this class knows a uniform location
 * exists.
 *
 * <pre>{@code
 * uniforms.set("uTime", seconds)
 *         .set("uResolution", width, height)
 *         .set("uColour", theme.getPrimary())      // vec4, already 0..1
 *         .sampler("uScene", sceneTexture, 0);
 * }</pre>
 *
 * <h2>Why it is mutable and reused</h2>
 *
 * <p>Uniforms are rewritten every frame, so a fresh immutable value per frame
 * would allocate a map, a boxed float and an array per uniform, sixty times a
 * second, for the lifetime of the client. Instead an instance is kept and
 * refilled: {@link #clear()} marks everything stale without releasing anything,
 * and setting the same name again writes into the slot that already exists. A
 * steady-state frame allocates nothing.
 *
 * <p>{@link ShaderService#use} keeps one of these per registered shader and
 * hands it to the caller already cleared, so most clients never construct one.
 *
 * <p>Not thread safe, and not meant to be: drawing happens on the render thread.
 */
public final class Uniforms {

    private final Map<String, Entry> byName = new HashMap<>();

    /** The slot pool, in first-set order, which is the order the sink sees. */
    private final List<Entry> ordered = new ArrayList<>();

    /**
     * Bumped by {@link #clear()}. An entry belongs to the current fill only when
     * its stamp matches, which is what lets a slot be forgotten and later reused
     * without removing it from the pool.
     */
    private long generation = 1L;

    private int live;

    // ------------------------------------------------------------- floats

    public Uniforms set(String name, float value) {
        floats(name, 1)[0] = value;
        return this;
    }

    public Uniforms set(String name, float x, float y) {
        float[] slot = floats(name, 2);
        slot[0] = x;
        slot[1] = y;
        return this;
    }

    public Uniforms set(String name, float x, float y, float z) {
        float[] slot = floats(name, 3);
        slot[0] = x;
        slot[1] = y;
        slot[2] = z;
        return this;
    }

    public Uniforms set(String name, float x, float y, float z, float w) {
        float[] slot = floats(name, 4);
        slot[0] = x;
        slot[1] = y;
        slot[2] = z;
        slot[3] = w;
        return this;
    }

    /** A {@code vec2}, from the same type the maths layer uses for screen points. */
    public Uniforms set(String name, Vec2 value) {
        Validate.notNull(value, "value");
        return set(name, (float) value.getX(), (float) value.getY());
    }

    /** A {@code vec3}. */
    public Uniforms set(String name, Vec3 value) {
        Validate.notNull(value, "value");
        return set(name, (float) value.getX(), (float) value.getY(), (float) value.getZ());
    }

    /**
     * A {@code vec4} of red, green, blue and alpha in 0..1.
     *
     * <p>Normalised on the way in because that is what GLSL wants and forgetting
     * the divide is the single most common way a shader comes out white.
     */
    public Uniforms set(String name, Color value) {
        Validate.notNull(value, "value");
        return set(name, value.redF(), value.greenF(), value.blueF(), value.alphaF());
    }

    /** A {@code vec3} of red, green and blue in 0..1, dropping the alpha. */
    public Uniforms rgb(String name, Color value) {
        Validate.notNull(value, "value");
        return set(name, value.redF(), value.greenF(), value.blueF());
    }

    // --------------------------------------------------------------- ints

    public Uniforms set(String name, int value) {
        ints(name, 1)[0] = value;
        return this;
    }

    public Uniforms set(String name, int x, int y) {
        int[] slot = ints(name, 2);
        slot[0] = x;
        slot[1] = y;
        return this;
    }

    /** A {@code bool}, recorded as 0 or 1 because GLSL has no boolean uniform upload. */
    public Uniforms set(String name, boolean value) {
        return set(name, value ? 1 : 0);
    }

    // ------------------------------------------------------------ matrices

    /**
     * A square matrix, column-major.
     *
     * @param values 4, 9 or 16 floats, for {@code mat2}, {@code mat3} or {@code mat4}
     */
    public Uniforms matrix(String name, float[] values) {
        Validate.notNull(values, "matrix values");
        int order = (int) Math.round(Math.sqrt(values.length));
        Validate.check(order >= 2 && order <= 4 && order * order == values.length,
                "matrix " + name + " must have 4, 9 or 16 values, got " + values.length);
        Entry entry = entry(name);
        entry.kind = Kind.MATRIX;
        entry.count = order;
        if (entry.floats == null || entry.floats.length < values.length) {
            entry.floats = new float[values.length];
        }
        // Copied rather than kept: the caller usually owns a scratch array of its
        // own and will have rewritten it by the time the sink runs.
        System.arraycopy(values, 0, entry.floats, 0, values.length);
        return this;
    }

    // ------------------------------------------------------------ samplers

    /**
     * Binds {@code texture} to a texture unit and points {@code name} at it.
     *
     * <p>Units are the caller's to allocate; a pass that samples two textures
     * uses 0 and 1. Passing a null or disposed texture records nothing, so a
     * frame drawn before an image finished loading degrades to an unsampled
     * shader rather than a GL error.
     */
    public Uniforms sampler(String name, Texture texture, int unit) {
        Validate.check(unit >= 0, "texture unit must not be negative, got " + unit);
        if (texture == null || !texture.isValid()) {
            return this;
        }
        Entry entry = entry(name);
        entry.kind = Kind.SAMPLER;
        entry.count = unit;
        entry.texture = texture;
        return this;
    }

    // ---------------------------------------------------------------- bulk

    /** Copies every live value of {@code other} over this one. */
    public Uniforms setAll(Uniforms other) {
        Validate.notNull(other, "other");
        for (int i = 0; i < other.ordered.size(); i++) {
            Entry source = other.ordered.get(i);
            if (source.stamp == other.generation) {
                source.copyInto(this);
            }
        }
        return this;
    }

    /** Hands every value to {@code sink}, in the order it was first set. */
    public void forEach(UniformSink sink) {
        Validate.notNull(sink, "sink");
        for (int i = 0; i < ordered.size(); i++) {
            Entry entry = ordered.get(i);
            if (entry.stamp == generation) {
                entry.kind.emit(entry, sink);
            }
        }
    }

    /**
     * Forgets every value.
     *
     * <p>Keeps the slots for the next fill, so a shader drawn every frame stops
     * allocating after the first one.
     */
    public void clear() {
        generation++;
        live = 0;
    }

    /** Forgets one value. */
    public Uniforms remove(String name) {
        Entry entry = byName.get(name);
        if (entry != null && entry.stamp == generation) {
            entry.stamp = 0L;
            entry.texture = null;
            live--;
        }
        return this;
    }

    public boolean has(String name) {
        Entry entry = byName.get(name);
        return entry != null && entry.stamp == generation;
    }

    /** @return how many values are set. */
    public int size() {
        return live;
    }

    public boolean isEmpty() {
        return live == 0;
    }

    // ------------------------------------------------------------ internals

    private float[] floats(String name, int count) {
        Entry entry = entry(name);
        entry.kind = Kind.FLOATS;
        entry.count = count;
        if (entry.floats == null || entry.floats.length < count) {
            entry.floats = new float[count];
        }
        return entry.floats;
    }

    private int[] ints(String name, int count) {
        Entry entry = entry(name);
        entry.kind = Kind.INTS;
        entry.count = count;
        if (entry.ints == null || entry.ints.length < count) {
            entry.ints = new int[count];
        }
        return entry.ints;
    }

    private Entry entry(String name) {
        Validate.notBlank(name, "uniform name");
        Entry existing = byName.get(name);
        if (existing == null) {
            existing = new Entry(name);
            byName.put(name, existing);
            ordered.add(existing);
        }
        if (existing.stamp != generation) {
            existing.stamp = generation;
            live++;
        }
        existing.texture = null;
        return existing;
    }

    /** One slot. Reused across frames, which is why nothing here is final but the name. */
    private static final class Entry {

        private final String name;

        private Kind kind = Kind.FLOATS;

        /**
         * Components for {@link Kind#FLOATS} and {@link Kind#INTS}, matrix order
         * for {@link Kind#MATRIX}, texture unit for {@link Kind#SAMPLER}.
         */
        private int count;

        private float[] floats;
        private int[] ints;
        private Texture texture;

        private long stamp;

        private Entry(String name) {
            this.name = name;
        }

        private void copyInto(Uniforms target) {
            kind.copy(this, target);
        }
    }

    /**
     * What a slot holds.
     *
     * <p>Behaviour lives on the constant rather than in a {@code switch} at the
     * two places that need it, so adding a kind cannot leave one of them behind.
     */
    private enum Kind {

        FLOATS {
            @Override
            void emit(Entry entry, UniformSink sink) {
                sink.floats(entry.name, entry.floats, entry.count);
            }

            @Override
            void copy(Entry entry, Uniforms target) {
                float[] slot = target.floats(entry.name, entry.count);
                System.arraycopy(entry.floats, 0, slot, 0, entry.count);
            }
        },

        INTS {
            @Override
            void emit(Entry entry, UniformSink sink) {
                sink.ints(entry.name, entry.ints, entry.count);
            }

            @Override
            void copy(Entry entry, Uniforms target) {
                int[] slot = target.ints(entry.name, entry.count);
                System.arraycopy(entry.ints, 0, slot, 0, entry.count);
            }
        },

        MATRIX {
            @Override
            void emit(Entry entry, UniformSink sink) {
                sink.matrix(entry.name, entry.floats, entry.count);
            }

            @Override
            void copy(Entry entry, Uniforms target) {
                int length = entry.count * entry.count;
                float[] values = new float[length];
                System.arraycopy(entry.floats, 0, values, 0, length);
                target.matrix(entry.name, values);
            }
        },

        SAMPLER {
            @Override
            void emit(Entry entry, UniformSink sink) {
                sink.sampler(entry.name, entry.texture, entry.count);
            }

            @Override
            void copy(Entry entry, Uniforms target) {
                target.sampler(entry.name, entry.texture, entry.count);
            }
        };

        abstract void emit(Entry entry, UniformSink sink);

        abstract void copy(Entry entry, Uniforms target);
    }
}
