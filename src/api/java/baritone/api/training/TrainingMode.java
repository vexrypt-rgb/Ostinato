package baritone.api.training;

/** Ostinato: which information a training run may use. */
public enum TrainingMode {
    /** Ground truth for pathfinding autotune. Task blocks and entities are listed even when hidden. */
    FULL,
    /** Player-available cues only: render view, F3 and pie, and sounds the client would play. */
    LEGIT
}
