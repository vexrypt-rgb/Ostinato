package baritone.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Map;

import java.util.TreeMap;

/** Tells the four bastion layouts apart (speedrun names: housing, stables, treasure, bridge) from the loaded blocks. */
final class BastionVariant {
    private BastionVariant() {}

    /** Chunks of the 7x7 window around {@code at} that are loaded. */
    static int loaded(Level level, BlockPos at) {
        int n = 0, cx = at.getX() >> 4, cz = at.getZ() >> 4;
        for (int x = cx - 3; x <= cx + 3; x++) for (int z = cz - 3; z <= cz + 3; z++) if (level.getChunk(x, z, ChunkStatus.FULL, false) != null) n++;
        return n;
    }

    /** Null until most of the structure is loaded. Thresholds fitted to 24 server-labelled bastions (basalt count separates them). */
    static String classify(Level level, BlockPos at) {
        if (loaded(level, at) < 40) return null;
        Map<String, Integer> f = features(level, at, 3);
        int basalt = f.getOrDefault("polished_basalt", 0);
        if (basalt >= 80) return "bridge";
        if (basalt >= 28) return "treasure";
        if (basalt >= 5) return "stables";
        return "housing";
    }

    /** Block-name histogram (netherrack and air left out) plus the extents of the blackstone mass. */
    static Map<String, Integer> features(Level level, BlockPos at, int chunkRadius) {
        Map<String, Integer> h = new TreeMap<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int cx = at.getX() >> 4, cz = at.getZ() >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                ChunkAccess c = level.getChunk(x, z, ChunkStatus.FULL, false);
                if (c == null) continue;
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int y = c.getMinY(); y < c.getMaxY(); y++) {
                    for (int dz = 0; dz < 16; dz++) {
                        for (int dx = 0; dx < 16; dx++) {
                            p.set(c.getPos().getMinBlockX() + dx, y, c.getPos().getMinBlockZ() + dz);
                            BlockState s = c.getBlockState(p);
                            if (s.isAir()) continue;
                            String n = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                            if (n.equals("netherrack") || n.equals("bedrock") || n.equals("lava")) continue;
                            h.merge(n, 1, Integer::sum);
                            if (n.contains("blackstone") || n.equals("chain") || n.equals("gold_block")) {
                                minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
                                minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
                                minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
                            }
                        }
                    }
                }
            }
        }
        h.put("_spanX", maxX - minX);
        h.put("_spanY", maxY - minY);
        h.put("_spanZ", maxZ - minZ);
        return h;
    }
}
