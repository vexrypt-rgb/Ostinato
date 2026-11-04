/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.input.Input;
import baritone.pathing.RouteReuse;
import baritone.pathing.calc.MaestroPathFinder;
import baritone.pathing.legato.LegatoFailures;
import baritone.process.BuilderProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in live A/B of Cadenza in a fresh, disposable survival world (-Dostinato.cadenzatest=true with the test harness
 * on). Never touches an existing save. Peaceful. Builds one small scenario arena high over spawn before every lap and
 * runs the real process on it (mine, get-to-block with open on arrival, build) in three arms:
 * <ul>
 * <li>A: Cadenza off (the existing goals);</li>
 * <li>B: Cadenza on;</li>
 * <li>C: Cadenza on with Legato smoothing.</li>
 * </ul>
 * With -Dostinato.cadenzatest.arms=combined the arms are 1 stock, 2 Cadenza, 3 Maestro, 4 Cadenza and Maestro, 5 Cadenza,
 * Maestro and Legato smoothing. With -Dostinato.cadenzatest.arms=v2 they are Cadenza v2's: A stock, B Cadenza v1, C v1
 * with cadenzaTurnAware, D C with cadenzaBatch (no Legato in any of them).
 * Presto, Legato search and kinematicLine stay off in every arm. Each repetition runs every scenario once per arm, the
 * arm order rotated by repetition and scenario (a Latin square). A lap is DONE when the world shows the task done
 * (every target block broken, the container screen open, every block placed); DIED, FELL and TIMEOUT are failures.
 */
public final class CadenzaLiveTest implements AbstractGameEventListener {
    private static final int REPS = Integer.getInteger("ostinato.cadenzatest.reps", 4), TIMEOUT = 1200;
    private static final boolean DEBUG = Boolean.getBoolean("ostinato.cadenzatest.debug");
    /**
     * Per-cycle trace (CADENZATEST trace ...): a line whenever the controlling process, the goal, the path, the mining
     * selection or the interaction changes, and every path event, each with the lap tick, feet and heading.
     */
    private static final boolean TRACE = Boolean.getBoolean("ostinato.cadenzatest.trace");
    private String traceSig = "";
    /** The combined Cadenza x Maestro arms (see the class comment), else Cadenza's own A/B/C. */
    private static final boolean COMBINED = "combined".equals(System.getProperty("ostinato.cadenzatest.arms"));
    /** Cadenza v2's arms (see the class comment). */
    private static final boolean V2 = "v2".equals(System.getProperty("ostinato.cadenzatest.arms"));
    /**
     * Cadenza v2.1's dropped-item control: A stock, B stock with mineScanDroppedItems off, C Cadenza (v1 + the aim fix,
     * arm B of v2), D C with mineScanDroppedItems off.
     */
    private static final boolean V21MINE = "v21mine".equals(System.getProperty("ostinato.cadenzatest.arms"));
    /** With v21mine: C and D also get cadenzaTurnAware + cadenzaBatch (v2's arm D), where v2 saw MULTI's long laps. */
    private static final boolean V21MINE_BATCH = V21MINE && Boolean.getBoolean("ostinato.cadenzatest.v21mine.batch");
    /**
     * With v21mine: the final validation arms instead, A stock, B Cadenza v2 pre-fix (both v2.1 fixes off), C Cadenza
     * v2.1, D C with mineScanDroppedItems off. Cadenza is v2's arm B (v1 + the aim fix, no turn model, no batch).
     */
    private static final boolean V21MINE_PREFIX = V21MINE && Boolean.getBoolean("ostinato.cadenzatest.v21mine.prefix");
    /**
     * Cadenza v2.1's Builder A/B: A stock, B v2 (cadenzaBuilder + cadenzaTurnAware) with the v2.1 memo fix off (v2
     * pre-fix), C the same with it on (v2.1), D v1 (cadenzaBuilder, no turn model) with it on.
     */
    private static final boolean V21PLACE = "v21place".equals(System.getProperty("ostinato.cadenzatest.arms"));
    /**
     * v2.1 post-hoc diagnostic (not pre-registered): a MINE lap is DONE only once the blocks are gone and no gold item
     * is left in the arena (the miner keeps its dropped items as goals), capped at DRAIN_MAX ticks after the blocks.
     * Without it a lap ends at the last break, so items still on the ground were never given the chance. (Waiting for
     * the miner to finish does not work: exploreForBlocks, on by default, keeps it running.)
     */
    private static final boolean DRAIN = Boolean.getBoolean("ostinato.cadenzatest.drain");
    private static final int DRAIN_MAX = 300;
    private static final String[] ARMS = COMBINED ? new String[]{"1", "2", "3", "4", "5"} : V2 || V21MINE || V21PLACE ? new String[]{"A", "B", "C", "D"} : new String[]{"A", "B", "C"};
    /** Floor top at F - 1, feet at F; the arena spans 0..20 in x and z. */
    static final int F = 200, SIZE = 20;
    static final int SX = 2, SZ = 10;

    enum Task { MINE, OPEN, PLACE }

