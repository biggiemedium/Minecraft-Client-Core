package dev.px.combat.monitor;

import dev.px.core.event.Event;

/**
 * The {@link DamageMonitor} changed its mind: predictions it trusted have drifted
 * from what happens in game, or ones it distrusted are accurate again.
 *
 * <p>Posted on whatever thread drives the monitor's ticks: the game thread, when
 * it is driven by Core's tick.
 *
 * <pre>{@code
 * @Subscribe
 * private void onDrift(DamageDriftEvent event) {
 *     if (!event.isReliable()) {
 *         Core.notifications().warn("Crystal damage", "Model is off: check " + event.getReport().getSuspect());
 *     }
 * }
 * }</pre>
 */
public final class DamageDriftEvent extends Event {

    private final DamageReport report;

    public DamageDriftEvent(DamageReport report) {
        this.report = report;
    }

    public DamageReport getReport() {
        return report;
    }

    public boolean isReliable() {
        return report.isReliable();
    }
}
