/*
 * Ostinato: one predicted run. Not part of upstream Baritone.
 */
package baritone.contract;

/**
 * A run under one physics semantics.
 * {@code robustness} is optional and is null when this harness did not compute a margin.
 */
public record Trajectory(String initialRequirements, PlayerState initial, InputSequence controls,
                         java.util.List<PlayerState> predicted, String validity, int durationTicks, double cost,
                         Double robustness) {
    public Trajectory {
        predicted = java.util.List.copyOf(predicted);
    }
}
