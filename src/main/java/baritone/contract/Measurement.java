/*
 * Ostinato: one row of a comparison. Not part of upstream Baritone.
 */
package baritone.contract;

import java.util.Locale;

/** One navigator's result. {@code trajectory} is null when the row was not executed. */
public record Measurement(String navigator, Equivalence equivalence, String semantics, boolean executed,
                          boolean reached, int durationTicks, double cost, String note, Trajectory trajectory) {

    public String line() {
        String costText = Double.isFinite(cost) ? String.format(Locale.ROOT, "%.1f", cost) : "-";
        return String.format(Locale.ROOT, "%s %s %s executed=%s reached=%s ticks=%d cost=%s %s",
                navigator, equivalence, semantics, executed, reached, durationTicks, costText, note);
    }
}
