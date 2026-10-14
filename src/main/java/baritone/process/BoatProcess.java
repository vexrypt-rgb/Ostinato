package baritone.process;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.world.item.component.SwingAnimation;

import baritone.Baritone;
import baritone.pathing.movement.movements.MovementFall;
import baritone.utils.BoatUtil;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Boat travel: when the goal lies across open water and we carry a boat, place it, get in, sail a
 * water-surface route (kept off the shoreline, where a 1.4-wide boat snags), get out at the far shore,
 * break the boat and pick it back up, then hand control back to whatever process owns the goal.
 * A boat does ~8 blocks/s on open water against ~2 for swimming and ~5.6 for sprinting.
 */
public final class BoatProcess extends BaritoneProcessHelper {

    private enum Phase { IDLE, APPROACH, PLACE, MOUNT, SAIL, EXIT, BREAK, COLLECT }

    private static final int RADIUS = 160, MAX_CELLS = 90_000, MIN_GAIN = 24;

    private Phase phase = Phase.IDLE;
    private Goal target;
    private int surfaceY;
    private List<BlockPos> route = Collections.emptyList();
    private boolean fromCustom;
    private int routeIdx;
    private BlockPos launch;
    /** Land cell (feet) beside the launch water, so the boat goes onto the surface from above; null to place from the water. */
    private BlockPos bank;
    private Entity boat;
    private int phaseTicks, sailStuck;
    private double lastProgressD;
    private int lastCheck;
    /** The route smoothed into a lane for the steering below; null until the first SAIL tick after a plan. */
    private List<Vec3> lane;
    private int laneSeg = -1;
    private int lastKey;
    private boolean lastFwd;
    /** Answers of {@link #surface} for the search under way, by cell; null outside one. */
    private Long2ByteOpenHashMap seen;

