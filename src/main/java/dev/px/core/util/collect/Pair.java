package dev.px.core.util.collect;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An immutable two-value tuple.
 *
 * <p>The escape hatch for the case that otherwise breeds throwaway classes: a
 * method that must return two things, or a list of associated values that is not
 * a map because keys repeat &mdash; a rotation and the tick it was sent on, a
 * name and its measured width.
 *
 * <p>Immutable on purpose. A mutable pair used as a map key or held in a cached
 * list is a bug waiting to happen, which is why {@link #withFirst} returns a new
 * instance rather than assigning.
 *
 * <p>If the two values have a meaning worth naming, name them: write the small
 * class instead. This is for the cases where {@code first} and {@code second}
 * really are the clearest labels available.
 *
 * <p><b>Complexity.</b> Every operation is O(1).
 * really are the clearest labels available.
 *
 * @param <A> the first value's type
 * @param <B> the second value's type
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class Pair<A, B> {

    private final A first;
    private final B second;

    public static <A, B> Pair<A, B> of(A first, B second) {
        return new Pair<>(first, second);
    }

    public Pair<A, B> withFirst(A newFirst) {
        return new Pair<>(newFirst, second);
    }

    public Pair<A, B> withSecond(B newSecond) {
        return new Pair<>(first, newSecond);
    }

    /** @return the same values the other way round. */
    public Pair<B, A> swap() {
        return new Pair<>(second, first);
    }

    public <R> Pair<R, B> mapFirst(Function<? super A, ? extends R> mapper) {
        return new Pair<>(mapper.apply(first), second);
    }

    public <R> Pair<A, R> mapSecond(Function<? super B, ? extends R> mapper) {
        return new Pair<>(first, mapper.apply(second));
    }

    /** Collapses both values into one, for the common {@code pair -> label} case. */
    public <R> R map(BiFunction<? super A, ? super B, ? extends R> mapper) {
        return mapper.apply(first, second);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ")";
    }
}
