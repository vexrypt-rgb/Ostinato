package baritone.pathing;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Optional hint for columns the client has not loaded and has not cached.
 * No worldgen runs here. Nothing is registered until a caller supplies a seed and a predictor,
 * and loaded chunks always win over a prediction.
 */
public final class SeedPlan {

    public interface Predictor {
        boolean covers(int blockX, int blockZ);

        /** World coordinates. Null means the cell is unknown and stays unloaded. */
        BlockState block(int x, int y, int z);
    }

    private static volatile long seed = Long.MIN_VALUE;
    private static volatile Predictor predictor;

    private SeedPlan() {}

    public static void use(long worldSeed, Predictor hint) {
        seed = worldSeed;
        predictor = hint;
    }

    public static void clear() {
        seed = Long.MIN_VALUE;
        predictor = null;
    }

    public static long seed() {
        return seed;
    }

    public static boolean active() {
        return predictor != null && seed != Long.MIN_VALUE;
    }

    public static boolean covers(int x, int z) {
        Predictor p = predictor;
        return p != null && seed != Long.MIN_VALUE && p.covers(x, z);
    }

    public static BlockState predict(int x, int y, int z) {
        Predictor p = predictor;
        if (p == null || seed == Long.MIN_VALUE || !p.covers(x, z)) {
            return null;
        }
        return p.block(x, y, z);
    }
}
