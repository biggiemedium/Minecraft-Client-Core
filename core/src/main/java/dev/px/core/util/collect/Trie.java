package dev.px.core.util.collect;

import dev.px.core.util.Validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A prefix tree, for completing what someone has started typing.
 *
 * <p>Completion by scanning is the obvious implementation and it is fine until it
 * is not: every keystroke walks every candidate and calls {@code startsWith} on
 * each. With sixty commands nobody notices. With four hundred modules, their
 * aliases, every online player's name and a completion running per character
 * typed, it is work done on the render thread for no reason.
 *
 * <p>A trie walks the prefix once &mdash; a handful of map lookups, one per
 * character typed &mdash; and then reads off the subtree beneath it. The cost
 * depends on the length of what was typed and how many things match, not on how
 * much is registered.
 *
 * <p>Lookups ignore case, because a user typing {@code killa} means
 * {@code KillAura}; the original spelling is what comes back, because that is
 * what belongs in the chat box. Results come out alphabetically, which is what a
 * completion list should be and what a {@link TreeMap} of children gives for
 * free.
 *
 * <pre>{@code
 * Trie commands = Trie.of();
 * registry.forEach(command -> commands.insert(command.getName()));
 *
 * List<String> suggestions = commands.startingWith("fri");   // [Friend, Friends]
 * String filled = commands.longestCommonPrefix("fri");       // "Friend"
 * }</pre>
 *
 * <p><b>Complexity.</b> With L the length of the word or prefix, n the number of
 * words stored and k the number of matches: {@link #insert}, {@link #contains},
 * {@link #get} and {@link #remove} are O(L); {@link #startingWith(String)} is
 * O(L + k); {@link #longestCommonPrefix} is O(L + d) for a shared run of d
 * characters. None of them depend on n, which is the whole point &mdash; the
 * linear scan they replace is O(n · L).
 *
 * <p>Not thread-safe.
 */
public final class Trie {

    private final Node root = new Node();

    private int size;

    private Trie() {
    }

    public static Trie of() {
        return new Trie();
    }

    /** Builds a trie over {@code words}. */
    public static Trie of(Collection<String> words) {
        Trie trie = new Trie();
        words.forEach(trie::insert);
        return trie;
    }

    // -------------------------------------------------------------- writing

    /**
     * Adds a word.
     *
     * @return whether it was new. Re-inserting replaces the stored spelling, so
     *         the most recent casing is the one suggested
     */
    public boolean insert(String word) {
        Validate.notBlank(word, "word");
        Node node = root;
        String key = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < key.length(); i++) {
            node = node.children.computeIfAbsent(key.charAt(i), character -> new Node());
        }
        boolean isNew = node.word == null;
        node.word = word;
        if (isNew) {
            size++;
        }
        return isNew;
    }

    /**
     * Removes a word.
     *
     * @return whether it was present
     *
     * <p>Unlinks the branch it leaves behind, so a trie that is filled and emptied
     * repeatedly &mdash; a player list across server hops &mdash; does not keep
     * the shape of everything it has ever held.
     */
    public boolean remove(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        boolean removed = remove(root, word.toLowerCase(Locale.ROOT), 0);
        if (removed) {
            size--;
        }
        return removed;
    }

    private boolean remove(Node node, String key, int depth) {
        if (depth == key.length()) {
            if (node.word == null) {
                return false;
            }
            node.word = null;
            return true;
        }
        char character = key.charAt(depth);
        Node child = node.children.get(character);
        if (child == null || !remove(child, key, depth + 1)) {
            return false;
        }
        if (child.word == null && child.children.isEmpty()) {
            node.children.remove(character);
        }
        return true;
    }

    public void clear() {
        root.children.clear();
        root.word = null;
        size = 0;
    }

    // -------------------------------------------------------------- reading

    /** @return whether the exact word is stored, ignoring case. */
    public boolean contains(String word) {
        Node node = find(word);
        return node != null && node.word != null;
    }

    /** @return the stored spelling of {@code word}, or {@code null}. */
    public String get(String word) {
        Node node = find(word);
        return node == null ? null : node.word;
    }

    /**
     * @return every word starting with {@code prefix}, alphabetically
     *
     * <p>An empty prefix returns everything, which is what an empty completion
     * box should offer.
     */
    public List<String> startingWith(String prefix) {
        return startingWith(prefix, Integer.MAX_VALUE);
    }

    /**
     * @param limit the most results to return. A completion list nobody can read
     *              past twenty entries should not cost a thousand
     */
    public List<String> startingWith(String prefix, int limit) {
        List<String> matches = new ArrayList<>();
        Node start = find(prefix == null ? "" : prefix);
        if (start != null) {
            collect(start, matches, limit);
        }
        return matches;
    }

    /**
     * @return the longest string every match shares, or {@code prefix} if the
     *         matches diverge immediately
     *
     * <p>What pressing tab should actually insert. Given {@code Friend} and
     * {@code Friends}, completing {@code fri} to the first match is a guess;
     * completing it to {@code Friend} is not, and it leaves the user one keystroke
     * from either.
     */
    public String longestCommonPrefix(String prefix) {
        String key = prefix == null ? "" : prefix;
        Node node = find(key);
        if (node == null) {
            return key;
        }
        StringBuilder common = new StringBuilder(key);
        Node current = node;
        // Walk down while there is exactly one way to go and nothing ends here.
        while (current.word == null && current.children.size() == 1) {
            Map.Entry<Character, Node> only = current.children.entrySet().iterator().next();
            common.append(only.getKey());
            current = only.getValue();
        }
        // Every word under here shares the prefix, so any of them can supply the
        // spelling. Returning the lowercased key instead would complete "k" to
        // "kill" and put a name in the chat box the server does not know.
        String spelling = anyWord(current);
        return spelling != null && spelling.length() >= common.length()
                ? spelling.substring(0, common.length())
                : common.toString();
    }

    /** @return how many words are stored. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** @return every word, alphabetically. */
    public List<String> words() {
        return startingWith("");
    }

    @Override
    public String toString() {
        return "Trie[" + size + " words]";
    }

    // ------------------------------------------------------------- internals

    private Node find(String prefix) {
        Node node = root;
        String key = prefix.toLowerCase(Locale.ROOT);
        for (int i = 0; i < key.length(); i++) {
            node = node.children.get(key.charAt(i));
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** @return any word stored at or below {@code node}, for its original casing. */
    private static String anyWord(Node node) {
        if (node.word != null) {
            return node.word;
        }
        for (Node child : node.children.values()) {
            String found = anyWord(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void collect(Node node, List<String> into, int limit) {
        if (into.size() >= limit) {
            return;
        }
        if (node.word != null) {
            into.add(node.word);
        }
        for (Node child : node.children.values()) {
            if (into.size() >= limit) {
                return;
            }
            collect(child, into, limit);
        }
    }

    /** A TreeMap of children is what makes results come out sorted. */
    private static final class Node {

        private final Map<Character, Node> children = new TreeMap<>();

        /** The original spelling, non-null exactly when a word ends here. */
        private String word;
    }
}