    public BoatProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().allowBoats.value) {
            return reset();
        }
        if (phase != Phase.IDLE) {
            return true;
        }
        if (ctx.player().getVehicle() instanceof AbstractBoat) {
            BoatUtil.restore(ctx);
            if (MovementFall.boatRideClaimed(ctx.player().tickCount)) return false;
            if (!BoatUtil.isDriver(ctx.player())) {
                // A passenger can't steer: get out and leave the boat to its driver.
                target = currentGoal();
                boat = null;
                return enter(Phase.EXIT);
            }
            if (BoatUtil.mobAboard(ctx.player())) {
                target = currentGoal();
                boat = ctx.player().getVehicle();
                return enter(Phase.EXIT);
            }
            // Seated by someone else, a relog, or a boat fall that just landed: sail if there's somewhere to go.
            Goal g = currentGoal();
            boat = ctx.player().getVehicle();
            if (g == null) {
                // Nothing to go to: leave the player seated. Like Soprano, whose BoatTrip only acts on a seated
                // player when a path asks for it. Getting out here broke boats the player had just boarded
                // (a boat dropped onto the water is briefly not "in water").
                return false;
            }
            if (plan(g, ctx.playerFeet(), true)) {
                return enter(Phase.SAIL);
            }
            if (!boat.isInWater()) {
                // Aground: get out and take the boat with us.
                logDebug("Boat: seated aground with nowhere to sail, getting out");
                target = g;
                return enter(Phase.EXIT);
            }
            return false;
        }
        if (lastCheck > ctx.player().tickCount + 200) {
            lastCheck = 0; // tickCount starts again with a new player entity (respawn, another dimension)
        }
        if (ctx.player().tickCount - lastCheck < 40) {
            return false;
        }
        boolean own = BoatUtil.hasBoat(ctx.player().getInventory().getNonEquipmentItems());
        Entity found = floatingBoat();
        if (!own && found == null) {
            return false;
        }
        lastCheck = ctx.player().tickCount;
        Goal g = currentGoal();
        if (g == null || !baritone.getPathingBehavior().isPathing() && !baritone.getCustomGoalProcess().isActive()) {
            return false;
        }
        BlockPos feet = ctx.playerFeet();
        if (g.isInGoal(feet)) {
            return false;
        }
        // A free boat already afloat nearby: sail from it if that beats placing ours (or we have none),
        // counting the walk/swim over to it against the crossing's gain.
        if (found != null) {
            BlockPos at = BlockPos.containing(found.getX(), found.getY() + 0.1, found.getZ());
            double walk = ctx.player().distanceTo(found);
            // With our own boat, only detour to a found one that's close; placing ours costs about that much.
            if ((!own || walk < 8) && plan(g, at, false, walk)) {
                boat = found;
                logDebug("Boat: using a boat already afloat " + Math.round(walk) + " blocks away");
                return enter(Phase.MOUNT);
            }
            if (!own) return false;
        }
        return plan(g, feet, false, 0) && enter(Phase.APPROACH);
    }

    /** Nearest free boat sitting on water within reach of a short walk or swim. */
    private Entity floatingBoat() {
        Entity best = null;
        for (Entity e : ctx.entities()) {
            if (BoatUtil.free(e) && e.isInWater() && ctx.player().distanceTo(e) < 16
                    && (best == null || ctx.player().distanceTo(e) < ctx.player().distanceTo(best))) best = e;
        }
        return best;
    }

    private boolean enter(Phase p) {
        phase = p;
        phaseTicks = 0;
        logDebug("Boat: " + p + (p == Phase.APPROACH || p == Phase.SAIL ? " route " + route.size() + " cells" : ""));
        return true;
    }

    private boolean reset() {
        phase = Phase.IDLE;
        boat = null;
        route = Collections.emptyList();
        return false;
    }

    private Goal currentGoal() {
        if (target != null && phase != Phase.IDLE) return target;
        Goal g = baritone.getCustomGoalProcess().getGoal();
        fromCustom = g != null && baritone.getCustomGoalProcess().isActive();
        return g != null ? g : baritone.getPathingBehavior().getGoal();
    }


    // ---- planning --------------------------------------------------------------------------

    /** Open water surface: water with air (not a roof, not a lily pad) above. */
    private boolean surface(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (!MovementHelper.isWater(ctx.world().getBlockState(p))) return false;
        BlockState up = ctx.world().getBlockState(p.above());
        return up.isAir() && ctx.world().isLoaded(p);
    }

    /** Standable: solid top at y with two air blocks above. */
    private boolean land(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return !MovementHelper.isWater(ctx.world().getBlockState(p)) && MovementHelper.canWalkOn(ctx, p) && ctx.world().getBlockState(p.above()).isAir() && ctx.world().getBlockState(p.above(2)).isAir();
    }

    private void findBank() {
        BlockPos me = ctx.playerFeet();
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(6, route.size()); i++) {
            BlockPos c = route.get(i);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (!land(c.getX() + d[0], surfaceY, c.getZ() + d[1])) continue;
                BlockPos b = new BlockPos(c.getX() + d[0], surfaceY + 1, c.getZ() + d[1]);
                double dd = b.distSqr(me) + i * 4;
                if (dd < bestD) { bestD = dd; bank = b; launch = c; }
            }
        }
    }

    /** {@link #surface} within one search, which asks about every cell a dozen times over. */
    private boolean surfaceSeen(int x, int y, int z) {
        long k = key(x, z);
        byte v = seen.get(k);
        if (v == 0) {
            v = (byte) (surface(x, y, z) ? 1 : 2);
            seen.put(k, v);
        }
        return v == 1;
    }

    private boolean nearShore(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!surfaceSeen(x + dx, y, z + dz)) return true;
        }
        return false;
    }

    /**
     * Dijkstra over the water surface from the cell nearest the player to the cell nearest the goal.
     * Accept only when the crossing gains at least MIN_GAIN blocks toward the goal.
     */
    private boolean plan(Goal g, BlockPos feet, boolean aboard) {
        return plan(g, feet, aboard, 0);
    }

    /** extra: blocks spent reaching the start (e.g. walking to a found boat), taken off the gain. */
    private boolean plan(Goal g, BlockPos feet, boolean aboard, double extra) {
        seen = new Long2ByteOpenHashMap();
        try {
            return search(g, feet, aboard, extra);
        } finally {
            seen = null;
        }
    }

    private boolean search(Goal g, BlockPos feet, boolean aboard, double extra) {
        BlockPos start = null;
        search:
        for (int r = 0; r <= 3; r++) {
            for (int dy = 1; dy >= -2; dy--) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                if (surface(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz)) {
                    start = new BlockPos(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    break search;
                }
            }
        }
        if (start == null) return false;
        int y = start.getY();
        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        long s = key(start.getX(), start.getZ());
        dist.put(s, 0.0);
        open.add(new double[]{0, start.getX(), start.getZ()});
        long best = s;
        double bestH = flatDist(g, start.getX(), y, start.getZ());
        BlockPos me = ctx.playerFeet();
        double startH = flatDist(g, me.getX(), me.getY(), me.getZ()) - extra;
        while (!open.isEmpty() && dist.size() < MAX_CELLS) {
            double[] c = open.poll();
            int cx = (int) c[1], cz = (int) c[2];
            long ck = key(cx, cz);
            if (c[0] > dist.get(ck)) continue;
            double h = flatDist(g, cx, y, cz);
            if (h < bestH) { bestH = h; best = ck; }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = cx + dx, nz = cz + dz;
                if (Math.abs(nx - start.getX()) > RADIUS || Math.abs(nz - start.getZ()) > RADIUS || !surfaceSeen(nx, y, nz)) continue;
                if (dx != 0 && dz != 0 && (!surfaceSeen(cx + dx, y, cz) || !surfaceSeen(cx, y, cz + dz))) continue;
                double nd = c[0] + (dx != 0 && dz != 0 ? 1.414 : 1) + (nearShore(nx, y, nz) ? 3 : 0);
                long nk = key(nx, nz);
                Double old = dist.get(nk);
                if (old == null || nd < old) {
                    dist.put(nk, nd);
                    parent.put(nk, ck);
                    open.add(new double[]{nd, nx, nz});
                }
            }
        }
        if (!aboard && startH - bestH < MIN_GAIN) return false;
        if (aboard && best == s) return false;
        LinkedList<BlockPos> r = new LinkedList<>();
        for (Long k = best; k != null; k = parent.get(k)) {
            r.addFirst(new BlockPos((int) (k >> 32), y, (int) (long) k));
        }
        route = new ArrayList<>(r);
        routeIdx = 0;
        lane = null;
        laneSeg = -1;
        lastKey = 0;
        lastFwd = false;
        surfaceY = y;
        launch = route.get(Math.min(2, route.size() - 1));
        bank = null;
        if (!aboard && !ctx.player().isInWater()) findBank();
        target = g;
        sailStuck = 0;
        lastProgressD = Double.MAX_VALUE;
        return true;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static double flatDist(Goal g, int x, int y, int z) {
        // Goal heuristics are in ticks; walking a block costs ~4.6, which is close enough to rank cells.
        return g.heuristic(x, y + 1, z) / 4.633;
    }

    // ---- control ---------------------------------------------------------------------------

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        baritone.getInputOverrideHandler().clearAllKeys();
        phaseTicks++;
        switch (phase) {
            case APPROACH: {
                // Get within reach of the launch cell (the pathfinder brings us to the water's edge).
                if (bank != null) {
                    if (ctx.playerFeet().equals(bank) && ctx.player().onGround()) return enter(Phase.PLACE) ? pause() : pause();
                } else if (ctx.player().position().distanceTo(center(launch)) < 3.5) return enter(Phase.PLACE) ? pause() : pause();
                if (phaseTicks > 400) return abort("could not reach the water");
                return new PathingCommand(bank != null ? new GoalBlock(bank) : new GoalNear(launch, 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            case PLACE: {
                if (findBoat(4) != null) { enter(Phase.MOUNT); return pause(); }
                int slot = BoatUtil.hotbarBoat(ctx);
                if (slot < 0 || phaseTicks > 100) return abort("could not place the boat (" + Minecraft.getInstance().hitResult + " eye " + ctx.player().getEyeY() + ")");
                ctx.player().getInventory().setSelectedSlot(slot);
                if (surfacing()) {
                    if (phaseTicks < 15) return pause();
                    // Can't place from under the surface; let the swim carry on and look again shortly.
                    PathingCommand c = abort("underwater");
                    lastCheck = ctx.player().tickCount;
                    return c;
                }
                // Boats only go on the water's top surface (or a block top with room), so aim at it from above.
                // A boat is 1.375 wide and placement fails if it would touch a block, so a hit at the middle of a
                // shore cell clips the bank; aim half a block further out, away from the bank.
                double ax = launch.getX() + 0.5, az = launch.getZ() + 0.5;
                if (bank != null) {
                    ax += 0.5 * Integer.signum(launch.getX() - bank.getX());
                    az += 0.5 * Integer.signum(launch.getZ() - bank.getZ());
                }
                Rotation aim = look(ax, surfaceY + 0.85, az);
                if (phaseTicks % 2 == 1) {
                    // the look target only lands on the next player tick; useItem raycasts (and sends) the current
                    // rotation, so face the surface now or the use misses and we wait for the next retry
                    ctx.player().setYRot(aim.getYaw());
                    ctx.player().setXRot(aim.getPitch());
                    Minecraft.getInstance().gameMode.useItem(ctx.player(), InteractionHand.MAIN_HAND);
                }
                return pause();
            }
            case MOUNT: {
                if (ctx.player().getVehicle() instanceof AbstractBoat) {
                    boat = ctx.player().getVehicle();
                    enter(BoatUtil.mobAboard(ctx.player()) ? Phase.EXIT : Phase.SAIL);
                    return pause();
                }
                surfacing();
                Entity b = findBoat(8);
                if (b == null || phaseTicks > 100) return abort("could not board");
                if (ctx.player().distanceTo(b) > 3) return new PathingCommand(new GoalNear(b.blockPosition(), 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                look(b.getX(), b.getY() + 0.3, b.getZ());
                if (phaseTicks % 4 == 3) Minecraft.getInstance().gameMode.interact(ctx.player(), b, new net.minecraft.world.phys.EntityHitResult(b), InteractionHand.MAIN_HAND);
                return pause();
            }
            case SAIL:
                return sail();
            case EXIT: {
                if (!(ctx.player().getVehicle() instanceof AbstractBoat)) {
                    enter(Phase.BREAK);
                    return pause();
                }
                if (phaseTicks > 40) return abort("stuck in the boat");
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return pause();
            }
            case BREAK: {
                if (boat == null || !boat.isAlive()) { enter(Phase.COLLECT); return pause(); }
                if (danger()) { logDebug("Boat: danger about, leaving the boat"); return finish(); }
                if (phaseTicks > 100 || ctx.player().distanceTo(boat) > 5) return finish();
                look(boat.getX(), boat.getY() + 0.3, boat.getZ());
                if (phaseTicks % 4 == 0) {
                    Minecraft.getInstance().gameMode.attack(ctx.player(), boat);
                    ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                }
                return pause();
            }
            case COLLECT: {
                if (danger()) { logDebug("Boat: danger about, leaving the drop"); return finish(); }
                ItemEntity drop = boatDrop();
                if (drop == null) {
                    // the item spawns a tick or two after the boat breaks
                    return phaseTicks > 12 ? finish() : pause();
                }
                if (phaseTicks > 160 || ctx.player().distanceTo(drop) < 0.6 && phaseTicks > 40) return finish();
                // the path to the old goal is still running: drop it so the walk goes to the item, not onward
                return new PathingCommand(new GoalBlock(drop.blockPosition()),
                        phaseTicks % 40 == 1 ? PathingCommandType.CANCEL_AND_SET_GOAL : PathingCommandType.SET_GOAL_AND_PATH);
            }
            default:
                return pause();
        }
    }

    private PathingCommand sail() {
        if (!(ctx.player().getVehicle() instanceof AbstractBoat)) return abort("fell out of the boat");
        boat = ctx.player().getVehicle();
        if (!BoatUtil.isDriver(ctx.player()) || BoatUtil.mobAboard(ctx.player())) { enter(Phase.EXIT); return pause(); }
        double bx = boat.getX(), bz = boat.getZ();
        if (lane == null) {
            lane = smoothLane(route);
            laneSeg = -1;
        }
        // Where we are on the lane: project onto the segments around the last one, all of them the first time.
        int lo = laneSeg < 0 ? 0 : Math.max(0, laneSeg - 4);
        int hi = laneSeg < 0 ? lane.size() - 1 : Math.min(lane.size() - 1, laneSeg + 12);
        int bestSeg = lo;
        double bestT = 0, lateral = Double.MAX_VALUE;
        for (int seg = lo; seg < hi; seg++) {
            Vec3 a = lane.get(seg), b = lane.get(seg + 1);
            double abx = b.x - a.x, abz = b.z - a.z, len2 = abx * abx + abz * abz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((bx - a.x) * abx + (bz - a.z) * abz) / len2));
            double px = a.x + abx * t, pz = a.z + abz * t;
            double d = Math.sqrt((bx - px) * (bx - px) + (bz - pz) * (bz - pz));
            if (d < lateral) { lateral = d; bestSeg = seg; bestT = t; }
        }
        laneSeg = bestSeg;
        // two rounds of corner cutting put about four lane points per route cell
        routeIdx = Math.min(route.size() - 1, (bestSeg + 1) / 4);
        BlockPos end = route.get(route.size() - 1);
        double endD = Math.hypot(end.getX() + 0.5 - bx, end.getZ() + 0.5 - bz);
        // Pinned against the bank near the landing counts as arrived.
        if (endD < 1.8 || endD < 4 && (sailStuck > 20 || boat.horizontalCollision)) {
            enter(Phase.EXIT);
            return pause();
        }
        // The rollout controller can't see what pins a boat against a bank: when we've made no headway for 2 s,
        // or just after a stall replan, use Ostinato's original aim-and-turn steering until headway resumes.
        if (sailStuck > 40 || sailStuck < -100) legacySteer(bx, bz);
        else steer(bestSeg, bestT, endD);
        // Stall guard: no progress toward the landing for 5 s → replan from here (once), then give up.
        if (endD < lastProgressD - 1) { lastProgressD = endD; sailStuck = 0; }
        else if (++sailStuck > 100) {
            if (!plan(target, BlockPos.containing(bx, boat.getY(), bz), true)) return abort("stuck at sea");
            logDebug("Boat: replanned from " + BlockPos.containing(bx, boat.getY(), bz));
            sailStuck = -200;
        }
        if (phaseTicks % 40 == 0) logDebug("Boat: sail " + routeIdx + "/" + route.size() + " d=" + Math.round(endD));
        return pause();
    }

    /** Ostinato's original steering: turn toward the farthest route cell in clear sight, forward once roughly aligned. */
    private void legacySteer(double bx, double bz) {
        int aim = Math.min(route.size() - 1, routeIdx + 1);
        for (int i = Math.min(route.size() - 1, routeIdx + 48); i > routeIdx + 1; i--) {
            BlockPos c = route.get(i);
            if (clearLine(bx, bz, c.getX() + 0.5, c.getZ() + 0.5)) { aim = i; break; }
        }
        BlockPos a = route.get(aim);
        double tx = a.getX() + 0.5 - bx, tz = a.getZ() + 0.5 - bz;
        float want = (float) (Mth.atan2(tz, tx) * 180 / Math.PI) - 90;
        float diff = Mth.wrapDegrees(want - boat.getYRot());
        if (diff > 4) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
        if (diff < -4) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
        if (Math.abs(diff) < 50) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getLookBehavior().updateTarget(new Rotation(want, 10), true);
        lastKey = diff > 4 ? 1 : diff < -4 ? -1 : 0;
        lastFwd = Math.abs(diff) < 50;
    }

    private boolean clearLine(double ax, double az, double bx, double bz) {
        double len = Math.hypot(bx - ax, bz - az);
        int steps = (int) Math.ceil(len * 2);
        double px = -(bz - az) / Math.max(len, 1e-6) * 0.7, pz = (bx - ax) / Math.max(len, 1e-6) * 0.7;
        for (int i = 0; i <= steps; i++) {
            double x = ax + (bx - ax) * i / Math.max(steps, 1), z = az + (bz - az) * i / Math.max(steps, 1);
            if (!surface(Mth.floor(x), surfaceY, Mth.floor(z))
                    || !surface(Mth.floor(x + px), surfaceY, Mth.floor(z + pz))
                    || !surface(Mth.floor(x - px), surfaceY, Mth.floor(z - pz))) return false;
        }
        return true;
    }

    // ---- steering, ported from Soprano ------------------------------------------------------
    // From Soprano (https://github.com/AverWasTaken/soprano, LGPL-3.0, by AverWasTaken),
    // src/main/java/baritone/pathing/path/BoatTrip.java: ride(), smoothLane(), stepBoat(), lateralAt(),
    // pointAlong(), headingAlong(), hullClips(), adapted to steer along BoatProcess's water-surface route.
    //
    // Vanilla boat physics (AbstractBoat.controlBoat / floatBoat): left/right add a degree a tick of spin,
    // forward adds 0.04 a tick of speed, both decay by 0.9 a tick on water. A boat's velocity lags its heading,
    // so steering on heading error alone overcorrects. Instead roll the physics forward HORIZON ticks for each
    // input we could hold (a tap of each length either way, or nothing, with and without forward) and keep the
    // one that ends nearest the lane point we'd expect to reach, with penalties for leaving the lane, clipping
    // a bank, pointing the wrong way and fidgeting. Re-picked every tick.
    private static final int HORIZON = 12;
    private static final double LANE_PENALTY = 4, CENTER_BAND = 0.8, COAST_PENALTY = 0.3;
    private static final double TURN_PENALTY = 0.15, SWITCH_PENALTY = 0.1, HEADING_PENALTY = 1.0, SPIN_PENALTY = 0.05;
    private static final int[] TAP_LENGTHS = {1, 2, 4, HORIZON};
    private static final double HULL_HALF = 0.6875, CLIP_PENALTY = 2.0, AHEAD_MIN = 1.5;
    private static final double BOAT_ACCEL = 0.04, TURN_ACCEL = 0.005, WATER_COAST = 9, LANE_SLACK = 0.6;

    private void steer(int seg0, double t0, double endD) {
        Vec3 motion = boat.getDeltaMovement();
        double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double ahead = Math.max(AHEAD_MIN, speed * HORIZON * 0.8 + 1.0);
        Vec3 goal = pointAlong(seg0, t0, ahead);
        float goalHeading = headingAlong(seg0, t0, ahead);
        int bestKey = 0;
        boolean bestFwd = false;
        double bestScore = Double.MAX_VALUE;
        for (int dir = -1; dir <= 1; dir++) {
            for (int taps : dir == 0 ? new int[]{0} : TAP_LENGTHS) {
                for (int fwd = 0; fwd <= 1; fwd++) {
                    double[] st = {boat.getX(), boat.getZ(), boat.getYRot(), Mth.wrapDegrees(boat.getYRot() - boat.yRotO), motion.x, motion.z};
                    double worstLateral = 0;
                    int clips = 0;
                    // the boat ticks before its passenger, so the first step is last tick's choice, already on its way
                    stepBoat(st, lastKey, lastFwd);
                    for (int k = 0; k < HORIZON; k++) {
                        stepBoat(st, k < taps ? dir : 0, fwd == 1);
                        worstLateral = Math.max(worstLateral, lateralAt(st[0], st[1], seg0));
                        if ((k & 1) == 1 && hullClips(st[0], st[1])) clips++;
                    }
                    double score = Math.hypot(st[0] - goal.x, st[1] - goal.z)
                            + LANE_PENALTY * Math.max(0, worstLateral - CENTER_BAND)
                            + CLIP_PENALTY * clips
                            + (fwd == 1 ? 0 : COAST_PENALTY)
                            + (dir != 0 ? TURN_PENALTY : 0)
                            + (dir != lastKey ? SWITCH_PENALTY : 0)
                            + HEADING_PENALTY * Math.abs(Mth.wrapDegrees((float) st[2] - goalHeading)) / 30
                            + SPIN_PENALTY * Math.abs(st[3]);
                    if (score < bestScore) { bestScore = score; bestKey = dir; bestFwd = fwd == 1; }
                }
            }
        }
        if (endD < WATER_COAST * speed + 0.4) bestFwd = false; // we'd slide past the landing
        lastKey = bestKey;
        lastFwd = bestFwd;
        if (bestKey != 0) baritone.getInputOverrideHandler().setInputForceState(bestKey > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT, true);
        if (bestFwd) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        // No look target while riding: the boat steers off its own yaw and clamps the passenger's head to it.
    }

    /** Route cells pulled taut (each point kept within LANE_SLACK of its cell), then two rounds of Chaikin corner cutting. */
    static List<Vec3> smoothLane(List<BlockPos> cells) {
        int n = cells.size();
        Vec3[] center = new Vec3[n], pts = new Vec3[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = cells.get(i);
            center[i] = new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            pts[i] = center[i];
        }
        for (int round = 0; round < 40; round++) {
            for (int i = 1; i < n - 1; i++) {
                Vec3 mid = pts[i - 1].add(pts[i + 1]).scale(0.5);
                Vec3 pulled = pts[i].add(mid.subtract(pts[i]).scale(0.5));
                double dx = pulled.x - center[i].x, dz = pulled.z - center[i].z, d = Math.sqrt(dx * dx + dz * dz);
                if (d > LANE_SLACK) pulled = new Vec3(center[i].x + dx / d * LANE_SLACK, center[i].y, center[i].z + dz / d * LANE_SLACK);
                pts[i] = pulled;
            }
        }
        List<Vec3> out = new ArrayList<>(Arrays.asList(pts));
        if (out.size() == 1) out.add(out.get(0));
        for (int round = 0; round < 2 && out.size() > 2; round++) {
            List<Vec3> cut = new ArrayList<>(out.size() * 2);
            cut.add(out.get(0));
            for (int i = 0; i + 1 < out.size(); i++) {
                Vec3 a = out.get(i), b = out.get(i + 1);
                cut.add(a.scale(0.75).add(b.scale(0.25)));
                cut.add(a.scale(0.25).add(b.scale(0.75)));
            }
            cut.add(out.get(out.size() - 1));
            out = cut;
        }
        return out;
    }

    /** One tick of vanilla boat physics on water; state is x, z, yaw, spin, vx, vz; key -1 left, 0, 1 right. */
    static void stepBoat(double[] s, int key, boolean forward) {
        s[3] = s[3] * 0.9 + key;
        s[2] += s[3];
        double thrust = (forward ? BOAT_ACCEL : 0) + (key != 0 && !forward ? TURN_ACCEL : 0);
        double yaw = Math.toRadians(s[2]);
        s[4] = s[4] * 0.9 - Math.sin(yaw) * thrust;
        s[5] = s[5] * 0.9 + Math.cos(yaw) * thrust;
        s[0] += s[4];
        s[1] += s[5];
    }

    private double lateralAt(double x, double z, int nearSeg) {
        double best = Double.MAX_VALUE;
        for (int seg = Math.max(0, nearSeg - 2); seg < Math.min(lane.size() - 1, nearSeg + 20); seg++) {
            Vec3 a = lane.get(seg), b = lane.get(seg + 1);
            double abx = b.x - a.x, abz = b.z - a.z, len2 = abx * abx + abz * abz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x) * abx + (z - a.z) * abz) / len2));
            double px = a.x + abx * t, pz = a.z + abz * t;
            best = Math.min(best, (x - px) * (x - px) + (z - pz) * (z - pz));
        }
        return Math.sqrt(best);
    }

    private Vec3 pointAlong(int seg, double t, double dist) {
        Vec3 a = lane.get(seg), b = lane.get(seg + 1);
        Vec3 p = a.add(b.subtract(a).scale(t));
        double remaining = dist;
        while (true) {
            b = lane.get(seg + 1);
            double d = Math.hypot(b.x - p.x, b.z - p.z);
            if (d >= remaining && d > 0) return p.add(b.subtract(p).scale(remaining / d));
            if (seg + 2 >= lane.size()) return b;
            remaining -= d;
            p = b;
            seg++;
        }
    }

    private float headingAlong(int seg, double t, double dist) {
        Vec3 a = lane.get(seg), b = lane.get(seg + 1);
        Vec3 p = a.add(b.subtract(a).scale(t));
        double remaining = dist;
        while (seg + 2 < lane.size()) {
            b = lane.get(seg + 1);
            double d = Math.hypot(b.x - p.x, b.z - p.z);
            if (d >= remaining) break;
            remaining -= d;
            p = b;
            seg++;
        }
        Vec3 c = lane.get(seg), n = lane.get(seg + 1);
        return (float) Math.toDegrees(Math.atan2(-(n.x - c.x), n.z - c.z));
    }

    /** Would the hull's axis-aligned 1.375 box, centred here, overlap a block at water level or one above? */
    private boolean hullClips(double x, double z) {
        for (int cx = -1; cx <= 1; cx += 2) {
            for (int cz = -1; cz <= 1; cz += 2) {
                int hx = Mth.floor(x + cx * HULL_HALF), hz = Mth.floor(z + cz * HULL_HALF);
                if (blocksHull(hx, surfaceY, hz) || blocksHull(hx, surfaceY + 1, hz)) return true;
            }
        }
        return false;
    }

    private boolean blocksHull(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return !ctx.world().getBlockState(p).getCollisionShape(ctx.world(), p).isEmpty();
    }

    private Entity findBoat(double r) {
        Entity best = null;
        for (Entity e : ctx.entities()) {
            if (BoatUtil.free(e) && ctx.player().distanceTo(e) < r
                    && (best == null || ctx.player().distanceTo(e) < ctx.player().distanceTo(best))) best = e;
        }
        return best;
    }

    /** Hostile mob close by, low health, burning or in lava: not the time to stand about collecting a boat. */
    private boolean danger() {
        var p = ctx.player();
        if (p.getHealth() < 8 || p.isOnFire() || p.isInLava()) return true;
        for (Entity e : ctx.entities()) {
            if (e instanceof net.minecraft.world.entity.monster.Enemy && e.isAlive() && p.distanceTo(e) < 12) return true;
        }
        return false;
    }

    private ItemEntity boatDrop() {
        for (Entity e : ctx.entities()) {
            if (e instanceof ItemEntity && ((ItemEntity) e).getItem().getItem() instanceof BoatItem && ctx.player().distanceTo(e) < 8) return (ItemEntity) e;
        }
        return null;
    }

    private Rotation look(double x, double y, double z) {
        double dx = x - ctx.player().getX(), dy = y - ctx.player().getEyeY(), dz = z - ctx.player().getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * 180 / Math.PI) - 90;
        float pitch = (float) -(Mth.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI);
        Rotation r = new Rotation(yaw, pitch);
        baritone.getLookBehavior().updateTarget(r, true);
        return r;
    }

    private static net.minecraft.world.phys.Vec3 center(BlockPos p) {
        return new net.minecraft.world.phys.Vec3(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5);
    }

    private PathingCommand pause() {
        return new PathingCommand(target, PathingCommandType.REQUEST_PAUSE);
    }

    /** Swim up while the eyes are under the surface; items and entities can't be used well from below. */
    private boolean surfacing() {
        boolean under = ctx.player().getEyeY() < surfaceY + 1.1 && ctx.player().isInWater();
        if (under) baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        return under && phaseTicks < 80;
    }

    private PathingCommand abort(String why) {
        logDebug("Boat: giving up (" + why + ")");
        lastCheck = ctx.player().tickCount + 200; // don't retry straight away
        return finish();
    }

    private PathingCommand finish() {
        Goal g = target;
        reset();
        baritone.getInputOverrideHandler().clearAllKeys();
        // A failed calc toward the drop can knock the custom goal process out; hand the goal back.
        if (g != null && fromCustom && !baritone.getCustomGoalProcess().isActive()) baritone.getCustomGoalProcess().setGoalAndPath(g);
        return new PathingCommand(g, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        if (ctx.player() != null && ctx.player().getVehicle() instanceof AbstractBoat) return; // keep sailing state
        reset();
    }

    @Override
    public String displayName0() {
        return "Boat " + phase;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return 3;
    }
}
