package baritone.maze;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Ostinato: live survival proof for {@code #maze.backrooms build}. Test harness only: needs {@code testHarness} (or
 * {@code -Dostinato.testHarness=true}) and {@code -Dostinato.mazetest=true}.
 * <p>
 * Creates a fresh default (survival) world, gives an iron pickaxe, cobblestone and food, turns maze mode on and runs
 * {@code maze.backrooms build} from the surface. The bot digs down and carves the room in survival. When the builder
 * stops, the server world is read back cell by cell against the plan, the player is moved into the room (still
 * survival) and four screenshots are saved. One {@code MAZETEST} line per step goes to stdout and
 * {@code mazetest-result.txt} in the game directory; with {@code -Dostinato.mazetest.exit=true} the client then quits.
 * <p>
 * {@code -Dostinato.mazetest.fill=true} (default) fills the player's cell with stone 2..40 below the surface first, so
 * a cave can't make the planner refuse every spot. The room itself is still dug by the bot.
 */
public final class MazeTest implements AbstractGameEventListener {

    private static final String SEED = System.getProperty("ostinato.mazetest.seed", "71006");
    private static final boolean FILL = !"false".equals(System.getProperty("ostinato.mazetest.fill"));
    private static final boolean EXIT = Boolean.getBoolean("ostinato.mazetest.exit");
    private static final int TIMEOUT_TICKS = Integer.getInteger("ostinato.mazetest.timeoutTicks", 20 * 60 * 25);

    private final Baritone b;
    private int state, wait, ticks, shot;
    private boolean builderSeen;
    private int repass;
    private int placeTries;
    private int furnishTries;
    // Ostinato: furnish leg of the harness (-Dostinato.mazetest.furnish, default true): dig, then hand the room to TenorClef.
    private static final boolean FURNISH = !"false".equals(System.getProperty("ostinato.mazetest.furnish"));
    private static final int FURNISH_TIMEOUT = Integer.getInteger("ostinato.mazetest.furnishTimeoutTicks", 20 * 60 * 40);
    private volatile baritone.api.maze.MazeFurnishJob job;
    // Ostinato: stuck watch for the furnish leg. No placed-count change for STUCK_TICKS logs "MAZETEST STUCK-NOW".
    private static final int STUCK_TICKS = Integer.getInteger("ostinato.mazetest.stuckTicks", 20 * 60 * 5);
    private String flatLine = ""; private int flatSince; private BlockPos flatPos; private double flatMoved;
    // Ostinato: expand mode (-Dostinato.mazetest.rooms=N, default 1 = the single-room proof). After a room passes dig+furnish,
    // the harness walks the bot's spawn to a nearby unclaimed cell, preferring a theme not built yet, and runs the same
    // setup -> buildHere -> dig -> furnish -> verify loop there. RESULT is printed once, after the last room.
    // Ostinato: rooms<=0 = forever (keep expanding until a room FAILs or the client dies).
    private static final int ROOMS = Integer.getInteger("ostinato.mazetest.rooms", 1);
    private static final boolean FOREVER = ROOMS <= 0;
    private static final String ROOMS_CAP = FOREVER ? "inf" : String.valueOf(ROOMS);
    // Ostinato: -Dostinato.mazetest.mode=tunnels runs #maze.tunnels (dig-only forever, survival kit + bed spawn) instead of rooms.
    private static final boolean TUNNELS = "tunnels".equals(System.getProperty("ostinato.mazetest.mode"));
    private int tunnelEdges = -1, tunnelFlat;
    private int roomsDone, failStreak;
    private final java.util.List<String> roomLog = new java.util.ArrayList<>();
    private final java.util.Set<Long> cellsDone = new java.util.HashSet<>();
    private int[] nextCell; // Ostinato: run94 expand target cellX, cellZ, preferredY from nextRoom's dry run

    private MazeTest(Baritone b) {
        this.b = b;
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.mazetest")) b.getGameEventHandler().registerEventListener(new MazeTest(b));
    }

