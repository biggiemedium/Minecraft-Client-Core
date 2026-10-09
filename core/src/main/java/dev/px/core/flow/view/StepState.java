package dev.px.core.flow.view;

/** Where one step stands, as the live view shows it. */
public enum StepState {

    /** Not started yet, or not this time round. */
    IDLE,

    /** Being ticked. */
    RUNNING,

    /** Finished, and it worked. */
    DONE,

    /** Finished, and it did not; the view says why. */
    FAILED,

    /** Stopped for something more urgent, to be started again. */
    PAUSED,

    /** Stopped because it was no longer wanted. */
    CANCELLED
}
