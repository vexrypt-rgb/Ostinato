/*
 * Ostinato: an ordered list of control ticks. Not part of upstream Baritone.
 */
package baritone.contract;

import java.util.List;

/** Controls in tick order. A PPE held input is the same tick repeated for the horizon. */
public record InputSequence(List<ControlTick> ticks) {
    public InputSequence {
        ticks = List.copyOf(ticks);
    }
}
