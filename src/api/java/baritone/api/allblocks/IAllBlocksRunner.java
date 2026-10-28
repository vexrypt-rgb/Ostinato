package baritone.api.allblocks;

/**
 * Ostinato: what {@code #allblocks} drives. A client mod (TenorClef) gathers one of every obtainable item into labeled
 * chests and places one of every placeable block on a grid; Ostinato only owns the command. Every method is called on
 * the client thread and returns the line to show the player.
 */
public interface IAllBlocksRunner {

    /** Starts or resumes the run. {@code args} is what followed "start" (an origin "x y z", or empty). */
    String start(String args);

    /** Obtained / placed / skipped / remaining counts and the current item. */
    String status();

    /** Stops the run. Progress stays on disk; the next start resumes. */
    String stop();

    /** Unsupported and skipped entries with their reasons, at most {@code max} lines. */
    String report(int max);

    /** Forgets the saved progress for this world (the chests and the grid stay in the world). */
    String reset();

    default String name() {
        return getClass().getSimpleName();
    }
}
