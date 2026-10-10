package baritone.process;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.pathing.kinematic.ChainTemplates;
import baritone.pathing.kinematic.ClimbTemplates;
import baritone.pathing.kinematic.JumpTemplates;
import baritone.pathing.kinematic.SimTrace;
import baritone.pathing.movement.movements.MovementChainJump;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementClimbJump;
import baritone.pathing.movement.movements.MovementJump;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flies every kinematic jump template once in a singleplayer world and reports how well the simulation predicted the real
 * player, and whether the jump landed. Each template gets its own little course in the sky, built with commands (the same
 * way {@link VexBench} sets up its arena); the bot is dropped at the start and told to path to the landing block, which the
 * course makes reachable only by that jump.
 * <p>
 * Enabled with {@code -Dostinato.simbench=jump,climb,chain} (any subset, or {@code all}). Also
 * {@code -Dostinato.simbench.limit=N} (courses per kind, evenly spread) and {@code -Dostinato.simbench.exit=true}.
 * Prints SIMBENCH lines and writes {@code simbench/simbench.csv}; the per-tick numbers are in {@code simtrace/}.
 * Needs a superflat-like world, and turns on {@code experimentalMovement} and {@code kinematicTrace} while it runs.
 */
public final class SimBench implements AbstractGameEventListener {

    private static final int OX = 0, OY = 40, OZ = 0, TIMEOUT = 20 * 20;

    private static final class Course {
        final String kind, label;
        final List<String> cmds = new ArrayList<>();

        Course(String kind, String label) {
            this.kind = kind;
            this.label = label.replace(',', ';'); // it is a CSV column
        }

        int gx, gy, gz;
        double sx, sy, sz;
        boolean ladderGoal;
        // clutch / exp courses: what the bot starts with, how the settings are, what should happen
        String item = null;
        int items;
        boolean experimental = true, clutch;
        double health = 20;
        String expect = "ok";
        int minLoss = 0, maxLoss = 0;
        int fellY = OY - 8;
    }

    private final Baritone baritone;
    private final List<Course> courses = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private int index = -1, wait = 100, ticks, grace;
    private boolean arrived;
    private int hold;
    private double startHp, minHp;
    private boolean done, running, started, warmup;
    private int pathTick;
    private boolean oldExperimental, oldTrace, oldPlace, oldBreak, oldClutch;

    private SimBench(Baritone baritone, String kinds) {
        this.baritone = baritone;
        int limit = Integer.getInteger("ostinato.simbench.limit", Integer.MAX_VALUE);
        boolean all = kinds.contains("all");
        String only = System.getProperty("ostinato.simbench.filter", "");
        if (all || kinds.contains("jump")) {
            List<Course> c = new ArrayList<>();
            for (JumpTemplates.Template t : JumpTemplates.ALL) c.add(jump(t));
            courses.addAll(spread(filter(c, only), limit));
        }
        if (all || kinds.contains("climb")) {
            List<Course> c = new ArrayList<>();
            for (ClimbTemplates.Template t : ClimbTemplates.ALL) c.add(climb(t));
            courses.addAll(spread(filter(c, only), limit));
        }
        if (all || kinds.contains("chain")) {
            List<Course> c = new ArrayList<>();
            for (ChainTemplates.Template t : ChainTemplates.ALL) c.add(chain(t));
            courses.addAll(spread(filter(c, only), limit));
        }
        if (all || kinds.contains("clutch")) courses.addAll(filter(clutchCourses(), only));
        if (all || kinds.contains("exp")) courses.addAll(filter(expCourses(), only));
        if (!courses.isEmpty()) {
            // the first search in a fresh JVM is slow (cold code); a bot on a one block ladder slides off meanwhile
            courses.add(0, courses.get(0));
            warmup = true;
        }
    }

