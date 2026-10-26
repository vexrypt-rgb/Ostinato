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
    // Ostinato: furnish leg of the harness (-Dostinato.mazetest.furnish, default true): dig, then hand the room to TenorClef.
    private static final boolean FURNISH = !"false".equals(System.getProperty("ostinato.mazetest.furnish"));
    private static final int FURNISH_TIMEOUT = Integer.getInteger("ostinato.mazetest.furnishTimeoutTicks", 20 * 60 * 40);
    private volatile baritone.api.maze.MazeFurnishJob job;

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
                setup(mc);
                state = 3;
                wait = 0;
            }
            case 3 -> {
                if (++wait < 60) return;
                Baritone.settings().mazeMode.value = true;
                var out = b.getMazeProcess().buildHere();
                say("build " + (out.ok() ? "ok: " + out.message : "refused: " + out.error)
                        + " gamemode=" + mc.gameMode.getPlayerMode().getName());
                if (!out.ok()) {
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
            case 8 -> furnishTick(mc);
            case 5, 6 -> shots(mc);
            default -> { }
        }
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
        state = 8;
        wait = 0;
        if (real == null || !out.ok() || job == null) { state = 6; enterRoom(mc); }
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
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
            boolean ok = switch (st.kind()) {
                case SEAL -> !bs.isAir() && bs.getFluidState().isEmpty();
                case FILL -> id.contains("decorated_pot")
                        || (lvl.getBlockEntity(bp) instanceof net.minecraft.world.Container c && !c.isEmpty());
                default -> st.blockIds().isEmpty() ? !bs.isAir() : st.blockIds().contains(id);
            };
            if (ok) have[k]++;
        }
        String tc = tenorState();
        String line = "light=" + have[0] + "/" + want[0] + " container=" + have[1] + "/" + want[1] + " fill=" + have[2] + "/" + want[2]
                + " seal=" + have[3] + "/" + want[3];
        say("FURNISH progress t=" + wait + " " + line + " tenorclef=" + tc + " feet=" + mc.player.blockPosition().toShortString());
        boolean done = have[0] == want[0] && have[1] == want[1] && have[3] == want[3];
        if (done || wait > FURNISH_TIMEOUT || (wait > 400 && tc.startsWith("idle"))) {
            say("FURNISH verify " + line + " -> " + (done ? "PASS" : "FAIL"));
            state = done ? 5 : 6;
            wait = 0;
            enterRoom(mc);
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

    private void setup(Minecraft mc) {
        noPauseOnLostFocus(mc);
        BlockPos p = mc.player.blockPosition();
        say("in world at " + p.toShortString() + " gamemode=" + mc.gameMode.getPlayerMode().getName());
        run("gamerule spawn_mobs false");
        run("gamerule advance_time false");
        run("time set day");
        run("gamemode survival @p");
        run("clear @p");
        run("give @p iron_pickaxe");
        run("give @p iron_shovel");
        run("give @p cobblestone 64");
        run("give @p bread 16");
        // Ostinato: harness-only light kit (-Dostinato.mazetest.lightKit, default true). Survival rooms plan glowstone lights,
        // which a fresh overworld cannot supply; this lets the furnish leg exercise light placement + seals.
        if (!"false".equals(System.getProperty("ostinato.mazetest.lightKit"))) run("give @p glowstone 8");
        if (FILL) {
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
        finish(mc, state == 5 ? "PASS" : "FAIL verify");
    }

    private void finish(Minecraft mc, String result) {
        say("RESULT " + result + " gamemode=" + mc.gameMode.getPlayerMode().getName() + " ticks=" + ticks);
        state = 7;
        if (EXIT) mc.stop();
    }
}
