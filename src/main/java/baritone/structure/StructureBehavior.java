package baritone.structure;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.event.events.type.EventState;
import baritone.behavior.Behavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Finds structures. Client mode counts signature blocks per loaded chunk and matches {@link Signatures} over 3x3 chunk
 * windows; server mode (singleplayer) reads the exact structure starts. Results are remembered per world.
 */
public final class StructureBehavior extends Behavior {
    public enum Mode { CLIENT, SERVER, BOTH }

    private static final int PER_TICK = 4;

    private final Map<Long, int[]> counts = new HashMap<>();
    private final Map<Long, long[]> sums = new HashMap<>();
    private final List<DetectedStructure> found = new ArrayList<>();
    private final List<Consumer<DetectedStructure>> listeners = new ArrayList<>();
    private Mode mode = Mode.CLIENT;
    private boolean enabled = true;
    private String dimension = "";
    private long tick;

    public StructureBehavior(Baritone baritone) {
        super(baritone);
    }

    public void setEnabled(boolean on) { enabled = on; }
    public boolean isEnabled() { return enabled; }
    public Mode getMode() { return mode; }
    public void setMode(Mode m) { mode = m; clear(); }

    /** Called once for every newly found structure (API hook for TenorClef). */
    public void addListener(Consumer<DetectedStructure> l) { listeners.add(l); }

    public synchronized List<DetectedStructure> all() { return new ArrayList<>(found); }

    public synchronized void clear() {
        counts.clear();
        sums.clear();
        found.clear();
    }

