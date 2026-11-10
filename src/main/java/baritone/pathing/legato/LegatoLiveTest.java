/*
 * Ostinato: new file (Legato). Not part of upstream Baritone.
 */
package baritone.pathing.legato;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import baritone.harness.PlanLoad;
import baritone.pathing.RouteReuse;
import baritone.pathing.calc.MaestroPathFinder;
import baritone.pathing.calc.SimEdge;
import baritone.pathing.calc.SimEdgePathFinder;
import baritone.pathing.kinematic.KinematicLine;
import baritone.pathing.movement.movements.MovementLine;
import baritone.pathing.movement.movements.MovementStraight;
import baritone.pathing.physics.PhysicsTravel;
import baritone.pathing.unified.UnifiedExecutor;
import baritone.utils.PlanStats;
import baritone.utils.TickClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opt-in live A/B of Legato in a fresh, disposable survival world (-Dostinato.legatotest=true with the test harness
 * on). Never touches an existing save. Builds {@link LegatoCourse} high over spawn and walks each leg in four modes,
 * Presto off throughout:
 * <ul>
 * <li>A: Legato off (the baseline);</li>
 * <li>B: smoothing only;</li>
 * <li>C: any-angle search only;</li>
 * <li>D: both.</li>
 * </ul>
 * With -Dostinato.legatotest.arms=kline the four arms are the kinematic MovementLine experiment instead (Legato search
 * off in all of them): A baseline; B kinematicLine; C Legato smoothing; D both. With arms=maestro: A baseline; B Legato
 * smoothing; C Maestro; D Maestro and Legato smoothing (Legato search off throughout). With arms=simedge (Ostinato 2.0
 * Phase 05) the course is {@link SimEdgeCourse} and the arms are A legacy A*, B simEdgeSearch, C legacy again, D
 * simEdgeSearch again: two independent replications (B vs A, D vs C) and an A/A' noise control (C vs A). Each lap
 * also records what its first plan predicted, under both cost models, for planning accuracy. With arms=unified (Ostinato
 * 2.0 Phase 06) the course is the simedge one and B and D drive with unifiedExecutor on, A and C with it off.
 * <p>
 * Each repetition walks every leg once per mode, the mode order rotated by repetition and leg (a Latin square), so
 * no mode always goes first. Route reuse and the failed-line memory are cleared before every lap. Every row reports
 * what was measured; the summary compares each lap with the baseline lap of the same leg and repetition.
 */
public final class LegatoLiveTest implements AbstractGameEventListener {
    private static final int REPS = Integer.getInteger("ostinato.legatotest.reps", 5), TIMEOUT = 1500;
    private static final String[] MODES = {"A", "B", "C", "D"};
    /** The kinematic MovementLine experiment's arms (see the class comment), else Legato's own. */
    private static final boolean KLINE = "kline".equals(System.getProperty("ostinato.legatotest.arms"));
    /** Ostinato Maestro: -Dostinato.legatotest.arms=maestro; A baseline, B Legato smoothing, C Maestro, D Maestro + smoothing. */
    private static final boolean MAESTRO = "maestro".equals(System.getProperty("ostinato.legatotest.arms"));
    /**
     * Ostinato 2.0 Phase 05B: -Dostinato.legatotest.arms=simedge5b. The simedge course and arms, except that D searches
     * with simEdgeHorizon 8 (the pre-registered horizon experiment) and B with the default 32 (the existing SimEdge).
     */
    private static final boolean SIMEDGE5B = "simedge5b".equals(System.getProperty("ostinato.legatotest.arms"));
    /** Ostinato 2.0 Phase 05: -Dostinato.legatotest.arms=simedge (see the class comment); also on for simedge5b. */
    private static final boolean SIMEDGE = SIMEDGE5B || "simedge".equals(System.getProperty("ostinato.legatotest.arms"));
    /** The horizon arm D of simedge5b searches with. */
    private static final int HORIZON_5B = 8;
    /**
     * Ostinato 2.0 Phase 04 validation: -Dostinato.legatotest.arms=tolerance. The course is {@link SimEdgeCourse}; A and
     * C run with predictionTolerance off, B and D with it on. Nothing else differs between the arms.
     */
    private static final boolean TOLERANCE = "tolerance".equals(System.getProperty("ostinato.legatotest.arms"));
    /**
     * Ostinato 2.0 Phase 06: -Dostinato.legatotest.arms=unified. The course is {@link SimEdgeCourse}; A and C drive with
     * unifiedExecutor off, B and D with it on. predictionTolerance stays at its default in every arm; nothing else differs.
     */
    private static final boolean UNIFIED = "unified".equals(System.getProperty("ostinato.legatotest.arms"));
    /**
     * Ostinato 2.0 Phase 07: -Dostinato.legatotest.arms=stateids. The course is {@link SimEdgeCourse}; A and C run with
     * pathingStateIdCache off, B and D with it on. Every other setting stays at its default; nothing else differs.
     */
    private static final boolean STATEIDS = "stateids".equals(System.getProperty("ostinato.legatotest.arms"));
    /**
     * Ostinato 2.0 Phase 10: -Dostinato.legatotest.arms=production. The course is {@link SimEdgeCourse}; all four arms
     * run the production defaults (no experimental switch), so every lap is a production baseline lap and B, C and D
     * against A are A/A noise controls.
     */
    private static final boolean PRODUCTION = "production".equals(System.getProperty("ostinato.legatotest.arms"));
    /**
     * OAP scheduling study: -Dostinato.legatotest.arms=planload. The production course and defaults of arms=production,
     * plus a controlled planner load ({@link PlanLoad}) while the lap runs: A none, B one search, C none, D three. A
     * against C is the A/A noise control. Each lap also records thread CPU, GC, frame intervals and server tick times.
     */
    private static final boolean PLANLOAD = "planload".equals(System.getProperty("ostinato.legatotest.arms"));
    private static final int[] LOADS = {0, 1, 0, 3};
    /** The arms that time every lap tick: no Phase 05 pricing in the lap, and a tick file after it. */
    private static final boolean TIMED = TOLERANCE || UNIFIED || STATEIDS || PRODUCTION || PLANLOAD;
    /** The timed arm's name and its switch, for the tick file and the closing log line. */
    private static final String TIMED_NAME = UNIFIED ? "unified" : STATEIDS ? "stateids" : PRODUCTION ? "production" : PLANLOAD ? "planload" : "tolerance";

