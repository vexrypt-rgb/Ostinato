/*
 * Ostinato: hook for a later shared world-knowledge service. Not part of upstream Baritone.
 */
package baritone.api.legit;

/**
 * Phase 2 may share a block only through this predicate. Nothing is stored here.
 * <p>
 * When legit mode is effectively on, a block may be shared only if {@link SeenOnly#knowable} was true for it.
 * When legit mode is off, this predicate does not remove the block. The service itself is not built here.
 */
public final class SeenOnlyShare {

    private SeenOnlyShare() {}

    public static boolean mayShare(boolean legitActive, boolean knowable) {
        return !legitActive || knowable;
    }
}
