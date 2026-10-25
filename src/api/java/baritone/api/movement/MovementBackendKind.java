package baritone.api.movement;

/**
 * Which travel layer a driver prefers. The block route is still Baritone's search.
 * {@link #AUTO} is kinematic, then physics, then the sim probe, then {@link #BARITONE} as the temporary backup.
 * {@link PathResult#getBackend()} stays {@link #BARITONE}: that value is the route engine, not the key layer.
 */
public enum MovementBackendKind {
    BARITONE,
    KINEMATIC,
    PHYSICS,
    SIM,
    AUTO
}
