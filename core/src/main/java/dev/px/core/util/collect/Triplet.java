package dev.px.core.util.collect;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * An immutable three-value tuple, the {@link Pair} that needed one more slot.
 *
 * <p>Same caveat as {@link Pair}, and it applies harder here: three anonymous
 * values is usually a type that has not been named yet. Reach for this when the
 * grouping is local and short-lived &mdash; a block position, its material and
 * its distance, on the way to a sort &mdash; not when it is about to be stored
 * or passed across a package boundary.
 *
 * <p><b>Complexity.</b> Every operation is O(1).
 * or passed across a package boundary.
 *
 * @param <A> the first value's type
 * @param <B> the second value's type
 * @param <C> the third value's type
 */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
public final class Triplet<A, B, C> {

    private final A first;
    private final B second;
    private final C third;

    public static <A, B, C> Triplet<A, B, C> of(A first, B second, C third) {
        return new Triplet<>(first, second, third);
    }

    public Triplet<A, B, C> withFirst(A newFirst) {
        return new Triplet<>(newFirst, second, third);
    }

    public Triplet<A, B, C> withSecond(B newSecond) {
        return new Triplet<>(first, newSecond, third);
    }

    public Triplet<A, B, C> withThird(C newThird) {
        return new Triplet<>(first, second, newThird);
    }

    /** @return the first two values, dropping the third. */
    public Pair<A, B> toPair() {
        return Pair.of(first, second);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ", " + third + ")";
    }
}
