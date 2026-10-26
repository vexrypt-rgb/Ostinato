package baritone.utils;

// Ostinato: render-distance square. A chunk outside this is not a chunk the player can see.

/**
 * Chebyshev distance in chunks from the player chunk, matching the client chunk square
 * ({@code render distance} chunks on each side). Block coordinates use the same shift as the chunk map.
 */
public final class LegitHorizon {

    private LegitHorizon() {}

    /** Chunk index of a block coordinate. Negative blocks stay on the negative chunk, the way the client does. */
    public static int chunkOfBlock(int block) {
        return block >> 4;
    }

    /** Chunk index of an entity coordinate. */
    public static int chunkOf(double block) {
        return (int) Math.floor(block) >> 4;
    }

    /**
     * @param renderChunks the client's render distance in chunks. 0 is only the player's chunk.
     */
    public static boolean inside(int blockX, int blockZ, int originChunkX, int originChunkZ, int renderChunks) {
        if (renderChunks < 0) {
            return false;
        }
        int dx = Math.abs(chunkOfBlock(blockX) - originChunkX);
        int dz = Math.abs(chunkOfBlock(blockZ) - originChunkZ);
        return Math.max(dx, dz) <= renderChunks;
    }
}
