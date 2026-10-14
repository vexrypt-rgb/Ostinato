package baritone.structure;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * TEMP parkour harness, installed like BastTest and inert unless -Dostinato.pktest=<save folder> is set.
 * Opens that save, sets the map's game mode (creative/spectator become adventure) and walks a course script
 * (-Dostinato.pktest.goals=<file>: "tp x y z", "cmd <command>", "goal x y z", "near x y z r"), or, without one,
 * pressure plates in nearest-first order. Every jump-like movement is recorded. A fall puts the player back where the
 * jump started (as a map checkpoint would); the same jump failing maxFails times is skipped by a tp to its landing.
 * PK lines in the log; CSV + summary under run/pk-results/.
 * -Dostinato.pktest.mode=physics|kinematic, .minutes, .goalSeconds, .maxFails, .scan
 */
public final class PkTest implements AbstractGameEventListener {

    private static final String WORLD = System.getProperty("ostinato.pktest");
    private static final String MODE = System.getProperty("ostinato.pktest.mode", "kinematic");
    private static final String GOALS = System.getProperty("ostinato.pktest.goals");
    private static final int MINUTES = Integer.getInteger("ostinato.pktest.minutes", 20);
    private static final int GOAL_TICKS = 20 * Integer.getInteger("ostinato.pktest.goalSeconds", 240);
    private static final int MAX_FAILS = Integer.getInteger("ostinato.pktest.maxFails", 3);
    private static final int SCAN = Integer.getInteger("ostinato.pktest.scan", 160);
    private static final Set<String> JUMPS = Set.of("MovementParkour", "MovementJump", "MovementChainJump", "MovementClimbJump",
            "MovementSlime", "LadderClutch", "MovementAscend", "MovementPillar", "MovementDiagonal");

    public static void install(Baritone b) {
        if (WORLD != null && !WORLD.isEmpty()) b.getGameEventHandler().registerEventListener(new PkTest(b));
    }

    private static final class Attempt {
        int goal; String cls; BetterBlockPos src, dst; List<String> tags; boolean ok; String why = ""; int ticks;
    }

    /** One course-script line. kind: tp, cmd, goal, near. */
    private record Step(String kind, int x, int y, int z, int r, String text) {
        BlockPos pos() { return new BlockPos(x, y, z); }
    }

    private final Baritone b;
    private int state, wait, tick, goalTick, idle, goalFails, stepIdx = -1, goalNo, goalCount, falls, resets, skippedGoals, skippedJumps, reached, deathCool;
    private final List<Step> steps = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private final Map<BlockPos, Integer> jumpFails = new HashMap<>();
    private Attempt active, pending; private IMovement activeMv; private int activeStart;
    private String drillTag; private boolean resume, reissue; private int idleNoPlan; private Vec3 lastPos; private BlockPos lastGood, checkpoint; private double bestDist; private int bestTick;

    private PkTest(Baritone b) { this.b = b; }

    private static void say(String m) { System.out.println("[PK] " + m); }