    private void say(String m) {
        String line = "MAZETEST " + m;
        System.out.println(line);
        try {
            Path out = Minecraft.getInstance().gameDirectory.toPath().resolve("mazetest-result.txt");
            Files.writeString(out, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void run(String cmd) {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        s.submit(() -> {
            var p = s.getPlayerList().getPlayers().get(0);
            var src = s.createCommandSourceStack().withPosition(p.position()).withLevel(p.level());
            s.getCommands().performPrefixedCommand(src, cmd);
        }).join();
    }

    private static void noPauseOnLostFocus(Minecraft mc) {
        // Ostinato: an unfocused background client must keep ticking. Reflection keeps this compiling across versions.
        try {
            var f = mc.options.getClass().getField("pauseOnLostFocus");
            f.setBoolean(mc.options, false);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    @Override
    public void onTick(TickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) {
            if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
                noPauseOnLostFocus(mc);
                state = 1;
                say("creating world seed=" + SEED);
                CreateWorldScreen.openFresh(mc, () -> {});
                wait = 80;
            } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen cw) {
                cw.getUiState().setSeed(SEED);
                for (var c : cw.children()) {
                    if (c instanceof Button bt && bt.getMessage().getString().contains("Create New World")) {
                        state = 2;
                        wait = 0;
                        bt.onPress(new net.minecraft.client.input.InputWithModifiers() {
                            public int input() { return 0; }
                            public int modifiers() { return 0; }
                        });
                    }
                }
            }
            return;
        }
        if (e.getType() != TickEvent.Type.IN) return;
        ticks++;
        switch (state) {
            case 2 -> {
                if (++wait < 200) return;
                // Ostinato: run96, problem 4. Room 1 was dug straight down from spawn with only the kit (no wood), so its
                // furnish climbed back out for sticks and the barrel (run93 "Surfacing for stick" planks=0 logs=0). Gather
                // the wood on the surface first, like every expand room; setup runs again after it, keeping that wood.
                boolean woodFirst = roomsDone == 0 && !TUNNELS && !room1Provisioned;
                setup(mc, !woodFirst);
                if (woodFirst) { room1Provisioned = true; state = 31; wait = 0; provisionAsked = false; provisionStage = 0; return; }
                state = 3;
                wait = 0;
            }
            case 3 -> {
                if (++wait < 60) return;
                if (TUNNELS) {
                    say("TUNNELS start " + b.getTunnelProcess().start(Baritone.settings().mazeSeed.value) + " gamemode=" + mc.gameMode.getPlayerMode().getName());
                    state = 20;
                    wait = 0;
                    return;
                }
                Baritone.settings().mazeMode.value = true;
                // Ostinato: harness knob so a proof run can pick a room theme/loot roll (default keeps the setting).
                String ms = System.getProperty("ostinato.mazetest.mazeSeed");
                if (ms != null) Baritone.settings().mazeSeed.value = Long.parseLong(ms);
                say("mazeSeed=" + Baritone.settings().mazeSeed.value);
                // Ostinato: run94. An expand room builds the cell nextRoom dry-ran (null on room 1 and when no candidate sealed).
                var out = nextCell != null ? b.getMazeProcess().buildAt(nextCell[0], nextCell[1], nextCell[2]) : b.getMazeProcess().buildHere();
                say("build " + (out.ok() ? "ok: " + out.message : "refused: " + out.error)
                        + " gamemode=" + mc.gameMode.getPlayerMode().getName());
                if (!out.ok()) {
                    // Ostinato: run40 refused on the first look (air/fluid/unloaded within the old radius 3).
                    // Chunks are still arriving; try again before quitting.
                    if (++placeTries < 5) {
                        say("build retry " + placeTries);
                        wait = 0;
                        return;
                    }
                    finish(mc, "FAIL refused");
                    return;
                }
                state = 4;
                wait = 0;
            }
            case 4 -> {
                boolean busy = b.getMazeProcess().isActive() || b.getBuilderProcess().isActive();
                if (b.getBuilderProcess().isActive()) builderSeen = true;
                if (++wait % 100 == 0) {
                    say("progress t=" + wait + " feet=" + mc.player.blockPosition().toShortString() + " " + b.getBuilderProcess().stallDebug
                            + " builder=" + (b.getBuilderProcess().isActive() ? b.getBuilderProcess().displayName() : "idle")
                            + " maze=" + b.getMazeProcess().status() + " " + diag(mc));
                }
                if (wait > TIMEOUT_TICKS) {
                    verify(mc);
                    // Ostinato: run91. A dig timeout (hand-mining with no pick) ended a forever run outright; only a verify FAIL
                    // got the failStreak second chance. In forever mode it is a FAILed room like any other: stop the builder,
                    // log ROOM-RESULT, and the next room (with pick provisioning first) goes on unless two fail in a row.
                    if (FOREVER && b.getMazeProcess().lastBuilt() != null) {
                        say("dig timeout after " + wait + " ticks -> room FAIL, forever continues");
                        b.getMazeProcess().cancel();
                        b.getPathingBehavior().cancelEverything();
                        state = 6;
                        wait = 0;
                        enterRoom(mc);
                        return;
                    }
                    finish(mc, "FAIL timeout");
                    return;
                }
                if (!builderSeen && repass > 0 && wait > 100) {
                    builderSeen = true; // recheck pass found nothing to do
                }
                if (builderSeen && !busy) {
                    say("builder finished after " + wait + " ticks");
                    boolean ok = verify(mc);
                    if (!ok && repass < 2 && b.getMazeProcess().lastBuilt() != null) {
                        // Ostinato: the builder can call a room done while one block is a client ghost (broken locally,
                        // still stone on the server). A second builder pass over the same plan picks it up.
                        repass++;
                        RoomPlan plan = b.getMazeProcess().lastBuilt();
                        say("recheck pass " + repass + ": building the same plan again");
                        b.getBuilderProcess().build("maze-recheck-" + repass, new MazeSchematic(plan, java.util.List.of(), true),
                                new BlockPos(plan.originX, plan.originY, plan.originZ));
                        builderSeen = false;
                        wait = 0;
                        return;
                    }
                    if (ok && FURNISH) { startFurnish(mc); return; }
                    state = ok ? 5 : 6;
                    wait = 0;
                    enterRoom(mc);
                }
            }
            case 20 -> tunnelsTick(mc); // Ostinato: forever; never prints RESULT
            case 30 -> walkTick(mc); // Ostinato: expand walk to the next cell
            case 31 -> provisionTick(mc); // Ostinato: picks for the next dig
            case 7 -> { if (++wait >= 20) startFurnish(mc); }
            case 8 -> furnishTick(mc);
            case 5, 6 -> shots(mc);
            default -> { }
        }
    }

    /** Ostinato: tunnels mode watch. Status every 600 ticks; STUCK-NOW when dug count is flat for STUCK_TICKS. */
    private BlockPos provAt;
    private int provItems;

    private void tunnelsTick(Minecraft mc) {
        if (++wait % 600 != 0) return;
        var tp = b.getTunnelProcess();
        int dug = tp.plan() == null ? 0 : tp.plan().dugCount();
        if (dug != tunnelEdges) { tunnelEdges = dug; tunnelFlat = wait; }
        // Ostinato: provisioning (gathering a pick, food, armor) digs nothing by design; count movement or inventory
        // change as progress there so STUCK-NOW only fires when the bot is really frozen.
        if (tp.provisioning()) {
            BlockPos at = mc.player.blockPosition();
            int items = 0;
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) items += mc.player.getInventory().getItem(i).getCount();
            if (provAt == null || provAt.distManhattan(at) > 3 || items != provItems) tunnelFlat = wait;
            provAt = at;
            provItems = items;
        } else provAt = null;
        say("TUNNELS status t=" + wait + " " + tp.status() + " feet=" + mc.player.blockPosition().toShortString() + " hp=" + (int) mc.player.getHealth() + " food=" + mc.player.getFoodData().getFoodLevel() + " tenorclef=" + tenorState());
        if (wait - tunnelFlat >= STUCK_TICKS) say("STUCK-NOW tunnels dug flat " + ((wait - tunnelFlat) / 1200) + "min dug=" + dug + " feet=" + mc.player.blockPosition().toShortString());
        if (!tp.running()) { say("TUNNELS process stopped; restarting from here"); tp.start(Baritone.settings().mazeSeed.value + wait); }
    }

    /** Ostinato: wraps the registered furnisher to capture the job, then calls furnishHere(). */
    private void startFurnish(Minecraft mc) {
        var real = baritone.api.maze.MazeFurnishers.get();
        say("FURNISH furnisher=" + (real == null ? "none" : real.name()));
        if (real != null) {
            baritone.api.maze.MazeFurnishers.register(new baritone.api.maze.IMazeFurnisher() {
                public boolean furnish(baritone.api.maze.MazeFurnishJob j) { job = j; return real.furnish(j); }
                public String name() { return real.name(); }
            });
        }
        var out = b.getMazeProcess().furnishHere();
        if (real != null) baritone.api.maze.MazeFurnishers.register(real);
        say("FURNISH handoff " + (out.ok() ? "ok: " + out.message : "refused: " + out.error));
        if (real == null || !out.ok() || job == null) {
            // Ostinato: run44 refused on the tick the builder finished. Try again before calling the dig a verify fail.
            if (++furnishTries < 8) {
                say("FURNISH retry " + furnishTries);
                state = 7;
                wait = 0;
                return;
            }
            state = 6;
            enterRoom(mc);
            return;
        }
        state = 8;
        wait = 0;
    }

    private String lightDesc(ServerLevel lvl) {
        StringBuilder s = new StringBuilder();
        for (var st : job.steps) if (st.kind().ordinal() == 3) { var bs = lvl.getBlockState(new BlockPos(st.x(), st.y(), st.z())); if (bs.isAir() || !bs.getFluidState().isEmpty()) s.append("openSeal=").append(st.x()).append(",").append(st.y()).append(",").append(st.z()).append("=").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath()).append(" "); }
        for (var st : job.steps) if (st.kind().ordinal() == 0) s.append(st.x()).append(",").append(st.y()).append(",").append(st.z()).append("=")
                .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(lvl.getBlockState(new BlockPos(st.x(), st.y(), st.z())).getBlock()).getPath()).append(" ");
        return s.toString().trim();
    }

    // Ostinato: run58. Level.getBlockEntity returns null for a server level read off the server thread (this runs on the
    // render thread), so FILL could never count. Read it on the server thread.
    private static boolean containerFilled(Minecraft mc, ServerLevel lvl, BlockPos bp) {
        var server = mc.getSingleplayerServer();
        java.util.function.Supplier<Boolean> q = () -> lvl.getBlockEntity(bp) instanceof net.minecraft.world.Container c && !c.isEmpty();
        if (server == null || server.isSameThread()) return q.get();
        try { return server.submit(q).get(1, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { return false; }
    }

    private void furnishTick(Minecraft mc) {
        if (++wait % 100 != 0 && wait <= FURNISH_TIMEOUT) return;
        ServerLevel lvl = mc.getSingleplayerServer().overworld();
        int[] want = new int[4], have = new int[4];
        for (var st : job.steps) {
            int k = st.kind().ordinal();
            want[k]++;
            BlockPos bp = new BlockPos(st.x(), st.y(), st.z());
            var bs = lvl.getBlockState(bp);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath(); // Ostinato: plan ids are bare ("glowstone"), not "minecraft:glowstone"
            boolean ok = switch (st.kind()) {
                case SEAL -> !bs.isAir() && bs.getFluidState().isEmpty();
                case FILL -> id.contains("decorated_pot")
                        || containerFilled(mc, lvl, bp); // Ostinato: run58 server-thread read
                default -> st.blockIds().isEmpty() ? !bs.isAir() : st.blockIds().contains(id) || (id.endsWith("_planks") && st.blockIds().stream().anyMatch(b -> b.endsWith("_planks"))); // Ostinato: run48. Any wood counts on a wood cell
            };
            if (ok) have[k]++;
        }
        String tc = tenorState();
        String line = "light=" + have[0] + "/" + want[0] + " container=" + have[1] + "/" + want[1] + " fill=" + have[2] + "/" + want[2]
                + " seal=" + have[3] + "/" + want[3];
        String ch = tenorChain(); // Ostinato: run98
        say("FURNISH progress t=" + wait + " " + line + " tenorclef=" + tc + " " + lightDesc(lvl) + " feet=" + mc.player.blockPosition().toShortString() + " chain=" + ch);
        stuckWatch(mc, line, tc + " chain=" + ch);
        // Ostinato: a filled container is part of the survival proof. An empty barrel is not a pass.
        boolean done = have[0] == want[0] && have[1] == want[1] && have[2] == want[2] && have[3] == want[3];
        if (done || wait > FURNISH_TIMEOUT || (wait > 400 && tc.startsWith("idle"))) {
            say("FURNISH verify " + line + " lights=" + lightDesc(lvl) + " -> " + (done ? "PASS" : "FAIL"));
            state = done ? 5 : 6;
            wait = 0;
            enterRoom(mc);
        }
    }

    /** Ostinato: placed counts flat for STUCK_TICKS = stuck. Logged every 1200 ticks after that so GB/poll can grep it. */
    private void stuckWatch(Minecraft mc, String line, String tc) {
        BlockPos p = mc.player.blockPosition();
        if (!line.equals(flatLine)) { flatLine = line; flatSince = wait; flatPos = p; flatMoved = 0; return; }
        flatMoved = Math.max(flatMoved, Math.sqrt(p.distSqr(flatPos)));
        int flat = wait - flatSince;
        if (flat >= STUCK_TICKS && (flat - STUCK_TICKS) % 1200 == 0) {
            // Ostinato: run98. The chain tail says which subtask is stuck; keep the top task short.
            int ci = tc.indexOf(" chain=");
            String top = ci < 0 ? tc : tc.substring(0, ci), task = (top.length() > 160 ? top.substring(0, 160) : top) + (ci < 0 ? "" : tc.substring(ci));
            say("STUCK-NOW furnish progress flat " + (flat / 1200) + "min (" + flat + " ticks) " + line + " maxMovedFromStart=" + (int) flatMoved
                    + (flatMoved < 4 ? " POSITION-STAGNANT" : " WANDERING") + " feet=" + p.toShortString() + " task=" + task);
        }
    }

    /**
     * Ostinato: run98. The user task's debug state alone said "Kit spare_pickaxe" for 3 min of "Failed, blacklisting and
     * wandering" with no hint of what it was failing to reach. The sub-task chain below the user task, by reflection.
     */
    private static String tenorChain() {
        try {
            Object mod = Class.forName("adris.altoclef.AltoClef").getMethod("getInstance").invoke(null);
            Object chain = mod.getClass().getMethod("getUserTaskChain").invoke(mod);
            Object task = chain.getClass().getMethod("getCurrentTask").invoke(chain);
            // The deepest task says what is actually happening; it keeps 160 chars, the ones above it 60.
            java.util.List<String> names = new java.util.ArrayList<>();
            for (int d = 0; task != null && d < 8; d++) {
                task = task.getClass().getMethod("getSub").invoke(task);
                if (task != null) names.add(String.valueOf(task).replace('\n', ' '));
            }
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < names.size(); k++) {
                String n = names.get(k);
                sb.append(k == 0 ? "" : " / ").append(n, 0, Math.min(k == names.size() - 1 ? 160 : 60, n.length()));
            }
            return sb.length() == 0 ? "-" : sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Ostinato: TenorClef user-task state by reflection (no compile dependency). */
    private static String tenorState() {
        try {
            Object mod = Class.forName("adris.altoclef.AltoClef").getMethod("getInstance").invoke(null);
            Object chain = mod.getClass().getMethod("getUserTaskChain").invoke(mod);
            boolean active = (boolean) chain.getClass().getMethod("isActive").invoke(chain);
            Object task = null;
            try { task = chain.getClass().getMethod("getCurrentTask").invoke(chain); } catch (ReflectiveOperationException ignored) { }
            return (active ? "active" : "idle") + (task == null ? "" : ":" + String.valueOf(task).replace('\n', ' '));
        } catch (Throwable t) {
            return "absent(" + t.getClass().getSimpleName() + ")";
        }
    }

    /** Ostinato: why a builder that is "active" is not moving: what it wants, what it presses, what it looks at. */
    private String diag(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        try {
            var f = b.getBuilderProcess().getClass().getDeclaredField("incorrectPositions");
            f.setAccessible(true);
            Object v = f.get(b.getBuilderProcess());
            sb.append("incorrect=").append(v instanceof java.util.Collection<?> c ? c.size() : -1);
            RoomPlan plan = b.getMazeProcess().lastBuilt();
            if (plan != null && v instanceof java.util.Collection<?> c && c.size() <= 6) {
                sb.append(" [");
                for (Object o : c) {
                    if (!(o instanceof BlockPos bp)) continue;
                    int rx = bp.getX() - plan.originX, ry = bp.getY() - plan.originY, rz = bp.getZ() - plan.originZ;
                    String role = plan.inRelative(rx, ry, rz) ? plan.role(rx, ry, rz).name() : "OUT";
                    sb.append(bp.toShortString()).append('=').append(role).append(':')
                            .append(mc.level.getBlockState(bp).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
                }
                sb.append("] size=").append(plan.sizeX).append('x').append(plan.sizeY).append('x').append(plan.sizeZ);
            }
            var pf = b.getBuilderProcess().getClass().getDeclaredField("paused");
            pf.setAccessible(true);
            sb.append(" paused=").append(pf.get(b.getBuilderProcess()));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            sb.append("incorrect=?");
        }
        var in = b.getInputOverrideHandler();
        sb.append(" keys=");
        for (var i : baritone.api.utils.input.Input.values()) if (in.isInputForcedDown(i)) sb.append(i.name()).append(',');
        sb.append(" goal=").append(b.getPathingBehavior().getGoal());
        sb.append(" pathing=").append(b.getPathingBehavior().isPathing());
        sb.append(" rot=").append((int) mc.player.getYRot()).append('/').append((int) mc.player.getXRot());
        sb.append(" hit=").append(mc.hitResult == null ? "null" : mc.hitResult.getType()
                + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh ? "@" + bh.getBlockPos().toShortString() : ""));
        sb.append(" held=").append(mc.player.getMainHandItem().getItem());
        sb.append(" onGround=").append(mc.player.onGround());
        sb.append(" screen=").append(mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
        sb.append(" focused=").append(mc.isWindowActive());
        return sb.toString();
    }

    /** Ostinato: fullbright. OptionInstance.set() clamps gamma to 0..1, so write the backing value field directly. */
    private void fullbright(Minecraft mc) {
        double g = Double.parseDouble(System.getProperty("ostinato.mazetest.gamma", "16.0"));
        try {
            Object opt = mc.options.gamma();
            for (Class<?> c = opt.getClass(); c != null; c = c.getSuperclass())
                for (var fl : c.getDeclaredFields())
                    if (fl.getName().equals("value") && !java.lang.reflect.Modifier.isStatic(fl.getModifiers())) { fl.setAccessible(true); fl.set(opt, g); say("fullbright gamma=" + g); return; }
            say("fullbright: no value field");
        } catch (Throwable t) { say("fullbright failed " + t); }
    }
    private boolean room1Provisioned; private int setupRoom = -1; // Ostinato: run96 room 1 wood before the dig
    private void setup(Minecraft mc, boolean caveGuard) {
        boolean firstSetup = setupRoom != roomsDone; // Ostinato: run96 kit and torch kit once per room
        setupRoom = roomsDone;
        noPauseOnLostFocus(mc);
        fullbright(mc);
        BlockPos p = mc.player.blockPosition();
        say("in world at " + p.toShortString() + " gamemode=" + mc.gameMode.getPlayerMode().getName());
        run("gamerule spawn_mobs false");
        // Ostinato: run42. spawn_mobs false still left cave zombies, which killed the dig five times
        // on the walk to a far room. Peaceful clears hostiles; gamemode stays survival.
        run("difficulty peaceful");
        run("gamerule advance_time false");
        run("time set day");
        run("gamemode survival @p");
        // Ostinato: the starter kit is only for the first room; later rooms keep what the bot carried and farmed.
        if (roomsDone == 0 && firstSetup) {
            run("clear @p");
            run("give @p iron_pickaxe");
            run("give @p iron_shovel");
            run("give @p cobblestone 64");
            run("give @p bread 16");
        } else say("setup: keeping inventory for room " + (roomsDone + 1));
        // Ostinato: harness-only light kit (-Dostinato.mazetest.lightKit, default true). Survival rooms plan glowstone lights,
        // which a fresh overworld cannot supply; this lets the furnish leg exercise light placement + seals.
        if (firstSetup && !"false".equals(System.getProperty("ostinato.mazetest.lightKit"))) run("give @p torch 16"); // Ostinato: torch light kit
        if (FILL && caveGuard) {
            int x0 = MazeSeed.cellOf(p.getX()) * MazeSeed.CELL, z0 = MazeSeed.cellOf(p.getZ()) * MazeSeed.CELL;
            int top = p.getY() - 2, bottom = p.getY() - 40;
            int mid = (top + bottom) / 2;
            run("forceload add " + x0 + " " + z0 + " " + (x0 + 31) + " " + (z0 + 31));
            run("fill " + x0 + " " + bottom + " " + z0 + " " + (x0 + 31) + " " + mid + " " + (z0 + 31) + " stone");
            run("fill " + x0 + " " + (mid + 1) + " " + z0 + " " + (x0 + 31) + " " + top + " " + (z0 + 31) + " stone");
            say("filled cell " + x0 + "," + z0 + " y " + bottom + ".." + top + " with stone (cave guard, not the room)");
        }
    }

    /** Reads the server world back against the plan. Interior must be air, the shell must be solid. */
    private boolean verify(Minecraft mc) {
        RoomPlan plan = b.getMazeProcess().lastBuilt();
        if (plan == null) {
            say("verify: no plan was handed to the builder");
            return false;
        }
        ServerLevel lvl = mc.getSingleplayerServer().overworld();
        int interior = 0, carved = 0, shell = 0, solid = 0;
        for (int x = 0; x < plan.sizeX; x++) {
            for (int y = 0; y < plan.sizeY; y++) {
                for (int z = 0; z < plan.sizeZ; z++) {
                    BlockRole r = plan.role(x, y, z);
                    if (r == BlockRole.OUTSIDE) continue;
                    var st = lvl.getBlockState(new BlockPos(plan.originX + x, plan.originY + y, plan.originZ + z));
                    if (r == BlockRole.AIR) {
                        interior++;
                        if (st.isAir()) carved++;
                    } else {
                        shell++;
                        if (!st.isAir() && st.getFluidState().isEmpty()) solid++;
                    }
                }
            }
        }
        boolean ok = interior > 0 && carved == interior; // dig-only survival: shell gaps (entry shaft) are reported, sealed later
        say("verify origin=" + plan.originX + "," + plan.originY + "," + plan.originZ + " size=" + plan.sizeX + "x" + plan.sizeY + "x" + plan.sizeZ
                + " theme=" + plan.theme.id + " interiorCarved=" + carved + "/" + interior + " shellSolid=" + solid + "/" + shell
                + " gamemode=" + mc.gameMode.getPlayerMode().getName() + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    private void enterRoom(Minecraft mc) {
        RoomPlan plan = b.getMazeProcess().lastBuilt();
        // Ostinato: lowest interior cell in the middle column is where feet go.
        int cx = plan.sizeX / 2, cz = plan.sizeZ / 2, fy = 1;
        for (int y = 0; y < plan.sizeY; y++) {
            BlockRole r = plan.role(cx, y, cz);
            if (r == BlockRole.AIR || r == BlockRole.LIGHT || r == BlockRole.FEATURE) { fy = y; break; }
        }
        run("effect give @p night_vision infinite 0 true");
        run("tp @p " + (plan.originX + cx + 0.5) + " " + (plan.originY + fy) + " " + (plan.originZ + cz + 0.5) + " 0 10");
        say("player moved into room for screenshots, gamemode=" + mc.gameMode.getPlayerMode().getName());
    }

    private void shots(Minecraft mc) {
        if (++wait < 40) return;
        if (shot < 4) {
            mc.player.setYRot(shot * 90f);
            mc.player.setXRot(10f);
            if (wait < 60) return;
            net.minecraft.client.Screenshot.grab(mc, false);
            say("screenshot " + (shot + 1) + "/4 yaw=" + (int) mc.player.getYRot() + " saved to " + mc.gameDirectory.toPath().resolve("screenshots"));
            shot++;
            wait = 0;
            return;
        }
        RoomPlan done = b.getMazeProcess().lastBuilt();
        roomsDone++;
        String entry = (done == null ? "?" : done.theme.id + "@" + done.cellX + "," + done.cellZ) + "=" + (state == 5 ? "PASS" : "FAIL");
        roomLog.add(entry);
        if (done != null) cellsDone.add(((long) done.cellX << 32) ^ (done.cellZ & 0xffffffffL));
        say("ROOM-RESULT " + roomsDone + "/" + ROOMS_CAP + " " + entry + " ticks=" + ticks);
        // Ostinato: run90. Forever mode keeps expanding past one failed room (2 cells short of 133 ended the run before the
        // walk to room 2 was ever exercised). The FAIL stays in ROOM-RESULT/roomLog; two failed rooms in a row still stop.
        failStreak = state == 5 ? 0 : failStreak + 1;
        boolean go = state == 5 || (FOREVER && state == 6 && failStreak < 2);
        if (go && (FOREVER || roomsDone < ROOMS) && done != null && nextRoom(mc, done)) return;
        String summary = " rooms=" + roomsDone + "/" + ROOMS_CAP + " themes=" + roomLog.stream().map(r -> r.split("@")[0]).distinct().toList() + " " + roomLog;
        finish(mc, (state == 5 && !FOREVER && roomsDone >= ROOMS ? "PASS" : state == 5 ? "FAIL expand" : failStreak >= 2 ? "FAIL verify x2" : "FAIL verify") + (ROOMS != 1 ? summary : ""));
    }

    /** Ostinato: expand mode. Moves the player to the surface over the next cell and restarts the room loop there. */
    private boolean nextRoom(Minecraft mc, RoomPlan from) {
        long seed = Baritone.settings().mazeSeed.value;
        java.util.Set<String> built = new java.util.HashSet<>();
        for (String r : roomLog) built.add(r.split("@")[0]);
        if (FOREVER && built.size() >= 3) built.retainAll(java.util.Set.of(roomLog.get(roomLog.size() - 1).split("@")[0])); // Ostinato: forever: all themes seen -> restart rotation, just avoid repeating the last one
        java.util.List<int[]> cands = new java.util.ArrayList<>();
        // Ostinato: 2-cell steps so the planner's search radius cannot fold the new room back into the last one.
        for (int ring = 2; ring <= 12; ring += 2) {
            for (int dx = -ring; dx <= ring; dx += 2) for (int dz = -ring; dz <= ring; dz += 2) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                int x = from.cellX + dx, z = from.cellZ + dz;
                if (cellsDone.contains(((long) x << 32) ^ (z & 0xffffffffL))) continue;
                MazeTheme t = MazeTheme.forCell(seed, x, z);
                if (!t.stubbed && !built.contains(t.id)) cands.add(new int[]{x, z});
            }
        }
        cands.add(new int[]{from.cellX + 2, from.cellZ}); // every theme seen: keep growing east
        // Ostinato: run94. The target cell was not the built cell: buildHere() spiralled from the feet to the first cell that
        // seals, so cell -5,1 (hub) was walked to and -6,0 (yellow_office) was built. Dry-run the seal check for each
        // candidate at the Y the build will use (surface - 16), walk to the first that seals, and build exactly that cell.
        MinecraftServer s = mc.getSingleplayerServer();
        int[] pick = null; int top = 0, tried = 0; String why = ""; nextCell = null;
        for (int[] c : cands) {
            if (tried >= 8) break;
            tried++;
            int cbx = c[0] * MazeSeed.CELL + MazeSeed.CELL / 2, cbz = c[1] * MazeSeed.CELL + MazeSeed.CELL / 2;
            int[] r;
            try {
                r = s.submit(() -> {
                    ServerLevel lvl = s.overworld();
                    for (int ccx = (c[0] * MazeSeed.CELL) >> 4; ccx <= (c[0] * MazeSeed.CELL + MazeSeed.CELL - 1) >> 4; ccx++)
                        for (int ccz = (c[1] * MazeSeed.CELL) >> 4; ccz <= (c[1] * MazeSeed.CELL + MazeSeed.CELL - 1) >> 4; ccz++) lvl.getChunk(ccx, ccz);
                    int h = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cbx, cbz);
                    int pref = Math.max(lvl.getMinY(), Math.min(h - 16, lvl.getMaxY() - MazePlanner.SIZE_Y - MazeSeed.COVER));
                    MazePlanner.Attempt a = MazePlanner.place(seed, c[0], c[1], pref, lvl.getMinY(), lvl.getMaxY(), new MazeWorldProbe(lvl));
                    return new int[]{h, a.ok() ? 1 : 0, pref};
                }).get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ex) {
                say("EXPAND failed to load cell " + c[0] + "," + c[1] + ": " + ex);
                continue;
            }
            if (pick == null) { pick = c; top = r[0]; nextCell = null; } // fallback: the first candidate, planner picks near it
            if (r[1] == 1) { pick = c; top = r[0]; nextCell = new int[]{c[0], c[1], r[2]}; break; }
            why += " " + c[0] + "," + c[1] + "=no-seal";
        }
        if (pick == null) return false;
        MazeTheme want = MazeTheme.forCell(seed, pick[0], pick[1]);
        int bx = pick[0] * MazeSeed.CELL + MazeSeed.CELL / 2, bz = pick[1] * MazeSeed.CELL + MazeSeed.CELL / 2;
        say("EXPAND room " + (roomsDone + 1) + "/" + ROOMS_CAP + " -> cell " + pick[0] + "," + pick[1] + " theme=" + want.id
                + " surface=" + bx + "," + top + "," + bz + " built=" + built + " dryrun=" + (nextCell != null ? "seals" : "none") + " tried=" + tried + why);
        run("effect clear @p night_vision");
        // Ostinato: walk there with the kit and everything farmed so far (was: tp). A survival bot has to cross the ground.
        walkTo = new BlockPos(bx, top, bz);
        walkStart = ticks;
        walkLast = mc.player.blockPosition();
        walkLastTick = ticks;
        b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(bx, bz));
        state = 30; wait = 0; shot = 0; builderSeen = false; repass = 0; placeTries = 0; furnishTries = 0; job = null;
        flatLine = ""; flatSince = 0; flatPos = null; flatMoved = 0;
        return true;
    }

    // Ostinato: run91. The kit is only given on room 1 now, and the room dig is Baritone's builder, which never makes a tool:
    // room 2 wore the last pick out and hand-mined stone (1 block / 5-10s) into a 10-min FAIL timeout. Before each later
    // dig, ask TenorClef for stone picks when less than two stone picks' worth of durability is left.
    private boolean provisionAsked;
    private static final int PICK_DURABILITY_MIN = 262;

    private int pickDurability(Minecraft mc, int[] stonePicks) {
        int sum = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (st.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (!id.endsWith("_pickaxe") || id.startsWith("wooden") || id.startsWith("golden")) continue;
            sum += st.getMaxDamage() - st.getDamageValue();
            if (id.equals("stone_pickaxe")) stonePicks[0] += st.getCount();
        }
        return sum;
    }

    private static int itemCount(Minecraft mc, String path) {
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (!st.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().equals(path)) n += st.getCount();
        }
        return n;
    }

    // Ostinato: run92. Wood ran out across rooms too: room 2's kit had planks=1 logs=0, the barrel was skipped and the
    // container step timed out. Provisioning is a short list of stages (picks, then wood); each asks TenorClef only when low.
    private int provisionStage, provisionGainTick;
    private int provisionBest; // Ostinato: run98 best stage metric (pick durability for stage 0, planks for wood)
    // Ostinato: run105. 64 covered the barrel and picks but not the cladding: yellow_office and hub take ~105 planks of
    // floor/wall (yellow concrete is not obtainable, so walls are planks too) plus the barrel. Each wood room then made a
    // second wood trip from the dug room (surface-for / return-home, 2-5 min); the surface trip here takes ~1 min more.
    // Wood left over (a pipes room) carries to the next room, so nothing is wasted.
    private static final int WOOD_MIN_PLANKS = 128;

    private int woodPlanks(Minecraft mc, int[] logs) {
        int planks = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (st.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (id.endsWith("_planks")) planks += st.getCount();
            else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.startsWith("stripped")) { planks += 4 * st.getCount(); logs[0] += st.getCount(); }
        }
        return planks;
    }

    /** The command for the current stage, or null when that stage is already satisfied. */
    private String provisionCommand(Minecraft mc) {
        if (provisionStage == 0) {
            int[] stone = {0};
            int dur = pickDurability(mc, stone);
            if (dur >= PICK_DURABILITY_MIN) { say("PROVISION picks ok durability=" + dur); return null; }
            say("PROVISION picks low durability=" + dur);
            // Ostinato: run97. Owner rule: copper tools before stone. Held ingots (the copper armor kit's leftovers) make a
            // copper pick in one craft; stone stays the fallback, since smelting raw copper first would stall the dig.
            int ingots = itemCount(mc, "copper_ingot");
            if (ingots >= 3) return "@get copper_pickaxe " + (itemCount(mc, "copper_pickaxe") + Math.min(2, ingots / 3));
            return "@get stone_pickaxe " + (stone[0] + 2);
        }
        int[] logs = {0};
        int planks = woodPlanks(mc, logs);
        if (planks >= WOOD_MIN_PLANKS) { say("PROVISION wood ok planks=" + planks); return null; }
        say("PROVISION wood low planks=" + planks + " logs=" + logs[0]);
        return "@get log " + (logs[0] + (WOOD_MIN_PLANKS - planks + 3) / 4);
    }

    private void provisionTick(Minecraft mc) {
        String tc = tenorState();
        if (!provisionAsked) {
            if (provisionStage > 1) { provisionStage = 0; state = 2; wait = 120; return; }
            String cmd = provisionCommand(mc);
            if (cmd == null) { provisionStage++; return; }
            say("PROVISION stage " + provisionStage + " -> " + cmd);
            try {
                Object ex = Class.forName("adris.altoclef.AltoClef").getMethod("getCommandExecutor").invoke(null);
                ex.getClass().getMethod("execute", String.class).invoke(ex, cmd);
            } catch (Throwable t) { say("PROVISION no TenorClef " + t); provisionStage = 0; state = 2; wait = 120; return; }
            provisionAsked = true; wait = 0; provisionBest = Integer.MIN_VALUE; provisionGainTick = 0;
            return;
        }
        int[] stone = {0}, logs = {0};
        int dur = pickDurability(mc, stone), planks = woodPlanks(mc, logs);
        String have = "durability=" + dur + " planks=" + planks;
        // Ostinato: run99. Wood went silent at planks=10 for 2 min (no path, no mining, no chat) and the line only said
        // "Mine And Collect"; the sub-task chain and feet say whether it idles, wanders or walks to a far log.
        if (++wait % 600 == 0) say("PROVISION waiting " + have + " feet=" + mc.player.blockPosition().toShortString()
                + " tenorclef=" + (tc.length() > 120 ? tc.substring(0, 120) : tc) + " chain=" + tenorChain());
        // Ostinato: run93. "@get log 16" reached 48 planks then sat 3+ min on an unreachable log (1M-node path searches).
        // A stage that gains nothing for 2 min ends with what it has.
        // Ostinato: run98. It compared the whole have-string, and durability drops with every block mined: the wood stage
        // sat at 38 planks for 2.5 min while digging and still ran to the 5-min cap. Only the stage's own metric rising counts.
        int metric = provisionStage == 0 ? dur : planks;
        if (metric > provisionBest) { provisionBest = metric; provisionGainTick = wait; }
        boolean flat = wait - provisionGainTick > 2400;
        if ((wait > 100 && tc.startsWith("idle")) || wait > 6000 || flat) {
            say("PROVISION stage " + provisionStage + " done " + have + " after " + wait + " ticks" + (wait > 6000 ? " (cap)" : flat ? " (no gain 2min)" : ""));
            if (wait > 6000 || flat) { try { Class<?> ac = Class.forName("adris.altoclef.AltoClef"); Object mod = ac.getMethod("getInstance").invoke(null); Object chain = ac.getMethod("getUserTaskChain").invoke(mod); chain.getClass().getMethod("cancel", ac).invoke(chain, mod); } catch (Throwable t) { say("PROVISION cancel failed " + t); } }
            provisionAsked = false; provisionStage++;
        }
    }

    private BlockPos walkTo, walkLast;
    private int walkStart, walkLastTick;

    /** Ostinato: expand walk. Arrive (xz within 4) -> room loop; 10 min or 2 min without progress -> logged tp fallback. */
    private void walkTick(Minecraft mc) {
        BlockPos at = mc.player.blockPosition();
        int dx = at.getX() - walkTo.getX(), dz = at.getZ() - walkTo.getZ();
        if (dx * dx + dz * dz <= 16) {
            b.getPathingBehavior().cancelEverything();
            say("EXPAND walked to " + at.toShortString() + " in " + (ticks - walkStart) + " ticks");
            state = 31; wait = 0; provisionAsked = false; provisionStage = 0;
            return;
        }
        if (at.distSqr(walkLast) > 9) { walkLast = at; walkLastTick = ticks; }
        boolean stalled = ticks - walkLastTick > 2400, capped = ticks - walkStart > 12000;
        if (stalled || capped) {
            b.getPathingBehavior().cancelEverything();
            say("EXPAND walk " + (capped ? "cap" : "stalled") + " at " + at.toShortString() + " -> tp fallback to " + walkTo.toShortString());
            run("tp @p " + (walkTo.getX() + 0.5) + " " + walkTo.getY() + " " + (walkTo.getZ() + 0.5));
            state = 31; wait = 0; provisionAsked = false; provisionStage = 0;
            return;
        }
        if (!b.getCustomGoalProcess().isActive()) b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalXZ(walkTo.getX(), walkTo.getZ()));
        if ((ticks - walkStart) % 600 == 0) say("EXPAND walking at " + at.toShortString() + " dist=" + (int) Math.sqrt(dx * dx + dz * dz));
    }

    private void finish(Minecraft mc, String result) {
        say("RESULT " + result + " gamemode=" + mc.gameMode.getPlayerMode().getName() + " ticks=" + ticks);
        state = 7;
        if (EXIT) mc.stop();
    }
}
