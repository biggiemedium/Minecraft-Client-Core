package dev.px.core.target;

import dev.px.core.entity.Tracked;
import dev.px.core.util.Validate;

/**
 * Holds one target across ticks, so a module does not flick between two players
 * who trade places at the top of the ranking every other tick.
 *
 * <pre>{@code
 * private final TargetLock<EntityPlayer> lock = Core.targets().lock(enemies);
 *
 * @Subscribe
 * private void onTick(TickEvent event) {
 *     Tracked<EntityPlayer> target = lock.update();
 *     if (target == null) return;
 *     if (lock.hasChanged()) resetAim();
 *     aimAt(target);
 * }
 *
 * @Override protected void onDisable() { lock.release(); }
 * }</pre>
 *
 * <p><b>Sticky</b> by default: the held target is kept for as long as it still
 * passes the selector, which is an O(1) check, and the search runs only once it
 * stops. Turn stickiness off and every {@link #update()} takes whichever is best
 * that tick.
 *
 * <p>Because a {@link Tracked} is one object per entity for as long as its
 * tracker keeps it, holding one across ticks is safe: it keeps moving with the
 * entity, and {@link Tracked#isTracked()} turns false when the tracker lets go.
 *
 * <p>Game thread only.
 *
 * @param <E> the game's type for what the selector's tracker holds
 */
public final class TargetLock<E> {

    private final TargetService targets;
    private final TargetSelector<E> selector;

    private boolean sticky = true;
    private Tracked<E> current;
    private boolean changed;

    TargetLock(TargetService targets, TargetSelector<E> selector) {
        this.targets = Validate.notNull(targets, "targets");
        this.selector = Validate.notNull(selector, "selector");
    }

    /**
     * Keeps the target if it still qualifies (when sticky), otherwise picks the
     * best one now. Call once a tick.
     *
     * @return the target, or null when nothing qualifies
     */
    public Tracked<E> update() {
        Tracked<E> previous = current;
        if (!sticky || !targets.accepts(selector, previous)) {
            current = targets.best(selector);
        }
        changed = current != previous;
        return current;
    }

    /** @return the target as of the last {@link #update()}, without checking it again */
    public Tracked<E> get() {
        return current;
    }

    public boolean hasTarget() {
        return current != null;
    }

    /** @return whether the last {@link #update()} switched to a different target, or to none */
    public boolean hasChanged() {
        return changed;
    }

    /** Drops the target; the next {@link #update()} searches afresh. */
    public void release() {
        changed = current != null;
        current = null;
    }

    /**
     * Holds {@code target} whether or not it is the best, for as long as it keeps
     * passing the selector. For a module that lets the player pick.
     *
     * @return whether it passes now, and so was taken
     */
    public boolean lockOn(Tracked<E> target) {
        if (!targets.accepts(selector, target)) {
            return false;
        }
        changed = current != target;
        current = target;
        return true;
    }

    public boolean isSticky() {
        return sticky;
    }

    public TargetLock<E> setSticky(boolean sticky) {
        this.sticky = sticky;
        return this;
    }

    public TargetSelector<E> getSelector() {
        return selector;
    }
}
