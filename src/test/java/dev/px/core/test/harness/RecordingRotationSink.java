package dev.px.core.test.harness;

import dev.px.core.math.Vec2;
import dev.px.core.movement.rotation.RotationMode;
import dev.px.core.movement.rotation.RotationSink;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A rotation sink with no player behind it.
 *
 * <p>Stands in for both halves of the seam: it reports a rotation the test can
 * move around, and it writes down what it was asked to apply. Everything
 * {@link dev.px.core.movement.rotation.RotationService} promises &mdash; who wins, how far
 * the head turns in a tick, that reads and requests commute, that the rotation
 * eases back rather than snapping &mdash; is observable from here with no game
 * running.
 *
 * <p>{@link #setWriteBack(boolean)} models the difference between the two modes.
 * A real {@link RotationMode#CLIENT} sink moves the camera, so the next
 * {@link #getRotation()} returns what was just applied; a
 * {@link RotationMode#SILENT} one leaves the camera alone, which is what makes
 * the easing phase observable.
 */
public final class RecordingRotationSink implements RotationSink {

    /** Where the "player" is looking. */
    @Setter
    private Vec2 rotation = Vec2.rotation(0f, 0f);

    /** Every rotation applied, in order. */
    @Getter
    private final List<Vec2> applied = new ArrayList<>();

    @Getter
    private RotationMode lastMode;

    /** Whether applying also moves the player, the way a CLIENT-mode sink does. */
    @Setter
    private boolean writeBack;

    @Override
    public Vec2 getRotation() {
        return rotation;
    }

    @Override
    public void apply(Vec2 rotation, RotationMode mode) {
        applied.add(rotation);
        lastMode = mode;
        if (writeBack) {
            this.rotation = rotation;
        }
    }

    public Vec2 lastApplied() {
        return applied.isEmpty() ? null : applied.get(applied.size() - 1);
    }

    public int appliedCount() {
        return applied.size();
    }

    public void clear() {
        applied.clear();
        lastMode = null;
    }
}
