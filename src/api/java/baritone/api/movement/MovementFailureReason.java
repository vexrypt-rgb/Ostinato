package baritone.api.movement;

/**
 * Structured travel failure reasons for {@link PathResult}.
 * Prefer these over bare null/false when reporting engine outcomes.
 */
public enum MovementFailureReason {
    NONE,
    BACKEND_UNAVAILABLE,
    DECLINED,
    PATH_CALC_FAILED,
    INTERRUPTED,
    UNSUPPORTED_GOAL,
    EXCEPTION
}