    public static void install(Baritone baritone) {
        String kinds = System.getProperty("ostinato.simbench", "");
        if (!kinds.isEmpty()) baritone.getGameEventHandler().registerEventListener(new SimBench(baritone, kinds));
    }

    /** -Dostinato.simbench.filter=text keeps the courses whose label contains it (commas are semicolons in labels). */
    private static List<Course> filter(List<Course> all, String only) {
        if (only.isEmpty()) return all;
        List<Course> out = new ArrayList<>();
        for (Course c : all) if (c.label.contains(only)) out.add(c);
        return out;
    }

    private static List<Course> spread(List<Course> all, int limit) {
        if (all.size() <= limit) return all;
        List<Course> out = new ArrayList<>();
        for (int i = 0; i < limit; i++) out.add(all.get((int) ((long) i * all.size() / limit)));
        return out;
    }

    // ---- course building: frame approach +x, lateral +z, take-off block at (OX, OY - 1, OZ) ----

    private static String block(int a, int y, int b, String state) {
        return String.format("setblock %d %d %d %s", OX + a, OY + y, OZ + b, state);
    }

    private static Course base(String kind, String label) {
        Course c = new Course(kind, label);
        c.cmds.add(String.format("fill %d %d %d %d %d %d air", OX - 8, OY - 6, OZ - 10, OX + 16, OY + 6, OZ + 10));
        return c;
    }

    private static Course jump(JumpTemplates.Template t) {
        Course c = base("jump", "jump " + t.a + "," + t.dy + "," + t.b + " runUp=" + t.runUp);
        for (int a = -t.runUp; a <= 0; a++) c.cmds.add(block(a, -1, 0, "stone"));
        c.cmds.add(block(t.a, t.dy - 1, t.b, "stone"));
        for (int[] cell : t.cells) if (cell[3] == 1) c.cmds.add(block(cell[0], cell[1], cell[2], "stone"));
        goal(c, t.a, t.dy, t.b, false);
        start(c, 0.5, 0, 0.5);
        return c;
    }

    private static Course chain(ChainTemplates.Template t) {
        Course c = base("chain", "chain pad=" + t.padA + "," + t.padDy + " " + t.a + "," + t.dy + "," + t.b);
        for (int a = -t.runUp; a <= 0; a++) c.cmds.add(block(a, -1, 0, "stone"));
        c.cmds.add(block(t.padA, t.padDy - 1, 0, "stone"));
        c.cmds.add(block(t.a, t.dy - 1, t.b, "stone"));
        for (int[] cell : t.cells) if (cell[3] == 1) c.cmds.add(block(cell[0], cell[1], cell[2], "stone"));
        goal(c, t.a, t.dy, t.b, false);
        start(c, 0.5, 0, 0.5);
        return c;
    }

    private static String facing(int[] wall) { // the way a ladder faces is away from its wall
        return wall[0] == 1 ? "west" : wall[0] == -1 ? "east" : wall[1] == 1 ? "north" : "south";
    }

    private static void ladder(Course c, int a, int y, int b, int[] wall) {
        c.cmds.add(block(a + wall[0], y, b + wall[1], "stone"));
        c.cmds.add(block(a, y, b, "ladder[facing=" + facing(wall) + "]"));
    }

    private static Course climb(ClimbTemplates.Template t) {
        boolean leap = t.mode == ClimbTemplates.LEAP;
        Course c = base("climb", "climb " + (leap ? "leap" : "grab") + " wall=" + t.wall + " " + t.a + "," + t.dy + "," + t.b + (t.destLadder ? " ladder" : ""));
        int[] wall = ClimbTemplates.wallVector(t.wall);
        if (!leap) {
            for (int a = -t.runUp; a <= 0; a++) c.cmds.add(block(a, -1, 0, "stone"));
            ladder(c, t.a, t.dy, t.b, wall);
            start(c, 0.5, 0, 0.5);
        } else {
            ladder(c, 0, 0, 0, wall);
            if (t.destLadder) ladder(c, t.a, t.dy, t.b, wall);
            else c.cmds.add(block(t.a, t.dy - 1, t.b, "stone"));
            start(c, 0.5 - wall[0] * 0.2, 0, 0.5 - wall[1] * 0.2); // hanging off the ladder, on its open side
        }
        goal(c, t.a, t.dy, t.b, t.destLadder);
        return c;
    }

