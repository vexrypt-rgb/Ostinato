package baritone.process;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

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
import net.minecraft.world.entity.vehicle.AbstractBoat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

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
            if (g != null && plan(g, ctx.playerFeet(), true)) {
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
        boolean own = BoatUtil.hasBoat(ctx.player().getInventory().items);
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

    /** Every half-block along the segment sits over open water, with the boat's half-width to spare. */
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
                ctx.player().getInventory().selected = slot;
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
                if (phaseTicks % 2 == 1) Minecraft.getInstance().gameMode.interact(ctx.player(), b, InteractionHand.MAIN_HAND);
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
                if (phaseTicks > 100 || ctx.player().distanceTo(boat) > 5) return finish();
                look(boat.getX(), boat.getY() + 0.3, boat.getZ());
                if (phaseTicks % 4 == 0) {
                    Minecraft.getInstance().gameMode.attack(ctx.player(), boat);
                    ctx.player().swing(InteractionHand.MAIN_HAND);
                }
                return pause();
            }
            case COLLECT: {
                ItemEntity drop = boatDrop();
                if (drop == null || phaseTicks > 100) return finish();
                return new PathingCommand(new GoalNear(drop.blockPosition(), 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
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
        // Advance past cells we've reached, then aim at the farthest one in clear sight.
        int nearest = routeIdx;
        double nd = Double.MAX_VALUE;
        for (int i = routeIdx; i < Math.min(route.size(), routeIdx + 30); i++) {
            BlockPos c = route.get(i);
            double d = Math.hypot(c.getX() + 0.5 - bx, c.getZ() + 0.5 - bz);
            if (d < nd) { nd = d; nearest = i; }
        }
        routeIdx = nearest;
        BlockPos end = route.get(route.size() - 1);
        double endD = Math.hypot(end.getX() + 0.5 - bx, end.getZ() + 0.5 - bz);
        // Pinned against the bank near the landing counts as arrived.
        if (endD < 1.8 || routeIdx >= route.size() - 1 || endD < 4 && (sailStuck > 20 || boat.horizontalCollision)) {
            enter(Phase.EXIT);
            return pause();
        }
        int aim = routeIdx + 1;
        for (int i = Math.min(route.size() - 1, routeIdx + 48); i > routeIdx + 1; i--) {
            BlockPos c = route.get(i);
            if (clearLine(bx, bz, c.getX() + 0.5, c.getZ() + 0.5)) { aim = i; break; }
        }
        BlockPos a = route.get(aim);
        double tx = a.getX() + 0.5 - bx, tz = a.getZ() + 0.5 - bz;
        float want = (float) (Mth.atan2(tz, tx) * 180 / Math.PI) - 90;
        float diff = Mth.wrapDegrees(want - boat.getYRot());
        // Left/right turn the boat 1 degree/tick (it carries momentum); forward only once roughly aligned.
        if (diff > 4) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
        if (diff < -4) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
        if (Math.abs(diff) < 50) baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getLookBehavior().updateTarget(new Rotation(want, 10), true);
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

    private Entity findBoat(double r) {
        Entity best = null;
        for (Entity e : ctx.entities()) {
            if (BoatUtil.free(e) && ctx.player().distanceTo(e) < r
                    && (best == null || ctx.player().distanceTo(e) < ctx.player().distanceTo(best))) best = e;
        }
        return best;
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
