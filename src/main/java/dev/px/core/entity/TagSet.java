package dev.px.core.entity;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A fixed group of {@link EntityTag}s, resolved to bits once so testing an
 * entity against all of them is one AND per 64 tags declared.
 *
 * <p>Selectors build these for you from {@code withAny}, {@code withAll} and
 * {@code without}. Build one yourself for a hot loop of your own:
 *
 * <pre>{@code
 * private static final TagSet IGNORED = TagSet.of(Tags.DEAD, Tags.INVISIBLE);
 * ...
 * if (IGNORED.matchesAny(entity)) continue;
 * }</pre>
 *
 * <p>Immutable.
 */
public final class TagSet {

    private static final TagSet EMPTY = new TagSet(new long[0], Collections.<EntityTag>emptySet());

    private final long[] words;
    private final Set<EntityTag> tags;

    private TagSet(long[] words, Set<EntityTag> tags) {
        this.words = words;
        this.tags = tags;
    }

    public static TagSet of(EntityTag... tags) {
        return of(Arrays.asList(tags));
    }

    public static TagSet of(Collection<? extends EntityTag> tags) {
        if (tags.isEmpty()) {
            return EMPTY;
        }
        long[] words = new long[0];
        for (EntityTag tag : tags) {
            int slot = Slots.of(tag);
            int word = slot >>> 6;
            if (word >= words.length) {
                words = Arrays.copyOf(words, word + 1);
            }
            words[word] |= 1L << slot;
        }
        return new TagSet(words, Collections.unmodifiableSet(new LinkedHashSet<EntityTag>(tags)));
    }

    public static TagSet empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return words.length == 0;
    }

    public Set<EntityTag> getTags() {
        return tags;
    }

    /** @return whether the entity has at least one of these tags; false for an empty set */
    public boolean matchesAny(TrackedEntity entity) {
        long[] has = entity.tagWords;
        int shared = Math.min(words.length, has.length);
        for (int i = 0; i < shared; i++) {
            if ((words[i] & has[i]) != 0L) {
                return true;
            }
        }
        return false;
    }

    /** @return whether the entity has every one of these tags; true for an empty set */
    public boolean matchesAll(TrackedEntity entity) {
        long[] has = entity.tagWords;
        for (int i = 0; i < words.length; i++) {
            long present = i < has.length ? has[i] : 0L;
            if ((words[i] & present) != words[i]) {
                return false;
            }
        }
        return true;
    }

    /** @return whether the entity has none of these tags; true for an empty set */
    public boolean matchesNone(TrackedEntity entity) {
        return !matchesAny(entity);
    }

    @Override
    public String toString() {
        return "TagSet" + tags;
    }
}
