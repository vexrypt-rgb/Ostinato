package baritone.agent;

// Ostinato: decide how a race world is opened. No Minecraft classes, so tests can pin each case.

/**
 * Live versus stubbed world create. A missing world is not built through the Create World screen.
 */
public final class RaceWorldPlans {

    public enum Kind {
        /** Folder already exists. The client may call openWorld. */
        OPEN_EXISTING,
        /** No folder. New-world UI is not automated. */
        STUB_NEW,
        /** That race world is already loaded. */
        ALREADY_OPEN,
        /** Some other world is loaded. Leave it before opening another. */
        BUSY,
        /** Name is not a race leftover, so it is never created or deleted. */
        REJECT
    }

    private RaceWorldPlans() {}

    public static Kind plan(boolean raceName, boolean folderExists, boolean worldOpen, boolean sameWorld) {
        if (!raceName) {
            return Kind.REJECT;
        }
        if (worldOpen && sameWorld) {
            return Kind.ALREADY_OPEN;
        }
        if (worldOpen) {
            return Kind.BUSY;
        }
        if (folderExists) {
            return Kind.OPEN_EXISTING;
        }
        return Kind.STUB_NEW;
    }
}
