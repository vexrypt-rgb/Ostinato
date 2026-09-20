package baritone.movement;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.movement.IMovementBackend;
import baritone.api.movement.IMovementEngine;
import baritone.api.movement.MovementBackendKind;
import baritone.api.utils.Helper;

/**
 * Resolves the active travel movement backend from {@link baritone.api.Settings#movementBackend}.
 * Mining / digging / builder processes always keep classic Baritone pathing.
 * <p>
 * Prefer {@link #engine(IBaritone)} / {@link HybridMovementEngine} for TenorClef travel.
 */
public final class MovementBackends implements Helper {

    private MovementBackends() {}

    public static MovementBackendKind preference() {
        return MovementBackendKind.fromString(BaritoneAPI.getSettings().movementBackend.value);
    }

    public static IMovementBackend current() {
        MovementBackendKind kind = preference();
        return switch (kind) {
            case BARITONE -> BaritoneMovementBackend.INSTANCE;
            case TUNGSTEN, AUTO -> {
                if (TungstenMovementBackend.INSTANCE.isAvailable()) {
                    yield TungstenMovementBackend.INSTANCE;
                }
                yield BaritoneMovementBackend.INSTANCE;
            }
        };
    }

    /** True when CustomGoal travel should prefer Tungsten over Baritone pathing. */
    public static boolean preferTungstenTravel() {
        MovementBackendKind kind = preference();
        return switch (kind) {
            case BARITONE -> false;
            case TUNGSTEN, AUTO -> TungstenMovementBackend.INSTANCE.isAvailable();
        };
    }

    /**
     * TenorClef-facing hybrid engine bound to a Baritone instance.
     * Soft-fails Tungsten; never throws when Tungsten is absent.
     */
    public static IMovementEngine engine(IBaritone baritone) {
        return HybridMovementEngine.forBaritone(baritone);
    }

    public static String statusLine() {
        MovementBackendKind kind = preference();
        boolean tung = TungstenMovementBackend.INSTANCE.isAvailable();
        return "movementBackend=" + kind
                + " tungsten=" + (tung ? "AVAILABLE (" + TungstenMovementBackend.INSTANCE.detail() + ")"
                : "MISSING (" + TungstenMovementBackend.INSTANCE.detail() + ")");
    }
}