    /** One arena: what is built and what the task is. Targets are what must be broken / opened / placed. */
    enum Scenario {
        EXPOSED(Task.MINE),
        WALL(Task.MINE),
        ABOVE(Task.MINE),
        BELOW(Task.MINE),
        MULTI(Task.MINE),
        CLUSTER(Task.MINE),
        OCCLUDED(Task.MINE),
        HAZARD(Task.MINE),
        CHEST(Task.OPEN),
        CHEST_HIGH(Task.OPEN),
        PLACE_ROW(Task.PLACE),
        PLACE_HIGH(Task.PLACE),
        /** EXPOSED with a cow that cannot move (NoAI) on the near side of the ore. Opt-in only (.only=MOB). */
        MOB(Task.MINE),
        // --- Cadenza v2 (opt-in by name: .only=...) ---
        /** EXPOSED starting faced away (yaw 90, west). */
        EXPOSED_BACK(Task.MINE, 90),
        /** Two pairs in the wall twelve apart: no cell reaches both pairs. */
        TWO_POSE(Task.MINE),
        /** Two in a line into the wall: the second is only seen once the first is gone. */
        VISIBILITY(Task.MINE),
        /** CLUSTER, one of the four turned to stone 25 ticks into the lap (the target vanishes). */
        INVALIDATE(Task.MINE),
        /** Ores on a raised step, on the floor and sunk into it. */
        MIXED_MINE(Task.MINE),
        /** PLACE_ROW starting faced away (yaw 90, west). */
        PLACE_ROW_BACK(Task.PLACE, 90),
        /** Two holes in the floor, a block below the feet. */
        PLACE_BELOW(Task.PLACE),
        /** Three cells around the approach: north, south and beyond. */
        PLACE_SPREAD(Task.PLACE),
        /** PLACE_ROW down a two-high trench open only to the west. */
        PLACE_TRENCH(Task.PLACE),
        /** Cells on a raised step and on the floor beside it. */
        PLACE_MIXED(Task.PLACE);

        final Task task;
        /** The yaw the lap starts at (-90: facing the arena, east). */
        final float startYaw;

        Scenario(Task task) {
            this(task, -90);
        }

        Scenario(Task task, float startYaw) {
            this.task = task;
            this.startYaw = startYaw;
        }

        /** Opt-in only: run when named in .only. */
        boolean optIn() {
            return this == MOB || ordinal() > MOB.ordinal();
        }

        /** Target cells (mine: gold blocks; open: the chest; place: the cells to fill). */
        List<BlockPos> targets() {
            return switch (this) {
                case EXPOSED, HAZARD, MOB, EXPOSED_BACK -> List.of(new BlockPos(12, F, 10));
                case WALL -> List.of(new BlockPos(13, F + 1, 10));
                case ABOVE -> List.of(new BlockPos(12, F + 3, 10));
                case BELOW -> List.of(new BlockPos(12, F - 1, 10));
                case MULTI -> List.of(new BlockPos(11, F, 6), new BlockPos(11, F, 14), new BlockPos(15, F, 10));
                case CLUSTER, INVALIDATE -> List.of(new BlockPos(13, F, 9), new BlockPos(13, F + 1, 10), new BlockPos(13, F + 2, 11), new BlockPos(13, F, 11));
                case TWO_POSE -> List.of(new BlockPos(13, F, 4), new BlockPos(13, F + 1, 4), new BlockPos(13, F, 16), new BlockPos(13, F + 1, 16));
                case VISIBILITY -> List.of(new BlockPos(13, F + 1, 10), new BlockPos(14, F + 1, 10));
                case MIXED_MINE -> List.of(new BlockPos(10, F + 1, 6), new BlockPos(15, F, 14), new BlockPos(6, F - 1, 13));
                case OCCLUDED -> List.of(new BlockPos(12, F + 1, 10));
                case CHEST -> List.of(new BlockPos(12, F, 10));
                case CHEST_HIGH -> List.of(new BlockPos(12, F + 2, 10));
                case PLACE_ROW, PLACE_ROW_BACK, PLACE_TRENCH -> List.of(new BlockPos(12, F, 10), new BlockPos(13, F, 10), new BlockPos(14, F, 10));
                case PLACE_BELOW -> List.of(new BlockPos(12, F - 1, 10), new BlockPos(14, F - 1, 10));
                case PLACE_SPREAD -> List.of(new BlockPos(12, F, 7), new BlockPos(12, F, 13), new BlockPos(15, F, 10));
                case PLACE_MIXED -> List.of(new BlockPos(10, F + 1, 8), new BlockPos(10, F + 1, 12), new BlockPos(13, F, 10));
                // head height: Builder only places up to one above the feet, so F + 2 (the first try) failed in every arm
                case PLACE_HIGH -> List.of(new BlockPos(12, F + 1, 10));
            };
        }

