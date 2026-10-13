package baritone.structure;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exact ground truth from the integrated server's already-generated structure starts. Singleplayer only. */
public final class ServerStructureSource {
    private ServerStructureSource() {}

    public static boolean available() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    /** Every structure start in the chunks around {@code center} (chunk radius), in the player's current dimension. */
    public static List<DetectedStructure> scan(BlockPos center, int chunkRadius, long tick) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return new ArrayList<>();
        var dimKey = mc.player.level().dimension();
        List<DetectedStructure> out = new ArrayList<>();
        try {
            server.submit(() -> {
                ServerLevel level = server.getLevel(dimKey);
                if (level == null) return;
                var reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
                String dim = dimKey.identifier().getPath();
                int cx = center.getX() >> 4, cz = center.getZ() >> 4;
                for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
                    for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                        ChunkAccess chunk = level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS, false);
                        if (chunk == null) continue;
                        for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                            StructureStart s = e.getValue();
                            if (!s.isValid()) continue;
                            // each start is stored in its own chunk only
                            ChunkPos sp = s.getChunkPos();
                            if (sp.x() != x || sp.z() != z) continue;
                            var key = reg.getKey(e.getKey());
                            if (key == null) continue;
                            BoundingBox bb = s.getBoundingBox();
                            out.add(new DetectedStructure(key.getPath(), dim, bb.getCenter(), DetectedStructure.Source.SERVER, 1.0, tick));
                        }
                    }
                }
            }).join();
        } catch (RuntimeException ignored) {
        }
        return out;
    }
}
