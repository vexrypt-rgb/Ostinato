/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.calc.PathNode;
import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.kinematic.TasBrute;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.PositionSync;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ostinato: a jump no template or movement covers, found by {@link TasBrute} and flown by replaying its inputs.
 * <p>
 * The brute force takes a fraction of a second per jump, far too slow for every node the planner expands. So it runs
 * only after a search failed to reach its goal: from the explored nodes nearest the goal ({@link #frontier}) toward
 * blocks the search never reached, nearer the goal. What it finds is kept as an edge of that node, which the next
 * search takes like any other movement. The jump starts standing still on the node; the executor walks there, faces the
 * search's first yaw, and plays the inputs back, searching again from the real state if it drifts off the sim.
 */
public class MovementTas extends Movement {

    /** Moves slots; slot k takes the k-th TAS edge out of a node. */
    public static final int SLOTS = 3;

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    /** Searched per failed path at most; nodes and landings are tried nearest the goal first. */
    private static final int FRONTIER_NODES = 3, LANDINGS_PER_NODE = 6, MAX_SEARCHES = 12;
    private static final long BUDGET_MS = 8000;
    /** Landings searched: this far out sideways, and from this far down to this far up. */
    private static final int REACH = 5, DOWN = 4, UP = 1;
    /** A pair is searched again only after this long (the blocks may have changed). */
    private static final long RETRY_MS = 5 * 60_000;

    /** A jump the brute force found from standing still at (sx, sy, sz) on {@code src}, camera at {@code yaw0}. */
    static final class Edge {
        final BetterBlockPos src, dest;
        final boolean grab;
        final double sx, sy, sz;
        final float yaw0;
        final TasBrute.Result r;
        final double cost;
        final Set<BetterBlockPos> cells;

        Edge(BetterBlockPos src, BetterBlockPos dest, boolean grab, PlayerSim start, float yaw0, TasBrute.Result r, double cost, Set<BetterBlockPos> cells) {
            this.src = src;
            this.dest = dest;
            this.grab = grab;
            this.sx = start.x;
            this.sy = start.y;
            this.sz = start.z;
            this.yaw0 = yaw0;
            this.r = r;
            this.cost = cost;
            this.cells = cells;
        }
    }

    private static final Map<Long, List<Edge>> EDGES = new ConcurrentHashMap<>();
    private static final Map<Long, Long> TRIED = new ConcurrentHashMap<>();
    private static final Map<Long, Long> FAILED = new ConcurrentHashMap<>();

    /** Forget every edge and failure (benches reuse the same coordinates for every course). */
    public static void forgetFailures() {
        FAILED.clear();
        TRIED.clear();
        EDGES.clear();
    }

    private static long pairKey(int x, int y, int z, int dx, int dy, int dz) {
        return BetterBlockPos.longHash(x, y, z) * 31 + BetterBlockPos.longHash(dx, dy, dz);
    }

    private static List<Edge> options(int x, int y, int z) {
        List<Edge> all = EDGES.get(BetterBlockPos.longHash(x, y, z));
        if (all == null) {
            return List.of();
        }
        if (FAILED.isEmpty()) {
            return all;
        }
        long now = System.currentTimeMillis();
        List<Edge> out = new ArrayList<>();
        for (Edge e : all) {
            Long until = FAILED.get(pairKey(x, y, z, e.dest.x, e.dest.y, e.dest.z));
            if (until == null || until <= now) out.add(e);
        }
        return out;
    }

    public static void cost(CalculationContext context, int x, int y, int z, int slot, MutableMoveResult res) {
        if (!context.allowTasJumps || EDGES.isEmpty()) {
            return;
        }
        List<Edge> o = options(x, y, z);
        if (slot >= o.size()) {
            return;
        }
        Edge e = o.get(slot);
        res.x = e.dest.x;
        res.y = e.dest.y;
        res.z = e.dest.z;
        res.cost = e.cost;
    }

    public static MovementTas cost(CalculationContext context, BetterBlockPos src, int slot) {
        if (!context.allowTasJumps) {
            return null;
        }
        List<Edge> o = options(src.x, src.y, src.z);
        return slot < o.size() ? new MovementTas(context.getBaritone(), o.get(slot)) : null;
    }

    /**
     * After a search that did not reach its goal: brute force jumps from the explored nodes nearest the goal to standable
     * blocks (or ladders) nearby it never reached and that are nearer the goal. Runs on the calculating thread.
     */
    public static void frontier(CalculationContext context, Iterable<PathNode> nodes, Goal goal) {
        if (!context.allowParkour || !context.allowTasJumps) {
            return;
        }
        long t0 = System.currentTimeMillis();
        Set<Long> reached = new HashSet<>();
        List<PathNode> starts = new ArrayList<>();
        for (PathNode n : nodes) {
            reached.add(BetterBlockPos.longHash(n.x, n.y, n.z));
            if (standable(context, n.x, n.y, n.z)) starts.add(n);
        }
        starts.sort((a, b) -> Double.compare(a.estimatedCostToGoal, b.estimatedCostToGoal));
        PlayerSim.World world = new BsiWorld(context.bsi);
        int searches = 0, found = 0;
        for (int k = 0; k < Math.min(FRONTIER_NODES, starts.size()); k++) {
            PathNode s = starts.get(k);
            List<int[]> landings = new ArrayList<>();
            for (int dx = -REACH; dx <= REACH; dx++) for (int dz = -REACH; dz <= REACH; dz++) for (int dy = -DOWN; dy <= UP; dy++) {
                int x = s.x + dx, y = s.y + dy, z = s.z + dz;
                if (Math.abs(dx) + Math.abs(dz) < 2 || reached.contains(BetterBlockPos.longHash(x, y, z))) continue;
                boolean grab = climbable(context, x, y, z);
                if (!grab && !standable(context, x, y, z)) continue;
                if (goal.heuristic(x, y, z) >= s.estimatedCostToGoal - 1) continue;
                landings.add(new int[]{x, y, z, grab ? 1 : 0});
            }
            landings.sort((a, b) -> Double.compare(goal.heuristic(a[0], a[1], a[2]), goal.heuristic(b[0], b[1], b[2])));
            for (int i = 0, n = 0; i < landings.size() && n < LANDINGS_PER_NODE; i++) {
                int[] l = landings.get(i);
                long key = pairKey(s.x, s.y, s.z, l[0], l[1], l[2]);
                Long when = TRIED.get(key);
                if (when != null && System.currentTimeMillis() - when < RETRY_MS) continue;
                if (searches >= MAX_SEARCHES || System.currentTimeMillis() - t0 > BUDGET_MS) {
                    log("frontier budget spent: " + searches + " searches, " + found + " found, " + (System.currentTimeMillis() - t0) + " ms");
                    return;
                }
                n++;
                searches++;
                TRIED.put(key, System.currentTimeMillis());
                if (search(context, world, new BetterBlockPos(s.x, s.y, s.z), new BetterBlockPos(l[0], l[1], l[2]), l[3] == 1)) found++;
            }
        }
        if (searches > 0) {
            log("frontier: " + searches + " searches, " + found + " found, " + (System.currentTimeMillis() - t0) + " ms");
        }
    }

    private static void log(String msg) {
        if (Baritone.settings().kinematicTrace.value) {
            System.out.println("KTRACE tas " + msg);
        }
    }

    private static boolean standable(CalculationContext context, int x, int y, int z) {
        return MovementHelper.canWalkOn(context, x, y - 1, z) && MovementHelper.fullyPassable(context, x, y, z) && MovementHelper.fullyPassable(context, x, y + 1, z)
                && !climbable(context, x, y, z);
    }

    private static boolean climbable(CalculationContext context, int x, int y, int z) {
        return MovementHelper.isClimbable(context.get(x, y, z).getBlock());
    }

    /** Standing still on node (x, y, z): over the middle of the cell, or the nearest point the floor's real shape holds. */
    static PlayerSim standingStart(PlayerSim.World world, CalculationContext context, int x, int y, int z) {
        PlayerSim s = new PlayerSim(world);
        s.y = MovementParkour.standingY(context, x, y, z);
        s.x = x + 0.5;
        s.z = z + 0.5;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        List<double[]> floor = new ArrayList<>();
        world.collect(x - 1, s.y - 0.02, z - 1, x + 2, s.y, z + 2, floor);
        double h = PlayerSim.HALF_WIDTH - 0.06; // keep a little of the box over the support
        double bestD = Double.MAX_VALUE, bx = s.x, bz = s.z;
        for (double[] b : floor) {
            if (b[4] < s.y - 0.01 || b[4] > s.y + 0.01) continue;
            double cx = Mth.clamp(x + 0.5, b[0] - h, b[3] + h), cz = Mth.clamp(z + 0.5, b[2] - h, b[5] + h);
            if (PlayerSim.floor(cx) != x || PlayerSim.floor(cz) != z) continue; // stay on the node
            double d = (cx - x - 0.5) * (cx - x - 0.5) + (cz - z - 0.5) * (cz - z - 0.5);
            if (d < bestD) {
                bestD = d;
                bx = cx;
                bz = cz;
            }
        }
        s.x = bx;
        s.z = bz;
        return s;
    }

    private static TasBrute brute(PlayerSim.World world, double minY) {
        TasBrute b = new TasBrute(world);
        b.maxTurn = 30;
        b.maxTicks = 45;
        b.floorY = minY - 1.5;
        return b;
    }

    private static boolean search(CalculationContext context, PlayerSim.World world, BetterBlockPos src, BetterBlockPos dest, boolean grab) {
        PlayerSim start = standingStart(world, context, src.x, src.y, src.z);
        float yaw0 = (float) Math.toDegrees(Math.atan2(-(dest.x + 0.5 - start.x), dest.z + 0.5 - start.z));
        TasBrute.Landing goal = new TasBrute.Landing(dest.x, dest.y, dest.z);
        if (grab) goal.grab();
        TasBrute.Result r = brute(world, Math.min(src.y, dest.y)).search(start, yaw0, goal);
        if (r == null) {
            return false;
        }
        // the cells the box's feet pass through, so the path executor doesn't take the swing for leaving the path
        Set<BetterBlockPos> cells = new HashSet<>();
        cells.add(src);
        cells.add(dest);
        PlayerSim s = new PlayerSim(world).copyFrom(start);
        for (int t = 0; t < r.ticks(); t++) {
            s.tick(r.yaw[t], r.input[t], r.input[t] > 0, r.jump[t]);
            cells.add(new BetterBlockPos(PlayerSim.floor(s.x), PlayerSim.floor(s.y + 0.01), PlayerSim.floor(s.z)));
        }
        double cost = r.ticks() * 1.2 + context.jumpPenalty + 10;
        Edge e = new Edge(src, dest, grab, start, yaw0, r, cost, cells);
        EDGES.compute(BetterBlockPos.longHash(src.x, src.y, src.z), (k, old) -> {
            List<Edge> l = old == null ? new ArrayList<>() : new ArrayList<>(old);
            l.removeIf(o -> o.dest.equals(dest));
            l.add(e);
            l.sort((a, b) -> Double.compare(a.cost, b.cost));
            return l.size() > SLOTS ? new ArrayList<>(l.subList(0, SLOTS)) : l;
        });
        log("found " + src + " -> " + dest + (grab ? " grab" : "") + " " + r);
        return true;
    }

    private final Edge e;
    private TasBrute.Result plan;
    private PlayerSim real, predicted;
    private int phase, tick, settle, waited, replans;
    private boolean running;

    private MovementTas(IBaritone baritone, Edge e) {
        super(baritone, e.src, e.dest, EMPTY);
        this.e = e;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        if (!context.allowTasJumps) {
            return COST_INF;
        }
        for (Edge o : options(src.x, src.y, src.z)) {
            if (o == e) return e.cost;
        }
        return COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return e.cells;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || !running;
    }

    @Override
    public void reset() {
        super.reset();
        plan = null;
        real = predicted = null;
        phase = tick = settle = waited = replans = 0;
        running = false;
    }

    private MovementState fail(MovementState state, String why) {
        FAILED.put(pairKey(src.x, src.y, src.z, dest.x, dest.y, dest.z), System.currentTimeMillis() + 30_000);
        logDebug("tas jump: " + why + " (" + src + " -> " + dest + "); avoiding it for 30s");
        log("fail " + why + " " + src + "->" + dest + " at " + ctx.player().position());
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    private TasBrute.Landing landing() {
        TasBrute.Landing l = new TasBrute.Landing(dest.x, dest.y, dest.z);
        return e.grab ? l.grab() : l;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        Vec3 p = ctx.player().position();
        if (p.y < Math.min(src.y, dest.y) - 2.5) {
            return fail(state, "fell");
        }
        if (real == null) {
            real = new PlayerSim(new ClientWorld(ctx));
            predicted = new PlayerSim(new ClientWorld(ctx));
        }
        ClientWorld.readPlayer(ctx, real);
        Vec3 m = ctx.player().getDeltaMovement();
        if (phase == 0) {
            // walk to the start, sneaking so a narrow floor (a ladder's top, a candle) can't be walked off
            double dx = e.sx - p.x, dz = e.sz - p.z;
            if (dx * dx + dz * dz > 0.06 * 0.06) {
                if (++waited > 200) return fail(state, "never reached the start");
                MovementHelper.walkToSpot(ctx, state, dx, dz);
                return state.setInput(Input.SNEAK, true);
            }
            if (Math.abs(m.x) + Math.abs(m.z) > 0.005 || !real.onGround) {
                return state.setInput(Input.SNEAK, true);
            }
            phase = 1;
            waited = 0;
        }
        if (phase == 1) {
            // stand up and face the search's first yaw, still
            state.setTarget(new MovementState.MovementTarget(new Rotation(e.yaw0, ctx.playerRotations().getPitch()), true));
            if ((Math.abs(Mth.wrapDegrees(e.yaw0 - ctx.player().getYRot())) > 1 || ctx.player().isCrouching()) && ++waited < 20) {
                return state;
            }
            double off = Math.abs(p.x - e.sx) + Math.abs(p.z - e.sz) + Math.abs(p.y - e.sy);
            plan = e.r;
            if (off > 0.02) {
                plan = brute(real.world(), Math.min(src.y, dest.y)).search(real, ctx.player().getYRot(), landing());
                if (plan == null) return fail(state, "no jump from where we stand");
            }
            phase = 2;
            tick = 0;
            running = true;
            PositionSync.sync(ctx.player());
        }
        if (phase == 2) {
            if (tick > 0) {
                double dev = Math.abs(real.x - predicted.x) + Math.abs(real.y - predicted.y) + Math.abs(real.z - predicted.z);
                if (dev > 0.05 && replans < 2 && tick < plan.ticks() - 2) {
                    replans++;
                    TasBrute.Result again = brute(real.world(), Math.min(src.y, dest.y)).search(real, ctx.player().getYRot(), landing());
                    log("drift " + String.format("%.3f", dev) + " at tick " + tick + ", search again: " + (again != null));
                    if (again != null) {
                        plan = again;
                        tick = 0;
                    }
                }
            }
            if (tick < plan.ticks()) {
                int in = plan.input[tick];
                state.setTarget(new MovementState.MovementTarget(new Rotation(plan.yaw[tick], ctx.playerRotations().getPitch()), true));
                state.setInput(Input.MOVE_FORWARD, in > 0);
                state.setInput(Input.MOVE_BACK, in < 0);
                state.setInput(Input.SPRINT, in > 0);
                state.setInput(Input.JUMP, plan.jump[tick]);
                predicted.copyFrom(real);
                predicted.tick(plan.yaw[tick], in, in > 0, plan.jump[tick]);
                tick++;
                return state;
            }
            phase = 3;
        }
        // played out: done once standing on the landing (or hanging on its ladder) a few ticks
        boolean there = e.grab ? ctx.playerFeet().equals(dest) && ctx.player().onClimbable() : ctx.playerFeet().equals(dest) && ctx.player().onGround();
        if (there) {
            if (++settle > 2) return state.setStatus(MovementStatus.SUCCESS);
        } else if (++waited > 30) {
            return fail(state, "missed the landing");
        }
        return state;
    }
}
