package baritone.api.training;

/**
 * Ostinato: one circuit's score. Bucket tags live here. Pathfinding features (rises, gaps) are a separate file
 * joined only by {@link #runId}.
 */
public final class TrainingResult {

    public final String runId;
    public final String circuitId;
    public final long seed;
    public final String mode;
    public final String modelId;
    public final boolean success;
    public final int totalTicks;
    public final int mineTicks;
    public final int craftTicks;
    public final int placeTicks;
    public final int deaths;
    public final String deathCauses;
    public final int pathFailures;
    public final int blocksBroken;
    public final int blocksPlaced;
    public final int durabilitySpent;
    public final String gearTool;
    public final String gearArmor;
    public final String gearReason;
    public final String bucketKey;
    public final String biome;
    public final double temperature;
    public final String mobPressure;
    public final String inventoryPressure;
    public final String cues;
    public final String actions;
    /** Ostinato: true when gear-choice scoring published this row. False on the harness baseline. */
    public final boolean gearScored;
    /** Ostinato: time + deaths * weight + durability * weight. Zero when {@link #gearScored} is false. */
    public final int gearScore;

    public TrainingResult(String runId, String circuitId, long seed, String mode, String modelId, boolean success,
            int totalTicks, int mineTicks, int craftTicks, int placeTicks, int deaths, String deathCauses,
            int pathFailures, int blocksBroken, int blocksPlaced, int durabilitySpent, String gearTool,
            String gearArmor, String gearReason, String bucketKey, String biome, double temperature,
            String mobPressure, String inventoryPressure, String cues, String actions) {
        this(runId, circuitId, seed, mode, modelId, success, totalTicks, mineTicks, craftTicks, placeTicks, deaths,
                deathCauses, pathFailures, blocksBroken, blocksPlaced, durabilitySpent, gearTool, gearArmor, gearReason,
                bucketKey, biome, temperature, mobPressure, inventoryPressure, cues, actions, false, 0);
    }

    public TrainingResult(String runId, String circuitId, long seed, String mode, String modelId, boolean success,
            int totalTicks, int mineTicks, int craftTicks, int placeTicks, int deaths, String deathCauses,
            int pathFailures, int blocksBroken, int blocksPlaced, int durabilitySpent, String gearTool,
            String gearArmor, String gearReason, String bucketKey, String biome, double temperature,
            String mobPressure, String inventoryPressure, String cues, String actions, boolean gearScored, int gearScore) {
        this.runId = runId;
        this.circuitId = circuitId;
        this.seed = seed;
        this.mode = mode;
        this.modelId = modelId;
        this.success = success;
        this.totalTicks = totalTicks;
        this.mineTicks = mineTicks;
        this.craftTicks = craftTicks;
        this.placeTicks = placeTicks;
        this.deaths = deaths;
        this.deathCauses = deathCauses;
        this.pathFailures = pathFailures;
        this.blocksBroken = blocksBroken;
        this.blocksPlaced = blocksPlaced;
        this.durabilitySpent = durabilitySpent;
        this.gearTool = gearTool;
        this.gearArmor = gearArmor;
        this.gearReason = gearReason;
        this.bucketKey = bucketKey;
        this.biome = biome;
        this.temperature = temperature;
        this.mobPressure = mobPressure;
        this.inventoryPressure = inventoryPressure;
        this.cues = cues;
        this.actions = actions;
        this.gearScored = gearScored;
        this.gearScore = gearScore;
    }

    /** Same row, marked as the gear-choice winner. */
    public TrainingResult withGearScore(int score) {
        return new TrainingResult(runId, circuitId, seed, mode, modelId, success, totalTicks, mineTicks, craftTicks,
                placeTicks, deaths, deathCauses, pathFailures, blocksBroken, blocksPlaced, durabilitySpent, gearTool,
                gearArmor, gearReason, bucketKey, biome, temperature, mobPressure, inventoryPressure, cues, actions,
                true, score);
    }
}
