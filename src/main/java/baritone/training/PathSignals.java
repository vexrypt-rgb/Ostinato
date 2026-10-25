package baritone.training;

/** Ostinato: pathfinding features for a run. Written to their own file, never into the task record. */
public final class PathSignals {

    public final String runId;
    public final String terrain;
    public final String jumpMajority;
    public final int riseBlocks;
    public final int gapBlocks;

    public PathSignals(String runId, String terrain, String jumpMajority, int riseBlocks, int gapBlocks) {
        this.runId = runId;
        this.terrain = terrain;
        this.jumpMajority = jumpMajority;
        this.riseBlocks = riseBlocks;
        this.gapBlocks = gapBlocks;
    }
}
