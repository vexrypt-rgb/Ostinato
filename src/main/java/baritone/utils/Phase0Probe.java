package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.input.Input;
import baritone.pathing.path.PathExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.io.BufferedWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ostinato: Phase 0 fidelity sampler. Drives a fixed waypoint route and logs tick time per driver,
 * planning counters, CPU, heap, GC and server corrections, so visible, hidden and headless runs compare
 * line for line. Off unless {@code -Dostinato.phase0=FILE}; tick times need {@code -Dostinato.tickClock=true}.
 * Route: {@code -Dostinato.phase0.route=x,y,z;x,z;...} (three values is a block goal, two is an XZ goal). With
 * {@code -Dostinato.phase0.relative=true} x and z are offsets from the block where the route starts, and y is absolute.
 * Sampling continues after the route ends, so a run can be held for a fixed wall time.
 */
public final class Phase0Probe implements AbstractGameEventListener {

    private static final String FILE = System.getProperty("ostinato.phase0", "");
    public static final boolean ON = !FILE.isEmpty();
    private static final int SETTLE = Integer.getInteger("ostinato.phase0.settle", 200);
    private static final int LEG_TIMEOUT = Integer.getInteger("ostinato.phase0.legTimeout", 6000);
    private static final int WINDOW = 200;

    private final Baritone baritone;
    private static final boolean RELATIVE = Boolean.getBoolean("ostinato.phase0.relative");
    /** Ostinato: chunks (cx,cz;cx,cz) reported as held or not on a C line every 20 ticks; empty = off. */
    private static final List<int[]> CHUNKS = parseChunks(System.getProperty("ostinato.phase0.chunks", ""));