    /** A drop of h blocks off a platform edge to a floor; wall stone beside the landing column on the given side (null = none). */
    private static Course drop(String kind, String label, int h, int[] wall) {
        Course c = new Course(kind, label);
        c.cmds.add(String.format("fill %d %d %d %d %d %d air", OX - 8, OY - h - 6, OZ - 10, OX + 16, OY + 6, OZ + 10));
        for (int a = -2; a <= 0; a++) c.cmds.add(block(a, -1, 0, "stone"));
        for (int a = 1; a <= 4; a++) for (int b = -1; b <= 1; b++) c.cmds.add(block(a, -h - 1, b, "stone"));
        if (wall != null) {
            // a wall beside the landing column (a = 1), all the way up to the platform
            for (int y = -h; y <= -1; y++) c.cmds.add(block(1 + wall[0], y, wall[1], "stone"));
        }
        goal(c, 1, -h, 0, false);
        start(c, 0.5, 0, 0.5);
        c.fellY = OY - h - 8;
        return c;
    }

    private static final int[] WALL_EAST = {1, 0}, WALL_SOUTH = {0, 1}, WALL_NORTH = {0, -1};

    private static Course clutchCourse(String item, int h, int[] wall, String name) {
        Course c = drop("clutch", "clutch " + item + " drop=" + h + " " + name, h, wall);
        c.clutch = true;
        c.item = item;
        c.items = 8;
        c.experimental = false; // otherwise it would just take the fall: the clutch is what is being tested
        // the goal is a step past the landing, otherwise the path ends the moment we land and the pickup never gets its ticks
        if (wall == WALL_EAST) goal(c, 1, -h, -1, false);
        else goal(c, 3, -h, 0, false);
        return c;
    }

    private static List<Course> clutchCourses() {
        List<Course> out = new ArrayList<>();
        // the wall straight ahead: no time to shed the walk-off speed on a short drop, a ladder doesn't fit once we are pinned against it, so the planner leaves it alone
        for (int h : new int[]{6, 8, 12, 20}) {
            Course c = clutchCourse("ladder", h, WALL_EAST, "wall=east");
            c.expect = "nopath";
            out.add(c);
        }
        for (int h : new int[]{6, 8}) out.add(clutchCourse("ladder", h, WALL_SOUTH, "wall=south"));
        out.add(clutchCourse("ladder", 12, WALL_SOUTH, "wall=south"));
        out.add(clutchCourse("ladder", 12, WALL_NORTH, "wall=north"));
        for (int h : new int[]{8, 12}) out.add(clutchCourse("vine", h, WALL_EAST, "wall=east"));
        // nothing to hang it on: no path at all, there is no clutch and experimentalMovement is off
        Course none = clutchCourse("ladder", 12, null, "no wall");
        none.expect = "nopath";
        out.add(none);
        // no ladders in the inventory: same
        Course empty = clutchCourse("ladder", 12, WALL_EAST, "no item");
        empty.items = 0;
        empty.expect = "nopath";
        out.add(empty);
        return out;
    }

    private static Course expDrop(int h, boolean on, double health, String expect) {
        Course c = drop("exp", "exp drop=" + h + " " + (on ? "on" : "off") + " hp=" + (int) health, h, null);
        c.experimental = on;
        c.health = health;
        c.expect = expect;
        if (expect.equals("ok")) {
            c.minLoss = Math.max(0, h - 3 - 1); // the sim of vanilla fall damage (blocks - 3), give or take the landing cell
            c.maxLoss = h - 3 + 1;
        }
        return c;
    }

