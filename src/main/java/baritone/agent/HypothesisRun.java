package baritone.agent;

// Ostinato: one A/B queue line. The world name has to be a race leftover so cleanup can delete it.

public final class HypothesisRun {

    public final int index;
    public final String hypothesis;
    public final String variant;
    public final String world;
    public final String seed;
    public final boolean flat;
    public final String prompt;
    /** Zero means the orchestrator default. */
    public final long timeoutMs;

    public HypothesisRun(int index, String hypothesis, String variant, String world, String seed, boolean flat,
            String prompt, long timeoutMs) {
        this.index = index;
        this.hypothesis = hypothesis;
        this.variant = variant;
        this.world = world;
        this.seed = seed == null ? "" : seed;
        this.flat = flat;
        this.prompt = prompt == null ? "" : prompt;
        this.timeoutMs = timeoutMs;
    }

    public String runId() {
        return hypothesis + "-" + variant + "-" + world;
    }

    public long timeoutOr(long fallback) {
        return timeoutMs > 0 ? timeoutMs : fallback;
    }
}
