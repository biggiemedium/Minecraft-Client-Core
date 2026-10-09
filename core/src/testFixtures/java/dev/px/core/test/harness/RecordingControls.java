package dev.px.core.test.harness;

import dev.px.core.control.Click;
import dev.px.core.control.ClickSink;
import dev.px.core.control.MovementSink;
import dev.px.core.movement.simulation.MovementInput;

import java.util.ArrayList;
import java.util.List;

/**
 * A movement sink and a click sink with no player behind them.
 *
 * <p>Writes down every set of keys applied and every click, hold and release, in
 * order, so whatever {@link dev.px.core.control.ControlService} promises &mdash;
 * who wins, that a hold is always let go &mdash; and whatever drives it, such as
 * a navigator, can be checked with no game running.
 */
public final class RecordingControls implements MovementSink, ClickSink {

    /** Every set of keys applied, in order. */
    public final List<MovementInput> moves = new ArrayList<>();

    /** Every click, hold and release, in order: {@code "click ATTACK"}, {@code "hold USE"}, {@code "release USE"}. */
    public final List<String> clicks = new ArrayList<>();

    @Override
    public void apply(MovementInput input) {
        moves.add(input);
    }

    @Override
    public void click(Click button) {
        clicks.add("click " + button);
    }

    @Override
    public void setHeld(Click button, boolean held) {
        clicks.add((held ? "hold " : "release ") + button);
    }

    /** @return the last keys applied, or null */
    public MovementInput lastMove() {
        return moves.isEmpty() ? null : moves.get(moves.size() - 1);
    }

    public void clear() {
        moves.clear();
        clicks.clear();
    }
}
