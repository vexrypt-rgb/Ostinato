package baritone.pathing.calc;

/**
 * Counts from one A* run. Block lookups already sit behind the chunk cache; this records how the
 * search spent its expansions. {@code System.nanoTime} is not sampled per node: on Windows that call
 * itself is the hotspot the search already avoids.
 */
public final class SearchProfile {

    public int nodes;
    public int movements;
    public int rejected;
    public int emptyChunks;
    public int favored;

    public String line() {
        return "search nodes=" + nodes
                + " moves=" + movements
                + " rejected=" + rejected
                + " emptyChunks=" + emptyChunks
                + " favored=" + favored;
    }
}