    private static boolean timedSwitch() {
        return PRODUCTION ? false : UNIFIED ? Baritone.settings().unifiedExecutor.value : STATEIDS ? Baritone.settings().pathingStateIdCache.value
                : Baritone.settings().predictionTolerance.value;
    }
    /** -Dostinato.legatotest.stack=&lt;travelStack&gt; pins the travel stack for every arm; unset leaves the setting alone. */
    private static final String STACK = System.getProperty("ostinato.legatotest.stack");
    private static final List<LegatoCourse.Leg> LEGS = SIMEDGE || TIMED ? SimEdgeCourse.LEGS : LegatoCourse.LEGS;

    private record Lap(int rep, LegatoCourse.Leg leg, int mode) {}

    private final Baritone baritone;
    private final List<Lap> laps = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    /** Reached lap ticks by leg, repetition and mode. */
    private final Map<String, Long> reached = new HashMap<>();
    private int state, wait, index, step;
    private volatile boolean ready;
    /** The last lap did not end cleanly: rebuild the course before the next. */
    private boolean dirty;
    private long ticks, lapStart;
    private BufferedWriter out;

    private volatile int positionPackets;
    private int calcs, driverChanges, rewinds, lastPathPos = -1, planned;
    private String lastDriver = "";
    private final Map<String, Integer> driverTicks = new TreeMap<>();
    private double travelled, turned, lastX, lastZ;
    private float lastYaw;
    private long[] before;
    private long alloc0, kinNs0;
    /** Ticks on a MovementLine / a MovementStraight by who drove them, and why an accepted line was not steered. */
    private final Map<String, Integer> lineTicks = new TreeMap<>(), straightTicks = new TreeMap<>(), lineDeclines = new TreeMap<>();
    private final Map<String, Integer> faults = new TreeMap<>();
    /** Ticks turning on the spot; plans dropped mid-lap; laps that left a key forced down. */
    private int turnInPlace, cancels, stuckKeyLaps;
    private boolean faultHooked;
    /** Phase 05: the body at the lap's start, the first plan's predictions (legacy model, sim model), and jumps. */
    private SimEdge.Body lapBody;
    private double predLegacy, predSim;
    private int planWait, links, simLinks, jumps;
    /** Phase 05: the first plan's last cell, and the lap tick the body first came within 0.8 of its centre (the lap's own arrival test, -1 never). Measures partial plans too. */
    private int segX, segY, segZ, segTicks;
    private boolean complete, wasGround;
    private long simSearch0, simFallback0;
    /** Phase 04 validation: client tick time of every lap tick (TickClock, ns) and who drove it; physics counters at the lap's start. */
    private final long[] tickNs = new long[TIMEOUT + 64];
    private final String[] tickDriver = new String[TIMEOUT + 64];
    /** arms=planload, per recorded tick: 1 = the load built a search in that tick, 2 = a GC pause ended since the last record. */
    private final byte[] tickFlag = new byte[TIMEOUT + 64];
    private boolean builtPrev;
    private long gcPrev;
    private int tickN;
    private String prevDriver = "start";
    private long physNs0, physPlans0, drift0;
    /** Phase 06: the unified executor's counters at the lap's start. */
    private long[] ux0;
    private BufferedWriter ticksOut;
    /** OAP scheduling study (arms=planload): the planner load, this lap's frame intervals and server tick times. */
    private PlanLoad planLoad;
    private final long[] frameNs = new long[8192], srvNs = new long[4096];
    private int frameN, srvN, srvLast, firstPath, loadLeft;
    private long lastFrame, lapNs0, load0, loadDone0, loadWall0, loadBuild0;
    private long[] cpu0;
    private final Map<String, BufferedWriter> sampleOut = new HashMap<>();
    /**
     * Ostinato 2.0 Phase 05B: -Dostinato.legatotest.trace=true writes every lap tick (the state before the tick, the yaw
     * its move used, the keys the input reported, who drove, the path and its position) to simedge-trace.csv and every
     * path a lap ran to simedge-paths.csv, for the offline multi-tick replay. Off: nothing is written.
     */
    private static final boolean TRACE = Boolean.getBoolean("ostinato.legatotest.trace");
    private BufferedWriter traceOut, pathsOut;
    private final Map<Object, Integer> pathIds = new java.util.IdentityHashMap<>();
    private boolean traceRow;
    private long traceT, planNs0;
    private double tx, ty, tz, tvx, tvy, tvz;
    private boolean tGround, tSprinting, tCollided;
    private float tYawUsed;
    private String tDriver;
    private int tPath, tPos, tPackets;

    private LegatoLiveTest(Baritone b) {
        baritone = b;
        for (int r = 0; r < REPS; r++) {
            for (int l = 0; l < LEGS.size(); l++) {
                for (int k = 0; k < MODES.length; k++) {
                    laps.add(new Lap(r, LEGS.get(l), (k + r + l) % MODES.length));
                }
            }
        }
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.legatotest")) b.getGameEventHandler().registerEventListener(new LegatoLiveTest(b));
    }

