/*
 * Ostinato: new file (Legato). Not part of upstream Baritone.
 */
package baritone.pathing.legato;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import baritone.pathing.RouteReuse;
import baritone.pathing.calc.MaestroPathFinder;
import baritone.pathing.calc.SimEdge;
import baritone.pathing.calc.SimEdgePathFinder;
import baritone.pathing.kinematic.KinematicLine;
import baritone.pathing.movement.movements.MovementLine;
import baritone.pathing.movement.movements.MovementStraight;
import baritone.pathing.physics.PhysicsTravel;
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
 * also records what its first plan predicted, under both cost models, for planning accuracy.
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
    /** Ostinato 2.0 Phase 05: -Dostinato.legatotest.arms=simedge (see the class comment). */
    private static final boolean SIMEDGE = "simedge".equals(System.getProperty("ostinato.legatotest.arms"));
    /**
     * Ostinato 2.0 Phase 04 validation: -Dostinato.legatotest.arms=tolerance. The course is {@link SimEdgeCourse}; A and
     * C run with predictionTolerance off, B and D with it on. Nothing else differs between the arms.
     */
    private static final boolean TOLERANCE = "tolerance".equals(System.getProperty("ostinato.legatotest.arms"));
    /** -Dostinato.legatotest.stack=&lt;travelStack&gt; pins the travel stack for every arm; unset leaves the setting alone. */
    private static final String STACK = System.getProperty("ostinato.legatotest.stack");
    private static final List<LegatoCourse.Leg> LEGS = SIMEDGE || TOLERANCE ? SimEdgeCourse.LEGS : LegatoCourse.LEGS;

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
    private int tickN;
    private String prevDriver = "start";
    private long physNs0, physPlans0, drift0;
    private BufferedWriter ticksOut;

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
            log("start kinematicTravel=" + Baritone.settings().kinematicTravel.value + " presto=false arms=" + (KLINE ? "kline" : MAESTRO ? "maestro" : SIMEDGE ? "simedge" : TOLERANCE ? "tolerance" : "legato") + " laps=" + laps.size()
                    + " reps=" + REPS + " legs=" + LEGS.size() + " order=latin travelStack=" + Baritone.settings().travelStack.value
                    + " tickClock=" + TickClock.ON + " seed=" + System.getProperty("ostinato.seed", "71006"));
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
                    boolean solid = SIMEDGE || TOLERANCE ? SimEdgeCourse.solid(x, y, z) : LegatoCourse.solid(x, y, z);
                    level.setBlock(new BlockPos(x, y, z), solid ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void mode(int mode) {
        Baritone.settings().presto.value = false;
        Baritone.settings().maestro.value = MAESTRO && mode >= 2;
        Baritone.settings().simEdgeSearch.value = SIMEDGE && (mode == 1 || mode == 3);
        if (TOLERANCE) Baritone.settings().predictionTolerance.value = mode == 1 || mode == 3; // mode(0) puts the default back
        if (STACK != null) Baritone.settings().travelStack.value = STACK;
        if (SIMEDGE || TOLERANCE) {
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
            physPlans0 = PhysicsTravel.plans.get();
            drift0 = PhysicsTravel.driftReplans.get();
            lapStart = ticks;
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
            tickDriver[tickN++] = prevDriver;
        }
        prevDriver = drv;
        if (!drv.equals(lastDriver)) {
            if (!lastDriver.isEmpty()) driverChanges++;
            lastDriver = drv;
        }
        int pos = ex == null ? -1 : ex.getPosition();
        if (pos >= 0 && lastPathPos >= 0 && pos < lastPathPos) rewinds++;
        if (pos >= 0) lastPathPos = pos;
        if (ex != null && planned == 0) {
            planned = ex.getPath().positions().size();
            // Phase 04 validation: the tolerance arms time ticks, and this pricing is Phase 05's and costs tick time
            if (!TOLERANCE) predict(ex.getPath(), l, ticks - lapStart);
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
            String result = there ? "REACHED" : died ? "DIED" : fell ? "FELL" : "TIMEOUT";
            dirty = !there;
            String row = String.format(Locale.ROOT, "lap %d rep=%d leg=%s mode=%s result=%s ticks=%d travelled=%.2f turned=%.0f x=%.2f z=%.2f"
                            + " planned=%d calcs=%d driverChanges=%d rewinds=%d posPackets=%d drivers=%s"
                            + " evals=%d feasible=%d shortcuts=%d removed=%d theta=%d/%d straights=%d straightFails=%d evalUs=%d smoothUs=%d"
                            + " kinNs=%d allocKB=%d lineTicks=%s straightTicks=%s turnInPlace=%d cancels=%d faults=%s"
                            + " klineAccepted=%d klineOff=%d klineGround=%d klinePending=%d klineDowngrades=%d lineDeclines=%s"
                            + " planWait=%d predLegacy=%.2f predSim=%.2f complete=%b links=%d simLinks=%d jumps=%d simSearches=%d simFallbacks=%d segTicks=%d",
                    index, l.rep(), l.leg().name(), MODES[l.mode()], result, took, travelled, turned, x, z,
                    planned, calcs, driverChanges, rewinds, d[0], driverTicks, d[1], d[2], d[3], d[4], d[6], d[5], d[7], d[8], d[9] / 1000, d[10] / 1000,
                    PlanStats.kinNs.get() - kinNs0, (allocated() - alloc0) / 1024, lineTicks, straightTicks, turnInPlace, cancels, faults,
                    d[11], d[12], d[13], d[14], d[15], lineDeclines,
                    planWait, predLegacy, predSim, complete, links, simLinks, jumps,
                    SimEdgePathFinder.searches.get() - simSearch0, SimEdgePathFinder.fallbacks.get() - simFallback0, segTicks);
            long[] sorted = java.util.Arrays.copyOf(tickNs, tickN);
            java.util.Arrays.sort(sorted);
            row += String.format(Locale.ROOT, " tickP50Us=%d tickP95Us=%d tickP99Us=%d tickMaxUs=%d tickN=%d physUs=%d physPlans=%d driftReplans=%d tolerance=%b stack=%s",
                    rank(sorted, 0.50) / 1000, rank(sorted, 0.95) / 1000, rank(sorted, 0.99) / 1000, rank(sorted, 1.0) / 1000, tickN,
                    (PlanStats.physNs.get() - physNs0) / 1000, PhysicsTravel.plans.get() - physPlans0,
                    PhysicsTravel.driftReplans.get() - drift0, Baritone.settings().predictionTolerance.value,
                    Baritone.settings().travelStack.value);
            if (TOLERANCE) writeTicks(l);
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

    /** Phase 04 validation: every lap tick's time and driver, written after the lap (outside the timed ticks). */
    private void writeTicks(Lap l) {
        try {
            if (ticksOut == null) {
                var file = Minecraft.getInstance().gameDirectory.toPath().resolve("tolerance-ticks.csv");
                boolean fresh = !Files.exists(file);
                ticksOut = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (fresh) {
                    ticksOut.write("seed,lap,rep,leg,mode,tolerance,i,ns,driver");
                    ticksOut.newLine();
                }
            }
            String head = System.getProperty("ostinato.seed", "71006") + "," + index + "," + l.rep() + "," + l.leg().name() + "," + MODES[l.mode()]
                    + "," + Baritone.settings().predictionTolerance.value + ",";
            for (int i = 0; i < tickN; i++) {
                ticksOut.write(head + i + "," + tickNs[i] + "," + tickDriver[i]);
                ticksOut.newLine();
            }
            ticksOut.flush();
        } catch (IOException e) {
            log("tick file failed: " + e);
        }
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
        if (TOLERANCE) {
            log("tolerance physPlans=" + PhysicsTravel.plans.get()
                    + " driftReplans=" + PhysicsTravel.driftReplans.get() + " travelStack=" + Baritone.settings().travelStack.value);
        }
        if (SIMEDGE || TOLERANCE) { // the second replication: D against C, same leg and repetition
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
