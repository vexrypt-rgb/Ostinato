/*
 * Ostinato: shared player state for a headless comparison. Not part of upstream Baritone.
 */
package baritone.contract;

/** One predicted or required pose. It is not a full {@code PlayerSim} (no jump cooldown, no sprint latch). */
public record PlayerState(double x, double y, double z, double vx, double vy, double vz, boolean onGround, float yaw) {
}
