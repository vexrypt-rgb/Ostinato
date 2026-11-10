/*
 * Ostinato 2.0 Phase 10: new file. Not part of upstream Baritone.
 */
package baritone.harness;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.input.Input;
import baritone.gui.tasks.StepType;
import baritone.gui.tasks.TaskList;
import baritone.gui.tasks.TaskRunner;
import baritone.gui.tasks.TaskStep;
import baritone.gui.tasks.game.BaritoneTaskAdapter;
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato 2.0 Phase 10: the Phase 08 navigate -> interact -> verify check on a second, natural arena
 * (-Dostinato.interactterrain=true, with the test harness on). The Phase 08 harness ({@link InteractLiveTest}) is left
 * as it was, so its results stay reproducible.
 * <p>
 * Fresh disposable survival world, Peaceful. The arena is the generated terrain around the player's spawn: a box of
 * {@link #R} blocks each way is copied once at setup (after every container block in it is removed) and put back before
 * every lap, so both arms see the same ground whatever the previous lap dug or placed. Each lap places, on the surface,
 * two chests, two furnaces, a blast furnace, an ender chest and a crafting table at fixed offsets from the start, so a
 * request has several candidates of one type, different distances and natural obstacles between.
 * <p>
 * Arms: A = {@code verifiedGetToBlock} off (legacy verdict), B = on; the order alternates by repetition and scenario.
 * The oracle is the <em>server-side</em> player's open menu (a chest by its block entity, the ender chest by the active
 * chest, the others by kind and distance to a fixture block); a new open is a new server menu object. Classes as in
 * Phase 08: TS, FS, TF, FF, HANG; a cancel gets a STOP row. The MAGMA scenario runs no task: it checks whether a key
 * forced while the idle player stands on magma is released after the player leaves it (B6).
 */
public final class InteractTerrainLiveTest implements AbstractGameEventListener {
    private static final int REPS = Integer.getInteger("ostinato.interactterrain.reps", 3), TIMEOUT = 600, POST = 40;
    private static final int R = 26, DOWN = 8, UP = 14;
    private static final String FLAG = "verifiedgettoblock";

    /** Fixture offsets (dx, dz) from the start column; y is the surface there. */
    private static final int[][] CHESTS = {{9, 5}, {-14, 11}}, FURNACES = {{-10, -15}, {21, 2}};
    private static final int[] BLAST = {15, 17}, ENDER = {-19, -3}, TABLE = {18, -12};

    enum Scenario {
        /** Two chests at different distances: either one opening is a success. */
        CHEST2,
        /** Two furnaces. */
        FURNACE2,
        /** A blast furnace. */
        BLAST,
        /** An ender chest, about 19 blocks away. */
        ENDER,
        /** A crafting table, about 22 blocks away. */
        TABLE,
        /** A chest-type menu the target is not (a plain container) is open when the run starts; target: furnace. */
        MENU_OPEN,
        /** Whichever fixture chest the player first comes within 3.5 blocks of turns to stone; the other remains. */
        VANISH_ONE,
        /** A sitting cat on the nearer chest (the server refuses it); the farther chest can be opened. Diagnostic. */
        CAT_ONE,
        /** The server thread stalls 400 ms when the right click is first held (answer within the 20-tick window). */
        LAG_SHORT,
        /** The server thread stalls 1600 ms when the right click is first held (answer after the 20-tick window). */
        LAG_LONG,
        /** runner.stop() 15 ticks in, then the same list again. */
        CANCEL_MOVE,
        /** runner.stop() on the first tick right click is held, then the same list again. */
        CANCEL_ACT,
        /** A custom goal is set 20 ticks in: the get-to-block process loses control. */
        PREEMPT,
        /** The chest list is replaced by a furnace list 15 ticks in. */
        REPLACE,
        /** No task: the idle player stands on magma 10 ticks, then is moved off it; keys checked 20 ticks later. */
        MAGMA,
        /**
         * Phase 10 diagnostic, only with {@code only=SURFACE}: no task; the idle player at the bottom of a 3x3 pool,
         * 7 deep, with little air. Keys are read 40 ticks after the air bar is full, and again 20 ticks after the
         * player is moved onto dry ground (B6: the surfacing JUMP).
         */
        SURFACE;

