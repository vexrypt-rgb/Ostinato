/*
 * Ostinato 2.0 Phase 08: new file. Not part of upstream Baritone.
 */
package baritone.harness;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.input.Input;
import baritone.gui.tasks.StepType;
import baritone.gui.tasks.TaskList;
import baritone.gui.tasks.TaskRunner;
import baritone.gui.tasks.TaskStep;
import baritone.gui.tasks.game.BaritoneTaskAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato 2.0 Phase 08: opt-in live check of the Navigate -> Interact -> Verify workflow (-Dostinato.interacttest=true,
 * with the test harness on). Fresh disposable survival world, Peaceful. Each lap builds a small arena high over spawn,
 * runs a task list through the production {@link TaskRunner} and {@link BaritoneTaskAdapter} (the in-game task builder's
 * own runner and adapter) and compares the runner's verdict with an oracle that does not use the code under test: the
 * menu the <em>server-side</em> player has open, and for a chest the block entity behind it.
 * <p>
 * Arms: A = {@code verifiedGetToBlock} off (legacy verdict), B = on. On a build without that setting both arms run the
 * legacy path and the start line says {@code flag=absent}. The arm order alternates by repetition and scenario.
 * <p>
 * One {@code INTERACTTEST row} per verdict: DONE with the target menu opened during the step is TS (true success), DONE
 * without it FS (false success), FAILED without it TF, FAILED with it FF, no verdict within {@link #TIMEOUT} ticks HANG.
 * A cancel ({@code STOP}) gets its own row with the release checks one tick and {@link #POST} ticks later.
 */
public final class InteractLiveTest implements AbstractGameEventListener {
    private static final int REPS = Integer.getInteger("ostinato.interacttest.reps", 3), TIMEOUT = 600, POST = 40;
    private static final String FLAG = "verifiedgettoblock";
    static final int F = 200, SIZE = 20, SX = 2, SZ = 10;
    static final BlockPos CHEST = new BlockPos(12, F, 10), TABLE = new BlockPos(12, F, 15);

    /** What each lap builds and does. */
    enum Scenario {
        /** A reachable chest: the normal case. */
        OPEN,
        /** A sitting cat on the chest: the server refuses to open it (action rejected). */
        CAT,
        /** The chest turns to stone when the bot is 3.5 blocks from it (the world changed at the final action). */
        VANISH,
        /** The chest is boxed in bedrock: no path (unreachable target). */
        SEALED,
        /** The run is stopped 15 ticks in (moving), then the same list is run again. */
        CANCEL_MOVE,
        /** The run is stopped on the first tick right-click is held (the final action), then run again. */
        CANCEL_ACT,
        /** A goto to another cell is issued 20 ticks in: the get-to-block process loses control (ownership lost). */
        PREEMPT,
        /** A chest list is replaced by a crafting-table list 15 ticks in (rapid goal replacement). */
        REPLACE,
        /** One list: chest, then crafting table (repeated execution, the chest screen still open). */
        REPEAT;

        boolean table() {
            return this == REPLACE || this == REPEAT;
        }

        /**
         * The world generates chests, and Baritone remembers them past the loaded chunks, so "the only one vanished"
         * or "the only one is sealed" needs a target the overworld never generates: an ender chest.
         */
        boolean ender() {
            return this == VANISH || this == SEALED;
        }

        String target() {
            return ender() ? "ender_chest" : "chest";
        }

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
            level.getEntitiesOfClass(Entity.class, new AABB(-3, F - 4, -3, SIZE + 4, F + 9, SIZE + 4), e -> !(e instanceof Player)).forEach(Entity::discard);
            if (this == SEALED) {
                BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
                for (int x = 11; x <= 13; x++) for (int y = F - 1; y <= F + 1; y++) for (int z = 9; z <= 11; z++) level.setBlock(new BlockPos(x, y, z), bedrock, 2);
            }
            cleared = clearOtherChests(level);
            level.setBlock(CHEST, (ender() ? Blocks.ENDER_CHEST : Blocks.CHEST).defaultBlockState(), 2);
            if (table()) level.setBlock(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
            if (this == CAT) {
                Cat cat = EntityTypes.CAT.create(level, EntitySpawnReason.COMMAND);
                cat.setNoAi(true);
                cat.setOrderedToSit(true);
                cat.setInSittingPose(true);
                cat.snapTo(CHEST.getX() + 0.5, F + 0.875, CHEST.getZ() + 0.5, 0, 0);
                level.addFreshEntity(cat);
            }
        }
    }

    /** Chests (and trapped chests) in loaded chunks other than the fixture's, removed at lap setup. */
    private static volatile int cleared;

    private static int clearOtherChests(ServerLevel level) {
        int n = 0;
        for (int cx = -32; cx <= 32; cx++) {
            for (int cz = -32; cz <= 32; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockPos pos : new ArrayList<>(chunk.getBlockEntities().keySet())) {
                    BlockState st = level.getBlockState(pos);
                    if ((st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST) || st.is(Blocks.ENDER_CHEST)) && !pos.equals(CHEST)) {
                        level.removeBlockEntity(pos);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private record Lap(int rep, Scenario scenario, int arm) {}

    private final Baritone baritone;
    private final List<Lap> laps = new ArrayList<>();
    private BufferedWriter out;
    private int state, wait, index, step;
    private volatile boolean ready;
    private volatile int usePackets;
    private boolean flagPresent;

    // per lap
    private TaskRunner runner;
    private long lapTicks, verdictAt, stopAt, stepStart, runStart, runnerNs, runnerTicks;
    private int lastIndex, lastCycleSeen, usesAtStep, phase;
    private TaskRunner.StepStatus lastStatus;
    /** Server-side menu kind seen last tick, and the lap tick a target menu last appeared. */
    private String lastMenu = "none";
    private AbstractContainerMenu lastMenuRef;
    private long chestOpenedAt = -1, tableOpenedAt = -1;
    private String post;

    private InteractLiveTest(Baritone b) {
        baritone = b;
        Scenario[] all = scenarios();
        for (int r = 0; r < REPS; r++) {
            for (int s = 0; s < all.length; s++) {
                for (int k = 0; k < 2; k++) laps.add(new Lap(r, all[s], (k + r + s) % 2));
            }
        }
    }

    private static Scenario[] scenarios() {
        String only = System.getProperty("ostinato.interacttest.only");
        if (only == null || only.isBlank()) return Scenario.values();
        List<Scenario> s = new ArrayList<>();
        for (String n : only.split(",")) s.add(Scenario.valueOf(n.trim().toUpperCase(Locale.ROOT)));
        return s.toArray(new Scenario[0]);
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.interacttest")) b.getGameEventHandler().registerEventListener(new InteractLiveTest(b));
    }

    private synchronized void log(String message) {
        System.out.println("INTERACTTEST " + message);
        try {
            if (out == null) {
                out = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath().resolve("interacttest-result.txt"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write("INTERACTTEST " + message);
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
            screen.getUiState().setSeed(System.getProperty("ostinato.seed", "88001"));
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
            log("start laps=" + laps.size() + " reps=" + REPS + " scenarios=" + scenarios().length + " timeout=" + TIMEOUT + " post=" + POST
                    + " seed=" + System.getProperty("ostinato.seed", "88001") + " flag=" + (flagPresent ? "present" : "absent")
                    + " rightClickContainerOnArrival=" + Baritone.settings().rightClickContainerOnArrival.value
                    + " cadenza=" + Baritone.settings().cadenza.value + " exploreForBlocks=" + Baritone.settings().exploreForBlocks.value);
            server(mc, () -> {
                mc.getSingleplayerServer().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL, true);
                mc.getSingleplayerServer().overworld().getGameRules().set(GameRules.SPAWN_MOBS, false, mc.getSingleplayerServer());
                Scenario.OPEN.build(mc.getSingleplayerServer().overworld());
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
            l.scenario().build(level);
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            p.getInventory().setSelectedSlot(0);
            p.inventoryMenu.sendAllDataToRemote();
            p.teleportTo(SX + 0.5, F, SZ + 0.5);
            p.setDeltaMovement(0, 0, 0);
            p.resetFallDistance();
        });
        mc.player.getInventory().setSelectedSlot(0);
        state = 4;
        step = 0;
        wait = 40;
    }

    private static TaskList list(String... blocks) {
        TaskList t = new TaskList("p08");
        for (String b : blocks) t.add(TaskStep.of(StepType.GET_TO_BLOCK, "block", b));
        return t;
    }

    private void startRun(TaskList t) {
        runner.run(t, lapTicks * 50L);
        runStart = lapTicks;
        lastIndex = -1;
        lastStatus = null;
        lastCycleSeen = 1;
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
            verdictAt = stopAt = -1;
            chestOpenedAt = tableOpenedAt = -1;
            lastMenu = "none";
            lastMenuRef = player(mc).containerMenu;
            runnerNs = runnerTicks = 0;
            phase = 0;
            post = null;
            usePackets = 0;
            log("begin lap=" + index + " scen=" + s + " arm=" + armName(l) + " rep=" + l.rep() + " clearedChests=" + cleared);
            startRun(s == Scenario.REPEAT ? list("chest", "crafting_table") : list(s.target()));
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
        // scenario events
        if (step == 1) {
            if (s == Scenario.VANISH && phase == 0 && mc.player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(CHEST)) < 3.5) {
                phase = 1;
                server(mc, () -> mc.getSingleplayerServer().overworld().setBlock(CHEST, Blocks.STONE.defaultBlockState(), 2));
            }
            if (s == Scenario.PREEMPT && lapTicks == 20) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(SX, F, SZ + 6));
            }
            if (s == Scenario.REPLACE && lapTicks == 15) {
                startRun(list("crafting_table"));
                phase = 1;
            }
            boolean act = s == Scenario.CANCEL_ACT && baritone.getInputOverrideHandler().isInputForcedDown(Input.CLICK_RIGHT);
            if ((s == Scenario.CANCEL_MOVE && lapTicks == 15 || act) && phase == 0) {
                runner.stop(lapTicks * 50L);
                stopAt = lapTicks;
                phase = 1;
                step = 2; // release checks
                return;
            }
        }
        if (step == 2) {
            // one tick after stop, and POST ticks later: anything still held, still active, or opened after the stop
            if (lapTicks == stopAt + 1) post = "active1=" + baritone.getGetToBlockProcess().isActive() + " keys1=" + heldKeys();
            if (lapTicks >= stopAt + POST) {
                boolean late = chestOpenedAt > stopAt;
                log("row lap=" + index + " scen=" + s + " arm=" + armName(l) + " rep=" + l.rep() + " part=stop verdict=" + runner.state()
                        + " cls=STOP " + post + " activeEnd=" + baritone.getGetToBlockProcess().isActive() + " keysEnd=" + heldKeys()
                        + " lateOpen=" + late + " uses=" + usePackets + " stopTick=" + stopAt + " outcome=" + outcome());
                // a new request after the cancel must not inherit anything: run the same list again
                usePackets = 0;
                startRun(list("chest"));
                step = 3;
            }
            return;
        }
        long t0 = System.nanoTime();
        runner.tick(lapTicks * 50L);
        runnerNs += System.nanoTime() - t0;
        runnerTicks++;
        watchSteps(l);
        if (verdictAt < 0 && !runner.active()) {
            verdictAt = lapTicks;
        }
        if (verdictAt >= 0 && lapTicks >= verdictAt + POST) {
            log("row lap=" + index + " scen=" + s + " arm=" + armName(l) + " rep=" + l.rep() + " part=end run=" + runner.state()
                    + " activeEnd=" + baritone.getGetToBlockProcess().isActive() + " keysEnd=" + heldKeys() + " uses=" + usePackets
                    + " runnerUs=" + String.format(Locale.ROOT, "%.2f", runnerNs / 1000.0 / Math.max(1, runnerTicks)));
            endLap(mc);
            return;
        }
        if (verdictAt < 0 && lapTicks - runStart > TIMEOUT) {
            row(l, "HANG", "no verdict in " + TIMEOUT + " ticks; goal " + baritone.getPathingBehavior().getGoal()
                    + "; feet " + baritone.getPlayerContext().playerFeet(), "HANG");
            endLap(mc);
        }
    }

    /** One row each time the runner gives a step a verdict (DONE or FAILED). */
    private void watchSteps(Lap l) {
        int i = runner.index();
        TaskRunner.StepStatus st = runner.status(i);
        if (lastIndex >= 0 && i != lastIndex) {
            // the runner moved on: the previous step got its verdict last tick
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
        long opened = block.equals("crafting_table") ? tableOpenedAt : chestOpenedAt;
        boolean ok = opened >= stepStart && opened >= 0;
        String cls = st == TaskRunner.StepStatus.DONE ? (ok ? "TS" : "FS") : st == TaskRunner.StepStatus.FAILED ? (ok ? "FF" : "TF") : "?";
        row(l, st + " step=" + (i + 1) + " block=" + block, runner.message(i), cls);
    }

    private void row(Lap l, String verdict, String msg, String cls) {
        log("row lap=" + index + " scen=" + l.scenario() + " arm=" + armName(l) + " rep=" + l.rep() + " part=" + (step == 3 ? "rerun" : "main")
                + " verdict=" + verdict + " cls=" + cls + " ticks=" + (lapTicks - stepStart) + " lapTicks=" + lapTicks
                + " uses=" + (usePackets - usesAtStep) + " menu=" + lastMenu + " active=" + baritone.getGetToBlockProcess().isActive()
                + " keys=" + heldKeys() + " outcome=" + outcome() + " msg=\"" + (msg == null ? "" : msg.replace('"', '\'')) + "\"");
    }

    /** What the process says became of its latest request (the system under test's own view, not the oracle). */
    private String outcome() {
        baritone.api.process.ProcessOutcome o = baritone.getGetToBlockProcess().outcome();
        return o.request() + ":" + o.state() + "/" + o.reason();
    }

    private String armName(Lap l) {
        return l.arm() == 0 ? "A" : "B";
    }

    /** The server-side player's open menu: the oracle. Records the lap tick a target menu appears. */
    private void observeMenu(Minecraft mc) {
        ServerPlayer p = player(mc);
        AbstractContainerMenu m = p.containerMenu;
        String kind = "none";
        if (m instanceof ChestMenu cm) {
            Container c = cm.getContainer();
            boolean ours = c instanceof BlockEntity be && be.getBlockPos().equals(CHEST)
                    || c == p.getEnderChestInventory() && p.level().getBlockEntity(CHEST) instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity e
                    && p.getEnderChestInventory().isActiveChest(e);
            kind = ours ? "chest" : "otherchest";
        } else if (m instanceof CraftingMenu && p.blockPosition().distSqr(TABLE) < 64) {
            kind = "table";
        } else if (m != p.inventoryMenu) {
            kind = m.getClass().getSimpleName();
        }
        if (m != lastMenuRef) {
            if (kind.equals("chest")) chestOpenedAt = lapTicks;
            if (kind.equals("table")) tableOpenedAt = lapTicks;
        }
        lastMenuRef = m;
        lastMenu = kind;
    }

    private void endLap(Minecraft mc) {
        if (runner.active()) runner.stop(lapTicks * 50L);
        baritone.getPathingBehavior().cancelEverything();
        String held = heldKeys();
        if (!held.isEmpty()) log("keys still held after lap " + index + ": " + held);
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
        if (Boolean.getBoolean("ostinato.interacttest.exit")) Minecraft.getInstance().stop();
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