    private static List<Course> expCourses() {
        List<Course> out = new ArrayList<>();
        // falls that hurt on purpose. the planner affords them down to experimentalMinHealth (12) so 20 hp covers 8 damage = 11 blocks
        for (int h : new int[]{4, 6, 8, 11}) out.add(expDrop(h, true, 20, "ok"));
        out.add(expDrop(12, true, 20, "nopath"));  // 9 damage would leave us under the limit
        out.add(expDrop(4, true, 14, "ok"));       // 1 damage from 14 is 13, fine
        out.add(expDrop(6, true, 14, "nopath"));   // 3 damage from 14 is 11, under the limit
        // controls: the same falls with it off
        out.add(expDrop(6, false, 20, "nopath"));
        out.add(expDrop(11, false, 20, "nopath"));
        // gating: a plain parkour gap, which the default settings don't allow, and which experimentalMovement does
        for (boolean on : new boolean[]{true, false}) {
            Course c = new Course("exp", "exp gap=3 " + (on ? "on" : "off"));
            c.cmds.add(String.format("fill %d %d %d %d %d %d air", OX - 8, OY - 6, OZ - 10, OX + 16, OY + 6, OZ + 10));
            for (int a = -3; a <= 0; a++) c.cmds.add(block(a, -1, 0, "stone"));
            c.cmds.add(block(4, -1, 0, "stone"));
            goal(c, 4, 0, 0, false);
            start(c, 0.5, 0, 0.5);
            c.experimental = on;
            c.expect = on ? "ok" : "nopath";
            out.add(c);
        }
        return out;
    }

    private static void goal(Course c, int a, int dy, int b, boolean ladder) {
        c.gx = OX + a;
        c.gy = OY + dy;
        c.gz = OZ + b;
        c.ladderGoal = ladder;
    }

    private static void start(Course c, double a, int y, double b) {
        c.sx = OX + a;
        c.sy = OY + y;
        c.sz = OZ + b;
    }

    // ---- driver ----

