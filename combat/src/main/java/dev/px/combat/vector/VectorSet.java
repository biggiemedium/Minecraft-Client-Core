package dev.px.combat.vector;

import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named collection of {@link TestVector}s: usually one game version on one
 * server's settings, saved as one file.
 *
 * <pre>{@code
 * VectorSet set = recorder.toSet("1.21.1 hard", Collections.singletonMap("server", "example.net"));
 * VectorJson.write(set, writer);
 * }</pre>
 *
 * <p>The label and metadata are yours: what version, which server, what
 * difficulty. Core reads neither. Immutable.
 */
public final class VectorSet {

    private final String label;
    private final Map<String, String> metadata;
    private final List<TestVector> vectors;

    private VectorSet(String label, Map<String, String> metadata, List<TestVector> vectors) {
        this.label = label;
        this.metadata = metadata;
        this.vectors = vectors;
    }

    public static VectorSet of(String label, Map<String, String> metadata, List<TestVector> vectors) {
        Validate.notNull(label, "label");
        Validate.notNull(metadata, "metadata");
        Validate.notNull(vectors, "vectors");
        return new VectorSet(label, Collections.unmodifiableMap(new LinkedHashMap<>(metadata)),
                Collections.unmodifiableList(new ArrayList<>(vectors)));
    }

    public static VectorSet of(String label, List<TestVector> vectors) {
        return of(label, Collections.<String, String>emptyMap(), vectors);
    }

    public String getLabel() {
        return label;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public List<TestVector> getVectors() {
        return vectors;
    }

    public int size() {
        return vectors.size();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VectorSet)) {
            return false;
        }
        VectorSet that = (VectorSet) other;
        return label.equals(that.label) && metadata.equals(that.metadata) && vectors.equals(that.vectors);
    }

    @Override
    public int hashCode() {
        return label.hashCode() * 31 + vectors.hashCode();
    }

    @Override
    public String toString() {
        return "VectorSet(" + label + ", " + vectors.size() + " vectors)";
    }
}
