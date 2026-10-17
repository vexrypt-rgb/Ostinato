package baritone.api.movement;

/**
 * Which backend accepted a travel request. Ostinato's own pathing (block A* with the kinematic and physics travel
 * drivers) is the only one; the enum stays so {@link PathResult#getBackend()} keeps its type for agent layers.
 */
public enum MovementBackendKind {
    BARITONE
}
