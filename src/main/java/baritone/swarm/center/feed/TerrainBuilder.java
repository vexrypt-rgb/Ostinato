package baritone.swarm.center.feed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1. Builds a {@link TerrainFeed} around a bot from a {@link Columns} view of its cache.
 * Pure: the bot supplies the columns (Baritone's cached overview), tests supply a map. The slice is chunk aligned,
 * {@link TerrainFeed#RADIUS_CHUNKS} chunks either side of the bot's chunk. Past 255 distinct blocks, further ids
 * share the last palette entry "other".
 */
public final class TerrainBuilder {

    /** One cached column. {@code block} null means not cached; {@code y} is the top block's real y. */
    public interface Columns {
        String block(int x, int z);

        int y(int x, int z);
    }

    public static final String OTHER = "other";

    private TerrainBuilder() {}

    public static TerrainFeed around(String bot, long nowMs, long seq, char dim, int blockX, int blockZ, Columns cols) {
        int r = TerrainFeed.RADIUS_CHUNKS;
        int originX = ((blockX >> 4) - r) << 4;
        int originZ = ((blockZ >> 4) - r) << 4;
        int size = (2 * r + 1) * 16;
        byte[] blocks = new byte[size * size];
        short[] heights = new short[size * size];
        List<String> palette = new ArrayList<>(List.of(TerrainFeed.UNCACHED));
        Map<String, Integer> index = new HashMap<>();
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int i = dz * size + dx;
                String id = cols.block(originX + dx, originZ + dz);
                if (id == null) {
                    heights[i] = TerrainFeed.UNKNOWN_Y;
                    continue;
                }
                if (id.startsWith("minecraft:")) {
                    id = id.substring(10);
                }
                Integer k = index.get(id);
                if (k == null) {
                    if (palette.size() < 255) {
                        k = palette.size();
                        palette.add(id);
                    } else {
                        if (!palette.contains(OTHER)) {
                            palette.add(OTHER);
                        }
                        k = palette.indexOf(OTHER);
                    }
                    index.put(id, k);
                }
                blocks[i] = (byte) (int) k;
                int y = cols.y(originX + dx, originZ + dz);
                heights[i] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, y));
            }
        }
        return new TerrainFeed(bot, nowMs, seq, dim, originX, originZ, size, size, palette, blocks, heights);
    }
}
