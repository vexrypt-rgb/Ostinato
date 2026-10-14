package baritone.structure;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.util.math.vector.Vector3i;
import net.minecraft.world.gen.feature.structure.StructurePiece;
import net.minecraft.world.gen.feature.structure.AbstractVillagePiece;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.util.math.MutableBoundingBox;
import net.minecraft.world.gen.feature.structure.Structure;
import net.minecraft.world.gen.feature.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exact ground truth from the integrated server's already-generated structure starts. Singleplayer only. */
public final class ServerStructureSource {
    private ServerStructureSource() {}

    /** Sub-type from the start piece's template path (bastions are picked by their first jigsaw pool). */
    static String variantOf(String id, StructureStart<?> s) {
        if (!id.equals("bastion_remnant") || s.getComponents().isEmpty()) return null;
        String d = String.valueOf(pieceName(s.getComponents().get(0)));
        for (StructurePiece p : s.getComponents()) {
            String t = pieceName(p);
            if (t.contains("bastion/")) { d = t; break; }
        }
        if (d.contains("/treasure/")) return "treasure";
        if (d.contains("/hoglin_stable/")) return "stables";
        if (d.contains("/bridge/")) return "bridge";
        if (d.contains("/units/")) return "housing";
        return null;
    }

    static String pieceName(StructurePiece p) {
        if (p instanceof AbstractVillagePiece) return String.valueOf(((AbstractVillagePiece) p).getJigsawPiece());
        return String.valueOf(p);
    }

    public static boolean available() {
        return Minecraft.getInstance().getIntegratedServer() != null;
    }

    /** Every structure start in the chunks around {@code center} (chunk radius), in the player's current dimension. */
    public static List<DetectedStructure> scan(BlockPos center, int chunkRadius, long tick) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getIntegratedServer();
        if (server == null || mc.player == null) return new ArrayList<>();
        RegistryKey<World> dimKey = mc.player.world.getDimensionKey();
        List<DetectedStructure> out = new ArrayList<>();
        try {
            server.runAsync(() -> {
                ServerWorld level = server.getWorld(dimKey);
                if (level == null) return;
                String dim = dimKey.getLocation().getPath();
                int cx = center.getX() >> 4, cz = center.getZ() >> 4;
                for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
                    for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                        IChunk chunk = level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, false);
                        if (chunk == null) continue;
                        for (Map.Entry<Structure<?>, StructureStart<?>> e : chunk.getStructureStarts().entrySet()) {
                            StructureStart<?> s = e.getValue();
                            if (!s.isValid()) continue;
                            // each start is stored in its own chunk only
                            if (s.getChunkPosX() != x || s.getChunkPosZ() != z) continue;
                            String key = e.getKey().getStructureName();
                            if (key == null) continue;
                            key = (key.contains(":") ? key.substring(key.indexOf(":") + 1) : key).toLowerCase(java.util.Locale.ROOT);
                            MutableBoundingBox bb = s.getBoundingBox();
                            Vector3i c = new Vector3i((bb.minX + bb.maxX) / 2, (bb.minY + bb.maxY) / 2, (bb.minZ + bb.maxZ) / 2);
                            out.add(new DetectedStructure(key, dim, new BlockPos(c), DetectedStructure.Source.SERVER, 1.0, tick, variantOf(key, s)));
                        }
                    }
                }
            }).join();
        } catch (RuntimeException ignored) {
        }
        return out;
    }
}
