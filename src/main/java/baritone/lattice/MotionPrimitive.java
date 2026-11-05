/*
 * Ostinato: one lattice motion primitive. Not part of upstream Baritone.
 */
package baritone.lattice;

import baritone.contract.ControlTick;
import baritone.contract.PlayerState;

import java.util.List;

/**
 * An open-loop move simulated through the existing physics adapter.
 * Cost is the tick count. {@code robustnessMargin} is the slack on {@code robustnessRule}.
 */
public record MotionPrimitive(String name, String kind, String preconditions, List<ControlTick> controls,
                              List<PlayerState> predicted, int ticks, String robustnessRule,
                              double robustnessMargin, boolean reachedGoal) {
    public MotionPrimitive {
        controls = List.copyOf(controls);
        predicted = List.copyOf(predicted);
        if (controls.size() != predicted.size() || controls.size() != ticks) {
            throw new IllegalArgumentException("primitive ticks do not match the recorded controls");
        }
    }

    /** Travel cost in ticks. */
    public double cost() {
        return ticks;
    }
}
