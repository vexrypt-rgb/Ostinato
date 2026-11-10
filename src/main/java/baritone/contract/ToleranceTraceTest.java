/*
 * Ostinato 2.0 Phase 04 validation: a live-client harness for PredictionTolerance. Not part of upstream Baritone.
 */
package baritone.contract;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.utils.InputOverrideHandler;
import baritone.utils.PlayerMovementInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Live one-step prediction error of {@link PlayerSim} against the 26.3 client, per {@link PredictionTolerance.Mode}
 * (-Dostinato.tolerancetrace=true with the test harness on). Never touches an existing save: it creates a fresh
 * Peaceful world from -Dostinato.seed and builds a course high over spawn:
 * <ul>
 * <li>a flat stone field with a grid of 1-high bumps in its east part (walk, sprint, sprint-jump, mixed keys);</li>
 * <li>open air over the field (falls, from a teleport 3 to 18 blocks up, with no keys or with forward held);</li>
 * <li>a still-water pool, 1 deep in its west half and 3 deep in its east half (fluid);</li>
 * <li>a stone wall with ladders and vines on its face (climb).</li>
 * </ul>
 * Each tick it picks seeded scripted controls (forward, sprint, jump, a yaw) and applies them as {@code PhysicsTravel}
 * does: {@code setYRot}, the look target at pitch 0, the keys forced through {@code PlayerMovementInput}. It reads the
 * player ({@link ClientWorld#readPlayer}), predicts the tick with the simulator's model for the mode
 * ({@link PlayerSim#tickWater} in water, {@link PlayerSim#tick} otherwise; jump cooldown tracked from its own
 * predictions as {@code PhysicsTravel} does) and writes the error against the next tick's real state to
 * {@code tolerance-trace.csv}. The mode is {@link PredictionTolerance#classify} on the pre-tick state, as the consumers
 * call it. Two diagnostics ride along: the land-tick error on water ticks (what {@code SimTrace} computes there) and
 * the error with the yaw the client actually moved with (the model's error without the look's lag).
 * <p>
 * Rows are excluded, with the reason written, on: a server position or motion packet for the player during the tick;
 * the {@value #SETTLE} ticks after a teleport; rest (on the ground, no keys, horizontal speed under 0.003); keys the
 * client used that differ from the script. Segments are chosen for the mode with the fewest usable rows (every fourth is
 * mixed keys), so the choice depends on counts only, never on errors. The session stops once every mode has
 * -Dostinato.tolerancetrace.target usable rows (2000) or after -Dostinato.tolerancetrace.maxTicks (30000).
 */
public final class ToleranceTraceTest implements AbstractGameEventListener {
    private static final long SEED = Long.parseLong(System.getProperty("ostinato.seed", "81001"));
    private static final int TARGET = Integer.getInteger("ostinato.tolerancetrace.target", 2000);
    private static final int MAX_TICKS = Integer.getInteger("ostinato.tolerancetrace.maxTicks", 30000);
    static final int SETTLE = 3;
    /** The course floor; the player stands at F + 1. */
    static final int F = 200;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState(),
            WATER = Blocks.WATER.defaultBlockState(),
            LADDER = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH),
            VINE = Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true);

    /** Areas the wander steers inside: min x, max x, min z, max z (block edges), margin. */
    private static final double[] FIELD = {0, 64, 0, 44, 6}, POOL = {5, 27, 47, 71, 2.5};

    private static final Constructor<PlayerMovementInput> PMI;

    static {
        try {
            PMI = PlayerMovementInput.class.getDeclaredConstructor(InputOverrideHandler.class);
            PMI.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private enum Seg { WALK, SPRINT, SPRINT_JUMP, MIXED, FALL, FLUID, CLIMB }

    private final Baritone baritone;
    private final ClientWorld world;
    private final PlayerSim real, pre, predLand, predWater, predYaw;
    private final Random rng = new Random(SEED);
    private final Map<PredictionTolerance.Mode, List<Double>> usable = new EnumMap<>(PredictionTolerance.Mode.class);
    private final Map<String, Integer> excluded = new TreeMap<>();
    private BufferedWriter out, csv;

    private int state, wait;
    private volatile boolean ready;
    private long ticks, runTicks;
    private volatile int positionPackets, motionPackets;

    // the prediction waiting for the next tick
    private boolean pending, pFwd, pSprint, pJump, pSettle, pRest;
    private PredictionTolerance.Mode pMode;
    private int pPos, pMot, segIndex;
    private float pYaw;
    private volatile float yawUsed, pitchUsed;
    private volatile boolean keysChecked, keysOk;

    // the script
    private Seg seg;
    private int segTick, segLeft, reroll, headingLeft, settle, teleportWait, landedFor, climbPhase, phaseLeft, cycles, cyclesWanted;
    private boolean fwd, sprint, jump, teleporting, climbJump, climbNoFwd;
    private int packetsAtTeleport, jumpTicks, falls, fellOff, deaths;
    private float yawCmd, desired, rate;

    private ToleranceTraceTest(Baritone b) {
        baritone = b;
        world = new ClientWorld(b.getPlayerContext());
        real = new PlayerSim(world);
        pre = new PlayerSim(world);
        predLand = new PlayerSim(world);
        predWater = new PlayerSim(world);
        predYaw = new PlayerSim(world);
        for (PredictionTolerance.Mode m : PredictionTolerance.Mode.values()) usable.put(m, new ArrayList<>());
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.tolerancetrace")) b.getGameEventHandler().registerEventListener(new ToleranceTraceTest(b));
    }

    private synchronized void log(String message) {
        System.out.println("TOLTRACE " + message);
        try {
            if (out == null) {
                out = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath().resolve("tolerancetrace-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write("TOLTRACE " + message);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() != EventState.PRE) return;
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            positionPackets++;
        } else if (event.getPacket() instanceof ClientboundSetEntityMotionPacket m) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && m.id() == p.getId()) motionPackets++;
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        // after LookBehavior's PRE: the rotation the move of this tick uses
        LocalPlayer p = Minecraft.getInstance().player;
        if (state == 4 && event.getState() == EventState.PRE && p != null) {
            yawUsed = p.getYRot();
            pitchUsed = p.getXRot();
        }
    }

    @Override
    public void onPostTick(TickEvent e) {
        // the keys the input reported for the tick that just ran
        LocalPlayer p = Minecraft.getInstance().player;
        if (state != 4 || !pending || p == null) return;
        net.minecraft.world.entity.player.Input k = p.input.keyPresses;
        keysChecked = true;
        keysOk = k.forward() == pFwd && k.sprint() == pSprint && k.jump() == pJump && !k.backward() && !k.left() && !k.right() && !k.shift();
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
            screen.getUiState().setSeed(Long.toString(SEED));
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
            log("start seed=" + SEED + " target=" + TARGET + " maxTicks=" + MAX_TICKS + " settle=" + SETTLE
                    + " predictionTolerance=" + Baritone.settings().predictionTolerance.value + " tickPackets=" + System.getProperty("ostinato.tickPackets", "true"));
            mc.getSingleplayerServer().execute(() -> {
                try {
                    mc.getSingleplayerServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                    build(mc);
                    ServerPlayer sp = player(mc);
                    sp.getInventory().clearContent();
                    sp.teleportTo(32.5, F + 1, 20.5);
                } catch (Throwable failure) {
                    log("FAIL setup " + failure);
                } finally {
                    ready = true;
                }
            });
            wait = 80;
        } else if (state == 3 && ready && --wait <= 0) {
            state = 4;
            openCsv(mc);
            yawCmd = desired = mc.player.getYRot();
            rate = 10;
            seg = null;
        } else if (state == 4) {
            run(mc);
        }
    }

    /** What the course holds at a cell of the cleared box. */
    static BlockState block(int x, int y, int z) {
        if (x >= 0 && x <= 63 && z >= 0 && z <= 43) { // field, bumps in the east part
            if (y == F) return STONE;
            if (y == F + 1 && x >= 40 && x % 4 == 0 && z % 4 == 0) return STONE;
            return AIR;
        }
        if (x >= 4 && x <= 27 && z >= 46 && z <= 71) { // pool: a ring, then 1-deep water west of x=16, 3-deep east
            if (x == 4 || x == 27 || z == 46 || z == 71) return y >= F - 3 && y <= F + 3 ? STONE : AIR;
            if (x >= 16) {
                if (y >= F - 3 && y <= F - 2) return STONE;
                if (y >= F - 1 && y <= F + 1) return WATER;
                return AIR;
            }
            if (y >= F - 3 && y <= F) return STONE;
            if (y == F + 1) return WATER;
            return AIR;
        }
        if (x >= 32 && x <= 63 && z >= 44 && z <= 61) { // climbing: floor, wall at z=60, ladders and vines on its north face
            if (y == F && z <= 59) return STONE;
            if (z == 60 && x >= 34 && x <= 61 && y >= F + 1 && y <= F + 14) return STONE;
            if (z == 59 && y >= F + 1 && y <= F + 13) {
                if (x >= 36 && x <= 47) return LADDER;
                if (x >= 48 && x <= 59) return VINE;
            }
            return AIR;
        }
        return AIR;
    }

    private static void build(Minecraft mc) {
        var level = mc.getSingleplayerServer().overworld();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -2; x <= 66; x++) {
            for (int z = -2; z <= 74; z++) {
                for (int y = F - 4; y <= F + 24; y++) {
                    level.setBlock(pos.set(x, y, z), block(x, y, z), 2);
                }
            }
        }
    }

    private void openCsv(Minecraft mc) {
        try {
            var file = mc.gameDirectory.toPath().resolve("tolerance-trace.csv");
            boolean fresh = !Files.exists(file);
            csv = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                csv.write("seed,tick,seg,segType,mode,fwd,sprint,jump,ground0,water0,climb0,sprinting0,swimming0,x0,y0,z0,vx0,vy0,vz0,"
                        + "yawCmd,yawUsed,err,dx,dy,dz,errLand,errYaw,excl");
                csv.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void run(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        runTicks++;
        if (p.isDeadOrDying()) {
            deaths++;
            log("died at tick " + runTicks + " seg=" + seg);
            p.respawn();
            pending = false;
            seg = null;
            return;
        }
        world.reset();
        ClientWorld.readPlayer(baritone.getPlayerContext(), real);
        real.swimming = p.isSwimming();
        if (pending) row(p);
        pending = false;
        if (finished()) {
            finish(mc);
            return;
        }
        if (teleporting) {
            keys(false, false, false);
            apply(p);
            if (positionPackets != packetsAtTeleport) {
                teleporting = false;
                settle = SETTLE;
                yawCmd = desired = p.getYRot();
            } else if (++teleportWait > 100) {
                log("teleport not seen after 100 ticks, seg=" + seg);
                teleporting = false;
                settle = SETTLE;
            }
            return;
        }
        boolean settling = settle > 0;
        if (settling) {
            settle--;
            keys(false, false, false);
        } else {
            if (seg == null || segOver(p)) {
                nextSegment(mc, p);
                if (teleporting) {
                    keys(false, false, false);
                    apply(p);
                    return;
                }
            }
            control(p);
            segTick++;
        }
        apply(p);
        // predict this tick as PhysicsTravel does: from the state just read, with the controls just applied
        real.jumpTicks = jumpTicks;
        pre.copyFrom(real);
        pMode = PredictionTolerance.classify(real, fwd, sprint);
        predLand.copyFrom(real);
        predLand.tick(yawCmd, fwd, sprint, jump);
        predWater.copyFrom(real);
        predWater.tickWater(yawCmd, 0f, fwd, sprint, jump, false);
        jumpTicks = (pMode == PredictionTolerance.Mode.FLUID ? predWater : predLand).jumpTicks;
        pFwd = fwd;
        pSprint = sprint;
        pJump = jump;
        pYaw = yawCmd;
        pSettle = settling;
        pRest = real.onGround && !fwd && !jump && real.vx * real.vx + real.vz * real.vz < 0.003 * 0.003;
        pPos = positionPackets;
        pMot = motionPackets;
        keysChecked = false;
        pending = true;
    }

    /** The row for the prediction made last tick, against the state just read. */
    private void row(LocalPlayer p) {
        boolean fluid = pMode == PredictionTolerance.Mode.FLUID;
        PlayerSim pred = fluid ? predWater : predLand;
        double dx = real.x - pred.x, dy = real.y - pred.y, dz = real.z - pred.z;
        double err = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double errLand = dist(predLand);
        predYaw.copyFrom(pre);
        if (fluid) predYaw.tickWater(yawUsed, pitchUsed, pFwd, pSprint, pJump, false);
        else predYaw.tick(yawUsed, pFwd, pSprint, pJump);
        double errYaw = dist(predYaw);
        String excl = positionPackets != pPos || motionPackets != pMot ? "packet"
                : pSettle ? "settle"
                : !keysChecked || !keysOk ? "keys"
                : pRest ? "rest" : "";
        if (excl.isEmpty()) usable.get(pMode).add(err);
        else excluded.merge(pMode + "/" + excl, 1, Integer::sum);
        try {
            csv.write(String.format(Locale.ROOT, "%d,%d,%d,%s,%s,%b,%b,%b,%b,%b,%b,%b,%b,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.3f,%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s",
                    SEED, runTicks - 1, segIndex, seg, pMode, pFwd, pSprint, pJump, pre.onGround, pre.inWater(), pre.climbable(), pre.sprinting, pre.swimming,
                    pre.x, pre.y, pre.z, pre.vx, pre.vy, pre.vz, pYaw, yawUsed, err, dx, dy, dz, errLand, errYaw, excl));
            csv.newLine();
            if (runTicks % 200 == 0) csv.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private double dist(PlayerSim s) {
        double dx = real.x - s.x, dy = real.y - s.y, dz = real.z - s.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean finished() {
        if (runTicks >= MAX_TICKS) return true;
        for (List<Double> v : usable.values()) if (v.size() < TARGET) return false;
        return true;
    }

    private boolean onField(LocalPlayer p) {
        return p.getY() > F + 0.5 && p.getY() < F + 3 && p.getX() > FIELD[0] && p.getX() < FIELD[1] && p.getZ() > FIELD[2] && p.getZ() < FIELD[3];
    }

    private boolean segOver(LocalPlayer p) {
        boolean inPool = p.getX() > POOL[0] && p.getX() < POOL[1] && p.getZ() > POOL[2] && p.getZ() < POOL[3];
        if (p.getY() < F - 1.5 && !inPool) { // off an edge: the void is 140 blocks down
            fellOff++;
            return true;
        }
        return switch (seg) {
            case FALL -> landedFor >= 10 || segTick > 160;
            case FLUID -> segLeft <= 0 || !inPool || p.getY() > F + 3;
            case CLIMB -> cycles >= cyclesWanted || segTick > 400;
            default -> segLeft <= 0;
        };
    }

    /** The next segment: the mode with the fewest usable rows (every fourth segment mixed keys). */
    private void nextSegment(Minecraft mc, LocalPlayer p) {
        segIndex++;
        if (segIndex % 4 == 3) {
            seg = Seg.MIXED;
        } else {
            PredictionTolerance.Mode least = null;
            for (PredictionTolerance.Mode m : PredictionTolerance.Mode.values()) {
                if (least == null || usable.get(m).size() < usable.get(least).size()) least = m;
            }
            seg = switch (least) {
                case WALK -> Seg.WALK;
                case SPRINT -> Seg.SPRINT;
                case SPRINT_JUMP -> Seg.SPRINT_JUMP;
                case FALL -> Seg.FALL;
                case FLUID -> Seg.FLUID;
                case CLIMB -> Seg.CLIMB;
            };
        }
        segTick = 0;
        reroll = 0;
        headingLeft = 0;
        landedFor = 0;
        switch (seg) {
            case WALK, SPRINT, MIXED -> segLeft = 40 + rng.nextInt(81);
            case SPRINT_JUMP -> segLeft = 60 + rng.nextInt(101);
            case FLUID -> segLeft = 120 + rng.nextInt(81);
            default -> segLeft = 0;
        }
        switch (seg) {
            case FALL -> {
                falls++;
                int h = 3 + rng.nextInt(16);
                boolean moving = rng.nextDouble() < 0.3;
                fwd = moving;
                sprint = moving && rng.nextBoolean();
                jump = false;
                teleport(mc, 8 + rng.nextInt(49) + 0.5, F + 1 + h, 8 + rng.nextInt(29) + 0.5, rng.nextFloat() * 360 - 180);
            }
            case FLUID -> teleport(mc, 7 + rng.nextInt(18) + 0.5, F + 1, 50 + rng.nextInt(18) + 0.5, rng.nextFloat() * 360 - 180);
            case CLIMB -> {
                boolean vine = rng.nextBoolean();
                climbPhase = 0;
                cycles = 0;
                cyclesWanted = 2 + rng.nextInt(2);
                fwd = true;
                sprint = rng.nextBoolean();
                jump = false;
                teleport(mc, (vine ? 49 : 37) + rng.nextInt(10) + 0.5, F + 1, 56.5, 0);
            }
            default -> {
                if (!onField(p)) teleport(mc, 32.5, F + 1, 20.5, rng.nextFloat() * 360 - 180);
            }
        }
    }

    private void teleport(Minecraft mc, double x, double y, double z, float yaw) {
        teleporting = true;
        teleportWait = 0;
        packetsAtTeleport = positionPackets;
        jumpTicks = 0;
        LocalPlayer p = mc.player;
        yawCmd = desired = p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot());
        mc.getSingleplayerServer().execute(() -> {
            ServerPlayer sp = player(mc);
            sp.resetFallDistance();
            sp.setHealth(sp.getMaxHealth());
            sp.getFoodData().setFoodLevel(20);
            sp.teleportTo(x, y, z);
            sp.setDeltaMovement(0, 0, 0);
        });
    }

    private void control(LocalPlayer p) {
        switch (seg) {
            case WALK -> {
                keys(segTick >= 5, false, false); // a stop first, so the walk is not a carried-over sprint
                wander(p, FIELD);
                segLeft--;
            }
            case SPRINT -> {
                keys(true, true, false);
                wander(p, FIELD);
                segLeft--;
            }
            case SPRINT_JUMP -> {
                keys(true, true, true);
                wander(p, FIELD);
                segLeft--;
            }
            case MIXED -> {
                if (--reroll <= 0) {
                    reroll = 3 + rng.nextInt(13);
                    keys(rng.nextDouble() < 0.8, rng.nextDouble() < 0.5, rng.nextDouble() < 0.3);
                }
                wander(p, FIELD);
                segLeft--;
            }
            case FALL -> {
                if (p.onGround()) landedFor++;
                wander(p, FIELD);
            }
            case FLUID -> {
                if (--reroll <= 0) {
                    reroll = 5 + rng.nextInt(16);
                    keys(rng.nextDouble() < 0.75, rng.nextDouble() < 0.4, rng.nextDouble() < 0.4);
                }
                wander(p, POOL);
                segLeft--;
            }
            case CLIMB -> climb(p);
        }
    }

    private void climb(LocalPlayer p) {
        if (--headingLeft <= 0) { // facing the wall, give or take
            headingLeft = 5 + rng.nextInt(16);
            desired = yawCmd + Mth.wrapDegrees(rng.nextFloat() * 50 - 25 - yawCmd);
            rate = 5;
        }
        turn();
        switch (climbPhase) {
            case 0 -> { // up to the wall
                keys(true, sprint, false);
                if (real.climbable() && p.horizontalCollision || segTick > 40) {
                    climbPhase = 1;
                    phaseLeft = 20 + rng.nextInt(51);
                    climbJump = rng.nextDouble() < 0.3;
                    climbNoFwd = rng.nextDouble() < 0.2;
                }
            }
            case 1 -> { // climb: into the wall, or jump held
                keys(!climbNoFwd, false, climbJump || climbNoFwd);
                if (--phaseLeft <= 0) {
                    climbPhase = 2;
                    phaseLeft = 10 + rng.nextInt(31);
                }
            }
            default -> { // let go and slide
                keys(false, false, false);
                if (--phaseLeft <= 0 || p.onGround()) {
                    cycles++;
                    climbPhase = real.climbable() ? 1 : 0;
                    phaseLeft = 20 + rng.nextInt(51);
                    climbJump = rng.nextDouble() < 0.3;
                    climbNoFwd = rng.nextDouble() < 0.2;
                    sprint = rng.nextBoolean();
                }
            }
        }
    }

    /** A seeded heading walk, turned back toward the middle near the area's edge. */
    private void wander(LocalPlayer p, double[] area) {
        double x = p.getX(), z = p.getZ(), m = area[4];
        if (x < area[0] + m || x > area[1] - m || z < area[2] + m || z > area[3] - m) {
            double cx = (area[0] + area[1]) / 2, cz = (area[2] + area[3]) / 2;
            float toward = (float) Math.toDegrees(Math.atan2(-(cx - x), cz - z));
            desired = yawCmd + Mth.wrapDegrees(toward - yawCmd);
            rate = 30;
        } else if (--headingLeft <= 0) {
            headingLeft = 5 + rng.nextInt(26);
            desired = yawCmd + (rng.nextFloat() * 180 - 90);
            rate = new float[]{3, 6, 12, 20, 30}[rng.nextInt(5)];
        }
        turn();
    }

    private void turn() {
        float d = desired - yawCmd;
        yawCmd += Math.max(-rate, Math.min(rate, d));
    }

    private void keys(boolean f, boolean s, boolean j) {
        fwd = f;
        sprint = s;
        jump = j;
    }

    /** The keys and yaw, as PhysicsTravel sets them. */
    private void apply(LocalPlayer p) {
        InputOverrideHandler in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        in.setInputForceState(Input.MOVE_FORWARD, fwd);
        in.setInputForceState(Input.SPRINT, sprint);
        in.setInputForceState(Input.JUMP, jump);
        // the handler swaps its input in only while a move key is forced, and it ran before this listener
        if (p.input.getClass() != PlayerMovementInput.class) {
            try {
                p.input = PMI.newInstance(in);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        p.setYRot(yawCmd);
        baritone.getLookBehavior().updateTarget(new Rotation(yawCmd, 0), false);
    }

    private void finish(Minecraft mc) {
        state = 5;
        baritone.getInputOverrideHandler().clearAllKeys();
        try {
            csv.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (PredictionTolerance.Mode m : PredictionTolerance.Mode.values()) {
            List<Double> v = new ArrayList<>(usable.get(m));
            Collections.sort(v);
            log(String.format(Locale.ROOT, "mode=%s n=%d p50=%.4f p95=%.4f p99=%.4f max=%.4f", m, v.size(),
                    q(v, 0.50), q(v, 0.95), q(v, 0.99), q(v, 1.0)));
        }
        log("excluded=" + excluded + " runTicks=" + runTicks + " segments=" + segIndex + " falls=" + falls + " fellOff=" + fellOff + " deaths=" + deaths);
        log("done seed=" + SEED);
        if (Boolean.getBoolean("ostinato.tolerancetrace.exit")) mc.stop();
    }

    private static double q(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return Double.NaN;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1)));
    }

    private static ServerPlayer player(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
    }
}
