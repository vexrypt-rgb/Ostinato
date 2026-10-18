package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.HeadlessToolSet;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import baritone.utils.PlanStats;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Scratch probe: plans legs of a real map dumped by export.py. -Dprobe.blocks=... -Dprobe.legs="x,y,z>x,y,z;..." */
public class MapProbeTest {
    static final int OFF = 64, MINY = -64, H = 384, SIZE = 128;

    static final class World extends BlockStateInterface {
        final BlockState[] v = new BlockState[SIZE * SIZE * H];
        World() { super(MINY, H, new BetterWorldBorder(-OFF, SIZE - OFF, -OFF, SIZE - OFF)); Arrays.fill(v, Blocks.AIR.defaultBlockState()); }
        int idx(int x, int y, int z) { return ((x + OFF) * SIZE + (z + OFF)) * H + (y - MINY); }
        boolean in(int x, int y, int z) { return x >= -OFF && x < SIZE - OFF && z >= -OFF && z < SIZE - OFF && y >= MINY && y < MINY + H; }
        @Override public BlockState get0(int x, int y, int z) { return in(x, y, z) ? v[idx(x, y, z)] : Blocks.AIR.defaultBlockState(); }
        @Override public boolean isLoaded(int x, int z) { return true; }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return true; }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState parse(String s, Map<String, BlockState> memo) {
        return memo.computeIfAbsent(s, k -> {
            int b = k.indexOf('[');
            Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(b < 0 ? k : k.substring(0, b)));
            BlockState st = block.defaultBlockState();
            if (b >= 0) {
                for (String kv : k.substring(b + 1, k.length() - 1).split(",")) {
                    String[] p = kv.split("=");
                    Property prop = block.getStateDefinition().getProperty(p[0]);
                    if (prop != null) {
                        Optional val = prop.getValue(p[1]);
                        if (val.isPresent()) st = st.setValue(prop, (Comparable) val.get());
                    }
                }
            }
            return st;
        });
    }

    @Test
    public void probe() throws Exception {
        if (System.getenv("PROBE_JDBG") != null) System.setProperty("ostinato.jumpdebug", System.getenv("PROBE_JDBG"));
        String file = System.getenv("PROBE_BLOCKS");
        Assume.assumeTrue(file != null);
        PathBench.boot();
        Settings s = Baritone.settings();
        s.allowBreak.value = false; s.allowPlace.value = false; s.allowInventory.value = false;
        s.allowWaterBucketFall.value = false; s.allowSprint.value = true;
        s.allowParkour.value = true; s.allowParkourAscend.value = true; s.allowNeos.value = true;
        s.allowClimbJumps.value = true; s.allowTasJumps.value = true; s.allowMomentumJumps.value = true;
        s.allowDiagonalAscend.value = true; s.allowDiagonalDescend.value = true;
        if (System.getenv("PROBE_FALL") != null) s.maxFallHeightNoWater.value = Integer.parseInt(System.getenv("PROBE_FALL"));
        if (System.getenv("PROBE_EAGER") != null) s.lazyJumpChecks.value = false;
        World w = new World();
        Map<String, BlockState> memo = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            String[] p = line.split(" ");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            if (w.in(x, y, z)) w.v[w.idx(x, y, z)] = parse(p[3], memo);
        }
        long timeout = Long.parseLong(Objects.requireNonNullElse(System.getenv("PROBE_MS"), "10000"));
        int reps = Integer.parseInt(Objects.requireNonNullElse(System.getenv("PROBE_REPS"), "1"));
        for (String leg : System.getenv("PROBE_LEGS").split(";")) {
            String[] se = leg.split(">");
            int[] a = Arrays.stream(se[0].split(",")).mapToInt(Integer::parseInt).toArray();
            int[] g = Arrays.stream(se[1].split(",")).mapToInt(Integer::parseInt).toArray();
            for (int r = 0; r < reps; r++) {
                CalculationContext ctx = new CalculationContext(w, new HeadlessToolSet(), false, MINY, H);
                long hh0 = PlanStats.hhNs.get(), hc0 = PlanStats.hhCalls.get(), c0 = PlanStats.ceilNs.get(), cs0 = PlanStats.ceilSims.get(),
                        p0 = PlanStats.partNs.get(), ps0 = PlanStats.partSims.get(), f0 = PlanStats.fullNs.get(), fs0 = PlanStats.fullSims.get();
                BetterBlockPos start = new BetterBlockPos(a[0], a[1], a[2]);
                java.io.PrintStream out = System.out;
                if (System.getenv("PROBE_JDBG") == null) System.setOut(new java.io.PrintStream(java.io.PrintStream.nullOutputStream()));
                long t0 = System.nanoTime();
                Optional<IPath> path;
                AStarPathFinder pf = new AStarPathFinder(start, a[0], a[1], a[2], new GoalBlock(g[0], g[1], g[2]), new Favoring(null, ctx), ctx);
                try {
                    path = pf.calculate0(timeout, timeout);
                    if (System.getenv("PROBE_DUMP") != null) {
                        StringBuilder sb = new StringBuilder();
                        for (PathNode n : pf.nodes()) if (n.cost < 1e6) sb.append(n.x).append(' ').append(n.y).append(' ').append(n.z).append(' ').append((int) n.cost).append((char) 10);
                        Files.writeString(Path.of(System.getenv("PROBE_DUMP")), sb);
                    }
                } finally {
                    System.setOut(out);
                }
                double ms = (System.nanoTime() - t0) / 1e6;
                boolean ok = path.isPresent() && path.get().getDest().equals(new BetterBlockPos(g[0], g[1], g[2]));
                System.out.printf(Locale.ROOT, "PROBE exh=%b to=%b%n", pf.exhausted(), pf.timedOut()); System.out.printf(Locale.ROOT, "PROBE %s ok=%b ms=%.0f nodes=%d hhMs=%.0f/%d ceil=%.0fms/%d part=%.0fms/%d full=%.0fms/%d len=%d %s%n", leg, ok, ms,
                        path.map(IPath::getNumNodesConsidered).orElse(-1), (PlanStats.hhNs.get() - hh0) / 1e6, PlanStats.hhCalls.get() - hc0,
                        (PlanStats.ceilNs.get() - c0) / 1e6, PlanStats.ceilSims.get() - cs0, (PlanStats.partNs.get() - p0) / 1e6, PlanStats.partSims.get() - ps0,
                        (PlanStats.fullNs.get() - f0) / 1e6, PlanStats.fullSims.get() - fs0, path.map(p -> p.length()).orElse(0), path.map(p -> p.positions().stream().map(q -> q.x + "," + q.y + "," + q.z).collect(java.util.stream.Collectors.joining(" "))).orElse(""));
            }
        }
    }
}