        String target() {
            switch (this) {
                case FURNACE2: case MENU_OPEN: return "furnace";
                case BLAST: return "blast_furnace";
                case ENDER: return "ender_chest";
                case TABLE: return "crafting_table";
                default: return "chest";
            }
        }
    }

    private record Lap(int rep, Scenario scenario, int arm) {}

    private final Baritone baritone;
    private final List<Lap> laps = new ArrayList<>();
    private BufferedWriter out;
    private int state, wait, index, step;
    private volatile boolean ready;
    private volatile int usePackets;
    private boolean flagPresent;

    // arena (server thread writes once at setup)
    private BlockPos origin;
    /** SURFACE: AirProcess seen in control, JUMP seen forced, the lap tick the air bar was full. */
    private boolean surfaceActive, surfaceJump;
    private long fullAt;
    private int minY;
    private BlockState[] snapshot;
    private volatile int restored, removedContainers, far;
    private final List<BlockPos> chests = new ArrayList<>(), furnaces = new ArrayList<>();
    private BlockPos blast, ender, table;

    // per lap
    private TaskRunner runner;
    private long lapTicks, verdictAt, stopAt, stepStart, runStart, runnerNs, runnerTicks, lagAt;
    private int lastIndex, usesAtStep, phase;
    private TaskRunner.StepStatus lastStatus;
    private String lastMenu = "none", opened = "-", post;
    private AbstractContainerMenu lastMenuRef;
    /** Lap tick a menu of each target kind last appeared (by kind name), or -1. */
    private final java.util.Map<String, Long> openedAt = new java.util.HashMap<>();
    private BlockPos vanished, catOn;
    /** Feet every 100 ticks of the current run: a HANG row shows whether the player was stuck or still walking. */
    private final StringBuilder trail = new StringBuilder();

    private InteractTerrainLiveTest(Baritone b) {
        baritone = b;
        Scenario[] all = scenarios();
        for (int r = 0; r < REPS; r++) {
            for (int s = 0; s < all.length; s++) {
                for (int k = 0; k < 2; k++) laps.add(new Lap(r, all[s], (k + r + s) % 2));
            }
        }
    }

    private static Scenario[] scenarios() {
        String only = System.getProperty("ostinato.interactterrain.only");
        if (only == null || only.isBlank()) {
            List<Scenario> all = new ArrayList<>(List.of(Scenario.values()));
            all.remove(Scenario.SURFACE); // a diagnostic, not part of the pre-registered runs
            return all.toArray(new Scenario[0]);
        }
        List<Scenario> s = new ArrayList<>();
        for (String n : only.split(",")) s.add(Scenario.valueOf(n.trim().toUpperCase(Locale.ROOT)));
        return s.toArray(new Scenario[0]);
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.interactterrain")) b.getGameEventHandler().registerEventListener(new InteractTerrainLiveTest(b));
    }

