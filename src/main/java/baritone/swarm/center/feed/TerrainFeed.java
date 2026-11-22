package baritone.swarm.center.feed;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1, {@code terrain/<name>.json}. A top-down slice of one bot's own Baritone cache (the
 * packed chunks pathfinding already keeps; no renderer), for the War room map backdrop. Columns are row-major from
 * ({@code originX}, {@code originZ}): index = (z - originZ) * width + (x - originX). {@code blocks} holds one palette
 * index per column (0 = not cached), {@code heights} the y of that top block (or {@link #UNKNOWN_Y}). Both travel as
 * base64 (bytes, then big-endian shorts) so a 144 x 144 slice stays under 100 KB.
 */
public record TerrainFeed(String bot, long writtenAtMs, long seq, char dim, int originX, int originZ, int width,
                          int depth, List<String> palette, byte[] blocks, short[] heights) {

    public static final short UNKNOWN_Y = Short.MIN_VALUE;
    /** Palette entry 0. */
    public static final String UNCACHED = "?";
    /** Chunks either side of the bot's chunk: 4 gives 9 x 9 chunks, 144 x 144 columns. */
    public static final int RADIUS_CHUNKS = 4;

    public TerrainFeed {
        bot = bot == null ? "" : bot;
        palette = palette == null || palette.isEmpty() ? List.of(UNCACHED) : List.copyOf(palette);
        if (width <= 0 || depth <= 0 || blocks == null || heights == null || blocks.length != width * depth
                || heights.length != width * depth) {
            throw new IllegalArgumentException("terrain size");
        }
        if (palette.size() > 256) {
            throw new IllegalArgumentException("terrain palette over 256");
        }
    }

    public boolean contains(int x, int z) {
        return x >= originX && z >= originZ && x < originX + width && z < originZ + depth;
    }

    /** The block id on top of column (x, z), or {@link #UNCACHED}. */
    public String blockAt(int x, int z) {
        if (!contains(x, z)) {
            return UNCACHED;
        }
        int i = blocks[(z - originZ) * width + (x - originX)] & 0xFF;
        return i < palette.size() ? palette.get(i) : UNCACHED;
    }

    /** The y of that top block, or {@link #UNKNOWN_Y}. */
    public int heightAt(int x, int z) {
        return contains(x, z) ? heights[(z - originZ) * width + (x - originX)] : UNKNOWN_Y;
    }

    public String toJson() {
        Map<String, Object> m = CenterFeed.envelope(CenterFeed.KIND_TERRAIN, writtenAtMs, seq);
        m.put("bot", bot);
        m.put("dim", String.valueOf(dim));
        m.put("originX", originX);
        m.put("originZ", originZ);
        m.put("width", width);
        m.put("depth", depth);
        m.put("palette", palette);
        m.put("blocks", Base64.getEncoder().encodeToString(blocks));
        ByteBuffer hb = ByteBuffer.allocate(heights.length * 2);
        for (short h : heights) {
            hb.putShort(h);
        }
        m.put("heights", Base64.getEncoder().encodeToString(hb.array()));
        return Json.write(m);
    }

    public static TerrainFeed fromJson(String text) {
        Map<String, Object> m = Json.object(text);
        String bad = CenterFeed.check(m, CenterFeed.KIND_TERRAIN);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        byte[] blocks = Base64.getDecoder().decode(Json.str(m, "blocks", ""));
        byte[] raw = Base64.getDecoder().decode(Json.str(m, "heights", ""));
        if (raw.length % 2 != 0) {
            throw new IllegalArgumentException("terrain heights");
        }
        short[] heights = new short[raw.length / 2];
        ByteBuffer hb = ByteBuffer.wrap(raw);
        for (int i = 0; i < heights.length; i++) {
            heights[i] = hb.getShort();
        }
        String d = Json.str(m, "dim", "?");
        return new TerrainFeed(Json.str(m, "bot", ""), Json.num(m, "writtenAtMs", 0), Json.num(m, "seq", 0),
                d.isEmpty() ? '?' : d.charAt(0), Json.i32(m, "originX", 0), Json.i32(m, "originZ", 0),
                Json.i32(m, "width", 0), Json.i32(m, "depth", 0), Json.strs(m, "palette"), blocks, heights);
    }
}