    private void run(String cmd) {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        s.submit(() -> {
            var p = s.getPlayerList().getPlayers().get(0);
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withPosition(p.position()).withLevel(p.level()), cmd);
        }).join();
    }

    private void tp(BlockPos p) {
        // stand on top of what is below: a fence or wall is 1.5 high, a slab 0.5
        Level lv = Minecraft.getInstance().level;
        var shape = lv.getBlockState(p.below()).getCollisionShape(lv, p.below());
        double y = shape.isEmpty() ? p.getY() : p.getY() - 1 + shape.max(net.minecraft.core.Direction.Axis.Y);
        run("tp @p " + (p.getX() + 0.5) + " " + y + " " + (p.getZ() + 0.5));
        lastPos = null;
    }

    // -Dostinato.pkshots: in-game screenshots of each path visualization, one viz shown at a time while moving
    private static final boolean SHOTS = System.getProperty("ostinato.pkshots") != null;
    private int shotTick, shotNo, shotWait;
    private long lastDriven;

    private void vizOnly(int k) {
        Settings st = BaritoneAPI.getSettings();
        Settings.Setting<Boolean>[] all = vizSettings(st);
        for (int i = 0; i < all.length; i++) all[i].value = k < 0 || i == k;
    }

    @SuppressWarnings("unchecked")
    private static Settings.Setting<Boolean>[] vizSettings(Settings st) {
        return new Settings.Setting[]{st.renderKinCorridor, st.renderKinRollouts, st.renderKinJumps,
                st.renderPhysSpeedTrail, st.renderPhysInputs, st.renderPhysSearch};
    }

    private void shots(Minecraft mc) {
        if (!SHOTS || shotNo >= 6 || mc.player == null || !b.getPathingBehavior().isPathing()) return;
        boolean kin = MODE.equals("kinematic");
        String[] labels = kin ? new String[]{"K1-corridor", "K2-rollouts", "K3-jumps"} : new String[]{"P1-speedtrail", "P2-inputs", "P3-search"};
        if (shotWait > 0) {
            if (--shotWait == 0) {
                int k = shotNo % 3;
                String name = "pathviz-" + labels[k] + "-" + (shotNo / 3 + 1) + ".png";
                net.minecraft.client.Screenshot.grab(mc, false); // F2: saved by time stamp, matched to the PK SHOT line
                say("PK SHOT " + name);
                vizOnly(-1);
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                shotNo++;
            }
            return;
        }
        long driven = baritone.pathing.kinematic.KinematicController.drivenTicks;
        boolean drivenNow = driven > lastDriven;
        lastDriven = driven;
        if (++shotTick < 60) return;
        boolean moving = mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.01;
        if (moving && (kin == drivenNow)) {
            shotTick = 0;
            vizOnly((kin ? 0 : 3) + shotNo % 3);
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK); // the viz is around and ahead of the player
            shotWait = 6;
        }
    }

    @Override
    public void onTick(TickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        try {
            shots(mc);
            if (state == 0) {
                if (mc.gui.screen() instanceof TitleScreen) {
                    state = 1;
                    say("PK opening " + WORLD + " mode=" + MODE);
                    mc.createWorldOpenFlows().openWorld(WORLD, () -> say("PK BLOCKER open cancelled"));
                }
                return;
            }
            if (mc.player == null || mc.getSingleplayerServer() == null) {
                if (state == 1 && mc.gui.screen() != null && ++wait % 20 == 0) confirm(mc.gui.screen());
                return;
            }
            if (state == 1) { state = 2; wait = 100; return; }
            if (state == 2) { if (--wait <= 0) setup(mc); return; }
            if (state == 3 && hopLeft > 0) { hop(mc); return; }
            if (state == 3) { tick++; if (wait > 0) { wait--; return; } if (resume) { resume = false; nextStep(); return; }
                if (reissue) { reissue = false; forget(); b.getCustomGoalProcess().setGoalAndPath(goal()); say("PK repath " + step().pos().toShortString()); } runTick(mc); return; }
            if (state == 4 && --wait <= 0) { state = 5; mc.stop(); }
        } catch (Throwable t) {
            say("PK ERROR " + t);
            t.printStackTrace();
        }
    }

    /** Load prompts (upgrade/backup, experimental settings): take the button that goes on loading. */
    private static void confirm(net.minecraft.client.gui.screens.Screen sc) {
        net.minecraft.client.gui.components.Button best = null; int rank = 0;
        for (var w : sc.children()) {
            if (!(w instanceof net.minecraft.client.gui.components.Button bt) || !bt.active) continue;
            String s = bt.getMessage().getString().toLowerCase(Locale.ROOT);
            int r = s.equals("load") ? 5 : s.contains("load") ? 4 : (s.contains("yes") || s.contains("proceed") || s.contains("continue")) ? 3 : 0;
            if (r > rank) { rank = r; best = bt; }
        }
        if (best != null) { say("PK clicking '" + best.getMessage().getString() + "' on " + sc.getClass().getSimpleName()); best.onPress(null); }
    }

    private void setup(Minecraft mc) {
        Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = false; s.allowPlace.value = false; s.allowInventory.value = false;
        s.allowWaterBucketFall.value = false; s.allowSprint.value = true; s.experimentalMovement.value = false;
        s.allowParkour.value = true; s.allowParkourAscend.value = true; s.allowNeos.value = true;
        s.allowClimbJumps.value = true; s.allowMomentumJumps.value = true; s.allowDiagonalAscend.value = true; s.allowDiagonalDescend.value = true;
        s.movementBackend.value = "baritone"; s.slowKinematic.value = false;
        boolean phys = MODE.equals("physics");
        s.physicsTravel.value = phys; s.kinematicTravel.value = !phys;
        s.kinematicTrace.value = true; // SIMTRACE: sim vs real per jump tick (MovementJump flights)
        GameType gt = mc.getSingleplayerServer().getDefaultGameType();
        GameType use = (gt == GameType.CREATIVE || gt == GameType.SPECTATOR) ? GameType.ADVENTURE : gt;
        run("gamemode " + use.getName() + " @p");
        run("effect clear @p");
        say("PK setup mode=" + MODE + " physicsTravel=" + s.physicsTravel.value + " kinematicTravel=" + s.kinematicTravel.value
                + " gamemode=" + gt.getName() + "->" + use.getName());
        if (GOALS != null && Files.exists(Path.of(GOALS))) {
            try {
                for (String line : Files.readAllLines(Path.of(GOALS))) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] a = line.split("\\s+");
                    switch (a[0]) {
                        case "cmd" -> steps.add(new Step("cmd", 0, 0, 0, 0, line.substring(4).trim()));
                        case "tag" -> steps.add(new Step("tag", 0, 0, 0, 0, a.length > 1 ? a[1] : ""));
                        case "wait" -> steps.add(new Step("wait", 0, 0, 0, Integer.parseInt(a[1]), ""));
                        case "hop" -> steps.add(new Step("hop", 0, Integer.parseInt(a[2]), 0, Integer.parseInt(a[1]), a.length > 3 ? a[3] : ""));
                        case "tp", "goal" -> steps.add(new Step(a[0], (int) Math.floor(Double.parseDouble(a[1])), (int) Math.floor(Double.parseDouble(a[2])), (int) Math.floor(Double.parseDouble(a[3])), 0, ""));
                        case "near" -> steps.add(new Step("near", Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), a.length > 4 ? Integer.parseInt(a[4]) : 2, ""));
                        default -> say("PK bad goal line " + line);
                    }
                }
            } catch (Exception ex) { say("PK BLOCKER goals file " + ex); }
        } else {
            plateGoals(mc);
        }
        goalCount = (int) steps.stream().filter(st -> st.kind.equals("goal") || st.kind.equals("near") || st.kind.equals("hop")).count();
        say("PK course " + goalCount + " goals: " + steps.stream().map(st -> st.kind + (st.kind.equals("cmd") ? "" : " " + st.pos().toShortString())).toList());
        if (goalCount == 0) { say("PK BLOCKER no goals"); end(); return; }
        state = 3;
        nextStep();
    }

    private void plateGoals(Minecraft mc) {
        Level lv = mc.level;
        BlockPos at = mc.player.blockPosition();
        List<BlockPos> plates = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int y0 = Math.max(lv.getMinY(), at.getY() - 48), y1 = Math.min(lv.getMaxY(), at.getY() + 128);
        for (int x = at.getX() - SCAN; x <= at.getX() + SCAN; x++) for (int z = at.getZ() - SCAN; z <= at.getZ() + SCAN; z++) {
            if (!lv.hasChunk(x >> 4, z >> 4)) continue;
            for (int y = y0; y <= y1; y++) if (lv.getBlockState(m.set(x, y, z)).getBlock() instanceof BasePressurePlateBlock) plates.add(m.immutable());
        }
        BlockPos cur = at;
        plates.removeIf(p -> p.distSqr(at) < 9);
        while (!plates.isEmpty()) {
            BlockPos c = cur;
            BlockPos n = Collections.min(plates, Comparator.comparingDouble(p -> p.distSqr(c)));
            plates.remove(n);
            plates.removeIf(p -> p.distSqr(n) < 9);
            steps.add(new Step("goal", n.getX(), n.getY(), n.getZ(), 0, ""));
            cur = n;
        }
    }

    private static String id(Block bl) { return bl.getDescriptionId().replace("block.minecraft.", ""); }

    private Step step() { return steps.get(stepIdx); }

    // "hop <ticks> <landY>": tick-exact headhitter momentum, no planner: hold W + sprint facing +z, press jump afresh on
    // every landing (the 3-tick hh cycle under a 2-high ceiling), log each tick, and report whether the far pad was reached
    private int hopLeft, hopT, hopLand, hopOk, hopTried;
    private double hopTop, hopStartY;
    private boolean hopJump;
    private String hopPattern = ""; // optional tick-exact inputs: J = jump pressed that tick, . = not

    private void hop(Minecraft mc) {
        var p = mc.player;
        var o = mc.options;
        p.setYRot(0f);
        p.setXRot(0f);
        boolean og = p.onGround();
        hopJump = hopPattern.isEmpty() ? og && !hopJump : hopT < hopPattern.length() && hopPattern.charAt(hopT) == 'J';
        boolean still = hopPattern.startsWith("!"); // "!J...": stand still, jump only (jump-height probe)
        if (still) hopJump = hopT + 1 < hopPattern.length() && hopPattern.charAt(hopT + 1) == 'J';
        o.keyUp.setDown(!still);
        o.keySprint.setDown(!still);
        o.keyJump.setDown(hopJump);
        if (still) {
            if (hopT == 0) hopStartY = p.getY();
            hopTop = Math.max(hopTop, p.getY() - hopStartY);
        }
        Vec3 v = p.getDeltaMovement();
        hopTop = Math.max(hopTop, Math.abs(v.z));
        say(String.format("PK HOP %s t=%d z=%.4f y=%.4f vz=%.4f vy=%.4f og=%b jump=%b", drillTag, hopT, p.getZ(), p.getY(), v.z, v.y, og, hopJump));
        hopT++;
        if (still) {
            if (hopT >= hopPattern.length() + 12) {
                hopLeft = 0;
                o.keyJump.setDown(false);
                say(String.format("PK JUMPPROBE %s startY=%.4f rise=%.4f", drillTag, hopStartY, hopTop));
                wait = 20;
                resume = true;
            }
            return;
        }
        boolean reached = og && p.getZ() >= 2.7 && p.getY() >= hopLand - 0.01;
        boolean fell = p.getY() < hopLand - 1.5;
        if (reached || fell || --hopLeft <= 0) {
            hopLeft = 0;
            o.keyUp.setDown(false);
            o.keySprint.setDown(false);
            o.keyJump.setDown(false);
            hopTried++;
            if (reached) hopOk++;
            say(String.format("PK HOPRESULT %s %s z=%.3f y=%.3f maxVz=%.4f (%d/%d)", drillTag, reached ? "REACHED" : "FAIL", p.getZ(), p.getY(), hopTop, hopOk, hopTried));
            wait = 20;
            resume = true;
        }
    }

    private void nextStep() {
        while (true) {
            stepIdx++; goalFails = 0;
            if (stepIdx >= steps.size()) { say("PK course done"); end(); return; }
            Step st = step();
            if (st.kind.equals("cmd")) { run(st.text); continue; }
            if (st.kind.equals("tag")) { drillTag = st.text; say("PK section " + drillTag); continue; }
            if (st.kind.equals("wait")) { wait = st.r; resume = true; return; }
            if (st.kind.equals("hop")) { hopLeft = st.r; hopT = 0; hopPattern = st.text; hopLand = st.y; hopTop = 0; hopJump = false; b.getPathingBehavior().cancelEverything(); return; }
            if (st.kind.equals("tp")) { tp(st.pos()); checkpoint = st.pos(); say("PK tp " + st.pos().toShortString()); wait = 20; continue; }
            goalNo++;
            lastGood = null;
            issue();
            return;
        }
    }

    /** A player tries a jump again after a reset; the planner's "failed lately" memory would make that a no-path. */
    private static void forget() {
        baritone.pathing.movement.movements.MovementJump.forgetFailures();
        baritone.pathing.movement.movements.MovementChainJump.forgetFailures();
    }

    private Goal goal() {
        Step st = step();
        return st.kind.equals("near") ? new GoalNear(st.pos(), st.r) : new GoalBlock(st.pos());
    }

    private void issue() {
        goalTick = 0; idle = 0; bestDist = Double.MAX_VALUE; bestTick = tick;
        if (Minecraft.getInstance().player != null && checkpoint == null) checkpoint = Minecraft.getInstance().player.blockPosition();
        activeMv = null;
        forget();
        b.getCustomGoalProcess().setGoalAndPath(goal());
        say("PK goal " + goalNo + "/" + goalCount + " " + step().pos().toShortString() + " try=" + (goalFails + 1));
    }

    /** A fall, death or map reset: back to where the failed jump started, or past it once it has failed too often. */
    private void fellOff(String why) {
        Attempt a = pending != null ? pending : active;
        if (pending != null) { finish(pending, false, why); pending = null; active = null; } else closeAttempt(false, why);
        b.getPathingBehavior().cancelEverything();
        Level lv = Minecraft.getInstance().level;
        // only a spot the player can stand on: a jump's src can be logged mid-air when the executor moves on in flight
        BlockPos from = a != null && solid(lv, a.src.below()) ? a.src : lastGood;
        if (from != null && !solid(lv, from.below())) from = checkpoint;
        if (from == null) { goalFail(why); return; }
        int n = jumpFails.merge(from, 1, Integer::sum);
        if (a != null && n >= MAX_FAILS) {
            skippedJumps++;
            tp(a.dst);
            lastGood = a.dst;
            checkpoint = a.dst;
            say("PK " + why + " -> skip jump " + a.src.toShortString() + "->" + a.dst.toShortString());
        } else {
            tp(from);
            say("PK " + why + " -> back to " + from.toShortString() + " (" + n + ")");
        }
        wait = 20;
        goalTick = 0; idle = 0; activeMv = null;
        reissue = true;
    }

    private void goalFail(String why) {
        closeAttempt(false, why);
        // drill: from the barrier catch floor (below the y=151 stands) nothing is plannable; that is a fall, not a no-plan
        boolean onCatchFloor = drillTag != null && Minecraft.getInstance().player.getY() < 150;
        if (onCatchFloor) say("PK catchfloor nopath ignored goal " + goalNo);
        if (why.equals("nopath") && goalFails == 0 && !onCatchFloor) {
            // the planner had no way onto the next checkpoint: count it as a failed jump of the hop's shape
            Minecraft mc = Minecraft.getInstance();
            BlockPos from = lastGood != null ? lastGood : mc.player.blockPosition();
            Attempt a = new Attempt();
            a.goal = goalNo; a.cls = "NOPLAN"; a.src = new BetterBlockPos(from); a.dst = new BetterBlockPos(step().pos());
            a.tags = tags(mc.level, "MovementJump", from, step().pos());
            if (drillTag != null) { a.tags = new ArrayList<>(a.tags); a.tags.add(0, "drill:" + drillTag); }
            finish(a, false, "noplan");
        }
        goalFails++;
        b.getPathingBehavior().cancelEverything();
        say("PK goal fail " + goalNo + " why=" + why + " fails=" + goalFails);
        if (goalFails >= (why.equals("nopath") ? 2 : MAX_FAILS)) {
            skippedGoals++;
            tp(step().pos());
            checkpoint = step().pos();
            say("PK skip goal " + goalNo);
            wait = 20;
            nextStep();
        } else {
            issue();
        }
    }

    private boolean atGoal(Vec3 pos) {
        Step st = step();
        if (st.kind.equals("near")) return pos.distanceToSqr(st.x + 0.5, st.y, st.z + 0.5) <= (st.r + 0.7) * (st.r + 0.7);
        return Math.abs(pos.x - (st.x + 0.5)) < 1.0 && Math.abs(pos.z - (st.z + 0.5)) < 1.0 && Math.abs(pos.y - st.y) < 1.0;
    }

    private void runTick(Minecraft mc) {
        var p = mc.player;
        if (tick > MINUTES * 1200) { say("PK time up"); end(); return; }
        if (p.isDeadOrDying()) { if (deathCool-- <= 0) { p.respawn(); deathCool = 40; falls++; fellOff("death"); } return; }
        Vec3 pos = p.position();
        if (lastPos != null && pos.distanceTo(lastPos) > 6) {
            Vec3 was = lastPos;
            lastPos = pos;
            if (atGoalPos(was)) { reachedGoal(); return; }
            resets++; say("PK reset to " + p.blockPosition().toShortString()); fellOff("reset"); return;
        }
        lastPos = pos;
        goalTick++;
        if (p.onGround() && atGoal(pos)) { reachedGoal(); return; }
        var cur = b.getPathingBehavior().getCurrent();
        IMovement mv = null;
        if (cur != null && cur.getPosition() < cur.getPath().movements().size()) mv = cur.getPath().movements().get(cur.getPosition());
        // A jump is judged on the first landing after its movement hands over (the executor moves on mid-air).
        if (pending != null && p.onGround()) {
            boolean ok = landed(p, pending.dst) || pos.y > pending.dst.y - 0.5 && pos.y < pending.dst.y + 2.5;
            if (!ok && pos.y < pending.dst.y - 0.5) { falls++; fellOff("fall"); return; } // short of it, on a catch floor
            finish(pending, ok, "missed");
            pending = null;
        }
        if (mv != activeMv) {
            if (active != null) {
                if (pending != null) finish(pending, landed(p, pending.dst), "missed");
                pending = active; active = null;
            }
            activeMv = mv;
            if (mv != null && JUMPS.contains(mv.getClass().getSimpleName())) open(mc, mv);
        }
        if (p.onGround() && active == null && pending == null && solid(mc.level, p.blockPosition().below())) lastGood = p.blockPosition();
        Attempt judge = pending != null ? pending : active;
        if (judge != null && pos.y < Math.min(judge.src.y, judge.dst.y) - 2.5) { falls++; fellOff("fall"); return; }
        double dist = pos.distanceTo(Vec3.atBottomCenterOf(step().pos()));
        if (dist < bestDist - 0.5) { bestDist = dist; bestTick = tick; }
        else if (tick - bestTick > 600) {
            BlockPos back = checkpoint != null ? checkpoint : lastGood;
            say("PK STUCK " + p.blockPosition().toShortString() + " goal " + step().pos().toShortString());
            if (++stuckCount % 3 == 0) alert("stuck:" + stuckCount, "STUCK x" + stuckCount + " at " + p.blockPosition().toShortString() + " goal " + step().pos().toShortString());
            if (pending != null) finish(pending, false, "stuck");
            pending = null; active = null;
            b.getPathingBehavior().cancelEverything();
            if (back != null) tp(back);
            bestDist = Double.MAX_VALUE; bestTick = tick; wait = 20; activeMv = null; goalTick = 0;
            goalFails++;
            if (goalFails >= MAX_FAILS) { goalFails = MAX_FAILS - 1; goalFail("stuck"); return; }
            reissue = true;
            return;
        }
        if (b.getPathingBehavior().getCurrent() == null && !b.getPathingBehavior().getInProgress().isPresent()) {
            if (++idleNoPlan == 60 && b.getCustomGoalProcess().isActive()) { say("PK IDLE no path running, repath"); reissue = true; alert("idle:" + goalNo, "IDLE goal " + goalNo + " " + step().pos().toShortString()); }
        } else idleNoPlan = 0;
        if (idleNoPlan > 120) { idleNoPlan = 0; goalFail("nopath"); return; }
        if (!b.getCustomGoalProcess().isActive() && !b.getPathingBehavior().isPathing()) { if (++idle > 60) goalFail("nopath"); }
        else idle = 0;
        if (goalTick > (drillTag != null ? 1200 : GOAL_TICKS)) goalFail("timeout");
    }

    private boolean atGoalPos(Vec3 v) { return atGoal(v) || v.distanceToSqr(step().x + 0.5, step().y, step().z + 0.5) < 9; }

    private void reachedGoal() {
        var pl = Minecraft.getInstance().player;
        if (pending != null) finish(pending, true, "");
        if (active != null && landed(pl, active.dst)) finish(active, true, "");
        pending = null; active = null;
        reached++;
        checkpoint = step().pos();
        say("PK reached goal " + goalNo + " ticks=" + goalTick);
        b.getPathingBehavior().cancelEverything();
        wait = 10;
        nextStep();
    }

    private static boolean landed(net.minecraft.client.player.LocalPlayer p, BetterBlockPos d) {
        Vec3 v = p.position();
        return Math.abs(v.x - (d.x + 0.5)) < 1.2 && Math.abs(v.z - (d.z + 0.5)) < 1.2 && v.y > d.y - 0.6 && v.y < d.y + 1.3;
    }

    private void open(Minecraft mc, IMovement mv) {
        String cls = mv.getClass().getSimpleName();
        BetterBlockPos s = mv.getSrc(), d = mv.getDest();
        List<String> tags = tags(mc.level, cls, s, d);
        if (tags == null) return;
        if (drillTag != null) { tags = new ArrayList<>(tags); tags.add(0, "drill:" + drillTag); }
        Attempt a = new Attempt();
        a.goal = goalNo; a.cls = cls; a.src = s; a.dst = d; a.tags = tags;
        active = a; activeStart = tick;
    }

    /** Close whatever is open: a pending jump that ended on the ground with ok, the active one with ok/why. */
    private void closeAttempt(boolean ok, String why) {
        if (pending != null) { finish(pending, ok, why); pending = null; }
        if (active != null) { finish(active, ok, why); active = null; }
    }

    private final Map<String, int[]> tally = new HashMap<>();
    private final Map<String, Map<String, Integer>> tallyWhy = new HashMap<>();
    private final Set<String> alerted = new HashSet<>();
    private int noplanStreak, stuckCount;

    /** Live trouble: a type failing 3+ times or under 50% after 4 tries, a no-plan streak, repeated STUCK/IDLE. */
    private void alert(String key, String text) {
        if (!alerted.add(key)) return;
        String line = "PK ALERT " + text + " world=" + WORLD + " mode=" + MODE + " section=" + drillTag;
        say(line);
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("pk-results");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("alerts.txt"), LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + line + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private void trackTrouble(Attempt a) {
        for (String tg : a.tags) {
            if (tg.startsWith("drill:") || tg.equals("flat")) continue;
            int[] c = tally.computeIfAbsent(tg, k -> new int[2]);
            c[0]++; if (a.ok) c[1]++;
            if (!a.ok) tallyWhy.computeIfAbsent(tg, k -> new HashMap<>()).merge(a.why, 1, Integer::sum);
            int fails = c[0] - c[1];
            if (fails >= 3 || (c[0] >= 4 && c[1] * 2 < c[0])) {
                String top = tallyWhy.getOrDefault(tg, Map.of()).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
                alert("type:" + tg + ":" + (fails >= 6 ? 2 : 1), tg + " " + c[1] + "/" + c[0] + " " + top);
            }
        }
        if (!a.ok && a.why.equals("noplan")) { if (++noplanStreak >= 3) alert("noplan:" + attempts.size() / 10, "noplan-streak " + noplanStreak + " last=" + String.join(",", a.tags)); }
        else noplanStreak = 0;
    }

    private void finish(Attempt a, boolean ok, String why) {
        a.ok = ok; a.why = ok ? "" : why; a.ticks = tick - activeStart;
        attempts.add(a);
        trackTrouble(a);
        if (ok) lastGood = a.dst;
        say("PK JUMP " + String.join(",", a.tags) + " " + (ok ? "ok" : "fail " + why) + " " + a.cls
                + " " + a.src.toShortString() + "->" + a.dst.toShortString() + " t=" + a.ticks);
    }

    private static boolean solid(Level lv, BlockPos p) { return !lv.getBlockState(p).getCollisionShape(lv, p).isEmpty(); }

    /** null when the movement is not a jump worth recording (a flat diagonal, a pillar off a ladder). */
    static List<String> tags(Level lv, String cls, BlockPos s, BlockPos d) {
        int dx = d.getX() - s.getX(), dy = d.getY() - s.getY(), dz = d.getZ() - s.getZ();
        int h = Math.max(Math.abs(dx), Math.abs(dz));
        List<String> t = new ArrayList<>();
        Block below = lv.getBlockState(d.below()).getBlock(), srcBelow = lv.getBlockState(s.below()).getBlock();
        boolean ladder = lv.getBlockState(d).getBlock() instanceof LadderBlock || lv.getBlockState(s).getBlock() instanceof LadderBlock
                || lv.getBlockState(d).getBlock() instanceof VineBlock;
        switch (cls) {
            case "MovementPillar": if (!ladder) return null; t.add("ladder"); break;
            case "MovementDiagonal": if (dy <= 0) return null; t.add("diag-up"); break;
            case "MovementAscend": t.add("step-up"); break;
            case "MovementSlime": t.add("slime"); break;
            case "LadderClutch": t.add("ladder"); break;
            default: t.add("gap" + Math.max(0, h - 1));
        }
        t.add(dy > 0 ? "up" + dy : dy < 0 ? "down" + (-dy) : "flat");
        if (dx != 0 && dz != 0 && !cls.equals("MovementDiagonal")) {
            boolean wall = solid(lv, s.offset(Integer.signum(dx), 1, 0)) || solid(lv, s.offset(0, 1, Integer.signum(dz)));
            t.add(wall ? "neo" : "diag");
        }
        if (ladder && !t.contains("ladder")) t.add("ladder");
        String bi = id(below) + id(srcBelow);
        if (bi.contains("ice")) t.add("ice");
        if (below instanceof SlimeBlock && !t.contains("slime")) t.add("slime");
        if (below instanceof HoneyBlock) t.add("honey");
        if (lv.getBlockState(d).getBlock() instanceof TrapDoorBlock || below instanceof TrapDoorBlock) t.add("trapdoor");
        if (below instanceof FenceBlock || below instanceof WallBlock || below instanceof FenceGateBlock) t.add("fence");
        if (below instanceof IronBarsBlock) t.add("pane");
        if (solid(lv, s.above(2)) || solid(lv, d.above(2))) t.add("headhitter");
        var shape = lv.getBlockState(d.below()).getCollisionShape(lv, d.below());
        if (!shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) < 0.99) t.add("partial");
        return t;
    }

    private void end() {
        if (state >= 4) return;
        if (pending != null) finish(pending, false, "end");
        pending = null; active = null;
        b.getPathingBehavior().cancelEverything();
        state = 4; wait = 60;
        Map<String, int[]> byTag = new TreeMap<>(), byCls = new TreeMap<>(), byWhy = new TreeMap<>();
        int ok = 0;
        StringBuilder csv = new StringBuilder("goal,class,src,dst,tags,ok,why,ticks\n");
        for (Attempt a : attempts) {
            if (a.ok) ok++;
            for (String t : a.tags) { int[] c = byTag.computeIfAbsent(t, k -> new int[2]); c[0]++; if (a.ok) c[1]++; }
            int[] c = byCls.computeIfAbsent(a.cls, k -> new int[2]); c[0]++; if (a.ok) c[1]++;
            if (!a.ok) byWhy.computeIfAbsent(a.why + ":" + String.join("+", a.tags), k -> new int[2])[0]++;
            csv.append(a.goal).append(',').append(a.cls).append(',').append(a.src.toShortString().replace(", ", " ")).append(',')
                    .append(a.dst.toShortString().replace(", ", " ")).append(',').append(String.join(" ", a.tags)).append(',')
                    .append(a.ok ? 1 : 0).append(',').append(a.why).append(',').append(a.ticks).append('\n');
        }
        String summary = "PK END world=" + WORLD + " mode=" + MODE + " goals=" + reached + "/" + goalCount + " skippedGoals=" + skippedGoals
                + " skippedJumps=" + skippedJumps + " jumps=" + ok + "/" + attempts.size() + " falls=" + falls + " resets=" + resets
                + " min=" + (tick / 1200) + " byTag=" + fmt(byTag) + " byClass=" + fmt(byCls) + " failures=" + fmtW(byWhy);
        say(summary);
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("pk-results");
            Files.createDirectories(dir);
            String f = WORLD + "-" + MODE + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm"));
            Files.writeString(dir.resolve(f + ".csv"), csv);
            Files.writeString(dir.resolve(f + ".txt"), summary + "\n");
        } catch (Exception ex) { say("PK csv failed " + ex); }
    }

    private static String fmt(Map<String, int[]> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(k).append('=').append(v[1]).append('/').append(v[0]).append(' '));
        return sb.append('}').toString();
    }

    private static String fmtW(Map<String, int[]> m) {
        StringBuilder sb = new StringBuilder("{");
        m.entrySet().stream().sorted((x, y) -> y.getValue()[0] - x.getValue()[0]).limit(10).forEach(en -> sb.append(en.getKey()).append('=').append(en.getValue()[0]).append(' '));
        return sb.append('}').toString();
    }
}
