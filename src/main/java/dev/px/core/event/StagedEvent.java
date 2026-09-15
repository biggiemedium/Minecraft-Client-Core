package dev.px.core.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Base for cancellable events posted both before and after their action.
 *
 * <p>Handlers narrow to one side with {@code @Subscribe(stage = Stage.PRE)};
 * an unfiltered handler receives both, so branch on {@link #getStage()} only
 * when a single method genuinely handles both sides.
 */
@Getter
@RequiredArgsConstructor
public abstract class StagedEvent extends CancellableEvent {

    private final Stage stage;
}
