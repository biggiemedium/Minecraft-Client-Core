package dev.px.gui.widget;

import dev.px.core.input.Key;
import dev.px.core.input.Modifier;
import dev.px.core.input.MouseButton;
import dev.px.core.layout.Bounds;
import dev.px.core.render.Render;
import dev.px.core.render.font.Font;
import dev.px.core.util.Validate;
import dev.px.gui.Screen;
import dev.px.gui.Widget;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One line of text to type into.
 *
 * <pre>{@code
 * TextField name = panel.add(new TextField("").placeholder("Name").maxLength(16)
 *         .onSubmit(text -> rename(text)));
 *
 * look.register(WidgetRenderer.of(TextField.class, (field, c) -> {
 *     c.align(Align.STRETCH).background(field.isFocused() ? FOCUSED : BASE, 3f).padding(4f);
 *     c.column(text -> text.name("text").min(0f, Render.textHeight()).backdrop((x, y, w, h) -> {
 *         Render.pushClip(x, y, w, h);
 *         if (field.hasSelection()) {
 *             float from = x + field.offsetOf(field.getSelectionStart());
 *             Render.rect(from, y, x + field.offsetOf(field.getSelectionEnd()) - from, h, SELECTION);
 *         }
 *         String shown = field.getText().isEmpty() ? field.getPlaceholder() : field.getText();
 *         Render.text(shown, x - field.getScrollX(), y, field.getText().isEmpty() ? MUTED : TEXT);
 *         if (field.isFocused()) {
 *             float caret = x + field.offsetOf(field.getCaret());
 *             Render.rect(caret, y, 1f, h, TEXT);
 *         }
 *         Render.popClip();
 *     }));
 * }));
 * }</pre>
 *
 * <p>The field holds the text, the caret and the selection; the renderer draws
 * them, in the part it names {@code "text"}. A press puts the caret where it
 * lands, and dragging selects. Characters typed while it has focus are inserted,
 * and it takes the usual editing keys itself:
 *
 * <table>
 *   <caption>Keys</caption>
 *   <tr><td>Left, Right</td><td>move the caret; with Shift, select; with Ctrl or Alt, a word at a time</td></tr>
 *   <tr><td>Home, End</td><td>to the start or end; with Shift, select</td></tr>
 *   <tr><td>Backspace, Delete</td><td>delete the selection or a character; with Ctrl or Alt, a word</td></tr>
 *   <tr><td>Ctrl or Cmd with A, C, X, V</td><td>select all, copy, cut, paste</td></tr>
 *   <tr><td>Enter</td><td>tells the {@link #onSubmit} listeners</td></tr>
 * </table>
 *
 * <p>Every one of those is a public method too, so a subclass that wants other
 * keys overrides {@link #keyPressed} and calls them. Copying and pasting go
 * through the screen's {@linkplain Screen#clipboard clipboard}.
 *
 * <p>Text is measured with {@link #font}, or Core's default font without one;
 * the renderer should draw with the same, so the caret sits where the text is.
 *
 * <p>Game thread only.
 */
public class TextField extends Widget {

    private String text;

    /** Where typing goes in, between characters: 0 is before the first. */
    @Getter
    private int caret;

    /** The other end of the selection; equal to the caret when nothing is selected. */
    @Getter
    private int anchor;

    /** Shown by the renderer while the field is empty. */
    @Getter
    private String placeholder = "";

    @Getter
    private int maxLength = Integer.MAX_VALUE;

    /** The font text is measured with; null for Core's default. */
    @Getter
    private Font font;

    private Predicate<Character> filter = character -> true;

    /** How far the text is scrolled left to keep the caret in view. */
    private float scrollX;

    private final List<Consumer<String>> changeListeners = new ArrayList<>(1);
    private final List<Consumer<String>> submitListeners = new ArrayList<>(1);

    public TextField(String text) {
        this.text = text == null ? "" : text;
        this.caret = this.text.length();
        this.anchor = caret;
    }

    // -------------------------------------------------------------- settings

    public TextField placeholder(String value) {
        this.placeholder = value == null ? "" : value;
        return this;
    }

    /** Caps how many characters it holds; typing and pasting past it are cut short. */
    public TextField maxLength(int length) {
        Validate.check(length >= 0, "a field's maximum length cannot be negative");
        this.maxLength = length;
        if (text.length() > length) {
            setText(text.substring(0, length));
        }
        return this;
    }

    /** Lets in only the characters {@code allowed} accepts, typed or pasted. */
    public TextField filter(Predicate<Character> allowed) {
        this.filter = Validate.notNull(allowed, "allowed");
        return this;
    }

    /** Measures text with this font, which the renderer should draw with too. Null for Core's default. */
    public TextField font(Font measuring) {
        this.font = measuring;
        return this;
    }

    /** Runs {@code listener} with the new text each time it changes. */
    public TextField onChange(Consumer<String> listener) {
        changeListeners.add(Validate.notNull(listener, "listener"));
        return this;
    }

    /** Runs {@code listener} with the text when Enter is pressed in the field. */
    public TextField onSubmit(Consumer<String> listener) {
        submitListeners.add(Validate.notNull(listener, "listener"));
        return this;
    }

    // ------------------------------------------------------------------ text

    public String getText() {
        return text;
    }

    /** Replaces the text, with the caret at its end, telling the listeners if it changed. */
    public void setText(String value) {
        String next = value == null ? "" : value;
        if (next.length() > maxLength) {
            next = next.substring(0, maxLength);
        }
        caret = next.length();
        anchor = caret;
        change(next);
    }

    public boolean hasSelection() {
        return caret != anchor;
    }

    public int getSelectionStart() {
        return Math.min(caret, anchor);
    }

    public int getSelectionEnd() {
        return Math.max(caret, anchor);
    }

    /** @return the selected text, empty for none. */
    public String getSelection() {
        return text.substring(getSelectionStart(), getSelectionEnd());
    }

    // --------------------------------------------------------------- editing

    /** Types {@code typed} at the caret, over the selection, keeping only what the filter allows and fits. */
    public void insert(String typed) {
        StringBuilder allowed = new StringBuilder();
        for (char character : (typed == null ? "" : typed).toCharArray()) {
            if (character >= ' ' && character != 127 && filter.test(character)) {
                allowed.append(character);
            }
        }
        int start = getSelectionStart();
        int end = getSelectionEnd();
        int room = maxLength - (text.length() - (end - start));
        String fitted = allowed.length() > room ? allowed.substring(0, Math.max(0, room)) : allowed.toString();
        if (fitted.isEmpty() && start == end) {
            return;
        }
        String next = text.substring(0, start) + fitted + text.substring(end);
        caret = start + fitted.length();
        anchor = caret;
        change(next);
    }

    /** Deletes the selection, or the character (or word) before the caret. */
    public void deleteBackward(boolean word) {
        if (!hasSelection()) {
            anchor = word ? wordBoundary(caret, -1) : Math.max(0, caret - 1);
        }
        insert("");
    }

    /** Deletes the selection, or the character (or word) after the caret. */
    public void deleteForward(boolean word) {
        if (!hasSelection()) {
            anchor = word ? wordBoundary(caret, 1) : Math.min(text.length(), caret + 1);
        }
        insert("");
    }

    /** Moves the caret one character (or word) left or right, selecting as it goes if asked. */
    public void moveCaret(int direction, boolean word, boolean select) {
        int target;
        if (!select && hasSelection() && !word) {
            target = direction < 0 ? getSelectionStart() : getSelectionEnd();
        } else {
            target = word ? wordBoundary(caret, direction) : caret + Integer.signum(direction);
        }
        moveCaretTo(target, select);
    }

    /** Puts the caret at an index, selecting from where it was if asked. */
    public void moveCaretTo(int index, boolean select) {
        caret = Math.max(0, Math.min(text.length(), index));
        if (!select) {
            anchor = caret;
        }
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
    }

    /** Copies the selection to the screen's clipboard. */
    public void copy() {
        Screen screen = getScreen();
        if (screen != null && hasSelection()) {
            screen.setClipboard(getSelection());
        }
    }

    /** Copies the selection and deletes it. */
    public void cut() {
        if (hasSelection()) {
            copy();
            insert("");
        }
    }

    /** Types the screen's clipboard at the caret. */
    public void paste() {
        Screen screen = getScreen();
        if (screen != null) {
            insert(screen.getClipboard());
        }
    }

    /** Tells the submit listeners, as Enter does. */
    public void submit() {
        for (Consumer<String> listener : new ArrayList<>(submitListeners)) {
            listener.accept(text);
        }
    }

    // -------------------------------------------------------------- geometry

    /**
     * @return how far from the left of the {@code "text"} part the gap before a
     *         character index is drawn, scroll included
     *
     * <p>For the renderer: where to draw the caret and the ends of the selection.
     */
    public float offsetOf(int index) {
        return widthOf(text.substring(0, Math.max(0, Math.min(text.length(), index)))) - getScrollX();
    }

    /**
     * @return how far the text is scrolled left, so the caret stays in the
     *         {@code "text"} part
     *
     * <p>The renderer draws the text this far left of the part's edge.
     */
    public float getScrollX() {
        float width = textArea().getWidth();
        float caretAt = widthOf(text.substring(0, caret));
        if (caretAt - scrollX > width) {
            scrollX = caretAt - width;
        } else if (caretAt < scrollX) {
            scrollX = caretAt;
        }
        scrollX = Math.max(0f, Math.min(scrollX, Math.max(0f, widthOf(text) - width)));
        return scrollX;
    }

    /** @return the character index nearest a point across the field, as it was last laid out. */
    public int indexAt(float x) {
        float local = x - textArea().getX() + getScrollX();
        int best = 0;
        float bestDistance = Math.abs(local);
        for (int i = 1; i <= text.length(); i++) {
            float distance = Math.abs(widthOf(text.substring(0, i)) - local);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean isFocusable() {
        return true;
    }

    @Override
    protected boolean mousePressed(float x, float y, MouseButton button) {
        if (button != MouseButton.LEFT) {
            return false;
        }
        moveCaretTo(indexAt(x), false);
        capture();
        return true;
    }

    /** Dragging selects from where the press landed. */
    @Override
    protected void mouseDragged(float x, float y) {
        moveCaretTo(indexAt(x), true);
    }

    @Override
    protected boolean charTyped(char character) {
        if (character < ' ' || character == 127) {
            return false;
        }
        insert(String.valueOf(character));
        return true;
    }

    @Override
    protected boolean keyPressed(Key key, Set<Modifier> modifiers) {
        boolean shift = modifiers.contains(Modifier.SHIFT);
        boolean shortcut = modifiers.contains(Modifier.CTRL) || modifiers.contains(Modifier.SUPER);
        boolean word = modifiers.contains(Modifier.CTRL) || modifiers.contains(Modifier.ALT);
        switch (key) {
            case LEFT: moveCaret(-1, word, shift); return true;
            case RIGHT: moveCaret(1, word, shift); return true;
            case HOME: moveCaretTo(0, shift); return true;
            case END: moveCaretTo(text.length(), shift); return true;
            case BACKSPACE: deleteBackward(word); return true;
            case DELETE: deleteForward(word); return true;
            case ENTER:
            case NUMPAD_ENTER: submit(); return true;
            case A: if (shortcut) { selectAll(); return true; } return false;
            case C: if (shortcut) { copy(); return true; } return false;
            case X: if (shortcut) { cut(); return true; } return false;
            case V: if (shortcut) { paste(); return true; } return false;
            default: return false;
        }
    }

    /** Losing focus drops the selection, so a field left behind does not look selected. */
    @Override
    protected void focusLost() {
        anchor = caret;
    }

    // ------------------------------------------------------------- internals

    private void change(String next) {
        if (next.equals(text)) {
            return;
        }
        text = next;
        for (Consumer<String> listener : new ArrayList<>(changeListeners)) {
            listener.accept(next);
        }
    }

    /** The next word boundary from an index in a direction: past any spaces, then past the word. */
    private int wordBoundary(int from, int direction) {
        int step = direction < 0 ? -1 : 1;
        int at = from;
        if (step < 0) {
            while (at > 0 && Character.isWhitespace(text.charAt(at - 1))) {
                at--;
            }
            while (at > 0 && !Character.isWhitespace(text.charAt(at - 1))) {
                at--;
            }
        } else {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            while (at < text.length() && !Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
        return at;
    }

    private Bounds textArea() {
        Bounds area = part("text");
        return area != null ? area : getBounds();
    }

    private float widthOf(String value) {
        return font == null ? Render.textWidth(value) : font.widthOf(value);
    }

    // --------------------------------------------------------------- chaining

    @Override
    public TextField visibleWhen(BooleanSupplier condition) {
        super.visibleWhen(condition);
        return this;
    }

    @Override
    public TextField enabledWhen(BooleanSupplier condition) {
        super.enabledWhen(condition);
        return this;
    }

    @Override
    public TextField grow(float weight) {
        super.grow(weight);
        return this;
    }

    @Override
    public TextField tooltip(String text) {
        super.tooltip(text);
        return this;
    }

    @Override
    public TextField tooltip(Supplier<String> text) {
        super.tooltip(text);
        return this;
    }
}
