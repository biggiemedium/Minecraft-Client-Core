package dev.px.combat.explosion.state;

import dev.px.core.util.Validate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a target was like at one moment, as named values you choose: the
 * numbers your mitigation needs, read off the game's object once.
 *
 * <pre>{@code
 * TargetState state = TargetState.builder()
 *         .put("armor", 20)
 *         .put("toughness", 12)
 *         .put("blastProtection", 16)
 *         .put("resistance", 0)
 *         .put("blocking", false)
 *         .build();
 * }</pre>
 *
 * <p>The names mean nothing to Core. They exist so a mitigation written against
 * them works on a live target and on a recorded one alike, which is what lets a
 * version profile be tested without the game.
 *
 * <p>Values are numbers, flags or text. Immutable; equal when the values are.
 */
public final class TargetState {

    private static final TargetState EMPTY = new TargetState(Collections.<String, Object>emptyMap());

    private final Map<String, Object> values;

    private TargetState(Map<String, Object> values) {
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TargetState empty() {
        return EMPTY;
    }

    /** @return the number under {@code key}, or NaN when there is none */
    public double get(String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    /** @return the number under {@code key}, or {@code fallback} when there is none */
    public double get(String key, double fallback) {
        double value = get(key);
        return Double.isNaN(value) ? fallback : value;
    }

    /** @return the flag under {@code key}; false when there is none */
    public boolean flag(String key) {
        return Boolean.TRUE.equals(values.get(key));
    }

    /** @return the text under {@code key}, or null */
    public String text(String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** @return every value, in the order they were put */
    public Map<String, Object> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TargetState && values.equals(((TargetState) other).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "TargetState" + values;
    }

    public static final class Builder {

        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder put(String key, double value) {
            values.put(Validate.notBlank(key, "key"), value);
            return this;
        }

        public Builder put(String key, boolean value) {
            values.put(Validate.notBlank(key, "key"), value);
            return this;
        }

        public Builder put(String key, String value) {
            values.put(Validate.notBlank(key, "key"), Validate.notNull(value, "value"));
            return this;
        }

        /** Puts a value of any of the three kinds; anything else is refused. */
        public Builder putAny(String key, Object value) {
            if (value instanceof Number) {
                return put(key, ((Number) value).doubleValue());
            }
            if (value instanceof Boolean) {
                return put(key, (Boolean) value);
            }
            if (value instanceof String) {
                return put(key, (String) value);
            }
            throw new IllegalArgumentException("a TargetState holds numbers, flags and text, not " + value);
        }

        public TargetState build() {
            return values.isEmpty() ? EMPTY : new TargetState(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
    }
}
