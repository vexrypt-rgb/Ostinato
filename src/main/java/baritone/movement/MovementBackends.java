package baritone.movement;

import baritone.api.IBaritone;
import baritone.api.movement.IMovementEngine;
import baritone.api.movement.MovementBackendKind;

/**
 * Entry points TenorClef reaches by reflection ({@code engine}, {@code preference}). Travel always runs on Ostinato's
 * own pathing; mining / digging / builder processes keep their own.
 * {@link #preference()} stays {@link MovementBackendKind#BARITONE}. The path executor reads {@code travelStack} itself.
 */
public final class MovementBackends {

    private MovementBackends() {}

    public static MovementBackendKind preference() {
        // Ostinato: TenorClef asserts this stays BARITONE. Key order is travelStack, not this method.
        return MovementBackendKind.BARITONE;
    }

    /** TenorClef-facing travel engine bound to a Baritone instance. */
    public static IMovementEngine engine(IBaritone baritone) {
        return HybridMovementEngine.forBaritone(baritone);
    }
}