    @Override
    public void onTick(TickEvent event) {
        if (done || event.getType() != TickEvent.Type.IN) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (me == null || server == null) return;
        if (me.isDeadOrDying()) {
            me.respawn();
            wait = Math.max(wait, 20);
            running = false;
            return;
        }
        if (wait > 0) {
            --wait;
            if (wait == 20 && running && ticks < 0 && index >= 0 && courses.get(index).health < 20) {
                run("damage @p " + (20 - courses.get(index).health) + " minecraft:generic"); // after the heal has landed
            }
            if (wait > 0) return;
            if (!started) {
                started = true;
                oldExperimental = Baritone.settings().experimentalMovement.value;
                oldTrace = Baritone.settings().kinematicTrace.value;
                oldPlace = Baritone.settings().allowPlace.value;
                oldBreak = Baritone.settings().allowBreak.value;
                oldClutch = Baritone.settings().allowLadderClutch.value;
                Baritone.settings().experimentalMovement.value = true;
                Baritone.settings().kinematicTrace.value = true;
                run("gamerule doMobSpawning false", "gamerule doImmediateRespawn true", "gamerule doDaylightCycle false", "gamerule naturalRegeneration false",
                        "time set day", "difficulty peaceful", "gamemode survival @a", "effect give @a saturation 999999 10 true");
                log(courses.size() + " courses");
            }
            if (!running) {
                if (++index >= courses.size()) {
                    finish();
                    return;
                }
                Course c = courses.get(index);
                MovementJump.forgetFailures();
                MovementClimbJump.forgetFailures();
                MovementChainJump.forgetFailures();
                MovementParkour.disabled = c.kind.equals("chain"); // plain parkour would pre-empt the chain on short gaps
                Baritone.settings().experimentalMovement.value = c.experimental;
                Baritone.settings().allowPlace.value = c.clutch;
                Baritone.settings().allowBreak.value = c.clutch;
                Baritone.settings().allowLadderClutch.value = c.clutch;
                run(c.cmds.toArray(new String[0]));
                run("clear @a", "effect give @a saturation 999999 10 true", "effect give @a instant_health 1 10 true");
                if (c.items > 0) run("give @a " + c.item + " " + c.items);
                run(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f -90 0", c.sx, c.sy, c.sz));
                wait = 40; // let the blocks reach the client and the bot settle
                running = true;
                ticks = -1;
            } else if (ticks < 0) {
                SimTrace.last = null;
                Course c = courses.get(index);
                // a bot hanging on a one block ladder slides off it while the course settles: put it back right before it starts
                run(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f -90 0", c.sx, c.sy, c.sz));
                // the previous course's movement can fail while the world is rebuilt under it: that is not this course's failure
                MovementJump.forgetFailures();
                MovementClimbJump.forgetFailures();
                MovementChainJump.forgetFailures();
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(c.gx, c.gy, c.gz));
                ticks = 0;
                hold = 0;
                startHp = me.getHealth();
                minHp = 99;
                arrived = false;
                pathTick = -1;
            }
            return;
        }
        if (!running || ticks < 0) return;
        ticks++;
        Course c = courses.get(index);
        if (ticks > 3) minHp = Math.min(minHp, me.getHealth()); // not the damage that sets the starting health up
        BlockPos feet = baritone.getPlayerContext().playerFeet();
        boolean there = feet.getX() == c.gx && feet.getY() == c.gy && feet.getZ() == c.gz
                && (c.ladderGoal ? me.onClimbable() : me.onGround());
        if (there) arrived = true; // a hang on a ladder does not last while the trace is awaited: the first arrival counts
        if (pathTick < 0 && baritone.getPathingBehavior().isPathing()) pathTick = ticks;
        if (ticks == 30 || ticks == 6) {
            StringBuilder sb = new StringBuilder();
            baritone.getPathingBehavior().getPath().ifPresent(path -> path.movements().forEach(m -> sb.append(m.getClass().getSimpleName()).append(' ').append(m.getDest()).append("; ")));
            log("path " + courses.get(index).label + " (first pathing tick " + pathTick + "): " + (sb.length() == 0 ? "none" : sb));
        }
        String why = arrived ? "ok" : me.getY() < c.fellY ? "fell" : ticks >= TIMEOUT ? "timeout"
                : ticks > 100 && !baritone.getPathingBehavior().isPathing() ? "nopath" : null;
        boolean traced = c.kind.equals("jump") || c.kind.equals("climb") || c.kind.equals("chain");
        if (why != null && why.equals("ok") && traced && SimTrace.last == null && grace++ < 40) {
            return; // landed; the movement reports its trace a tick or two later
        }
        if (why != null && why.equals("ok") && c.clutch && c.item.equals("ladder") && c.items > 0 && count(me, "ladder") < c.items && hold++ < 200) {
            return; // the ladder comes back off the wall after the landing
        }
        if (why != null && why.equals("ok") && !traced && hold++ < 10) {
            return; // the health update trails the landing by a tick or two
        }
        if (why != null && !traced) {
            double loss = startHp - minHp;
            int left = c.items > 0 ? count(me, c.item) : 0;
            log(String.format(Locale.ROOT, "detail %s: %s after %d ticks, hp %.1f -> low %.1f (lost %.1f), %s left %d/%d", c.label, why, ticks, startHp, minHp, loss, c.item, left, c.items));
            if (why.equals("ok")) {
                if (!c.expect.equals("ok")) why = "unexpected-path";
                else if (c.clutch && loss > 0.5) why = "hurt";
                else if (c.clutch && c.item.equals("ladder") && left < c.items) why = "ladder-lost";
                else if (!c.clutch && (loss < c.minLoss || loss > c.maxLoss)) why = "loss " + loss;
            } else if (why.equals(c.expect)) {
                why = "ok";
            }
        }
        if (why != null) {
            grace = 0;
            arrived = false;
            if (warmup && index == 0) {
                log("warm-up " + c.label + ": " + why + " after " + ticks + " ticks (not counted)");
            } else {
                record(c, why);
            }
            baritone.getPathingControlManager().cancelEverything();
            running = false;
            wait = 5;
        }
    }

    private void record(Course c, String why) {
        SimTrace t = SimTrace.last;
        String row = String.format(Locale.ROOT, "%s,%s,%s,%d,%s", c.kind, c.label, why, ticks,
                t == null ? ",,,,," : String.format(Locale.ROOT, "%.4f,%.4f,%d,%.3f,%d,%.1f", t.maxStep(), t.rmsStep(), t.firstDiverged(), t.openLoopError(), t.groundMismatches(), t.maxYawLag()));
        rows.add(row);
        log(row);
    }

    private void finish() {
        done = true;
        MovementParkour.disabled = false;
        Baritone.settings().experimentalMovement.value = oldExperimental;
        Baritone.settings().kinematicTrace.value = oldTrace;
        Baritone.settings().allowPlace.value = oldPlace;
        Baritone.settings().allowBreak.value = oldBreak;
        Baritone.settings().allowLadderClutch.value = oldClutch;
        List<String> out = new ArrayList<>();
        out.add("kind,course,result,ticks,maxStep,rmsStep,firstDiverged,openLoopEnd,groundMismatch,maxYawLag");
        out.addAll(rows);
        try {
            Path dir = Paths.get("simbench");
            Files.createDirectories(dir);
            Files.write(dir.resolve("simbench.csv"), out);
        } catch (Exception e) {
            log("csv: " + e);
        }
        for (String kind : new String[]{"jump", "climb", "chain", "clutch", "exp"}) {
            int n = 0, ok = 0, held = 0, traced = 0;
            List<Double> steps = new ArrayList<>(), drift = new ArrayList<>();
            for (String r : rows) {
                String[] f = r.split(",", -1);
                if (!f[0].equals(kind)) continue;
                n++;
                if (f[2].equals("ok")) ok++;
                if (!f[4].isEmpty()) {
                    traced++;
                    steps.add(Double.parseDouble(f[4]));
                    drift.add(Double.parseDouble(f[7]));
                    if (Integer.parseInt(f[6]) < 0) held++;
                }
            }
            if (n == 0) continue;
            Collections.sort(steps);
            Collections.sort(drift);
            log(String.format(Locale.ROOT, "SUMMARY %s landed=%d/%d traced=%d simHeld=%d medianMaxStep=%.4f p90MaxStep=%.4f medianOpenLoopEnd=%.3f", kind, ok, n, traced, held,
                    pct(steps, 0.5), pct(steps, 0.9), pct(drift, 0.5)));
        }
        if (Boolean.getBoolean("ostinato.simbench.exit")) Minecraft.getInstance().stop();
    }

    private static double pct(List<Double> sorted, double p) {
        return sorted.isEmpty() ? Double.NaN : sorted.get(Math.min(sorted.size() - 1, (int) (p * sorted.size())));
    }

    private static int count(LocalPlayer me, String item) {
        int n = 0;
        for (int slot = 0; slot < me.getInventory().getContainerSize(); slot++) if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(me.getInventory().getItem(slot).getItem()).getPath().equals(item)) n += me.getInventory().getItem(slot).getCount();
        return n;
    }

    private static void run(String... cmds) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
            var src = server.createCommandSourceStack();
            if (p != null) src = src.withPosition(new Vec3(OX, OY, OZ)).withLevel(p.level());
            for (String cmd : cmds) server.getCommands().performPrefixedCommand(src, cmd);
        }).join();
    }

    private static void log(String s) {
        System.out.println("SIMBENCH " + s);
    }
}