    private synchronized void log(String message) {
        System.out.println("LEGATOTEST " + message);
        try {
            if (out == null) {
                out = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath().resolve("legatotest-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write("LEGATOTEST " + message);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        // the harness never teleports mid-lap, so every server position packet in a lap is a correction
        if (event.getState() == EventState.PRE && event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            positionPackets++;
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        // after LookBehavior's PRE: the rotation this tick's move uses
        if (TRACE && traceRow && event.getState() == EventState.PRE && Minecraft.getInstance().player != null) {
            tYawUsed = Minecraft.getInstance().player.getYRot();
        }
    }

    @Override
    public void onPostTick(TickEvent event) {
        // the keys the input reported for the tick that just ran: the row for that tick is complete
        var p = Minecraft.getInstance().player;
        if (!TRACE || !traceRow || p == null) return;
        traceRow = false;
        var k = p.input.keyPresses;
        Lap l = laps.get(index);
        try {
            if (traceOut == null) {
                traceOut = open("simedge-trace.csv", "seed,lap,rep,leg,mode,t,x,y,z,vx,vy,vz,onGround,sprinting,collidedH,yawUsed,fwd,back,left,right,jump,sprintKey,sneak,driver,path,pos,packets");
            }
            traceOut.write(String.format(Locale.ROOT, "%s,%d,%d,%s,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%b,%b,%b,%.4f,%b,%b,%b,%b,%b,%b,%b,%s,%d,%d,%d",
                    System.getProperty("ostinato.seed", "71006"), index, l.rep(), l.leg().name(), MODES[l.mode()], traceT, tx, ty, tz, tvx, tvy, tvz,
                    tGround, tSprinting, tCollided, tYawUsed, k.forward(), k.backward(), k.left(), k.right(), k.jump(), k.sprint(), k.shift(),
                    tDriver, tPath, tPos, tPackets));
            traceOut.newLine();
        } catch (IOException e) {
            log("trace file failed: " + e);
        }
    }

    private static BufferedWriter open(String name, String header) throws IOException {
        var file = Minecraft.getInstance().gameDirectory.toPath().resolve(name);
        boolean fresh = !Files.exists(file);
        BufferedWriter w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            w.write(header);
            w.newLine();
        }
        return w;
    }

    /** Phase 05B trace: the state before this tick and who drives it; the row is written once the tick's keys are known. */
    private void traceTick(net.minecraft.client.player.LocalPlayer player, Lap l, baritone.pathing.path.PathExecutor ex, String drv) {
        int id = -1;
        if (ex != null) {
            Integer known = pathIds.get(ex.getPath());
            if (known == null) {
                id = pathIds.size();
                pathIds.put(ex.getPath(), id);
                writePath(l, id, ex.getPath());
            } else {
                id = known;
            }
        }
        var v = player.getDeltaMovement();
        traceT = ticks - lapStart;
        tx = player.getX();
        ty = player.getY();
        tz = player.getZ();
        tvx = v.x;
        tvy = v.y;
        tvz = v.z;
        tGround = player.onGround();
        tSprinting = player.isSprinting();
        tCollided = player.horizontalCollision;
        tYawUsed = Float.NaN;
        tDriver = drv;
        tPath = id;
        tPos = ex == null ? -1 : ex.getPosition();
        tPackets = positionPackets;
        traceRow = true;
    }

    private void writePath(Lap l, int id, baritone.api.pathing.calc.IPath path) {
        StringBuilder cells = new StringBuilder(), moves = new StringBuilder();
        for (var p : path.positions()) cells.append(cells.length() == 0 ? "" : "|").append(p.getX()).append(':').append(p.getY()).append(':').append(p.getZ());
        for (var m : path.movements()) moves.append(moves.length() == 0 ? "" : "|").append(m.getClass().getSimpleName().replace("Movement", ""));
        try {
            if (pathsOut == null) {
                pathsOut = open("simedge-paths.csv", "seed,lap,rep,leg,mode,t,path,simEdge,cells,moves");
            }
            pathsOut.write(System.getProperty("ostinato.seed", "71006") + "," + index + "," + l.rep() + "," + l.leg().name() + "," + MODES[l.mode()] + ","
                    + (ticks - lapStart) + "," + id + "," + Baritone.settings().simEdgeSearch.value + "," + cells + "," + moves);
            pathsOut.newLine();
            pathsOut.flush();
        } catch (IOException e) {
            log("paths file failed: " + e);
        }
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (state == 4 && step == 1 && (event == PathEvent.CALC_STARTED || event == PathEvent.NEXT_SEGMENT_CALC_STARTED)) {
            calcs++;
        }
        if (state == 4 && step == 1 && event == PathEvent.CANCELED) {
            cancels++; // the world changed under the plan, or the executor gave up on it
        }
    }

    @Override
    public void onTick(TickEvent e) {
        if (e.getState() != EventState.PRE) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
            state = 1;
            mc.options.pauseOnLostFocus = false;
            CreateWorldScreen.openFresh(mc, () -> {});
            wait = 40;
        } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
            screen.getUiState().setSeed(System.getProperty("ostinato.seed", "71006"));
            for (var child : screen.children()) {
                if (child instanceof Button button && button.getMessage().getString().contains("Create New World")) {
                    state = 2;
                    wait = 100;
                    button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                        public int input() { return 0; }
                        public int modifiers() { return 0; }
                    });
                    break;
                }
            }
        } else if (state == 2 && mc.player != null && mc.getSingleplayerServer() != null && --wait <= 0) {
            state = 3;
            ready = false;
            Baritone.settings().allowBreak.value = false;
            Baritone.settings().allowPlace.value = false;
            Baritone.settings().presto.value = false;
            if (!faultHooked) {
                faultHooked = true;
                var inner = Baritone.settings().movementFault.value;
                Baritone.settings().movementFault.value = (code, evidence) -> {
                    if (state == 4) faults.merge(code, 1, Integer::sum);
                    inner.accept(code, evidence);
                };
            }
            if (STACK != null) Baritone.settings().travelStack.value = STACK;
            log("start kinematicTravel=" + Baritone.settings().kinematicTravel.value + " presto=false arms=" + (KLINE ? "kline" : MAESTRO ? "maestro" : SIMEDGE5B ? "simedge5b" : SIMEDGE ? "simedge" : TOLERANCE ? "tolerance" : UNIFIED ? "unified" : STATEIDS ? "stateids" : PRODUCTION ? "production" : PLANLOAD ? "planload" : "legato") + " laps=" + laps.size()
                    + " reps=" + REPS + " legs=" + LEGS.size() + " order=latin travelStack=" + Baritone.settings().travelStack.value
                    + " tickClock=" + TickClock.ON + " seed=" + System.getProperty("ostinato.seed", "71006"));
            if (PLANLOAD) {
                // the pilot's far searches stopped at the loaded edge (50 border hits) in ~30 ms each: unbounded here, in every
                // arm, so a load search runs to its plan-ahead budget or until it has expanded every loaded node
                Baritone.settings().pathingMaxChunkBorderFetch.value = Integer.MAX_VALUE;
                log("planload env " + PlanLoad.environment() + " borderFetch=" + Baritone.settings().pathingMaxChunkBorderFetch.value + " maxFps=" + mc.options.framerateLimit().get() + " vsync=" + mc.options.enableVsync().get()
                        + " renderDistance=" + mc.options.renderDistance().get() + " simulationDistance=" + mc.options.simulationDistance().get()
                        + " inactivityFpsLimit=" + mc.options.inactivityFpsLimit().get() + " primaryMs=" + Baritone.settings().planAheadPrimaryTimeoutMS.value
                        + " failureMs=" + Baritone.settings().planAheadFailureTimeoutMS.value);
            }
            server(mc, () -> {
                // peaceful: the first run lost its last 28 laps to a creeper on the platform
                mc.getSingleplayerServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                build(mc);
                ServerPlayer p = player(mc);
                p.getInventory().clearContent();
                LegatoCourse.Leg first = LEGS.getFirst();
                p.teleportTo(first.sx() + 0.5, LegatoCourse.F + 1, first.sz() + 0.5);
            });
            wait = 80;
        } else if (state == 3 && ready && --wait <= 0) {
            next(mc);
        } else if (state == 4) {
            lap(mc);
        }
    }

    /** Lays the course down (server thread); again after any lap that did not end cleanly. */
    private static void build(Minecraft mc) {
        var level = mc.getSingleplayerServer().overworld();
        int F = LegatoCourse.F;
        for (int x = -2; x <= LegatoCourse.SIZE + 2; x++) {
            for (int z = -2; z <= LegatoCourse.SIZE + 2; z++) {
                for (int y = F - 1; y <= F + 6; y++) {
                    boolean solid = SIMEDGE || TIMED ? SimEdgeCourse.solid(x, y, z) : LegatoCourse.solid(x, y, z);
                    level.setBlock(new BlockPos(x, y, z), solid ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void mode(int mode) {
        Baritone.settings().presto.value = false;
        Baritone.settings().maestro.value = MAESTRO && mode >= 2;
        Baritone.settings().simEdgeSearch.value = SIMEDGE && (mode == 1 || mode == 3);
        var horizon = Baritone.settings().simEdgeHorizon;
        horizon.value = SIMEDGE5B && mode == 3 ? HORIZON_5B : horizon.defaultValue; // mode(0) puts the default back
        if (TOLERANCE) Baritone.settings().predictionTolerance.value = mode == 1 || mode == 3; // mode(0) puts the default back
        if (UNIFIED) Baritone.settings().unifiedExecutor.value = mode == 1 || mode == 3; // mode(0) puts the default back
        if (STATEIDS) Baritone.settings().pathingStateIdCache.value = mode == 1 || mode == 3; // mode(0) puts the default back
        if (STACK != null) Baritone.settings().travelStack.value = STACK;
        if (SIMEDGE || TIMED) {
            Baritone.settings().kinematicLine.value = false;
            Baritone.settings().legato.value = false;
            Baritone.settings().legatoSearch.value = false;
            return;
        }
        if (MAESTRO) {
            Baritone.settings().kinematicLine.value = false;
            Baritone.settings().legato.value = mode == 1 || mode == 3;
            Baritone.settings().legatoSmoothing.value = true; // its default; only legato switches it in
            Baritone.settings().legatoSearch.value = false;
            return;
        }
        if (KLINE) {
            Baritone.settings().kinematicLine.value = mode == 1 || mode == 3;
            Baritone.settings().legato.value = mode >= 2;
            Baritone.settings().legatoSmoothing.value = mode >= 2;
            Baritone.settings().legatoSearch.value = false;
            return;
        }
        Baritone.settings().legato.value = mode != 0;
        Baritone.settings().legatoSmoothing.value = mode == 1 || mode == 3;
        Baritone.settings().legatoSearch.value = mode == 2 || mode == 3;
    }

    private void next(Minecraft mc) {
        if (index >= laps.size()) {
            finish();
            return;
        }
        Lap l = laps.get(index);
        mode(0);
        String held = heldKeys();
        if (!held.isEmpty()) {
            stuckKeyLaps++;
            log("keys still held after lap " + (index - 1) + ": " + held);
        }
        ready = false;
        if (mc.player.isDeadOrDying()) {
            mc.player.respawn();
        }
        boolean rebuild = dirty;
        dirty = false;
        server(mc, () -> {
            if (rebuild) {
                build(mc);
                log("course rebuilt before lap " + index);
            }
            ServerPlayer p = player(mc);
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.teleportTo(l.leg().sx() + 0.5, LegatoCourse.F + 1, l.leg().sz() + 0.5);
            p.setDeltaMovement(0, 0, 0);
            p.resetFallDistance();
        });
        state = 4;
        step = 0;
        wait = 30;
    }

    private void lap(Minecraft mc) {
        if (!ready) return;
        Lap l = laps.get(index);
        var player = mc.player;
        if (step == 0) {
            if (--wait > 0) return;
            mode(l.mode());
            RouteReuse.clear();
            LegatoFailures.clear();
            // every lap starts facing +x, so the first turn is the same for every mode
            player.setYRot(-90);
            player.setXRot(0);
            lapBody = baritone.getPathingBehavior().playerBody();
            predLegacy = predSim = Double.NaN;
            planWait = -1;
            segTicks = -1;
            links = simLinks = jumps = 0;
            complete = false;
            wasGround = true;
            simSearch0 = SimEdgePathFinder.searches.get();
            simFallback0 = SimEdgePathFinder.fallbacks.get();
            before = counters();
            calcs = driverChanges = rewinds = planned = 0;
            lastPathPos = -1;
            lastDriver = "";
            driverTicks.clear();
            lineTicks.clear();
            straightTicks.clear();
            lineDeclines.clear();
            faults.clear();
            turnInPlace = cancels = 0;
            travelled = turned = 0;
            lastX = player.getX();
            lastZ = player.getZ();
            lastYaw = player.getYRot();
            alloc0 = allocated();
            kinNs0 = PlanStats.kinNs.get();
            tickN = 0;
            prevDriver = "start";
            physNs0 = PlanStats.physNs.get();
            planNs0 = PlanStats.astarNs.get();
            pathIds.clear();
            physPlans0 = PhysicsTravel.plans.get();
            drift0 = PhysicsTravel.driftReplans.get();
            ux0 = baritone.getUnifiedExecutor().snapshot();
            lapStart = ticks;
            if (PLANLOAD) startLoad(mc, l);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(l.leg().gx(), LegatoCourse.F + 1, l.leg().gz()));
            step = 1;
            return;
        }
        double x = player.getX(), y = player.getY(), z = player.getZ();
        double stepXZ = Math.hypot(x - lastX, z - lastZ), dYaw = Math.abs(Mth.wrapDegrees(player.getYRot() - lastYaw));
        travelled += stepXZ;
        turned += dYaw;
        if (player.onGround() && stepXZ < 0.05 && dYaw > 1) turnInPlace++;
        lastX = x;
        lastZ = z;
        lastYaw = player.getYRot();
        var ex = baritone.getPathingBehavior().getCurrent();
        String drv = ex == null ? "-" : ex.getLastDriver().toString();
        driverTicks.merge(drv, 1, Integer::sum);
        if (tickN < tickNs.length) { // the tick before this one, and who drove it (the executor ran in that tick's PRE)
            tickNs[tickN] = TickClock.last;
            if (PLANLOAD) {
                long gc = PlanLoad.gcPauses();
                tickFlag[tickN] = (byte) ((builtPrev ? 1 : 0) | (gc != gcPrev ? 2 : 0));
                gcPrev = gc;
            }
            tickDriver[tickN++] = prevDriver;
        }
        prevDriver = drv;
        if (PLANLOAD) loadTick(mc, l, ex != null);
        if (!drv.equals(lastDriver)) {
            if (!lastDriver.isEmpty()) driverChanges++;
            lastDriver = drv;
        }
        int pos = ex == null ? -1 : ex.getPosition();
        if (TRACE) traceTick(player, l, ex, drv);
        if (pos >= 0 && lastPathPos >= 0 && pos < lastPathPos) rewinds++;
        if (pos >= 0) lastPathPos = pos;
        if (ex != null && planned == 0) {
            planned = ex.getPath().positions().size();
            // Phase 04 validation: the tolerance arms time ticks, and this pricing is Phase 05's and costs tick time
            if (!TIMED) predict(ex.getPath(), l, ticks - lapStart);
        }
        if (planWait >= 0 && segTicks < 0 && Math.abs(x - (segX + 0.5)) < 0.8 && Math.abs(z - (segZ + 0.5)) < 0.8 && Math.abs(y - segY) < 1) segTicks = (int) (ticks - lapStart);
        if (wasGround && !player.onGround() && player.getDeltaMovement().y > 0.2) jumps++;
        wasGround = player.onGround();
        if (ex != null && pos >= 0 && pos < ex.getPath().movements().size()) {
            var mv = ex.getPath().movements().get(pos);
            if (mv instanceof MovementLine line) {
                lineTicks.merge(drv, 1, Integer::sum);
                if (line.kinematicDecision == KinematicLine.ACCEPTED && !"KINEMATIC".equals(drv)) {
                    String why = ex.kinematic().traceWhy;
                    lineDeclines.merge(why == null ? "-" : why.replaceAll("[-0-9.E]+$", "").trim().replace(' ', '_'), 1, Integer::sum);
                }
            } else if (mv instanceof MovementStraight) {
                straightTicks.merge(drv, 1, Integer::sum);
            }
        }
        boolean there = Math.abs(x - (l.leg().gx() + 0.5)) < 0.8 && Math.abs(z - (l.leg().gz() + 0.5)) < 0.8 && player.onGround();
        boolean fell = y < LegatoCourse.F + 0.5;
        boolean died = player.isDeadOrDying();
        long took = ticks - lapStart;
        if (there || fell || died || took > TIMEOUT) {
            baritone.getPathingBehavior().cancelEverything();
            String held = heldKeys();
            if (!held.isEmpty()) log("keys held right after cancel, lap " + index + ": " + held);
            long[] d = counters();
            for (int i = 0; i < d.length; i++) d[i] -= before[i];
            traceRow = false; // the lap ends before this tick moves
            if (traceOut != null) {
                try {
                    traceOut.flush();
                } catch (IOException ex2) {
                    log("trace flush failed: " + ex2);
                }
            }
            String result = there ? "REACHED" : died ? "DIED" : fell ? "FELL" : "TIMEOUT";
            dirty = !there;
            String row = String.format(Locale.ROOT, "lap %d rep=%d leg=%s mode=%s result=%s ticks=%d travelled=%.2f turned=%.0f x=%.2f z=%.2f"
                            + " planned=%d calcs=%d driverChanges=%d rewinds=%d posPackets=%d drivers=%s"
                            + " evals=%d feasible=%d shortcuts=%d removed=%d theta=%d/%d straights=%d straightFails=%d evalUs=%d smoothUs=%d"
                            + " kinNs=%d allocKB=%d lineTicks=%s straightTicks=%s turnInPlace=%d cancels=%d faults=%s"
                            + " klineAccepted=%d klineOff=%d klineGround=%d klinePending=%d klineDowngrades=%d lineDeclines=%s"
                            + " planWait=%d predLegacy=%.2f predSim=%.2f complete=%b links=%d simLinks=%d jumps=%d simSearches=%d simFallbacks=%d segTicks=%d simEdge=%b horizon=%d",
                    index, l.rep(), l.leg().name(), MODES[l.mode()], result, took, travelled, turned, x, z,
                    planned, calcs, driverChanges, rewinds, d[0], driverTicks, d[1], d[2], d[3], d[4], d[6], d[5], d[7], d[8], d[9] / 1000, d[10] / 1000,
                    PlanStats.kinNs.get() - kinNs0, (allocated() - alloc0) / 1024, lineTicks, straightTicks, turnInPlace, cancels, faults,
                    d[11], d[12], d[13], d[14], d[15], lineDeclines,
                    planWait, predLegacy, predSim, complete, links, simLinks, jumps,
                    SimEdgePathFinder.searches.get() - simSearch0, SimEdgePathFinder.fallbacks.get() - simFallback0, segTicks,
                    Baritone.settings().simEdgeSearch.value, Baritone.settings().simEdgeHorizon.value);
            long[] sorted = java.util.Arrays.copyOf(tickNs, tickN);
            java.util.Arrays.sort(sorted);
            row += String.format(Locale.ROOT, " tickP50Us=%d tickP95Us=%d tickP99Us=%d tickMaxUs=%d tickN=%d physUs=%d physPlans=%d driftReplans=%d tolerance=%b stack=%s planUs=%d",
                    rank(sorted, 0.50) / 1000, rank(sorted, 0.95) / 1000, rank(sorted, 0.99) / 1000, rank(sorted, 1.0) / 1000, tickN,
                    (PlanStats.physNs.get() - physNs0) / 1000, PhysicsTravel.plans.get() - physPlans0,
                    PhysicsTravel.driftReplans.get() - drift0, Baritone.settings().predictionTolerance.value,
                    Baritone.settings().travelStack.value, (PlanStats.astarNs.get() - planNs0) / 1000);
            if (UNIFIED) row += " unified=" + Baritone.settings().unifiedExecutor.value + " ux=" + uxDelta();
            if (STATEIDS) row += " stateIds=" + Baritone.settings().pathingStateIdCache.value;
            if (PLANLOAD) row += endLoad(l);
            if (TIMED) writeTicks(l);
            rows.add(row);
            log(row);
            if (there) {
                reached.put(l.leg().name() + "/" + l.rep() + "/" + l.mode(), took);
            }
            mode(0);
            index++;
            state = 3;
            wait = 20;
        }
    }

    /**
     * Phase 05 planning accuracy: the first plan of the lap priced from the lap's start body by both cost models, the
     * legacy closed-form costs and the chained sim (whichever search made the plan), to compare with the lap's ticks.
     */
    private void predict(baritone.api.pathing.calc.IPath path, Lap l, long at) {
        planWait = (int) at;
        complete = path.getDest().getX() == l.leg().gx() && path.getDest().getZ() == l.leg().gz();
        segX = path.getDest().getX();
        segY = path.getDest().getY();
        segZ = path.getDest().getZ();
        try {
            var ctx = new baritone.pathing.movement.CalculationContext(baritone);
            SimEdge e = new SimEdge(new baritone.pathing.kinematic.BsiWorld(ctx.bsi), ctx);
            e.sprintAscends = Baritone.settings().sprintAscends.value;
            List<int[]> cells = new ArrayList<>();
            for (var p : path.positions()) cells.add(new int[]{p.getX(), p.getY(), p.getZ()});
            double a = 0, b = 0;
            for (SimEdge.Priced pr : e.price(cells, lapBody)) {
                a += pr.legacy();
                b += pr.sim();
                links++;
                if (pr.simulated()) simLinks++;
            }
            predLegacy = a;
            predSim = b;
        } catch (RuntimeException ex) {
            log("predict failed lap " + index + ": " + ex);
        }
    }

    /** Nearest-rank quantile of a sorted array; 0 when it is empty. */
    private static long rank(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1))];
    }

    /** Phase 06: the unified executor's counters that moved this lap, as name=delta; CANCEL_LATENCY_MAX as it stands. */
    private String uxDelta() {
        long[] now = baritone.getUnifiedExecutor().snapshot();
        StringBuilder b = new StringBuilder("{");
        for (var c : UnifiedExecutor.Counter.values()) {
            long d = c == UnifiedExecutor.Counter.CANCEL_LATENCY_MAX ? now[c.ordinal()] : now[c.ordinal()] - ux0[c.ordinal()];
            if (d != 0) b.append(b.length() == 1 ? "" : ",").append(c).append('=').append(d);
        }
        return b.append('}').toString();
    }

    /** Phase 04/06 validation: every lap tick's time and driver, written after the lap (outside the timed ticks). */
    private void writeTicks(Lap l) {
        try {
            if (ticksOut == null) {
                var file = Minecraft.getInstance().gameDirectory.toPath().resolve(TIMED_NAME + "-ticks.csv");
                boolean fresh = !Files.exists(file);
                ticksOut = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (fresh) {
                    ticksOut.write("seed,lap,rep,leg,mode," + TIMED_NAME + ",i,ns,driver" + (PLANLOAD ? ",flags" : ""));
                    ticksOut.newLine();
                }
            }
            String head = System.getProperty("ostinato.seed", "71006") + "," + index + "," + l.rep() + "," + l.leg().name() + "," + MODES[l.mode()]
                    + "," + (PLANLOAD ? String.valueOf(LOADS[l.mode()]) : String.valueOf(timedSwitch())) + ",";
            for (int i = 0; i < tickN; i++) {
                ticksOut.write(head + i + "," + tickNs[i] + "," + tickDriver[i] + (PLANLOAD ? "," + tickFlag[i] : ""));
                ticksOut.newLine();
            }
            ticksOut.flush();
        } catch (IOException e) {
            log("tick file failed: " + e);
        }
    }

    /** arms=planload, lap start: clears what the last lap left, aims the load once, snapshots the counters, starts the load. */
    private void startLoad(Minecraft mc, Lap l) {
        if (planLoad == null) planLoad = new PlanLoad(baritone);
        loadLeft = planLoad.running();
        if (loadLeft > 0) planLoad.stop(); // a search of the last lap is still running: the row says so
        if (!planLoad.aimed()) {
            // natural terrain west of the course, heading 4000 blocks further west: every search is a far plan-ahead
            int x = -40, z = 40, y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            planLoad.aim(new BlockPos(x, y, z), new baritone.api.pathing.goals.GoalXZ(x - 4000, z));
            log("planload aim " + x + "," + y + "," + z + " below=" + mc.level.getBlockState(new BlockPos(x, y - 1, z)).getBlock() + " goal=" + (x - 4000) + "," + z);
        }
        load0 = planLoad.started.get();
        loadDone0 = planLoad.finished.get();
        loadWall0 = planLoad.wallNs.get();
        loadBuild0 = planLoad.buildNs.get();
        frameN = srvN = 0;
        lastFrame = 0;
        srvLast = mc.getSingleplayerServer().getTickCount() - 1;
        firstPath = -1;
        cpu0 = PlanLoad.cpu();
        gcPrev = PlanLoad.gcPauses();
        lapNs0 = System.nanoTime();
        if (LOADS[l.mode()] > 0) planLoad.tick(LOADS[l.mode()]);
        builtPrev = LOADS[l.mode()] > 0;
    }

    /** arms=planload, every lap tick: tops the load up, notes the first path, samples the server ticks that finished. */
    private void loadTick(Minecraft mc, Lap l, boolean pathing) {
        long s0 = planLoad.started.get();
        if (LOADS[l.mode()] > 0) planLoad.tick(LOADS[l.mode()]);
        builtPrev = planLoad.started.get() != s0;
        if (firstPath < 0 && pathing) firstPath = (int) (ticks - lapStart);
        var server = mc.getSingleplayerServer();
        int tc = server.getTickCount();
        long[] times = server.getTickTimesNanos();
        for (int t = Math.max(srvLast + 1, tc - times.length + 1); t < tc && srvN < srvNs.length; t++) srvNs[srvN++] = times[t % times.length];
        srvLast = Math.max(srvLast, tc - 1);
    }

    /** arms=planload, lap end: stops the load and returns the row's resource fields; writes the frame and server samples. */
    private String endLoad(Lap l) {
        long[] c = PlanLoad.cpu();
        long wall = System.nanoTime() - lapNs0;
        int running = planLoad.running();
        planLoad.stop();
        long[] f = java.util.Arrays.copyOf(frameNs, frameN), s = java.util.Arrays.copyOf(srvNs, srvN);
        java.util.Arrays.sort(f);
        java.util.Arrays.sort(s);
        writeSamples("frames", l, frameNs, frameN);
        writeSamples("server", l, srvNs, srvN);
        return String.format(Locale.ROOT, " load=%d loadStarted=%d loadDone=%d loadWallMs=%d loadBuildUs=%d loadLeft=%d loadRunningEnd=%d wallMs=%d workerCpuMs=%d renderCpuMs=%d"
                        + " serverCpuMs=%d procCpuMs=%d gcN=%d gcMs=%d gcConcMs=%d frames=%d frameP50Us=%d frameP95Us=%d frameP99Us=%d frameMaxUs=%d"
                        + " srvN=%d srvP50Us=%d srvP95Us=%d srvMaxUs=%d srvAvgUs=%d firstPath=%d",
                LOADS[l.mode()], planLoad.started.get() - load0, planLoad.finished.get() - loadDone0, (planLoad.wallNs.get() - loadWall0) / 1_000_000, (planLoad.buildNs.get() - loadBuild0) / 1000,
                loadLeft, running, wall / 1_000_000, (c[0] - cpu0[0]) / 1_000_000, (c[1] - cpu0[1]) / 1_000_000,
                (c[2] - cpu0[2]) / 1_000_000, (c[3] - cpu0[3]) / 1_000_000, c[4] - cpu0[4], c[5] - cpu0[5], c[6] - cpu0[6],
                frameN, rank(f, 0.5) / 1000, rank(f, 0.95) / 1000, rank(f, 0.99) / 1000, rank(f, 1.0) / 1000,
                srvN, rank(s, 0.5) / 1000, rank(s, 0.95) / 1000, rank(s, 1.0) / 1000,
                Minecraft.getInstance().getSingleplayerServer().getAverageTickTimeNanos() / 1000, firstPath);
    }

    /** arms=planload: one lap's samples (frame intervals or server tick times, ns) to planload-&lt;kind&gt;.csv. */
    private void writeSamples(String kind, Lap l, long[] ns, int n) {
        try {
            BufferedWriter w = sampleOut.get(kind);
            if (w == null) {
                var file = Minecraft.getInstance().gameDirectory.toPath().resolve("planload-" + kind + ".csv");
                boolean fresh = !Files.exists(file);
                w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (fresh) {
                    w.write("seed,lap,rep,leg,mode,load,i,ns");
                    w.newLine();
                }
                sampleOut.put(kind, w);
            }
            String head = System.getProperty("ostinato.seed", "71006") + "," + index + "," + l.rep() + "," + l.leg().name() + "," + MODES[l.mode()]
                    + "," + LOADS[l.mode()] + ",";
            for (int i = 0; i < n; i++) {
                w.write(head + i + "," + ns[i]);
                w.newLine();
            }
            w.flush();
        } catch (IOException e) {
            log(kind + " file failed: " + e);
        }
    }

    @Override
    public void onRenderPass(baritone.api.event.events.RenderEvent event) {
        // arms=planload: the interval between world frames, while a lap runs
        if (!PLANLOAD || state != 4 || step != 1) return;
        long now = System.nanoTime();
        if (lastFrame != 0 && frameN < frameNs.length) frameNs[frameN++] = now - lastFrame;
        lastFrame = now;
    }

    private long[] counters() {
        return new long[]{positionPackets, LegatoStats.evaluations(), LegatoStats.verdicts(LegatoFeasibility.FEASIBLE),
                LegatoStats.shortcuts(), LegatoStats.movesRemoved(), LegatoStats.thetaChecks(), LegatoStats.thetaAccepts(),
                LegatoStats.straightsDone(), LegatoStats.straightFails(), LegatoStats.evalNanos(), LegatoStats.smoothNanos(),
                KinematicLine.decisions(KinematicLine.ACCEPTED), KinematicLine.decisions(KinematicLine.OFF),
                KinematicLine.decisions(KinematicLine.GROUND), KinematicLine.decisions(KinematicLine.PENDING), KinematicLine.downgrades()};
    }

    /** Inputs the bot still forces down; after a cancel there must be none. */
    private String heldKeys() {
        StringBuilder b = new StringBuilder();
        for (Input in : Input.values()) {
            if (baritone.getInputOverrideHandler().isInputForcedDown(in)) b.append(b.length() == 0 ? "" : ",").append(in);
        }
        return b.toString();
    }

    private static long allocated() {
        return ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t ? t.getCurrentThreadAllocatedBytes() : 0;
    }

    private void finish() {
        for (int m = 0; m < MODES.length; m++) {
            String tag = " mode=" + MODES[m] + " ";
            int n = 0, ok = 0;
            List<Long> t = new ArrayList<>();
            for (String r : rows) {
                if (!r.contains(tag)) continue;
                n++;
                if (r.contains("result=REACHED")) {
                    ok++;
                    t.add(Long.parseLong(r.replaceAll(".* ticks=(\\d+) .*", "$1")));
                }
            }
            Collections.sort(t);
            log(String.format(Locale.ROOT, "summary mode=%s laps=%d reached=%d ticks median=%s mean=%.1f", MODES[m], n, ok,
                    t.isEmpty() ? "-" : t.get(t.size() / 2), t.stream().mapToLong(Long::longValue).average().orElse(Double.NaN)));
        }
        // paired: each lap against the baseline lap of the same leg and repetition
        for (LegatoCourse.Leg leg : LEGS) {
            for (int m = 1; m < MODES.length; m++) {
                List<Double> ratios = new ArrayList<>();
                List<Long> base = new ArrayList<>(), arm = new ArrayList<>();
                for (int r = 0; r < REPS; r++) {
                    Long a = reached.get(leg.name() + "/" + r + "/0"), b = reached.get(leg.name() + "/" + r + "/" + m);
                    if (a != null) base.add(a);
                    if (b != null) arm.add(b);
                    if (a != null && b != null) ratios.add(b / (double) a);
                }
                Collections.sort(ratios);
                Collections.sort(base);
                Collections.sort(arm);
                log(String.format(Locale.ROOT, "paired leg=%s mode=%s pairs=%d medianA=%s median%s=%s medianRatio=%s ratios=%s", leg.name(), MODES[m],
                        ratios.size(), base.isEmpty() ? "-" : base.get(base.size() / 2), MODES[m], arm.isEmpty() ? "-" : arm.get(arm.size() / 2),
                        ratios.isEmpty() ? "-" : String.format(Locale.ROOT, "%.3f", ratios.get(ratios.size() / 2)), ratios));
            }
        }
        if (TIMED) {
            log(TIMED_NAME + " physPlans=" + PhysicsTravel.plans.get()
                    + " driftReplans=" + PhysicsTravel.driftReplans.get() + " travelStack=" + Baritone.settings().travelStack.value);
        }
        if (UNIFIED) {
            long[] s = baritone.getUnifiedExecutor().snapshot();
            StringBuilder b = new StringBuilder("unified totals");
            for (var c : UnifiedExecutor.Counter.values()) b.append(' ').append(c).append('=').append(s[c.ordinal()]);
            log(b.toString());
        }
        if (SIMEDGE || TIMED) { // the second replication: D against C, same leg and repetition
            for (LegatoCourse.Leg leg : LEGS) {
                List<Double> ratios = new ArrayList<>();
                for (int r = 0; r < REPS; r++) {
                    Long c = reached.get(leg.name() + "/" + r + "/2"), d = reached.get(leg.name() + "/" + r + "/3");
                    if (c != null && d != null) ratios.add(d / (double) c);
                }
                Collections.sort(ratios);
                log(String.format(Locale.ROOT, "paired leg=%s mode=D base=C pairs=%d medianRatio=%s ratios=%s", leg.name(), ratios.size(),
                        ratios.isEmpty() ? "-" : String.format(Locale.ROOT, "%.3f", ratios.get(ratios.size() / 2)), ratios));
            }
            log("simedge searches=" + SimEdgePathFinder.searches.get() + " fallbacks=" + SimEdgePathFinder.fallbacks.get()
                    + " capped=" + SimEdgePathFinder.capped.get() + " exceptions=" + SimEdgePathFinder.exceptions.get()
                    + " simEdges=" + SimEdgePathFinder.simEdges.get() + " legacyEdges=" + SimEdgePathFinder.legacy.get()
                    + " sims=" + SimEdgePathFinder.sims.get() + " inconsistent=" + SimEdgePathFinder.inconsistencies.get());
        }
        log(LegatoStats.line());
        if (MAESTRO) { // Ostinato Maestro: proof that arms C/D searched with Maestro, not only that the switch was on
            log("maestro searches=" + MaestroPathFinder.searches.get() + " fallbacks=" + MaestroPathFinder.fallbacks.get()
                    + " capped=" + MaestroPathFinder.capped.get() + " exceptions=" + MaestroPathFinder.exceptions.get());
        }
        log("keys stuck after " + stuckKeyLaps + " laps");
        if (PLANLOAD && planLoad != null) {
            log("planload totals started=" + planLoad.started.get() + " finished=" + planLoad.finished.get() + " " + PlanLoad.environment());
        }
        mode(0);
        Baritone.settings().legatoSmoothing.value = true; // back to the default
        state = 5;
        if (Boolean.getBoolean("ostinato.legatotest.exit")) Minecraft.getInstance().stop();
    }

    private void server(Minecraft mc, Runnable r) {
        mc.getSingleplayerServer().execute(() -> {
            try {
                r.run();
            } catch (Throwable failure) {
                log("FAIL setup " + failure);
            } finally {
                ready = true;
            }
        });
    }

    private static ServerPlayer player(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
    }
}
