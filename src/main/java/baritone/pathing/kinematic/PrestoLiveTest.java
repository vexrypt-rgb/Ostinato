/*
 * Ostinato: new file (Presto). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import baritone.behavior.LookBehavior;
import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.RotationMoveEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.movements.MovementJump;
import baritone.utils.PlanStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static baritone.pathing.kinematic.PrestoCourse.F;
import static baritone.pathing.kinematic.PrestoCourse.GOAL_X;

/**
 * Opt-in live A/B of Presto in a fresh, disposable survival world (-Dostinato.prestotest=true with the test harness
 * on). Never touches an existing save. Builds a one-wide parkour course high over spawn (gaps of one to four, a step
 * up and down, a gap under a low ceiling) over a shallow pit ({@link PrestoCourse}), and runs it with Presto off and
 * on, interleaved lap by lap. Every line reports what was measured; nothing is inferred.
 * <p>
 * Presto v4.1: pairs are counterbalanced (off/on, then on/off). Jolt laps have the server pull the player back
 * {@link PrestoCourse#JOLT_BACK} once, as a correction would. In the default {@code safe} mode the jolt triggers past
 * x {@link PrestoCourse#SAFE_JOLT_X}, only while the player stands (client and server both on the ground), and only
 * onto walkway that is verified, on the server, to be clear and to have floor under the whole box; otherwise it waits
 * (and after {@link PrestoCourse#JOLT_WINDOW} is skipped, and logged).
 * {@code -Dostinato.prestotest.jolt=stress} keeps the old unchecked pull-back at x 7.2, which can drop the player
 * into the one-block gap: a separate stress scenario, tagged in every row so it is never pooled with safe runs.
 */
public final class PrestoLiveTest implements AbstractGameEventListener {
    private static final int LAPS = Integer.getInteger("ostinato.prestotest.laps", 8), TIMEOUT = 900;
    private static final boolean STRESS = "stress".equals(System.getProperty("ostinato.prestotest.jolt", "safe"));
    private static final String JOLT_MODE = STRESS ? "stress" : "safe";
    private static final boolean TRACE = Boolean.getBoolean("ostinato.prestotest.trace");
    /** Traced x range; the whole course by default (v4 traced 0-10 and 44-54 only and missed the 40>44 walk-off). */
    private static final double TRACE_FROM = Double.parseDouble(System.getProperty("ostinato.prestotest.traceFrom", "-1")),
            TRACE_TO = Double.parseDouble(System.getProperty("ostinato.prestotest.traceTo", "60"));

    private record Lap(boolean presto, boolean jolt) {}

    private final Baritone baritone;
    private final List<Lap> laps = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private int state, wait, index, step;
    private volatile boolean ready;
    private long ticks, lapStart;
    private boolean fell;
    private long fellAt;
    private double fellX, fellZ;
    private long[] before;
    // the yaw the last moveRelative really used (after LookBehavior, registered before this listener, rewrote it)
    private float moveYaw = Float.NaN;
    private BufferedWriter out;

    // Jolt bookkeeping. joltState: 0 pending, 1 asked of the server, 2 applied, 3 skipped. Exactly one per jolt lap.
    private volatile int joltState;
    private volatile String joltServer = "";
    private volatile double joltDestX = Double.NaN;
    private int joltAttempts;
    private long joltTick = -1, joltArrived = -1, joltSettled = -1;
    // Recovery and ownership bookkeeping, per lap
    private volatile int positionPackets;
    private int pathEvents, driverChanges, rewinds, lastPathPos = -1;
    private String lastDriver = "";
    private long kinNs0;

    private PrestoLiveTest(Baritone b) {
        baritone = b;
        for (int i = 0; i < LAPS; i++) {
            boolean first = i % 2 == 1; // ABBA: odd pairs run Presto first, so neither arm always goes second
            laps.add(new Lap(first, false));
            laps.add(new Lap(!first, false));
        }
        for (int i = 0; i < 2; i++) {
            laps.add(new Lap(i % 2 == 1, true));
            laps.add(new Lap(i % 2 == 0, true));
        }
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.prestotest")) b.getGameEventHandler().registerEventListener(new PrestoLiveTest(b));
    }