        /** Lays the arena down (server thread). */
        void build(ServerLevel level) {
            BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
            for (int x = -3; x <= SIZE + 3; x++) {
                for (int z = -3; z <= SIZE + 3; z++) {
                    for (int y = F - 4; y <= F + 8; y++) {
                        boolean floor = y >= F - 3 && y <= F - 1 && x >= 0 && x <= SIZE && z >= 0 && z <= SIZE;
                        level.setBlock(new BlockPos(x, y, z), floor ? stone : air, 2);
                    }
                }
            }
            switch (this) {
                case WALL, CLUSTER, INVALIDATE, TWO_POSE, VISIBILITY -> box(level, 13, F, 0, 15, F + 4, SIZE, stone);
                case MIXED_MINE -> {
                    box(level, 8, F, 4, 12, F, 16, stone); // a one-high step
                    box(level, 15, F, 13, 16, F + 1, 15, stone); // a lump the floor ore sits in
                }
                case PLACE_TRENCH -> {
                    box(level, 11, F, 9, 15, F + 1, 9, stone);
                    box(level, 11, F, 11, 15, F + 1, 11, stone);
                    box(level, 15, F, 10, 15, F + 1, 10, stone);
                }
                case PLACE_MIXED -> box(level, 8, F, 6, 11, F, 14, stone);
                case ABOVE -> box(level, 7, F + 3, 5, 17, F + 3, 15, stone);
                case OCCLUDED -> box(level, 11, F, 9, 13, F + 2, 11, stone);
                case HAZARD -> box(level, 11, F - 1, 9, 11, F - 1, 11, Blocks.LAVA.defaultBlockState());
                case CHEST -> {
                    // open only to the east (away from the start); nothing on top
                    box(level, 11, F, 9, 11, F + 1, 11, stone);
                    box(level, 12, F, 9, 12, F + 1, 9, stone);
                    box(level, 12, F, 11, 12, F + 1, 11, stone);
                }
                case CHEST_HIGH -> box(level, 12, F, 10, 12, F + 1, 10, stone);
                case PLACE_HIGH -> box(level, 13, F, 10, 13, F + 3, 10, stone);
                default -> {
                }
            }
            for (BlockPos t : targets()) {
                switch (task) {
                    case MINE -> level.setBlock(t, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
                    case OPEN -> level.setBlock(t, Blocks.CHEST.defaultBlockState(), 2);
                    case PLACE -> level.setBlock(t, air, 2);
                }
            }
            // no entity left over from earlier laps (drops, animals): one in the line of sight hides the target
            level.getEntitiesOfClass(Entity.class, new AABB(-3, F - 4, -3, SIZE + 4, F + 9, SIZE + 4), e -> !(e instanceof Player)).forEach(Entity::discard);
            if (this == MOB) {
                Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                cow.setNoAi(true);
                cow.snapTo(11.5, F, 10.5, 90, 0);
                level.addFreshEntity(cow);
            }
        }

        private static void box(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) level.setBlock(new BlockPos(x, y, z), s, 2);
        }
    }

    private record Lap(int rep, Scenario scenario, int arm) {}

    private final Baritone baritone;
    private final List<Lap> laps = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    /** DONE lap ticks by scenario, repetition and arm. */
    private final Map<String, Long> done = new HashMap<>();
    private int state, wait, index, step;
    private volatile boolean ready;
    private long ticks, lapStart, firstDone, blocksAt = -1;
    private BufferedWriter out;

    private volatile int positionPackets;
    private int calcs, cancels, navTicks, clickTicks, idleTicks, posPackets0, stuckKeyLaps;
    private double travelled, turned, lastX, lastZ;
    /** Placement laps (v2 instrumentation): Builder's phase per tick, the turn made while aligning, and every arrival
     * (a tick that starts aligning after not placing) with the yaw / pitch still to turn then and the pitch held. */
    private int alignTicks, placeClickTicks, blockedTicks, noneTicks, arrivals, firstClick, blockedUnsafe, blockedAir, blockedDelay;
    private double alignTurn, arrYaw, arrPitch, arrPitchHeld;
    private boolean lastPlacing;
    /**
     * v2.1 exclusive tick accounting: every lap tick lands in exactly one category, driver (P pathing, C clicking,
     * I neither) x Builder phase (placement laps) x whether a path calculation is in progress (+calc), so the counts
     * sum to the lap ticks. Plus goal changes (the goal not equal to the last tick's), repositions (a tick pathing
     * right after a tick placing), and ticks with a calculation in progress.
     */
    private final java.util.TreeMap<String, Integer> cats = new java.util.TreeMap<>();
    private int goalChanges, repositions, calcTicks;
    /**
     * v2.1: goal changes split by whether the new goal holds the same set of sub-goals (only their order changed) or a
     * different one; and gold-block pickups (items gained during the lap).
     */
    private int goalOrderOnly, goalSetChanges, pickups, lastGold;
    /** Ticks where the Cadenza goal is equal to the last one but lists its extras in another order (v2.1: no longer a change). */
    private int reorders;
    private Object lastGoalSeen;
    private boolean lastPlacingForRepos;
    private float lastYaw;
    private long[] cadenza0;
    /** Solid arena cells at lap start (not targets): how many the lap dug out. */
    private List<BlockPos> solid0 = new ArrayList<>();

    private CadenzaLiveTest(Baritone b) {
        baritone = b;
        Scenario[] all = scenarios();
        for (int r = 0; r < REPS; r++) {
            for (int s = 0; s < all.length; s++) {
                // a Latin square: each arm takes each position once every ARMS.length reps (and scenarios)
                for (int k = 0; k < ARMS.length; k++) {
                    laps.add(new Lap(r, all[s], (k + r + s) % ARMS.length));
                }
            }
        }
    }

