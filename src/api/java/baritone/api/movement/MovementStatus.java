package baritone.api.movement;

/**
 * High-level travel status for {@link IMovementEngine}.
 * Mining / builder processes do not report through this enum.
 */
public enum MovementStatus {
    IDLE,
    REQUESTED,
    PATHING,
    ARRIVED,
    FAILED,
    CANCELLED
}
