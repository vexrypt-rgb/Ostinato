/*
 * Ostinato: one tick of keys for a headless comparison. Not part of upstream Baritone.
 */
package baritone.contract;

/** Keys held for a single tick. Absence of a flag means that key is up. */
public record ControlTick(float yaw, boolean forward, boolean back, boolean left, boolean right,
                          boolean sprint, boolean jump, boolean sneak) {

    public static ControlTick sprintForward(float yaw) {
        return new ControlTick(yaw, true, false, false, false, true, false, false);
    }
}