    /** -Dostinato.cadenzatest.only=EXPOSED,CHEST runs only those; MOB runs only when named. */
    private static Scenario[] scenarios() {
        String only = System.getProperty("ostinato.cadenzatest.only");
        if (only == null || only.isBlank()) return java.util.Arrays.stream(Scenario.values()).filter(s -> !s.optIn()).toArray(Scenario[]::new);
        List<Scenario> s = new ArrayList<>();
        for (String n : only.split(",")) s.add(Scenario.valueOf(n.trim().toUpperCase(Locale.ROOT)));
        return s.toArray(new Scenario[0]);
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.cadenzatest")) b.getGameEventHandler().registerEventListener(new CadenzaLiveTest(b));
    }

    private synchronized void log(String message) {
        System.out.println("CADENZATEST " + message);
        try {
            if (out == null) {
                out = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath().resolve("cadenzatest-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write("CADENZATEST " + message);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() == EventState.PRE && event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            positionPackets++;
        }
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (state == 4 && step == 1 && (event == PathEvent.CALC_STARTED || event == PathEvent.NEXT_SEGMENT_CALC_STARTED)) {
            calcs++;
        }
        if (TRACE && state == 4 && step == 1) {
            trace("event " + event);
        }
        if (state == 4 && step == 1 && event == PathEvent.CANCELED) {
            cancels++;
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
            arm(0);
            log("start laps=" + laps.size() + " reps=" + REPS + " scenarios=" + scenarios().length + " order=latin timeout=" + TIMEOUT
                    + " presto=" + Baritone.settings().presto.value + " legatoSearch=" + Baritone.settings().legatoSearch.value
                    + " kinematicLine=" + Baritone.settings().kinematicLine.value + " reach=" + mc.player.blockInteractionRange());
            server(mc, () -> {
                mc.getSingleplayerServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                mc.getSingleplayerServer().overworld().getGameRules().set(GameRules.SPAWN_MOBS, false, mc.getSingleplayerServer());
                // the arena first: the smoke run teleported onto air and died from the fall before lap 0
                scenarios()[0].build(mc.getSingleplayerServer().overworld());
                ServerPlayer p = player(mc);
                p.teleportTo(SX + 0.5, F, SZ + 0.5);
                p.setDeltaMovement(0, 0, 0);
                p.resetFallDistance();
            });
            wait = 80;
        } else if (state == 3 && ready && --wait <= 0) {
            next(mc);
        } else if (state == 4) {
            lap(mc);
        }
    }

    /** Arm settings; everything else at its default. */
    private static void arm(int arm) {
        var s = Baritone.settings();
        s.presto.value = false;
        s.legatoSearch.value = false;
        s.kinematicLine.value = false;
        boolean cadenza = COMBINED ? arm == 1 || arm >= 3 : V21MINE && !V21MINE_PREFIX ? arm >= 2 : arm >= 1;
        s.cadenza.value = cadenza;
        s.cadenzaBuilder.value = cadenza; // the A/B measures placement too
        s.maestro.value = COMBINED && arm >= 2;
        s.legato.value = !V2 && !V21MINE && !V21PLACE && (COMBINED ? arm == 4 : arm == 2);
        s.cadenzaTurnAware.value = V2 && arm >= 2 || V21PLACE && (arm == 1 || arm == 2) || V21MINE_BATCH && arm >= 2;
        // v2 pre-fix arm (B of v21place, and of v21mine with -Dostinato.cadenzatest.v21mine.prefix=true): both v2.1 fixes off
        boolean prefix = V21PLACE && arm == 1 || V21MINE_PREFIX && arm == 1;
        CadenzaSession.invalidateOnConfirm = !prefix;
        CadenzaGoal.orderedExtras = prefix;
        s.cadenzaBatch.value = V2 && arm == 3 || V21MINE_BATCH && arm >= 2;
        s.legatoSmoothing.value = true;
        s.cadenzaDebug.value = cadenza && DEBUG;
        s.mineScanDroppedItems.value = !(V21MINE && (V21MINE_PREFIX ? arm == 3 : arm == 1 || arm == 3));
    }

    private void next(Minecraft mc) {
        if (index >= laps.size()) {
            finish();
            return;
        }
        Lap l = laps.get(index);
        arm(0);
        String held = heldKeys();
        if (!held.isEmpty()) {
            stuckKeyLaps++;
            log("keys still held after lap " + (index - 1) + ": " + held);
        }
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof TitleScreen)) {
            mc.player.closeContainer();
        }
        if (mc.player.isDeadOrDying()) {
            // respawn is a round trip to the server: set the lap up once the new player is alive
            mc.player.respawn();
            return;
        }
        ready = false;
        server(mc, () -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            l.scenario().build(level);
            ServerPlayer p = player(mc);
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.clearFire();
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            if (l.scenario().task == Task.PLACE) {
                p.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 64));
            }
            p.getInventory().setSelectedSlot(0);
            // full resync: a lap whose placements the server refused leaves the client's stack predicted away, and
            // setting the same stack again sends nothing (the smoke run's later placement laps started with no blocks)
            p.inventoryMenu.sendAllDataToRemote();
            p.teleportTo(SX + 0.5, F, SZ + 0.5);
            p.setDeltaMovement(0, 0, 0);
            p.resetFallDistance();
        });
        // the held slot too: the server-side select above is never sent to the client, which kept the previous lap's
        // block slot, never re-sent it, and had every placement refused (the server held the pickaxe) as a ghost
        mc.player.getInventory().setSelectedSlot(0);
        state = 4;
        step = 0;
        wait = 40;
    }

    private void lap(Minecraft mc) {
        if (!ready) return;
        Lap l = laps.get(index);
        var player = mc.player;
        if (step == 0) {
            if (--wait > 0) return;
            arm(l.arm());
            RouteReuse.clear();
            LegatoFailures.clear();
            player.setYRot(l.scenario().startYaw);
            player.setXRot(0);
            cadenza0 = cadenzaCounters();
            posPackets0 = positionPackets;
            calcs = cancels = navTicks = clickTicks = idleTicks = 0;
            travelled = turned = 0;
            alignTicks = placeClickTicks = blockedTicks = noneTicks = arrivals = blockedUnsafe = blockedAir = blockedDelay = 0;
            firstClick = -1;
            alignTurn = arrYaw = arrPitch = arrPitchHeld = 0;
            lastPlacing = false;
            cats.clear();
            goalChanges = repositions = calcTicks = 0;
            goalOrderOnly = goalSetChanges = pickups = lastGold = reorders = 0;
            lastGoalSeen = null;
            lastPlacingForRepos = false;
            BuilderProcess.lastPlacePhase = BuilderProcess.PlacePhase.OTHER;
            firstDone = -1;
            lastX = player.getX();
            lastZ = player.getZ();
            lastYaw = player.getYRot();
            solid0 = solidCells(mc, l.scenario());
            lapStart = ticks;
            blocksAt = -1;
            start(l.scenario());
            step = 1;
            return;
        }
        double x = player.getX(), y = player.getY(), z = player.getZ();
        double stepXZ = Math.hypot(x - lastX, z - lastZ), dYaw = Math.abs(Mth.wrapDegrees(player.getYRot() - lastYaw));
        travelled += stepXZ;
        turned += dYaw;
        lastX = x;
        lastZ = z;
        lastYaw = player.getYRot();
        boolean clicking = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT) || baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT);
        boolean pathing = baritone.getPathingBehavior().isPathing();
        if (pathing) navTicks++;
        else if (clicking) clickTicks++;
        else idleTicks++;
        boolean calcNow = baritone.getPathingBehavior().getInProgress().isPresent();
        if (calcNow) calcTicks++;
        String cat = (pathing ? "P" : clicking ? "C" : "I") + (l.scenario().task == Task.PLACE ? "." + BuilderProcess.lastPlacePhase : "") + (calcNow ? "+calc" : "");
        cats.merge(cat, 1, Integer::sum);
        Object goalNow = baritone.getPathingBehavior().getGoal();
        if (goalNow != null && lastGoalSeen != null && !goalNow.equals(lastGoalSeen)) {
            goalChanges++;
            if (canon(goalNow).equals(canon(lastGoalSeen))) goalOrderOnly++;
            else goalSetChanges++;
        }
        if (goalNow instanceof CadenzaGoal gn && lastGoalSeen instanceof CadenzaGoal gl && gn != gl && gn.equals(gl)
                && !java.util.Arrays.equals(gn.extras(), gl.extras())) reorders++;
        int gold = player.getInventory().countItem(Items.GOLD_BLOCK);
        if (gold > lastGold) pickups += gold - lastGold;
        lastGold = gold;
        if (goalNow != null) lastGoalSeen = goalNow;
        if (l.scenario().task == Task.PLACE) {
            var ph = BuilderProcess.lastPlacePhase;
            boolean placingNow = ph == BuilderProcess.PlacePhase.ALIGN || ph == BuilderProcess.PlacePhase.CLICK;
            if (pathing && lastPlacingForRepos) repositions++;
            lastPlacingForRepos = placingNow || (lastPlacingForRepos && !pathing);
        }
        if (l.scenario().task == Task.PLACE) {
            var phase = BuilderProcess.lastPlacePhase;
            switch (phase) {
                case ALIGN -> {
                    alignTicks++;
                    alignTurn += dYaw;
                }
                case CLICK -> {
                    placeClickTicks++;
                    alignTurn += dYaw;
                    if (firstClick < 0) firstClick = (int) (ticks - lapStart);
                }
                case BLOCKED -> {
                    blockedTicks++;
                    int why = BuilderProcess.lastPlaceBlocked;
                    if ((why & 1) != 0) blockedUnsafe++;
                    if ((why & 2) != 0) blockedAir++;
                    if ((why & 4) != 0) blockedDelay++;
                }
                case NONE -> noneTicks++;
                default -> {
                }
            }
            boolean placing = phase == BuilderProcess.PlacePhase.ALIGN || phase == BuilderProcess.PlacePhase.CLICK;
            if (placing && !lastPlacing) {
                arrivals++;
                arrYaw += BuilderProcess.lastPlaceYawErr;
                arrPitch += BuilderProcess.lastPlacePitchErr;
                arrPitchHeld += player.getXRot();
            }
            lastPlacing = placing;
        }
        if (TRACE) {
            traceTick(Minecraft.getInstance(), l.scenario());
        }
        if (l.scenario() == Scenario.INVALIDATE && ticks - lapStart == 25) {
            // the target vanishes mid-lap: whatever was planned for it must be dropped, not waited on
            server(mc, () -> mc.getSingleplayerServer().overworld().setBlock(new BlockPos(13, F + 2, 11), Blocks.STONE.defaultBlockState(), 2));
        }
        boolean complete = complete(mc, l.scenario());
        if (DRAIN && complete && l.scenario().task == Task.MINE) {
            if (blocksAt < 0) blocksAt = ticks;
            boolean goldOnGround = !mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new AABB(-3, F - 4, -3, SIZE + 4, F + 9, SIZE + 4), e -> e.getItem().is(Items.GOLD_BLOCK)).isEmpty();
            if (goldOnGround && ticks - blocksAt < DRAIN_MAX) complete = false;
        }
        boolean fell = y < F - 6;
        boolean died = player.isDeadOrDying();
        long took = ticks - lapStart;
        if (complete || fell || died || took > TIMEOUT) {
            baritone.getPathingBehavior().cancelEverything();
            String result = complete ? "DONE" : died ? "DIED" : fell ? "FELL" : "TIMEOUT";
            long[] c = cadenzaCounters();
            for (int i = 0; i < c.length; i++) c[i] -= cadenza0[i];
            int dug = 0;
            for (BlockPos p : solid0) if (mc.level.getBlockState(p).isAir()) dug++;
            int left = remaining(mc, l.scenario());
            // placement bookkeeping: cobblestone that ended up outside the target cells, and what is still held
            int stray = 0;
            List<BlockPos> targets = l.scenario().targets();
            for (int sx = -3; sx <= SIZE + 3; sx++) for (int sy = F - 3; sy <= F + 8; sy++) for (int sz = -3; sz <= SIZE + 3; sz++) {
                BlockPos q = new BlockPos(sx, sy, sz);
                if (mc.level.getBlockState(q).is(Blocks.COBBLESTONE) && !targets.contains(q)) stray++;
            }
            int held = player.getInventory().countItem(Items.COBBLESTONE);
            // living non-players left in the arena, and their health: MOB's cow alive, hurt or killed
            List<net.minecraft.world.entity.LivingEntity> mobs = mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new AABB(-3, F - 4, -3, SIZE + 4, F + 9, SIZE + 4), e -> !(e instanceof Player));
            float mobHp = (float) mobs.stream().mapToDouble(net.minecraft.world.entity.LivingEntity::getHealth).sum();
            String row = String.format(Locale.ROOT, "lap %d rep=%d scenario=%s task=%s arm=%s result=%s ticks=%d nav=%d click=%d idle=%d"
                            + " travelled=%.2f turned=%.0f calcs=%d cancels=%d posPackets=%d dug=%d left=%d stray=%d held=%d x=%.2f y=%.2f z=%.2f"
                            + " goals=%d noCandidate=%d attempted=%d confirmed=%d failed=%d invalidated=%d poseUnusable=%d charges=%d cadenzaMs=%d mobs=%d mobHp=%.1f"
                            + " align=%d pclick=%d blocked=%d none=%d alignTurn=%.0f arrivals=%d arrYaw=%.0f arrPitch=%.0f arrPitchHeld=%.0f firstClick=%d"
                            + " bUnsafe=%d bAir=%d bDelay=%d startYaw=%.0f goalChanges=%d repos=%d calcTicks=%d"
                            + " orderOnly=%d setChanges=%d reorders=%d gold=%d pickups=%d groundItems=%d scan=%s cats=%s",
                    index, l.rep(), l.scenario(), l.scenario().task, ARMS[l.arm()], result, took, navTicks, clickTicks, idleTicks,
                    travelled, turned, calcs, cancels, positionPackets - posPackets0, dug, left, stray, held, x, y, z,
                    c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8] / 1_000_000, mobs.size(), mobHp,
                    alignTicks, placeClickTicks, blockedTicks, noneTicks, alignTurn, arrivals, arrYaw, arrPitch, arrPitchHeld, firstClick,
                    blockedUnsafe, blockedAir, blockedDelay, l.scenario().startYaw, goalChanges, repositions, calcTicks,
                    goalOrderOnly, goalSetChanges, reorders, player.getInventory().countItem(Items.GOLD_BLOCK), pickups,
                    mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(-3, F - 4, -3, SIZE + 4, F + 9, SIZE + 4)).size(),
                    Baritone.settings().mineScanDroppedItems.value,
                    cats.entrySet().stream().map(en -> en.getKey() + ":" + en.getValue()).collect(java.util.stream.Collectors.joining(",")));
            if (DRAIN) row += " blocksDone=" + (blocksAt < 0 ? -1 : blocksAt - lapStart) + " drainCapped=" + (blocksAt >= 0 && ticks - blocksAt >= DRAIN_MAX);
            rows.add(row);
            log(row);
            if (complete) done.put(l.scenario() + "/" + l.rep() + "/" + l.arm(), took);
            if (mc.gui.screen() != null) player.closeContainer();
            arm(0);
            index++;
            state = 3;
            wait = 20;
        }
    }

    /** A goal's sub-goals as a set (recursively), so two goals that differ only in order compare equal. */
    private static Object canon(Object g) {
        if (g instanceof CadenzaGoal c) {
            return List.of("cadenza", c.options(), new java.util.HashSet<>(c.results()), canonAll(c.extras()));
        }
        if (g instanceof baritone.api.pathing.goals.GoalComposite c) {
            return List.of("composite", canonAll(c.goals()));
        }
        return g;
    }

    private static java.util.Set<Object> canonAll(baritone.api.pathing.goals.Goal[] gs) {
        java.util.Set<Object> s = new java.util.HashSet<>();
        for (baritone.api.pathing.goals.Goal x : gs) s.add(canon(x));
        return s;
    }

    private void start(Scenario s) {
        switch (s.task) {
            case MINE -> baritone.getMineProcess().mine(0, Blocks.GOLD_BLOCK);
            case OPEN -> baritone.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(Blocks.CHEST));
            case PLACE -> {
                List<BlockPos> t = s.targets();
                int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
                for (BlockPos p : t) {
                    x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
                    x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
                }
                BlockPos lo = new BlockPos(x0, y0, z0);
                int wx = x1 - x0 + 1, hy = y1 - y0 + 1, lz = z1 - z0 + 1;
                // only the target cells (a fill of the bounding box would also fill the cells between spread targets)
                baritone.getBuilderProcess().build("cadenza-" + s, new baritone.api.schematic.ISchematic() {
                    @Override
                    public boolean inSchematic(int x, int y, int z, BlockState current) {
                        return t.contains(lo.offset(x, y, z));
                    }

                    @Override
                    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
                        return Blocks.COBBLESTONE.defaultBlockState();
                    }

                    @Override
                    public int widthX() { return wx; }

                    @Override
                    public int heightY() { return hy; }

                    @Override
                    public int lengthZ() { return lz; }
                }, lo);
            }
        }
    }

    private static int remaining(Minecraft mc, Scenario s) {
        int n = 0;
        for (BlockPos p : s.targets()) {
            BlockState b = mc.level.getBlockState(p);
            switch (s.task) {
                case MINE -> n += b.is(Blocks.GOLD_BLOCK) ? 1 : 0;
                case PLACE -> n += b.is(Blocks.COBBLESTONE) ? 0 : 1;
                case OPEN -> n += 1;
            }
        }
        if (s.task == Task.OPEN && !(mc.player.containerMenu instanceof InventoryMenu)) n = 0;
        return n;
    }

    private void trace(String what) {
        var p = Minecraft.getInstance().player;
        log(String.format(java.util.Locale.ROOT, "trace t=%d feet=%s yaw=%.0f pitch=%.0f %s", ticks - lapStart,
                baritone.getPlayerContext().playerFeet().toShortString(), Mth.wrapDegrees(p.getYRot()), p.getXRot(), what));
    }

    /** One trace line when anything the gate asks about changed since the last one. */
    private void traceTick(Minecraft mc, Scenario s) {
        var ctx = baritone.getPlayerContext();
        var pb = baritone.getPathingBehavior();
        BlockPos feet = ctx.playerFeet();
        var goal = pb.getGoal();
        var cur = pb.getCurrent();
        String proc = baritone.getPathingControlManager().mostRecentInControl().map(q -> q.getClass().getSimpleName()).orElse("-");
        String cmd = baritone.getPathingControlManager().mostRecentCommand().map(c -> c.commandType.toString()).orElse("-");
        String g = goal == null ? "-" : (goal instanceof CadenzaGoal cg ? "cadenza#" + Integer.toHexString(cg.hashCode()) + " cells " + cg.cellCount()
                + " cand " + cg.isCandidate(feet.getX(), feet.getY(), feet.getZ()) : goal.getClass().getSimpleName() + "#" + Integer.toHexString(goal.hashCode()))
                + " in " + goal.isInGoal(feet);
        String path = cur == null ? "-" : cur.getPath().getDest().toShortString() + (cur.getPath().getGoal() == goal ? "" : cur.getPath().getGoal().equals(goal) ? " goal=" : " goal!=")
                + (goal instanceof CadenzaGoal cg && cg.isCandidate(cur.getPath().getDest().x, cur.getPath().getDest().y, cur.getPath().getDest().z) ? " destCand" : "");
        String mine = s.task == Task.MINE ? baritone.getMineProcess().cadenzaTrace() : BuilderProcess.lastPlacePhase.toString();
        String sel = ctx.getSelectedBlock().map(BlockPos::toShortString).orElse("-");
        boolean click = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT) || baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT);
        String sig = proc + "|" + cmd + "|" + g + "|" + path + "|" + pb.getInProgress().isPresent() + "|" + mine.replaceAll(" t -?\\d+/", " t ") + "|" + sel + "|" + click
                + "|" + remaining(mc, s);
        if (!sig.equals(traceSig)) {
            traceSig = sig;
            trace("proc " + proc + " cmd " + cmd + " goal " + g + " path " + path + " calc " + pb.getInProgress().isPresent() + " left " + remaining(mc, s)
                    + " crosshair " + sel + " click " + click + " | " + mine);
        }
        String gave = s.task == Task.MINE ? baritone.getMineProcess().cadenzaGaveUp : "";
        if (!gave.isEmpty()) {
            trace("GAVE_UP " + gave);
            baritone.getMineProcess().cadenzaGaveUp = "";
        }
    }

    private static boolean complete(Minecraft mc, Scenario s) {
        return remaining(mc, s) == 0;
    }

    private static List<BlockPos> solidCells(Minecraft mc, Scenario s) {
        List<BlockPos> out = new ArrayList<>();
        List<BlockPos> t = s.targets();
        for (int x = 0; x <= SIZE; x++) {
            for (int z = 0; z <= SIZE; z++) {
                for (int y = F - 3; y <= F + 6; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!t.contains(p) && !mc.level.getBlockState(p).isAir() && mc.level.getBlockState(p).getFluidState().isEmpty()) out.add(p);
                }
            }
        }
        return out;
    }

    private static long[] cadenzaCounters() {
        return new long[]{CadenzaStats.goals(), CadenzaStats.phases(CadenzaStats.Phase.NO_CANDIDATE), CadenzaStats.phases(CadenzaStats.Phase.ATTEMPTED),
                CadenzaStats.phases(CadenzaStats.Phase.CONFIRMED), CadenzaStats.phases(CadenzaStats.Phase.FAILED),
                CadenzaStats.phases(CadenzaStats.Phase.INVALIDATED), CadenzaStats.phases(CadenzaStats.Phase.POSE_UNUSABLE),
                CadenzaStats.terminalCharges(), CadenzaStats.nanos()};
    }

    private String heldKeys() {
        StringBuilder b = new StringBuilder();
        for (Input in : Input.values()) {
            if (baritone.getInputOverrideHandler().isInputForcedDown(in)) b.append(b.length() == 0 ? "" : ",").append(in);
        }
        return b.toString();
    }

    private void finish() {
        for (Scenario s : scenarios()) {
            for (int a = 0; a < ARMS.length; a++) {
                String tag = " scenario=" + s + " ", armTag = " arm=" + ARMS[a] + " ";
                int n = 0, ok = 0;
                List<Long> t = new ArrayList<>();
                for (String r : rows) {
                    if (!r.contains(tag) || !r.contains(armTag)) continue;
                    n++;
                    if (r.contains("result=DONE")) {
                        ok++;
                        t.add(Long.parseLong(r.replaceAll(".* ticks=(\\d+) .*", "$1")));
                    }
                }
                Collections.sort(t);
                log(String.format(Locale.ROOT, "summary scenario=%s arm=%s laps=%d done=%d ticks median=%s min=%s max=%s", s, ARMS[a], n, ok,
                        t.isEmpty() ? "-" : t.get(t.size() / 2), t.isEmpty() ? "-" : t.getFirst(), t.isEmpty() ? "-" : t.getLast()));
            }
            for (int a = 1; a < ARMS.length; a++) {
                List<Double> ratios = new ArrayList<>();
                for (int r = 0; r < REPS; r++) {
                    Long base = done.get(s + "/" + r + "/0"), arm = done.get(s + "/" + r + "/" + a);
                    if (base != null && arm != null) ratios.add(arm / (double) base);
                }
                Collections.sort(ratios);
                log(String.format(Locale.ROOT, "paired scenario=%s arm=%s pairs=%d medianRatio=%s ratios=%s", s, ARMS[a], ratios.size(),
                        ratios.isEmpty() ? "-" : String.format(Locale.ROOT, "%.3f", ratios.get(ratios.size() / 2)), ratios));
            }
        }
        log(CadenzaStats.line());
        if (COMBINED) { // proof that the Maestro arms searched with Maestro, not only that the switch was on
            log("maestro searches=" + MaestroPathFinder.searches.get() + " fallbacks=" + MaestroPathFinder.fallbacks.get()
                    + " capped=" + MaestroPathFinder.capped.get() + " exceptions=" + MaestroPathFinder.exceptions.get());
        }
        log("keys stuck after " + stuckKeyLaps + " laps");
        arm(0);
        Baritone.settings().cadenza.value = false;
        Baritone.settings().cadenzaBuilder.value = false;
        Baritone.settings().legato.value = false;
        Baritone.settings().cadenzaTurnAware.value = false;
        Baritone.settings().cadenzaBatch.value = false;
        Baritone.settings().mineScanDroppedItems.value = true;
        CadenzaSession.invalidateOnConfirm = true;
        CadenzaGoal.orderedExtras = false;
        state = 5;
        if (Boolean.getBoolean("ostinato.cadenzatest.exit")) Minecraft.getInstance().stop();
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
