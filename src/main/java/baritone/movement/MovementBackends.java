package baritone.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.movement.IMovementEngine;
import baritone.api.movement.MovementBackendKind;
import baritone.pathing.travel.TravelStack;

/**
 * Entry points TenorClef reaches by reflection ({@code engine}, {@code preference}). Travel always runs on Ostinato's
 * own pathing; mining / digging / builder processes keep their own.
 * {@link #preference()} is the {@code travelStack} setting: auto, kinematic, physics, sim, or baritone.
 */
public final class MovementBackends {

    private MovementBackends() {}

    public static MovementBackendKind preference() {
        return TravelStack.preference(Baritone.settings().travelStack.value);
    }

    /** TenorClef-facing travel engine bound to a Baritone instance. */
    public static IMovementEngine engine(IBaritone baritone) {
        return HybridMovementEngine.forBaritone(baritone);
    }
}