    private synchronized void log(String message) {
        System.out.println("PRESTOTEST " + message);
        try {
            if (out == null) {
                out = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath().resolve("prestotest-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write("PRESTOTEST " + message);
            out.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Trace lines go to the file only (the client log is kept readable); flushed at the end of every lap. */
    private synchronized void trace(String message) {
        try {
            if (out == null) {
                log("trace file open");
            }
            out.write("PRESTOTEST trace " + message);
            out.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void flush() {
        try {
            if (out != null) out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent e) {
        if (e.getType() == RotationMoveEvent.Type.MOTION_UPDATE) moveYaw = e.getYaw();
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        // every server position packet is a correction for this harness: it never teleports mid-lap except the jolt
        if (event.getState() == EventState.PRE && event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            positionPackets++;
        }
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (state == 4 && step == 1) {
            pathEvents++;
            if (TRACE) trace("lap " + index + " t=" + tick() + " path " + event);
        }
    }

    private static int tick() {
        var p = Minecraft.getInstance().player;
        return p == null ? -1 : p.tickCount;
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
            screen.getUiState().setSeed("71006");
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
            Baritone.settings().allowParkour.value = true;
            Baritone.settings().allowBreak.value = false;
            Baritone.settings().allowPlace.value = false;
            Baritone.settings().prestoDebug.value = true;
            if (TRACE) Baritone.settings().kinematicTrace.value = true; // Presto v4: the takeoff driver's own trace
            if (TRACE) LookBehavior.traceCallers = true; // Presto v4.1: who set the look target
            log("start kinematicTravel=" + Baritone.settings().kinematicTravel.value + " prestoPhysicsCheck=" + Baritone.settings().prestoPhysicsCheck.value
                    + " laps=" + laps.size() + " joltMode=" + JOLT_MODE + " joltX=" + (STRESS ? PrestoCourse.STRESS_JOLT_X : PrestoCourse.SAFE_JOLT_X)
                    + " order=ABBA trace=" + TRACE + (TRACE ? " traceX=" + TRACE_FROM + ".." + TRACE_TO : ""));
            server(mc, () -> {
                var level = mc.getSingleplayerServer().overworld();
                for (int x = -6; x <= GOAL_X + 8; x++) {
                    for (int z = -6; z <= 6; z++) {
                        for (int y = F - 3; y <= F + 8; y++) {
                            level.setBlock(new BlockPos(x, y, z), PrestoCourse.solid(x, y, z) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
                ServerPlayer p = player(mc);
                p.getInventory().clearContent();
                p.teleportTo(0.5, F + 1, 0.5);
            });
            wait = 60;
        } else if (state == 3 && ready && --wait <= 0) {
            next(mc);
        } else if (state == 4) {
            lap(mc);
        }
    }

    private void next(Minecraft mc) {
        if (index >= laps.size()) {
            finish();
            return;
        }
        // Setup teleports are not trial corrections. The lap's arm is enabled only after settling below.
        Baritone.settings().presto.value = false;
        ready = false;
        server(mc, () -> {
            ServerPlayer p = player(mc);
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.teleportTo(0.5, F + 1, 0.5);
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
        Presto p = KinematicController.presto(baritone.getPlayerContext());
        if (step == 0) {
            if (--wait > 0) return;
            Baritone.settings().presto.value = l.presto();
            before = counters(p);
            fell = false;
            joltState = 0;
            joltAttempts = 0;
            joltTick = joltArrived = joltSettled = -1;
            joltDestX = Double.NaN;
            joltServer = "";
            pathEvents = driverChanges = rewinds = 0;
            lastPathPos = -1;
            lastDriver = "";
            kinNs0 = PlanStats.kinNs.get();
            lapStart = ticks;
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(GOAL_X, F + 1, 0));
            step = 1;
            return;
        }
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        if (l.jolt() && !fell) {
            jolt(mc, x, y, z);
        }
        var ex = baritone.getPathingBehavior().getCurrent();
        String drv = ex == null ? "-" : ex.getLastDriver().toString();
        if (!drv.equals(lastDriver)) {
            if (!lastDriver.isEmpty()) driverChanges++;
            lastDriver = drv;
        }
        int pos = ex == null ? -1 : ex.getPosition();
        if (pos >= 0 && lastPathPos >= 0 && pos < lastPathPos) rewinds++;
        if (pos >= 0) lastPathPos = pos;
        if (TRACE && x >= TRACE_FROM && x <= TRACE_TO && !fell) {
            traceTick(mc, ex, drv, p);
        }
        boolean there = Math.abs(x - (GOAL_X + 0.5)) < 0.8 && Math.abs(z - 0.5) < 0.8 && mc.player.onGround() && y > F + 0.5;
        if (y < F + 0.5 && !fell) {
            fell = true;
            fellAt = ticks;
            fellX = x;
            fellZ = z;
            baritone.getPathingBehavior().cancelEverything();
        }
        long took = ticks - lapStart;
        // a fall ends the lap on touchdown in the pit, so the landing audit sees the landing it predicted otherwise
        if (there || fell && (mc.player.onGround() || ticks - fellAt > 40) || !fell && took > TIMEOUT) {
            baritone.getPathingBehavior().cancelEverything();
            long[] d = counters(p);
            for (int i = 0; i < d.length; i++) d[i] -= before[i];
            if (fell) {
                took = fellAt - lapStart;
                x = fellX;
            }
            String jolt = !l.jolt() ? "-" : switch (joltState) {
                case 2 -> String.format(Locale.ROOT, "applied@%d->%.2f%s", joltTick - lapStart, joltDestX, joltSettled >= 0 ? "" : "/unsettled");
                case 3 -> "skipped";
                default -> "never";
            };
            String row = String.format(Locale.ROOT, "lap %d presto=%b jolt=%b joltMode=%s joltResult=%s result=%s ticks=%d x=%.2f z=%.2f y=%.2f health=%.1f"
                            + " obs=%d distrusts=%d retrusts=%d corrections=%d checks=%d agree=%d unavailable=%d noLanding=%d damage=%d level=%d"
                            + " deferred=%d fallbacks=%d overridden=%d declinedTicks=%d hopsHeld=%d landings=%d landingMisses=%d ppeNs=%d hookNs=%d hookTicks=%d"
                            + " posPackets=%d pathEvents=%d driverChanges=%d rewinds=%d kinNs=%d",
                    index, l.presto(), l.jolt(), l.jolt() ? JOLT_MODE : "-", jolt, there ? "REACHED" : fell ? "FELL" : "TIMEOUT", took, x, fell ? fellZ : z, y,
                    mc.player.getHealth(), d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[10], d[11], d[12], d[13], d[14], d[15], d[16],
                    d[17], d[18], d[19], d[20], pathEvents, driverChanges, rewinds, PlanStats.kinNs.get() - kinNs0);
            rows.add(row);
            log(row);
            flush();
            index++;
            state = 3;
            wait = 20;
        }
    }

    /**
     * One jolt per jolt lap. Safe mode: past the trigger, ask the server for a pull-back onto verified walkway; while
     * none is safe, ask again next tick, and give up past the window. Then report when the client saw it arrive and
     * where it next stood.
     */
    private void jolt(Minecraft mc, double x, double y, double z) {
        double trigger = STRESS ? PrestoCourse.STRESS_JOLT_X : PrestoCourse.SAFE_JOLT_X;
        if (joltState == 0 && x > trigger) {
            if (!STRESS && x > trigger + PrestoCourse.JOLT_WINDOW) {
                joltState = 3;
                log(String.format(Locale.ROOT, "jolt lap %d skipped after %d attempts: client x %.3f past the window, last server %s", index, joltAttempts, x, joltServer));
                return;
            }
            joltState = 1;
            joltAttempts++;
            var v = mc.player.getDeltaMovement();
            String client = String.format(Locale.ROOT, "client t=%d pos=%.3f,%.3f,%.3f v=%.4f,%.4f,%.4f ground=%b", mc.player.tickCount, x, y, z, v.x, v.y, v.z, mc.player.onGround());
            long asked = ticks;
            boolean clientGround = mc.player.onGround();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    ServerPlayer sp = player(mc);
                    ServerLevel level = sp.level();
                    double sx = sp.getX(), sy = sp.getY(), sz = sp.getZ();
                    boolean standing = sp.onGround() && clientGround;
                    double dest = STRESS ? sx - PrestoCourse.JOLT_BACK : PrestoCourse.safeJoltX(sx, sy, sz, standing);
                    String why = !STRESS && !standing ? "airborne" : Double.isNaN(dest) ? "course" : STRESS ? "unchecked" : worldSafe(level, sp, dest);
                    joltServer = String.format(Locale.ROOT, "server pos=%.3f,%.3f,%.3f ground=%b dest=%.3f check=%s", sx, sy, sz, sp.onGround(), dest, why);
                    if (!STRESS && !"ok".equals(why)) {
                        joltState = 0; // not safe from here: ask again next tick
                        return;
                    }
                    sp.teleportTo(dest, sy, sz);
                    joltDestX = dest;
                    joltTick = asked;
                    joltState = 2;
                    log("jolt lap " + index + " applied (" + JOLT_MODE + ") " + client + " | " + joltServer + " | attempts " + joltAttempts);
                } catch (Throwable failure) {
                    joltState = 3;
                    log("FAIL jolt " + failure);
                }
            });
        } else if (joltState == 2 && joltArrived < 0 && Math.abs(x - joltDestX) < 0.05) {
            joltArrived = ticks;
            var v = mc.player.getDeltaMovement();
            log(String.format(Locale.ROOT, "jolt lap %d arrived after %d ticks: pos=%.3f,%.3f,%.3f v=%.4f,%.4f,%.4f ground=%b", index, ticks - joltTick,
                    x, y, z, v.x, v.y, v.z, mc.player.onGround()));
        } else if (joltState == 2 && joltArrived >= 0 && joltSettled < 0 && mc.player.onGround()) {
            joltSettled = ticks;
            log(String.format(Locale.ROOT, "jolt lap %d settled after %d ticks: pos=%.3f,%.3f,%.3f supported=%b", index, ticks - joltTick, x, y, z,
                    PrestoCourse.supported(x, y, z)));
        }
    }

    /** The server's own collision check of the pull-back: "ok", or why not. */
    private static String worldSafe(ServerLevel level, ServerPlayer sp, double dest) {
        AABB box = sp.getBoundingBox().move(dest - sp.getX(), 0, 0);
        if (!level.noCollision(sp, box)) {
            return "blocked";
        }
        // floor under both x edges of the box (and its z middle), within MAX_DROP under the feet
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (double ex : new double[]{box.minX + 0.01, box.maxX - 0.01}) {
            boolean floor = false;
            for (int by = (int) Math.floor(sp.getY() - 0.01); by >= (int) Math.floor(sp.getY() - PrestoCourse.MAX_DROP) && !floor; by--) {
                q.set((int) Math.floor(ex), by, (int) Math.floor(sp.getZ()));
                floor = !level.getBlockState(q).getCollisionShape(level, q).isEmpty();
            }
            if (!floor) {
                return "no floor at x " + String.format(Locale.ROOT, "%.2f", ex);
            }
        }
        return "ok";
    }

    private void traceTick(Minecraft mc, baritone.pathing.path.PathExecutor ex, String drv, Presto p) {
        var in = baritone.getInputOverrideHandler();
        String mv = "-", mvState = "";
        if (ex != null && ex.getPosition() < ex.getPath().movements().size()) {
            var m = ex.getPath().movements().get(ex.getPosition());
            mv = m.getClass().getSimpleName().replace("Movement", "") + "@" + m.getSrc().x + ">" + m.getDest().x;
            if (m instanceof MovementJump mj) mvState = " jmp[" + mj.traceState() + "]";
        }
        String kin = "-";
        if (ex != null) {
            KinematicController k = ex.kinematic();
            if (k.traceTick == mc.player.tickCount) {
                kin = String.format(Locale.ROOT, "r=%d why=%s long=%b delay=%s jump=%b gain=%.2f", k.traceR, k.traceWhy, k.traceLong,
                        k.traceDelay == Integer.MIN_VALUE ? "-" : Integer.toString(k.traceDelay), k.traceJump, k.traceGain);
            } else {
                kin = "idle";
            }
        }
        String simState = "";
        if (ex != null && (drv.equals("SIM") || ex.sim().tracePlanLen >= 0 && ex.sim().traceStep >= 0)) {
            SimTravel st = ex.sim();
            StringBuilder bits = new StringBuilder();
            for (int i = 0; i < st.tracePlanLen && i < 64; i++) bits.append((st.tracePlanBits >> i & 1) != 0 ? 'J' : '.');
            simState = String.format(Locale.ROOT, " sim[from=%.3f,%.3f yaw=%.1f dest=%d,%d plan=%s hit=%b step=%d]", st.traceFromX, st.traceFromZ,
                    st.traceYaw, st.traceDestX, st.traceDestZ, st.tracePlanLen < 0 ? "none" : bits, st.traceHit, st.traceStep);
        }
        var tgt = baritone.getLookBehavior().traceTarget;
        String tgtBy = baritone.getLookBehavior().traceTargetBy;
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        BlockPos below = BlockPos.containing(x, y - 0.01, z);
        trace(String.format(Locale.ROOT, "lap %d t=%d pos=%.3f,%.3f,%.3f v=%.4f,%.4f,%.4f yaw=%.1f mvYaw=%.1f tgt=%s ground=%b sprinting=%b hCol=%b"
                        + " below=%s keys W%b J%b S%b L%b R%b B%b drv=%s pp=%d mv=%s kin[%s]%s%s tgtBy=%s presto=%s gn=%b corr=%d",
                index, mc.player.tickCount, x, y, z, mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().y,
                mc.player.getDeltaMovement().z, mc.player.getYRot(), moveYaw, tgt == null ? "-" : String.format(Locale.ROOT, "%.1f", tgt.getYaw()),
                mc.player.onGround(), mc.player.isSprinting(), mc.player.horizontalCollision,
                mc.level.getBlockState(below).isAir() ? "air" : "solid",
                in.isInputForcedDown(Input.MOVE_FORWARD), in.isInputForcedDown(Input.JUMP), in.isInputForcedDown(Input.SPRINT),
                in.isInputForcedDown(Input.MOVE_LEFT), in.isInputForcedDown(Input.MOVE_RIGHT), in.isInputForcedDown(Input.MOVE_BACK),
                drv, ex == null ? -1 : ex.getPosition(), mv, kin, mvState, simState, tgtBy,
                !Baritone.settings().presto.value ? "off" : p.trusted() ? "trusted" : "untrusted",
                baritone.getGraceNote().holdsControl(), positionPackets));
    }

    private long[] counters(Presto p) {
        long[] v = p.verdicts;
        return new long[]{p.observations, p.distrusts, p.retrusts, p.corrections, p.checks,
                v[Presto.Verdict.AGREE.ordinal()], v[Presto.Verdict.UNAVAILABLE.ordinal()], v[Presto.Verdict.NO_LANDING.ordinal()],
                v[Presto.Verdict.DAMAGE.ordinal()], v[Presto.Verdict.LEVEL.ordinal()],
                p.deferred, p.fallbacks, p.overridden, p.declined, p.hopsHeld, p.landings, p.landingMisses, p.ppeNanos, p.hookNanos, p.hookTicks,
                positionPackets};
    }

    private void finish() {
        for (boolean jolt : new boolean[]{false, true}) {
            for (boolean on : new boolean[]{false, true}) {
                String tag = " presto=" + on + " jolt=" + jolt + " ";
                List<Long> t = new ArrayList<>();
                int n = 0, reached = 0, fellN = 0;
                for (String r : rows) {
                    if (!r.contains(tag)) continue;
                    n++;
                    if (r.contains("result=REACHED")) {
                        reached++;
                        t.add(Long.parseLong(r.replaceAll(".* ticks=(\\d+) .*", "$1")));
                    }
                    if (r.contains("result=FELL")) fellN++;
                }
                java.util.Collections.sort(t);
                double mean = t.stream().mapToLong(Long::longValue).average().orElse(Double.NaN);
                log(String.format(Locale.ROOT, "summary%slaps=%d reached=%d fell=%d ticks mean=%.1f median=%s min=%s max=%s", tag, n, reached, fellN, mean,
                        t.isEmpty() ? "-" : t.get(t.size() / 2), t.isEmpty() ? "-" : t.getFirst(), t.isEmpty() ? "-" : t.getLast()));
            }
        }
        log(KinematicController.presto(baritone.getPlayerContext()).summary());
        // Presto v4: the engine's own account of main-thread time per tick (capture + inline compute) and capture cuts
        log("ppe " + baritone.getPredictivePhysics().telemetry().summary()
                + (baritone.getPredictivePhysics() instanceof baritone.physics.mc.PredictivePhysics pp ? " | captureCuts " + pp.captureCuts() : "")
                + " | grace-note warm-up us " + baritone.getGraceNote().warmupNanos() / 1000);
        flush();
        Baritone.settings().presto.value = false;
        LookBehavior.traceCallers = false;
        state = 5;
        if (Boolean.getBoolean("ostinato.prestotest.exit")) Minecraft.getInstance().stop();
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