    public synchronized List<DetectedStructure> find(String query) {
        List<DetectedStructure> r = new ArrayList<>();
        for (DetectedStructure s : found) {
            if (query == null || query.isEmpty() || StructureInfo.matches(s.id, query)) r.add(s);
        }
        BlockPos me = ctx.playerFeet();
        r.sort((a, b) -> Double.compare(a.pos.distSqr(me), b.pos.distSqr(me)));
        return r;
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getState() == EventState.POST) clear();
    }

    @Override
    public void onTick(TickEvent event) {
        if (!enabled || event.getType() != TickEvent.Type.IN || ctx.player() == null || ctx.world() == null) return;
        tick++;
        String dim = ctx.world().dimension().identifier().getPath();
        if (!dim.equals(dimension)) {
            dimension = dim;
            clear();
        }
        if (mode != Mode.SERVER) scanClient();
        if (tick % 40 == 0) {
            if (mode != Mode.CLIENT && ServerStructureSource.available()) {
                for (DetectedStructure s : ServerStructureSource.scan(ctx.playerFeet(), 12, tick)) add(s);
            }
            if (mode != Mode.SERVER) evaluate();
        }
    }

    private synchronized void add(DetectedStructure s) {
        for (DetectedStructure o : found) {
            if (!o.dimension.equals(s.dimension)) continue;
            boolean sameSpot = o.pos.distSqr(s.pos) < 48 * 48;
            if (o.source == s.source && o.id.equals(s.id) && sameSpot) return;
            if (o.source != s.source && sameSpot && StructureInfo.matches(o.id, family(s.id))) {
                // the exact server record wins over a client guess at the same place
                if (s.source == DetectedStructure.Source.SERVER) {
                    found.remove(o);
                    break;
                }
                return;
            }
        }
        found.add(s);
        for (Consumer<DetectedStructure> l : listeners) l.accept(s);
    }

    private static String family(String id) {
        StructureInfo i = StructureInfo.byId(id);
        return i == null ? id : i.family;
    }

    private void scanClient() {
        int r = Math.min(10, net.minecraft.client.Minecraft.getInstance().options.renderDistance().get());
        int cx = ctx.playerFeet().getX() >> 4, cz = ctx.playerFeet().getZ() >> 4;
        int done = 0;
        for (int d = 0; d <= r && done < PER_TICK; d++) {
            for (int x = cx - d; x <= cx + d && done < PER_TICK; x++) {
                for (int z = cz - d; z <= cz + d && done < PER_TICK; z++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) != d) continue;
                    long key = ChunkPos.asLong(x, z);
                    synchronized (this) {
                        if (counts.containsKey(key)) continue;
                    }
                    ChunkAccess chunk = ctx.world().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    scan(chunk, key);
                    done++;
                }
            }
        }
    }

    private void scan(ChunkAccess chunk, long key) {
        int[] c = new int[Cat.ALL.length];
        long[] s = new long[Cat.ALL.length * 3];
        LevelChunkSection[] secs = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < secs.length; i++) {
            LevelChunkSection sec = secs[i];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> Cat.mask(st.getBlock()) != 0)) continue;
            int baseY = chunk.getMinY() + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        long m = Cat.mask(sec.getBlockState(x, y, z).getBlock());
                        if (m == 0) continue;
                        for (int k = 0; k < Cat.ALL.length; k++) {
                            if ((m & (1L << k)) == 0) continue;
                            c[k]++;
                            s[k * 3] += baseX + x;
                            s[k * 3 + 1] += baseY + y;
                            s[k * 3 + 2] += baseZ + z;
                        }
                    }
                }
            }
        }
        synchronized (this) {
            counts.put(key, c);
            sums.put(key, s);
        }
    }

    private synchronized void evaluate() {
        List<DetectedStructure> hits = new ArrayList<>();
        for (long key : new ArrayList<>(counts.keySet())) {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            int[] tot = new int[Cat.ALL.length];
            long[] sm = new long[Cat.ALL.length * 3];
            boolean any = false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int[] c = counts.get(ChunkPos.asLong(cx + dx, cz + dz));
                    if (c == null) continue;
                    long[] s = sums.get(ChunkPos.asLong(cx + dx, cz + dz));
                    for (int k = 0; k < tot.length; k++) {
                        tot[k] += c[k];
                        if (c[k] != 0) any = true;
                        for (int a = 0; a < 3; a++) sm[k * 3 + a] += s[k * 3 + a];
                    }
                }
            }
            if (!any) continue;
            BlockPos mid = new BlockPos(cx * 16 + 8, ctx.playerFeet().getY(), cz * 16 + 8);
            String biome = ctx.world().getBiome(mid).unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            for (Signatures.Hit h : Signatures.evaluate(tot, dimension, biome)) {
                BlockPos at = centroid(tot, sm, h.id, mid);
                hits.add(new DetectedStructure(h.id, dimension, at, DetectedStructure.Source.CLIENT, h.confidence, tick));
            }
        }
        for (DetectedStructure s : hits) add(s);
    }

    /** Where the signature blocks cluster; falls back to the window centre. */
    private static BlockPos centroid(int[] tot, long[] sm, String id, BlockPos fallback) {
        Cat key = anchor(id);
        if (key == null || tot[key.ordinal()] == 0) return fallback;
        int n = tot[key.ordinal()], k = key.ordinal() * 3;
        return new BlockPos((int) (sm[k] / n), (int) (sm[k + 1] / n), (int) (sm[k + 2] / n));
    }

    private static Cat anchor(String id) {
        if (id.startsWith("village")) return Cat.BED;
        if (id.startsWith("mineshaft")) return Cat.COBWEB;
        if (id.startsWith("ruined_portal")) return Cat.OBSIDIAN;
        return switch (id) {
            case "stronghold" -> Cat.END_FRAME;
            case "desert_pyramid" -> Cat.ORANGE_TERRACOTTA;
            case "jungle_pyramid" -> Cat.MOSSY_COBBLE;
            case "igloo" -> Cat.SNOW_BLOCK;
            case "swamp_hut" -> Cat.CAULDRON;
            case "pillager_outpost" -> Cat.DARK_OAK_LOG;
            case "mansion" -> Cat.DARK_OAK_PLANKS;
            case "monument" -> Cat.SEA_LANTERN;
            case "ancient_city" -> Cat.SCULK;
            case "trial_chambers" -> Cat.TUFF_BRICKS;
            case "trail_ruins" -> Cat.SUSPICIOUS_GRAVEL;
            case "fortress" -> Cat.NETHER_FENCE;
            case "bastion_remnant" -> Cat.GILDED;
            case "nether_fossil" -> Cat.BONE_BLOCK;
            case "end_city" -> Cat.END_ROD;
            default -> Cat.CHEST;
        };
    }
}