    private synchronized void log(String message) {
        System.out.println("INTERACTTERRAIN " + message);
        try {
            if (out == null) {
                out = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath().resolve("interactterrain-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write("INTERACTTERRAIN " + message);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (event.getState() == EventState.PRE && event.getPacket() instanceof ServerboundUseItemOnPacket) usePackets++;
    }

    @Override
    public void onTick(TickEvent e) {
        if (e.getState() != EventState.PRE) return;
        Minecraft mc = Minecraft.getInstance();
        if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
            state = 1;
            mc.options.pauseOnLostFocus = false;
            CreateWorldScreen.openFresh(mc, () -> {});
            wait = 40;
        } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
            screen.getUiState().setSeed(System.getProperty("ostinato.seed", "90001"));
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
            flagPresent = setting() != null;
            server(mc, () -> {
                mc.getSingleplayerServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                mc.getSingleplayerServer().overworld().getGameRules().set(GameRules.SPAWN_MOBS, false, mc.getSingleplayerServer());
                setupArena(mc.getSingleplayerServer().overworld(), player(mc).blockPosition());
                log("start laps=" + laps.size() + " reps=" + REPS + " scenarios=" + scenarios().length + " timeout=" + TIMEOUT + " post=" + POST
                        + " seed=" + System.getProperty("ostinato.seed", "90001") + " flag=" + (flagPresent ? "present" : "absent")
                        + " origin=" + origin.toShortString() + " box=" + (2 * R + 1) + "x" + (2 * R + 1) + "x" + (snapshot.length / ((2 * R + 1) * (2 * R + 1)))
                        + " removedContainers=" + removedContainers + " chests=" + chests + " furnaces=" + furnaces + " blast=" + blast.toShortString()
                        + " ender=" + ender.toShortString() + " table=" + table.toShortString() + " wet=" + wet(mc.getSingleplayerServer().overworld())
                        + " rightClickContainerOnArrival=" + Baritone.settings().rightClickContainerOnArrival.value
                        + " cadenza=" + Baritone.settings().cadenza.value + " exploreForBlocks=" + Baritone.settings().exploreForBlocks.value
                        + " swimInWater=" + Baritone.settings().swimInWater.value);
            });
            wait = 80;
        } else if (state == 3 && ready && --wait <= 0) {
            next(mc);
        } else if (state == 4) {
            lap(mc);
        }
    }

    /** Server thread. Picks the fixture cells on the surface, empties the box of containers and copies it. */
    private void setupArena(ServerLevel level, BlockPos spawn) {
        int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
        origin = new BlockPos(spawn.getX(), sy, spawn.getZ());
        for (int[] c : CHESTS) chests.add(surface(level, c));
        for (int[] c : FURNACES) furnaces.add(surface(level, c));
        blast = surface(level, BLAST);
        ender = surface(level, ENDER);
        table = surface(level, TABLE);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, origin.getX() + x, origin.getZ() + z);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        minY = lo - DOWN;
        int height = hi + UP - minY + 1, side = 2 * R + 1;
        snapshot = new BlockState[side * side * height];
        int i = 0, removed = 0;
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                for (int y = 0; y < height; y++, i++) {
                    BlockPos p = new BlockPos(origin.getX() + x, minY + y, origin.getZ() + z);
                    BlockState st = level.getBlockState(p);
                    if (container(st.getBlock())) {
                        level.removeBlockEntity(p);
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        st = Blocks.AIR.defaultBlockState();
                        removed++;
                    }
                    snapshot[i] = st;
                }
            }
        }
        removedContainers = removed;
    }

    /**
     * Fixtures with a fluid beside them. Baritone's block search ({@code MineProcess.searchWorld} → {@code prune} →
     * {@code plausibleToBreak} → {@code avoidBreaking}) drops a block it would not break, so such a fixture is never a
     * known target of {@code goto <block>}, in either arm (dev run 2, seed 90001).
     */
    private String wet(ServerLevel level) {
        List<String> w = new ArrayList<>();
        List<BlockPos> all = new ArrayList<>(chests);
        all.addAll(furnaces);
        all.add(blast);
        all.add(ender);
        all.add(table);
        for (BlockPos p : all) {
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (!level.getFluidState(p.relative(d)).isEmpty()) {
                    w.add(p.toShortString().replace(" ", ""));
                    break;
                }
            }
        }
        return w.toString().replace(" ", "");
    }

    private static boolean container(Block b) {
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.ENDER_CHEST || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE
                || b == Blocks.SMOKER || b == Blocks.CRAFTING_TABLE || b == Blocks.BARREL;
    }

    private BlockPos surface(ServerLevel level, int[] d) {
        int x = origin.getX() + d[0], z = origin.getZ() + d[1];
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** Server thread. Puts back every block that differs from the copy, and removes dropped items and other entities. */
    private void restore(ServerLevel level) {
        int side = 2 * R + 1, height = snapshot.length / (side * side), i = 0, n = 0;
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                for (int y = 0; y < height; y++, i++) {
                    BlockPos p = new BlockPos(origin.getX() + x, minY + y, origin.getZ() + z);
                    if (level.getBlockState(p) != snapshot[i]) {
                        level.removeBlockEntity(p);
                        level.setBlock(p, snapshot[i], 2);
                        n++;
                    }
                }
            }
        }
        restored = n;
        far = clearLoadedContainers(level);
        level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(R + 2, 40, R + 2), e -> !(e instanceof Player)).forEach(Entity::discard);
    }

    /**
     * Server thread. Baritone goes to any instance it knows, and the dev run (seed 90001) walked to natural chests 100+
     * blocks away: so every container block entity in the loaded chunks outside the box is removed too, each lap. A
     * legacy lap that explores loads new chunks; the next lap's clear removes what they held (the count is logged).
     */
    private int clearLoadedContainers(ServerLevel level) {
        int n = 0, ox = origin.getX() >> 4, oz = origin.getZ() >> 4;
        for (int cx = ox - 32; cx <= ox + 32; cx++) {
            for (int cz = oz - 32; cz <= oz + 32; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockPos pos : new ArrayList<>(chunk.getBlockEntities().keySet())) {
                    if (Math.abs(pos.getX() - origin.getX()) <= R && Math.abs(pos.getZ() - origin.getZ()) <= R) continue;
                    if (container(level.getBlockState(pos).getBlock())) {
                        level.removeBlockEntity(pos);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Server thread. The fixture: every target on the surface, with a solid block under it. */
    private void place(ServerLevel level, Scenario s) {
        for (BlockPos p : chests) put(level, p, Blocks.CHEST);
        for (BlockPos p : furnaces) put(level, p, Blocks.FURNACE);
        put(level, blast, Blocks.BLAST_FURNACE);
        put(level, ender, Blocks.ENDER_CHEST);
        put(level, table, Blocks.CRAFTING_TABLE);
        catOn = null;
        if (s == Scenario.CAT_ONE) {
            catOn = nearest(chests, origin);
            Cat cat = EntityTypes.CAT.create(level, EntitySpawnReason.COMMAND);
            cat.setNoAi(true);
            cat.setOrderedToSit(true);
            cat.setInSittingPose(true);
            cat.snapTo(catOn.getX() + 0.5, catOn.getY() + 0.875, catOn.getZ() + 0.5, 0, 0);
            level.addFreshEntity(cat);
        }
        if (s == Scenario.MAGMA) level.setBlock(origin.below(), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        if (s == Scenario.SURFACE) {
            // a 3x3 pool, 7 deep, open to the sky, walled and floored with stone, the surface one below the start
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -8; dy <= -1; dy++) {
                boolean inside = Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= -7;
                level.setBlock(origin.offset(dx, dy, dz), (inside ? Blocks.WATER : Blocks.STONE).defaultBlockState(), 2);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++) {
                level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static void put(ServerLevel level, BlockPos p, Block b) {
        if (!level.getBlockState(p.below()).isSolidRender()) level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(p, b.defaultBlockState(), 2);
        level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 2);
    }

    private static BlockPos nearest(List<BlockPos> ps, BlockPos to) {
        BlockPos best = null;
        for (BlockPos p : ps) if (best == null || p.distSqr(to) < best.distSqr(to)) best = p;
        return best;
    }

    @SuppressWarnings("unchecked")
    private static Settings.Setting<Boolean> setting() {
        return (Settings.Setting<Boolean>) Baritone.settings().byLowerName.get(FLAG);
    }

    private void arm(int arm) {
        if (flagPresent) setting().value = arm == 1;
    }

    private void next(Minecraft mc) {
        if (index >= laps.size()) {
            finish();
            return;
        }
        Lap l = laps.get(index);
        arm(0);
        if (mc.player.isDeadOrDying()) {
            mc.player.respawn();
            return;
        }
        if (mc.gui.screen() != null) mc.player.closeContainer();
        ready = false;
        server(mc, () -> {
            ServerLevel level = mc.getSingleplayerServer().overworld();
            ServerPlayer p = player(mc);
            p.closeContainer();
            restore(level);
            place(level, l.scenario());
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            p.getInventory().setSelectedSlot(0);
            p.inventoryMenu.sendAllDataToRemote();
            p.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
            p.setDeltaMovement(0, 0, 0);
            p.resetFallDistance();
            if (l.scenario() == Scenario.MENU_OPEN) {
                // a chest-type menu over a plain container: valid at any distance, and not the target
                p.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv), Component.literal("p10")));
            }
        });
        mc.player.getInventory().setSelectedSlot(0);
        state = 4;
        step = 0;
        wait = 40;
    }

    private static TaskList list(String block) {
        TaskList t = new TaskList("p10");
        t.add(TaskStep.of(StepType.GET_TO_BLOCK, "block", block));
        return t;
    }

    private void startRun(TaskList t) {
        runner.run(t, lapTicks * 50L);
        runStart = lapTicks;
        trail.setLength(0);
        lastIndex = -1;
        lastStatus = null;
    }

    private void lap(Minecraft mc) {
        if (!ready) return;
        Lap l = laps.get(index);
        Scenario s = l.scenario();
        if (step == 0) {
            if (--wait > 0) return;
            arm(l.arm());
            mc.player.setYRot(-90);
            mc.player.setXRot(0);
            runner = new TaskRunner(new BaritoneTaskAdapter());
            lapTicks = 0;
            verdictAt = stopAt = lagAt = -1;
            openedAt.clear();
            opened = "-";
            lastMenuRef = player(mc).containerMenu;
            lastMenu = kind(mc, lastMenuRef);
            runnerNs = runnerTicks = 0;
            phase = 0;
            post = null;
            usePackets = 0;
            vanished = null;
            log("begin lap=" + index + " scen=" + s + " arm=" + armName(l) + " rep=" + l.rep() + " restored=" + restored + " farContainers=" + far + " menuAtStart=" + lastMenu
                    + " known=" + (s == Scenario.MAGMA || s == Scenario.SURFACE ? "-" : known(s.target())));
            if (s == Scenario.MAGMA) {
                step = 5;
                return;
            }
            if (s == Scenario.SURFACE) {
                step = 6;
                surfaceActive = surfaceJump = false;
                fullAt = -1;
                return;
            }
            startRun(list(s.target()));
            step = 1;
            return;
        }
        lapTicks++;
        observeMenu(mc);
        if (mc.player.isDeadOrDying()) {
            row(l, "DIED", "died", "-");
            endLap(mc);
            return;
        }
        if (step == 5) {
            magma(mc, l);
            return;
        }
        if (step == 6) {
            surface(mc, l);
            return;
        }
        if (step == 1) {
            if (s == Scenario.VANISH_ONE && phase == 0) {
                for (BlockPos c : chests) {
                    if (mc.player.position().distanceTo(Vec3.atCenterOf(c)) < 3.5) {
                        phase = 1;
                        vanished = c;
                        server(mc, () -> mc.getSingleplayerServer().overworld().setBlock(c, Blocks.STONE.defaultBlockState(), 2));
                        break;
                    }
                }
            }
            boolean clicking = baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT);
            if ((s == Scenario.LAG_SHORT || s == Scenario.LAG_LONG) && phase == 0 && clicking) {
                phase = 1;
                lagAt = lapTicks;
                long ms = s == Scenario.LAG_SHORT ? 400 : 1600;
                mc.getSingleplayerServer().execute(() -> {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            if (s == Scenario.PREEMPT && lapTicks == 20) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(origin.getX() + 4, origin.getY(), origin.getZ() - 4));
            }
            if (s == Scenario.REPLACE && lapTicks == 15) {
                startRun(list("furnace"));
                phase = 1;
            }
            if ((s == Scenario.CANCEL_MOVE && lapTicks == 15 || s == Scenario.CANCEL_ACT && clicking) && phase == 0) {
                runner.stop(lapTicks * 50L);
                stopAt = lapTicks;
                phase = 1;
                step = 2;
                return;
            }
        }
        if (step == 2) {
            if (lapTicks == stopAt + 1) post = "active1=" + baritone.getGetToBlockProcess().isActive() + " keys1=" + heldKeys();
            if (lapTicks >= stopAt + POST) {
                boolean late = openedAt.getOrDefault("chest", -1L) > stopAt;
                log("row lap=" + index + " scen=" + s + " arm=" + armName(l) + " rep=" + l.rep() + " part=stop verdict=" + runner.state()
                        + " cls=STOP " + post + " activeEnd=" + baritone.getGetToBlockProcess().isActive() + " keysEnd=" + heldKeys()
                        + " lateOpen=" + late + " uses=" + usePackets + " stopTick=" + stopAt + " outcome=" + outcome());
                usePackets = 0;
                startRun(list(s.target()));
                step = 3;
            }
            return;
        }
        long t0 = System.nanoTime();
        runner.tick(lapTicks * 50L);
        runnerNs += System.nanoTime() - t0;
        runnerTicks++;
        if ((lapTicks - runStart) % 100 == 0) trail.append(' ').append(baritone.getPlayerContext().playerFeet().toShortString().replace(" ", ""));
        watchSteps(l);
        if (verdictAt < 0 && !runner.active()) {
            verdictAt = lapTicks;
        }
        if (verdictAt >= 0 && lapTicks >= verdictAt + POST) {
            String want = kindFor(s.target());
            boolean lateAfter = openedAt.getOrDefault(want, -1L) > verdictAt;
            log("row lap=" + index + " scen=" + s + " arm=" + armName(l) + " rep=" + l.rep() + " part=end run=" + runner.state()
                    + " activeEnd=" + baritone.getGetToBlockProcess().isActive() + " keysEnd=" + heldKeys() + " uses=" + usePackets
                    + " openAfterVerdict=" + lateAfter + " menuEnd=" + lastMenu + " lagAt=" + lagAt + " vanished=" + (vanished == null ? "-" : vanished.toShortString())
                    + " runnerUs=" + String.format(Locale.ROOT, "%.2f", runnerNs / 1000.0 / Math.max(1, runnerTicks)));
            endLap(mc);
            return;
        }
        if (verdictAt < 0 && lapTicks - runStart > TIMEOUT) {
            row(l, "HANG", "no verdict in " + TIMEOUT + " ticks; trail" + trail + "; feet " + baritone.getPlayerContext().playerFeet()
                    + "; goal " + baritone.getPathingBehavior().getGoal(), "HANG");
            endLap(mc);
        }
    }

    /** MAGMA: no task. Tick 10 on magma, then moved off; tick 30 the keys and the movement input are read again. */
    private void magma(Minecraft mc, Lap l) {
        if (lapTicks == 10) {
            post = "onMagma sneak=" + baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK) + " keys=" + heldKeys()
                    + " input=" + mc.player.input.getClass().getSimpleName();
            server(mc, () -> {
                ServerPlayer p = player(mc);
                BlockPos off = new BlockPos(origin.getX() + 3, 0, origin.getZ());
                int y = mc.getSingleplayerServer().overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, off.getX(), off.getZ());
                p.teleportTo(off.getX() + 0.5, y, off.getZ() + 0.5);
                p.setDeltaMovement(0, 0, 0);
            });
        }
        if (lapTicks == 30) {
            boolean sneak = baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK);
            boolean baritoneInput = mc.player.input.getClass().getSimpleName().equals("PlayerMovementInput");
            String cls = sneak || baritoneInput ? "STUCK" : "CLEAR";
            log("row lap=" + index + " scen=MAGMA arm=" + armName(l) + " rep=" + l.rep() + " part=idle " + post
                    + " offMagma sneak=" + sneak + " keys=" + heldKeys() + " input=" + mc.player.input.getClass().getSimpleName()
                    + " shift=" + mc.player.isShiftKeyDown() + " onMagmaNow=" + (mc.level.getBlockState(mc.player.blockPosition().below()).is(Blocks.MAGMA_BLOCK))
                    + " active=" + activeProcesses() + " cls=" + cls);
            // what a stuck idle player would need: nothing in Baritone clears it, so the lap end does
            baritone.getInputOverrideHandler().clearAllKeys();
            index++;
            state = 3;
            wait = 20;
            ready = true;
        }
    }

    /**
     * SURFACE: no task. Tick 2 the player is put at the bottom of the pool with 90 air (AirProcess starts below 100);
     * 40 ticks after the bar is full the keys and the movement input are read, then the player is moved onto dry
     * ground and they are read again 20 ticks later. STUCK if a movement key is still forced or Baritone's movement
     * input is still installed with no process surfacing; NOACTIVE if AirProcess never started within 600 ticks.
     */
    private void surface(Minecraft mc, Lap l) {
        if (lapTicks == 2) {
            server(mc, () -> {
                ServerPlayer p = player(mc);
                p.teleportTo(origin.getX() + 0.5, origin.getY() - 7, origin.getZ() + 0.5);
                p.setDeltaMovement(0, 0, 0);
                p.setAirSupply(90);
            });
            return;
        }
        if (lapTicks < 5) return;
        if (activeProcesses().startsWith("Surfacing")) surfaceActive = true;
        if (baritone.getInputOverrideHandler().isInputForcedDown(Input.JUMP)) surfaceJump = true;
        if (fullAt < 0 && surfaceActive && mc.player.getAirSupply() >= mc.player.getMaxAirSupply()) fullAt = lapTicks;
        if (fullAt < 0 && lapTicks > TIMEOUT) {
            surfaceRow(l, mc, "inWater", "NOACTIVE");
            return;
        }
        if (fullAt > 0 && lapTicks == fullAt + 40) {
            post = "airFull+40 inWater=" + mc.player.isInWater() + " keys=" + heldKeys() + " input=" + mc.player.input.getClass().getSimpleName()
                    + " control=" + activeProcesses() + " pathing=" + baritone.getPathingBehavior().isPathing();
            server(mc, () -> {
                ServerPlayer p = player(mc);
                BlockPos off = new BlockPos(origin.getX() + 4, 0, origin.getZ());
                int y = mc.getSingleplayerServer().overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, off.getX(), off.getZ());
                p.teleportTo(off.getX() + 0.5, y, off.getZ() + 0.5);
                p.setDeltaMovement(0, 0, 0);
            });
        }
        if (fullAt > 0 && lapTicks == fullAt + 60) {
            boolean forced = false;
            for (Input in : new Input[]{Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SNEAK, Input.JUMP}) {
                forced |= baritone.getInputOverrideHandler().isInputForcedDown(in);
            }
            boolean baritoneInput = mc.player.input.getClass().getSimpleName().equals("PlayerMovementInput");
            surfaceRow(l, mc, "onLand", forced || baritoneInput ? "STUCK" : "CLEAR");
        }
    }

    private void surfaceRow(Lap l, Minecraft mc, String where, String cls) {
        log("row lap=" + index + " scen=SURFACE arm=" + armName(l) + " rep=" + l.rep() + " part=idle surfacing=" + surfaceActive
                + " jumpSeen=" + surfaceJump + " fullAt=" + fullAt + " " + (post == null ? "-" : post) + " " + where + " keys=" + heldKeys()
                + " input=" + mc.player.input.getClass().getSimpleName() + " control=" + activeProcesses()
                + " air=" + mc.player.getAirSupply() + " cls=" + cls);
        baritone.getInputOverrideHandler().clearAllKeys(); // as MAGMA: nothing in Baritone would, so the lap end does
        index++;
        state = 3;
        wait = 20;
        ready = true;
    }

    /** What Baritone's own block search returns for the target at lap start (the request does the same search). */
    private String known(String block) {
        List<BlockPos> k = MineProcess.searchWorld(new CalculationContext(baritone),
                new BlockOptionalMetaLookup(block), 64, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        StringBuilder b = new StringBuilder().append(k.size());
        for (BlockPos p : k) if (Math.abs(p.getX() - origin.getX()) <= R && Math.abs(p.getZ() - origin.getZ()) <= R) b.append(',').append(p.toShortString().replace(" ", ""));
        return b.toString();
    }

    private String activeProcesses() {
        StringBuilder b = new StringBuilder();
        baritone.getPathingControlManager().mostRecentInControl().ifPresent(p -> b.append(p.displayName()));
        return b.length() == 0 ? "-" : b.toString().replace(' ', '_');
    }

    private void watchSteps(Lap l) {
        int i = runner.index();
        TaskRunner.StepStatus st = runner.status(i);
        if (lastIndex >= 0 && i != lastIndex) {
            verdict(l, lastIndex);
        } else if (st != lastStatus && (st == TaskRunner.StepStatus.DONE || st == TaskRunner.StepStatus.FAILED)) {
            verdict(l, i);
        }
        if (i != lastIndex || st == TaskRunner.StepStatus.RUNNING && lastStatus != TaskRunner.StepStatus.RUNNING) {
            if (st == TaskRunner.StepStatus.RUNNING) {
                stepStart = lapTicks;
                usesAtStep = usePackets;
            }
        }
        lastIndex = i;
        lastStatus = st;
    }

    private void verdict(Lap l, int i) {
        TaskRunner.StepStatus st = runner.status(i);
        String block = runner.list().get(i).get("block");
        long at = openedAt.getOrDefault(kindFor(block), -1L);
        boolean ok = at >= stepStart && at >= 0;
        String cls = st == TaskRunner.StepStatus.DONE ? (ok ? "TS" : "FS") : st == TaskRunner.StepStatus.FAILED ? (ok ? "FF" : "TF") : "?";
        row(l, st + " step=" + (i + 1) + " block=" + block, runner.message(i), cls);
    }

    private void row(Lap l, String verdict, String msg, String cls) {
        log("row lap=" + index + " scen=" + l.scenario() + " arm=" + armName(l) + " rep=" + l.rep() + " part=" + (step == 3 ? "rerun" : "main")
                + " verdict=" + verdict + " cls=" + cls + " ticks=" + (lapTicks - stepStart) + " lapTicks=" + lapTicks
                + " uses=" + (usePackets - usesAtStep) + " menu=" + lastMenu + " opened=" + opened + " active=" + baritone.getGetToBlockProcess().isActive()
                + " keys=" + heldKeys() + " outcome=" + outcome() + " msg=\"" + (msg == null ? "" : msg.replace('"', '\'')) + "\"");
    }

    private String outcome() {
        baritone.api.process.ProcessOutcome o = baritone.getGetToBlockProcess().outcome();
        return o.request() + ":" + o.state() + "/" + o.reason();
    }

    private String armName(Lap l) {
        return l.arm() == 0 ? "A" : "B";
    }

    private static String kindFor(String block) {
        switch (block) {
            case "furnace": return "furnace";
            case "blast_furnace": return "blast";
            case "ender_chest": return "ender";
            case "crafting_table": return "table";
            default: return "chest";
        }
    }

    /** The oracle: which fixture, if any, the server-side menu belongs to. */
    private String kind(Minecraft mc, AbstractContainerMenu m) {
        ServerPlayer p = player(mc);
        if (m == null || m == p.inventoryMenu) return "none";
        if (m instanceof ChestMenu cm) {
            Container c = cm.getContainer();
            if (c instanceof BlockEntity be && chests.contains(be.getBlockPos())) {
                opened = be.getBlockPos().toShortString().replace(" ", "");
                return "chest";
            }
            // the box holds no other ender chest (every container in it is removed at setup and the overworld
            // generates none), so the player's ender inventory with the player near the fixture is this ender chest
            boolean active = p.level().getBlockEntity(ender) instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity e
                    && p.getEnderChestInventory().isActiveChest(e);
            if (c == p.getEnderChestInventory() && (active || p.blockPosition().distSqr(ender) < 64)) {
                opened = "ender" + (active ? "" : "-near");
                return "ender";
            }
            return "otherchest-" + c.getClass().getSimpleName() + (c == p.getEnderChestInventory() ? "-own" : "");
        }
        if (m instanceof FurnaceMenu) {
            BlockPos f = nearest(furnaces, p.blockPosition());
            if (p.blockPosition().distSqr(f) < 64) {
                opened = f.toShortString().replace(" ", "");
                return "furnace";
            }
            return "otherfurnace";
        }
        if (m instanceof BlastFurnaceMenu) return p.blockPosition().distSqr(blast) < 64 ? "blast" : "otherblast";
        if (m instanceof CraftingMenu) return p.blockPosition().distSqr(table) < 64 ? "table" : "othertable";
        return m.getClass().getSimpleName();
    }

    private void observeMenu(Minecraft mc) {
        AbstractContainerMenu m = player(mc).containerMenu;
        String kind = kind(mc, m);
        if (m != lastMenuRef && !kind.equals("none")) openedAt.put(kind, lapTicks);
        lastMenuRef = m;
        lastMenu = kind;
    }

    private void endLap(Minecraft mc) {
        if (runner.active()) runner.stop(lapTicks * 50L);
        baritone.getPathingBehavior().cancelEverything();
        String held = heldKeys();
        if (!held.isEmpty() && !held.equals("-")) log("keys still held after lap " + index + ": " + held);
        index++;
        state = 3;
        wait = 20;
        ready = true;
    }

    private String heldKeys() {
        StringBuilder b = new StringBuilder();
        for (Input in : Input.values()) {
            if (baritone.getInputOverrideHandler().isInputForcedDown(in)) b.append(b.length() == 0 ? "" : ",").append(in);
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    private void finish() {
        log("finish laps=" + laps.size());
        arm(0);
        state = 5;
        if (Boolean.getBoolean("ostinato.interactterrain.exit")) Minecraft.getInstance().stop();
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
