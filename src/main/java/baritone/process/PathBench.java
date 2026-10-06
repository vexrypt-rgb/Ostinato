package baritone.process;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Path-following bench on the vexflat save: for each course (a walk, a bridge over a trench, a parkour gap) it sets the
 * goal and records time, sideways drift from the straight line and whether the player fell, once with
 * humanLookEverywhere off and once on. Enabled with -Dostinato.pathbench=R (repeats of the whole set); writes
 * pvpbench/pathbench.csv and prints "PATHBENCH" lines. Nothing here is used outside a bench run.
 */
public final class PathBench implements AbstractGameEventListener {
    private static final String[] KINDS = System.getProperty("ostinato.path.kinds", "walk,bridge,parkour").split(",");
    private static final int LIMIT_TICKS = 20 * 60;

    private final Baritone baritone;
    private final int repeats;
    private final List<String> rows = new ArrayList<>();
    private int round = -1, wait = 60, ticks, padY;
    private boolean worldAsked, started, done;
    private boolean fell;
    private double minY, maxDev, devSum;
    private Vec3 from, goal;
    private String kind;
    private boolean human;

    private PathBench(Baritone baritone, int repeats) {
        this.baritone = baritone;
        this.repeats = repeats;
    }

    public static void install(Baritone baritone) {
        int n = Integer.getInteger("ostinato.pathbench", 0);
        if (n > 0) baritone.getGameEventHandler().registerEventListener(new PathBench(baritone, n));
    }

    @Override
    public void onTick(TickEvent event) {
        if (done) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (me == null || server == null) {
            if (!worldAsked && server == null && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                worldAsked = true;
                log("opening vexflat");
                Minecraft.getInstance().createWorldOpenFlows().openWorld("vexflat", () -> {});
            }
            return;
        }
        if (event.getType() != TickEvent.Type.IN) return;
        if (wait > 0) {
            if (--wait == 0) begin(me);
            return;
        }
        if (!started) return;
        ticks++;
        if (ticks == 5) log("ground@11 " + me.level().getBlockState(new BlockPos(11, padY, 0)) + " below " + me.level().getBlockState(new BlockPos(11, padY - 3, 0)) + " player " + me.position());
        double dx = goal.x - from.x, dz = goal.z - from.z, len = Math.hypot(dx, dz);
        double dev = Math.abs((me.getX() - from.x) * dz - (me.getZ() - from.z) * dx) / len;
        maxDev = Math.max(maxDev, dev);
        devSum += dev;
        minY = Math.min(minY, me.getY());
        if (me.getY() < padY + 1 - 2.5) fell = true;
        boolean arrived = Math.hypot(me.getX() - goal.x, me.getZ() - goal.z) <= 1.5 && Math.abs(me.getY() - goal.y) < 1.5;
        if (arrived || ticks >= LIMIT_TICKS || me.isDeadOrDying()) {
            String result = arrived ? "done" : me.isDeadOrDying() ? "death" : "timeout";
            String row = String.format("%d,%s,%s,%s,%d,%.2f,%.2f,%s,%.1f", round, kind, human ? "on" : "off", result, ticks,
                    maxDev, devSum / Math.max(1, ticks), fell ? "fell" : "-", minY - (padY + 1));
            rows.add(row);
            log(row);
            started = false;
            baritone.getPathingControlManager().cancelEverything();
            wait = 20;
        }
    }

    /** Lay the course for the next round and send the player at it. */
    private void begin(LocalPlayer me) {
        if (++round >= repeats * KINDS.length * 2) {
            finish();
            return;
        }
        kind = KINDS[(round / 2) % KINDS.length];
        human = round % 2 == 1;
        if (padY == 0) {
            padY = me.level().getMinY();
            for (int y = me.level().getMinY(); y < me.level().getMaxY(); y++) {
                if (me.level().getBlockState(new BlockPos(0, y, 0)).is(Blocks.GRASS_BLOCK)) {
                    padY = y;
                    break;
                }
            }
        }
        if (padY <= me.level().getMinY()) padY = me.level().getMinY() + 1; // same floor as VexBench: grass on a bedrock row
        int stand = padY + 1;
        String name = me.getGameProfile().name();
        int gap = kind.equals("bridge") ? 8 : kind.equals("parkour") ? 3 : 0;
        run("gamerule spawn_mobs false", "gamerule keep_inventory true", "gamerule advance_time false", "time set day", "difficulty peaceful",
                "gamemode survival " + name, "clear " + name,
                // the save is one grass layer on the void: restore it, then cut the trench (wide enough that going around is not an option)
                "fill -5 " + (padY - 1) + " -60 40 " + (padY - 1) + " 60 bedrock",
                "fill -5 " + padY + " -60 40 " + padY + " 60 grass_block",
                "fill -5 " + (padY + 1) + " -60 40 " + (padY + 6) + " 60 air",
                gap > 0 ? "fill 10 " + (padY - 1) + " -60 " + (10 + gap - 1) + " " + padY + " 60 air" : "say walk",
                "give " + name + " cobblestone 64",
                "tp " + name + " 0 " + stand + " 0 -90 0");
        var s = baritone.settings();
        s.humanLookEverywhere.value = human;
        s.allowBreak.value = false;
        s.allowPlace.value = kind.equals("bridge");
        s.allowParkour.value = kind.equals("parkour");
        s.allowParkourPlace.value = false;
        s.allowParkourAscend.value = kind.equals("parkour");
        from = new Vec3(0.5, stand, 0.5);
        goal = kind.equals("walk") ? new Vec3(30.5, stand, 15.5) : new Vec3(10 + gap + 6.5, stand, 0.5);
        fell = false;
        ticks = 0;
        minY = stand;
        maxDev = devSum = 0;
        started = true;
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock((int) goal.x, (int) goal.y, (int) goal.z));
        log("start " + round + " " + kind + " human=" + human);
    }

    private void finish() {
        done = true;
        // the trench is cut into the shared vexflat save: put the layer back so later fight benches don't walk into the void
        run("fill -5 " + (padY - 1) + " -60 40 " + (padY - 1) + " 60 bedrock", "fill -5 " + padY + " -60 40 " + padY + " 60 grass_block");
        try {
            Path dir = Paths.get("pvpbench");
            Files.createDirectories(dir);
            List<String> out = new ArrayList<>();
            out.add("round,kind,human,result,ticks,maxDev,meanDev,fell,minDy");
            out.addAll(rows);
            Files.write(dir.resolve("pathbench.csv"), out);
        } catch (Exception e) {
            log("csv: " + e);
        }
        log("SUMMARY rows=" + rows.size());
        if (Boolean.getBoolean("ostinato.pathbench.exit")) Minecraft.getInstance().stop();
    }

    private static void run(String... cmds) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
            var src = server.createCommandSourceStack();
            if (p != null) src = src.withPosition(new Vec3(0, p.getY(), 0)).withLevel(p.level());
            for (String c : cmds) server.getCommands().performPrefixedCommand(src, c);
        }).join();
    }

    private static void log(String s) {
        System.out.println("PATHBENCH " + s);
    }
}