    static List<int[]> parseChunks(String text) {
        List<int[]> out = new ArrayList<>();
        for (String part : text.split(";")) {
            String[] v = part.trim().split(",");
            if (v.length == 2) {
                try {
                    out.add(new int[]{Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim())});
                } catch (NumberFormatException ignored) {
                    // not a chunk: skipped
                }
            }
        }
        return out;
    }
    /** Ostinato: also a T line every tick (position, speed, yaw, keys, the movement and the kinematic branch). */
    private static final boolean TRACE = Boolean.getBoolean("ostinato.phase0.trace");
    private final List<int[]> spec = new ArrayList<>();
    private final List<Goal> route = new ArrayList<>();
    private final List<String> routeText = new ArrayList<>();
    private BufferedWriter out;
    private int ticks;
    private int leg = -1;
    private int legStart;
    private boolean done;
    private String prevDriver = "-";
    private int corrections;
    private long lastTickNanos;
    private int legCorrections;
    private final Map<String, LongList> runTicks = new TreeMap<>();
    private final Map<String, LongList> windowTicks = new TreeMap<>();
    private long astar0, phys0, kin0, cpu0, gc0, gcMs0, wall0;
    private long wAstar, wPhys, wKin, wCpu, wGc, wGcMs, wWall;

    private Phase0Probe(Baritone baritone) {
        this.baritone = baritone;
        String text = System.getProperty("ostinato.phase0.route", "");
        for (String part : text.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] v = p.split(",");
            if (v.length != 2 && v.length != 3) continue;
            int[] g = new int[v.length];
            for (int i = 0; i < v.length; i++) g[i] = Integer.parseInt(v[i].trim());
            spec.add(g);
        }
    }

    private void resolveRoute(int ox, int oz) {
        for (int[] g : spec) {
            int x = g[0] + (RELATIVE ? ox : 0);
            int z = g[g.length - 1] + (RELATIVE ? oz : 0);
            if (g.length == 3) {
                route.add(new GoalBlock(x, g[1], z));
                routeText.add(x + "," + g[1] + "," + z);
            } else {
                route.add(new GoalXZ(x, z));
                routeText.add(x + "," + z);
            }
        }
    }

    public static void install(Baritone baritone) {
        if (!ON) {
            return;
        }
        baritone.getGameEventHandler().registerEventListener(new Phase0Probe(baritone));
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() == EventState.PRE && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            corrections++;
            legCorrections++;
        }
    }

    @Override
    public void onPostTick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ticks++;
        long now = System.nanoTime();
        if (ticks == 1) {
            snapshotRun();
            snapshotWindow();
            write(String.format(Locale.ROOT, "START wall=%d mode=%s spec=%s relative=%b tickClock=%b", System.currentTimeMillis(),
                    System.getProperty("ostinato.runMode", "normal"), System.getProperty("ostinato.phase0.route", ""), RELATIVE,
                    TickClock.ON));
        } else {
            long gap = now - lastTickNanos;
            runTicks.computeIfAbsent("GAP", k -> new LongList()).add(gap);
            windowTicks.computeIfAbsent("GAP", k -> new LongList()).add(gap);
        }
        lastTickNanos = now;
        // The executor ran in this tick's PRE; TickClock.last is the previous tick, driven by prevDriver.
        PathExecutor ex = baritone.getPathingBehavior().getCurrent();
        String drv = ex == null ? "-" : ex.getLastDriver().toString();
        if (ticks > 1 && TickClock.ON) {
            long ns = TickClock.last;
            runTicks.computeIfAbsent(prevDriver, k -> new LongList()).add(ns);
            runTicks.computeIfAbsent("ALL", k -> new LongList()).add(ns);
            windowTicks.computeIfAbsent(prevDriver, k -> new LongList()).add(ns);
            windowTicks.computeIfAbsent("ALL", k -> new LongList()).add(ns);
            runTicks.computeIfAbsent("TCPU", k -> new LongList()).add(TickClock.lastCpu);
            windowTicks.computeIfAbsent("TCPU", k -> new LongList()).add(TickClock.lastCpu);
        }
        prevDriver = drv;

        if (ticks % 20 == 0) {
            write(String.format(Locale.ROOT, "P t=%d leg=%d x=%.3f y=%.3f z=%.3f drv=%s", ticks, leg,
                    player.getX(), player.getY(), player.getZ(), drv));
        }
        if (!CHUNKS.isEmpty() && ticks % 20 == 0) {
            // Ostinato: harness check that named chunks are (not) held here; the client's chunk source, never the level
            var mc = Minecraft.getInstance();
            StringBuilder sb = new StringBuilder("C t=" + ticks + " rd=" + mc.options.renderDistance().get());
            for (int[] c : CHUNKS) {
                boolean held = mc.level != null && mc.level.getChunkSource().hasChunk(c[0], c[1]);
                sb.append(' ').append(c[0]).append(',').append(c[1]).append('=').append(held ? 1 : 0);
            }
            write(sb.toString());
        }
        if (TRACE) {
            trace(player, ex, drv);
        }
        if (ticks % WINDOW == 0) {
            write("S t=" + ticks + " leg=" + leg + " " + windowStats());
            snapshotWindow();
        }
        if (done) {
            return;
        }
        if (player.isDeadOrDying()) {
            endLeg("DIED");
            finish("DIED");
            return;
        }
        if (ticks < SETTLE) {
            return;
        }
        if (leg < 0) {
            BetterBlockPos o = baritone.getPlayerContext().playerFeet();
            resolveRoute(o.x, o.z);
            write("ROUTE origin=" + o.x + "," + o.y + "," + o.z + " goals=" + String.join(";", routeText));
            if (route.isEmpty()) {
                finish("NOROUTE");
                return;
            }
            startLeg(0);
            return;
        }
        int took = ticks - legStart;
        boolean active = baritone.getCustomGoalProcess().isActive() || baritone.getPathingBehavior().isPathing();
        if (took > 20 && !active) {
            BetterBlockPos feet = baritone.getPlayerContext().playerFeet();
            endLeg(route.get(leg).isInGoal(feet) ? "REACHED" : "STOPPED");
            next();
        } else if (took > LEG_TIMEOUT) {
            baritone.getPathingBehavior().cancelEverything();
            endLeg("TIMEOUT");
            next();
        }
    }

    private void next() {
        if (leg + 1 < route.size()) {
            startLeg(leg + 1);
        } else {
            finish("DONE");
        }
    }

    private void startLeg(int i) {
        leg = i;
        legStart = ticks;
        legCorrections = 0;
        var p = Minecraft.getInstance().player;
        write(String.format(Locale.ROOT, "LEG %d start t=%d goal=%s from=%.3f,%.3f,%.3f", i, ticks, routeText.get(i),
                p.getX(), p.getY(), p.getZ()));
        baritone.getCustomGoalProcess().setGoalAndPath(route.get(i));
    }

    private void endLeg(String result) {
        if (leg < 0) return;
        var p = Minecraft.getInstance().player;
        write(String.format(Locale.ROOT, "LEG %d %s ticks=%d end=%.3f,%.3f,%.3f corrections=%d", leg, result, ticks - legStart,
                p.getX(), p.getY(), p.getZ(), legCorrections));
    }

    private void finish(String why) {
        done = true;
        StringBuilder sb = new StringBuilder("END ").append(why).append(" t=").append(ticks)
                .append(" corrections=").append(corrections).append(' ');
        sb.append(String.format(Locale.ROOT, "astarUs=%d physUs=%d kinUs=%d cpuMs=%d gc=%d gcMs=%d wallMs=%d",
                (PlanStats.astarNs.get() - astar0) / 1000, (PlanStats.physNs.get() - phys0) / 1000,
                (PlanStats.kinNs.get() - kin0) / 1000, (cpuNs() - cpu0) / 1_000_000, gcCount() - gc0, gcMs() - gcMs0,
                System.currentTimeMillis() - wall0));
        sb.append(heap());
        for (Map.Entry<String, LongList> e : runTicks.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue().summary());
        }
        write(sb.toString());
    }

    private String windowStats() {
        long wall = System.currentTimeMillis() - wWall;
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "wallMs=%d astarUs=%d physUs=%d kinUs=%d cpuMs=%d gc=%d gcMs=%d corrections=%d",
                wall, (PlanStats.astarNs.get() - wAstar) / 1000, (PlanStats.physNs.get() - wPhys) / 1000,
                (PlanStats.kinNs.get() - wKin) / 1000, (cpuNs() - wCpu) / 1_000_000, gcCount() - wGc, gcMs() - wGcMs,
                corrections));
        sb.append(heap());
        for (Map.Entry<String, LongList> e : windowTicks.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue().summary());
        }
        return sb.toString();
    }

    private void snapshotRun() {
        astar0 = PlanStats.astarNs.get();
        phys0 = PlanStats.physNs.get();
        kin0 = PlanStats.kinNs.get();
        cpu0 = cpuNs();
        gc0 = gcCount();
        gcMs0 = gcMs();
        wall0 = System.currentTimeMillis();
    }

    private void snapshotWindow() {
        wAstar = PlanStats.astarNs.get();
        wPhys = PlanStats.physNs.get();
        wKin = PlanStats.kinNs.get();
        wCpu = cpuNs();
        wGc = gcCount();
        wGcMs = gcMs();
        wWall = System.currentTimeMillis();
        windowTicks.clear();
    }

    private static String heap() {
        MemoryUsage h = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage n = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        return String.format(Locale.ROOT, " heapUsedMB=%d heapCommittedMB=%d nonHeapCommittedMB=%d",
                h.getUsed() >> 20, h.getCommitted() >> 20, n.getCommitted() >> 20);
    }

    private static long cpuNs() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return 0;
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, b.getCollectionCount());
        return n;
    }

    private static long gcMs() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, b.getCollectionTime());
        return n;
    }

    private void trace(net.minecraft.client.player.LocalPlayer player, PathExecutor ex, String drv) {
        var v = player.getDeltaMovement();
        var in = baritone.getInputOverrideHandler();
        String keys = (in.isInputForcedDown(Input.MOVE_FORWARD) ? "W" : "") + (in.isInputForcedDown(Input.MOVE_BACK) ? "S" : "")
                + (in.isInputForcedDown(Input.MOVE_LEFT) ? "A" : "") + (in.isInputForcedDown(Input.MOVE_RIGHT) ? "D" : "")
                + (in.isInputForcedDown(Input.SPRINT) ? "R" : "") + (in.isInputForcedDown(Input.JUMP) ? "J" : "")
                + (in.isInputForcedDown(Input.SNEAK) ? "N" : "") + (in.isInputForcedDown(Input.CLICK_LEFT) ? "L" : "")
                + (in.isInputForcedDown(Input.CLICK_RIGHT) ? "U" : "");
        String mv = "-";
        String why = "-";
        if (ex != null) {
            int pos = ex.getPosition();
            var moves = ex.getPath().movements();
            if (pos >= 0 && pos < moves.size()) {
                var m = moves.get(pos);
                mv = pos + ":" + m.getClass().getSimpleName().replace("Movement", "") + ":" + m.getSrc().x + "," + m.getSrc().y + "," + m.getSrc().z
                        + ">" + m.getDest().x + "," + m.getDest().y + "," + m.getDest().z;
            }
            if (ex.kinematic().traceTick == player.tickCount) {
                why = String.valueOf(ex.kinematic().traceWhy).replace(' ', '_');
            }
        }
        write(String.format(Locale.ROOT, "T ms=%d t=%d x=%.3f y=%.3f z=%.3f vx=%.3f vy=%.3f vz=%.3f yaw=%.1f og=%b drv=%s mv=%s kin=%s keys=%s", System.currentTimeMillis(), ticks,
                player.getX(), player.getY(), player.getZ(), v.x, v.y, v.z, net.minecraft.util.Mth.wrapDegrees(player.getYRot()),
                player.onGround(), drv, mv, why, keys.isEmpty() ? "-" : keys));
    }

    private void write(String line) {
        try {
            if (out == null) {
                Path f = Paths.get(FILE);
                if (f.getParent() != null) {
                    Files.createDirectories(f.getParent());
                }
                out = Files.newBufferedWriter(f, StandardCharsets.UTF_8);
            }
            out.write(line);
            out.newLine();
            out.flush();
        } catch (Exception e) {
            System.err.println("[Phase0Probe] " + e);
        }
    }

    /** Tick times in ns; summary is n/p50/p95/p99/max in microseconds. */
    private static final class LongList {
        private long[] a = new long[1024];
        private int n;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        String summary() {
            long[] s = Arrays.copyOf(a, n);
            Arrays.sort(s);
            long sum = 0;
            for (long v : s) sum += v;
            // Ostinato: n/p50/p95/p99/max/mean, in microseconds. The mean is the useful figure for TCPU, whose
            // Windows thread clock only moves in 15.6 ms steps.
            return n + "/" + rank(s, 0.50) / 1000 + "/" + rank(s, 0.95) / 1000 + "/" + rank(s, 0.99) / 1000 + "/"
                    + (n == 0 ? 0 : s[n - 1] / 1000) + "/" + (n == 0 ? 0 : sum / n / 1000);
        }

        private static long rank(long[] s, double q) {
            if (s.length == 0) return 0;
            return s[Math.min(s.length - 1, (int) Math.ceil(q * s.length) - 1 < 0 ? 0 : (int) Math.ceil(q * s.length) - 1)];
        }
    }
}
