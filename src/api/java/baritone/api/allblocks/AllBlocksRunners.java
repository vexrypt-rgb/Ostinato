package baritone.api.allblocks;

/** Ostinato: where a client mod (TenorClef) registers its {@link IAllBlocksRunner}. At most one. */
public final class AllBlocksRunners {

    private static volatile IAllBlocksRunner runner;

    private AllBlocksRunners() {}

    public static void register(IAllBlocksRunner value) {
        runner = value;
    }

    /** Null when nothing is registered. */
    public static IAllBlocksRunner get() {
        return runner;
    }
}
