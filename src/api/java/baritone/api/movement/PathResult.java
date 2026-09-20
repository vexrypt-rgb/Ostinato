package baritone.api.movement;

/**
 * Outcome of a travel request against {@link IMovementEngine}.
 */
public final class PathResult {

    private final boolean accepted;
    private final MovementStatus status;
    private final MovementFailureReason failure;
    private final MovementBackendKind backend;
    private final String message;

    private PathResult(boolean accepted, MovementStatus status, MovementFailureReason failure,
                       MovementBackendKind backend, String message) {
        this.accepted = accepted;
        this.status = status;
        this.failure = failure != null ? failure : MovementFailureReason.NONE;
        this.backend = backend;
        this.message = message != null ? message : "";
    }

    public static PathResult accepted(MovementBackendKind backend, MovementStatus status) {
        return new PathResult(true, status, MovementFailureReason.NONE, backend, "");
    }

    public static PathResult accepted(MovementBackendKind backend, MovementStatus status, String message) {
        return new PathResult(true, status, MovementFailureReason.NONE, backend, message);
    }

    public static PathResult failed(MovementFailureReason reason, MovementBackendKind backend, String message) {
        return new PathResult(false, MovementStatus.FAILED, reason, backend, message);
    }

    public static PathResult cancelled(MovementBackendKind backend) {
        return new PathResult(false, MovementStatus.CANCELLED, MovementFailureReason.INTERRUPTED, backend, "cancelled");
    }

    public boolean isAccepted() {
        return accepted;
    }

    public MovementStatus getStatus() {
        return status;
    }

    public MovementFailureReason getFailure() {
        return failure;
    }

    public MovementBackendKind getBackend() {
        return backend;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "PathResult{accepted=" + accepted
                + ", status=" + status
                + ", failure=" + failure
                + ", backend=" + backend
                + ", message='" + message + "'}";
    }
}
