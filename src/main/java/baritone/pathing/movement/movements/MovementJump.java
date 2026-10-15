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

import baritone.pathing.kinematic.BsiWorld;

import baritone.utils.PositionSync;
import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.pathing.kinematic.SimTrace;
import baritone.pathing.kinematic.TasBrute;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.JumpSearch;
import baritone.pathing.kinematic.JumpTemplates;
import baritone.pathing.kinematic.MomentumHop;
import baritone.utils.PlanStats;
import net.minecraft.world.level.block.state.BlockState;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.JumpFailures;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.viz.PathViz;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A parkour jump from {@link JumpTemplates}: diagonal, knight's-move, down-and-across, 4-block flat, 3-block up and
 * neo jumps round the end of a wall. Planned by checking the template's swept cells, flown with {@link JumpSearch}
 * against the real world so the controls match the ones the jump was found with.
 */
public class MovementJump extends Movement {

    // TEMP: -Dostinato.jumpdebug=x,y,z[;x,y,z...] prints why each template is rejected at those nodes
    private static final java.util.Set<Long> DEBUG = parseDebug(System.getProperty("ostinato.jumpdebug"));

    private static java.util.Set<Long> parseDebug(String v) {
        if (v == null) return null;
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (String q : v.split(";")) {
            String[] p = q.split(",");
            out.add(BetterBlockPos.longHash(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())));
        }
        return out;
    }

    /** Moves slots; slot k takes the k-th feasible jump from a node. */
    public static final int SLOTS = 24;

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};
    /** Approach (ux, uz) and lateral (lx, lz) of the 8 frames: 4 directions, lateral side either way. */
    static final int[][] FRAMES = new int[8][];

    /** Ostinato: the straight head-hitter templates, then the rest. */
    private static final List<JumpTemplates.Template> HEAD_AND_ALL = new java.util.ArrayList<>();

    static {
        HEAD_AND_ALL.addAll(JumpTemplates.HEAD_HITTERS);
        HEAD_AND_ALL.addAll(JumpTemplates.ALL);
    }

    static {
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int i = 0; i < 8; i++) {
            int ux = dirs[i >> 1][0], uz = dirs[i >> 1][1], s = (i & 1) == 0 ? 1 : -1;
            FRAMES[i] = new int[]{ux, uz, -uz * s, ux * s, s};
        }
    }

    private static final class Option {
        final JumpTemplates.Template t;
        final int frame;
        final double cost;
        final int x, y, z;

        Option(JumpTemplates.Template t, int frame, double cost, int x, int y, int z) {
            this.t = t;
            this.frame = frame;
            this.cost = cost;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Cache {
        Object context;
        int x, y, z;
        /** Ostinato: head-hitter sims spent this calculation (each ~30 ms; they dominated A* under floating courses). */
        Object simContext;
        int sims;
        /** Ostinato: partial-block sims spent this calculation, and how many of those ran the whole plan grid. */
        int partialSims, partialFull;
        final List<Option> options = new ArrayList<>();
    }

    /** Forget which jumps failed (benches reuse the same coordinates for every course). */
    public static void forgetFailures() {
        JumpFailures.forget();
    }

    private static long failKey(int x, int y, int z, int dx, int dy, int dz) {
        return BetterBlockPos.longHash(x, y, z) * 31 + BetterBlockPos.longHash(dx, dy, dz);
    }

    private MovementState fail(MovementState state, String why) {
        JumpFailures.mark(failKey(src.x, src.y, src.z, dest.x, dest.y, dest.z), System.currentTimeMillis());
        logDebug(why + " (" + src + " -> " + dest + ", at " + ctx.player().position() + "); this jump costs more for 30s");
        if (Baritone.settings().kinematicTrace.value) {
            System.out.println("KTRACE jumpfail " + why + " " + src + "->" + dest + " t=" + t.a + "," + t.dy + "," + t.b + " r" + t.runUp + " at " + ctx.player().position() + " v=" + ctx.player().getDeltaMovement());
        }
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    private final JumpTemplates.Template t;
    private final int[] f;
    private JumpSearch js;
    private PlayerSim real;
    private boolean running, landed;
    private int settle;
    private int replans, replanCooldown;
    private int turnWait;
    /** A carried landing was already given one full search; don't pay for another every tick while it slows down. */
    private boolean carrySearched;
    /** The moving take-off was already given one capped-yaw search. */
    private boolean slewTried;
    /**
     * The two-yaw grid missed, so one beam search is flying instead. Same budget as
     * {@code TasBruteTest.headHitterLipFacingTheGap}: 2500 wide, 22 ticks, back allowed, 32° a tick.
     */
    private TasBrute.Result tasPlan;
    private int tasTick;
    /** Takeoff waits for the look to come within this many degrees of the plan's first yaw, for at most TICKS. */
    private static final float TURN_FIRST_DEG = 12;
    private static final int TURN_FIRST_TICKS = 12;
    private PlayerSim.World hopWorld;
    private boolean hopPrev;

    public int[] frame() {
        return f;
    }

    public int runUp() {
        return t.runUp;
    }

    public double lateral() {
        return t.lateral;
    }

    /** Along-a coordinate of the run-up start (take-off block centre 0.5). */
    public double startA() {
        return t.startA();
    }

    public int[] plan() {
        return t.plan;
    }

    public void markFailed() {
        JumpFailures.mark(failKey(src.x, src.y, src.z, dest.x, dest.y, dest.z), System.currentTimeMillis());
    }

    public double[] point(double a, double b) {
        return new double[]{wx(a, b), wz(a, b)};
    }

    private MovementJump(IBaritone baritone, BetterBlockPos src, JumpTemplates.Template t, int frame) {
        super(baritone, src, at(src, FRAMES[frame], t.a, t.dy, t.b), EMPTY, at(src, FRAMES[frame], t.a, t.dy - 1, t.b));
        this.t = t;
        this.f = FRAMES[frame];
    }

    private static BetterBlockPos at(BetterBlockPos src, int[] f, int a, int y, int b) {
        return new BetterBlockPos(src.x + a * f[0] + b * f[2], src.y + y, src.z + a * f[1] + b * f[3]);
    }

    private static List<Option> options(CalculationContext context, int x, int y, int z) {
        Cache c = CACHE.get();
        if (c.context == context && c.x == x && c.y == y && c.z == z) {
            return c.options;
        }
        c.context = context;
        if (c.simContext != context) {
            c.simContext = context;
            c.sims = c.partialSims = c.partialFull = 0;
        }
        int nodeSims = 0;
        c.x = x;
        c.y = y;
        c.z = z;
        c.options.clear();
        if (!context.allowParkour) {
            return c.options;
        }
        boolean dbg = DEBUG != null && DEBUG.contains(BetterBlockPos.longHash(x, y, z)); // TEMP
        // Ostinato: the templates assume a full block under take-off and landing and empty cells between. Off or onto a
        // low or narrow top (candle, chain, bed, slab), or past a thin shape (pane, chain), a jump is kept only once the
        // sim flies it from the real surface against the real blocks.
        boolean partialSrc = partial(context.get(x, y - 1, z));
        // Honey (including a carpet or slab on honey) rises about 0.4. MovementAscend and MovementParkour already
        // refuse an up jump off it; a diagonal template was still offered and the drill fell off the side.
        boolean low = MovementParkour.lowJump(MovementParkour.factorBlock(context, x, y, z));
        double startY = MovementParkour.standingY(context, x, y, z);
        int partialSims = 0;
        java.util.Set<Long> ledgeTried = new java.util.HashSet<>();
        for (int i = 0; i < FRAMES.length; i++) {
            int[] f = FRAMES[i];
            // straight ahead is walkable: nothing to jump over
            if (MovementHelper.canWalkOn(context, x + f[0], y - 1, z + f[1]) && MovementHelper.fullyPassable(context, x + f[0], y, z + f[1])) {
                continue;
            }
            templates:
            for (JumpTemplates.Template t : (i & 1) == 0 ? HEAD_AND_ALL : JumpTemplates.ALL) {
                if (t.neo && !context.allowNeos || t.runUp > 0 && !t.neo && !context.allowMomentumJumps) {
                    continue;
                }
                if (low && t.dy > 0) {
                    continue;
                }
                int dx = x + t.a * f[0] + t.b * f[2], dz = z + t.a * f[1] + t.b * f[3];
                if (!MovementHelper.canWalkOn(context, dx, y + t.dy - 1, dz)) {
                    continue;
                }
                double failMul = JumpFailures.multiplier(failKey(x, y, z, dx, y + t.dy, dz), System.currentTimeMillis());
                for (int r = 1; r <= t.runUp; r++) {
                    if (!MovementHelper.canWalkOn(context, x - r * f[0], y - 1, z - r * f[1])) {
                        // Ostinato: no room to run up, but a ledge juts from the front edge: try it from a standstill
                        if (!t.neo && ledgeAt(context, x + f[0], y - 1, z + f[1], startY) && ledgeTried.add(((long) i << 32) ^ (t.a << 16) ^ ((t.dy & 0xFF) << 8) ^ (t.b & 0xFF))) {
                            t = t.ledgeVariant();
                            break;
                        }
                        if (dbg) System.out.println("JDBG " + i + " " + t.a + "," + t.dy + "," + t.b + " r" + t.runUp + " runup");
                        continue templates;
                    }
                }
                boolean ceiling = false, partialCell = false;
                for (int[] cell : t.cells) {
                    int cx = x + cell[0] * f[0] + cell[2] * f[2], cy = y + cell[1], cz = z + cell[0] * f[1] + cell[2] * f[3];
                    if (MovementHelper.fullyPassable(context, cx, cy, cz) == (cell[3] == 1)) {
                        if (headHitterCell(context, cell, cx, cy, cz)) {
                            ceiling = true; // Ostinato: a low ceiling is checked by simulating the jump below
                            continue;
                        }
                        if (cell[3] == 0 && partialCell(context, cx, cy, cz)) {
                            partialCell = true; // Ostinato: a thin shape in the way: the sim flies past its real outline
                            continue;
                        }
                        if (cell[3] == 1 && cell[1] >= 2 && t.neo && !MovementHelper.fullyPassable(context, cx, y + 1, cz)) {
                            // Ostinato: a neo's feet stay within 1.25 of take-off, so the box always spans the y+1 row too:
                            // a solid y+1 in the same column stops it the same, and the wall may end below the template's top
                            continue;
                        }
                        if (dbg) System.out.println("JDBG " + i + " " + t.a + "," + t.dy + "," + t.b + " r" + t.runUp + " cell " + cell[0] + "," + cell[1] + "," + cell[2] + (cell[3] == 1 ? "s" : "") + " @" + cx + "," + cy + "," + cz);
                        continue templates;
                    }
                }
                if (dbg) System.out.println("JDBG " + i + " " + t.a + "," + t.dy + "," + t.b + " r" + t.runUp + " cells ok ceiling=" + ceiling);
                if (!ceiling && JumpTemplates.HEAD_HITTERS.contains(t)) {
                    continue; // open sky: MovementParkour's jump
                }
                if (ceiling && JumpTemplates.MOMENTUM.contains(t) && !lowTunnel(context, x, y, z, f)) {
                    continue; // Ostinato: a momentum run only pays under a real 2-high tunnel over take-off and gap
                }
                if (ceiling && JumpTemplates.MOMENTUM.contains(t) && hasShorter(c.options, t, dx, y + t.dy, dz)) {
                    continue; // Ostinato: a shorter momentum run-up already lands here; skip the costlier searches
                }
                if (!ceiling && JumpTemplates.MOMENTUM.contains(t)) {
                    continue;
                }
                if (ceiling) {
                    // Ostinato: cap the sims (cached across replans): per node and per calculation
                    Boolean known = HH_CACHE.get(hhKey(x, y, z, i, t, dx, dz));
                    if (known != null) {
                        if (!known) continue;
                    } else if (nodeSims >= MAX_NODE_SIMS || c.sims >= MAX_CALC_SIMS) {
                        continue;
                    }
                }
                long hh0 = ceiling ? System.nanoTime() : 0;
                boolean hhOk = !ceiling || hhCached(context, x, y, z, i, f, t, dx, y + t.dy, dz, c);
                if (ceiling) nodeSims++;
                if (ceiling) {
                    PlanStats.hhNs.addAndGet(System.nanoTime() - hh0);
                    PlanStats.hhCalls.incrementAndGet();
                }
                if (!hhOk) {
                    continue;
                }
                if (!ceiling && (partialSrc || partialCell || t.ledge || partial(context.get(dx, y + t.dy - 1, dz)))) {
                    long k = hhKey(x, y, z, i, t, dx, dz);
                    Boolean known = HH_CACHE.get(k);
                    if (known == null) {
                        if (partialSims >= MAX_NODE_PARTIAL || c.partialSims >= MAX_CALC_PARTIAL) {
                            continue;
                        }
                        partialSims++;
                        c.partialSims++;
                        long p0 = System.nanoTime();
                        known = partialLands(context, k, x, y, z, f, t, dx, y + t.dy, dz, startY, c);
                        PlanStats.hhNs.addAndGet(System.nanoTime() - p0);
                        PlanStats.hhCalls.incrementAndGet();
                    }
                    if (dbg) System.out.println("JDBG " + i + " " + t.a + "," + t.dy + "," + t.b + " r" + t.runUp + " partial sim=" + known);
                    if (!known) {
                        continue;
                    }
                }
                double cost = (t.ticks + t.runUp * WALK_ONE_BLOCK_COST + context.jumpPenalty) * context.jumpBias * failMul;
                int ddx = dx, ddy = y + t.dy, ddz = dz;
                // one option per landing block (the cheapest): the planner only has a fixed number of jump slots
                int same = -1;
                for (int k = 0; k < c.options.size() && same < 0; k++) {
                    Option o = c.options.get(k);
                    if (o.x == ddx && o.y == ddy && o.z == ddz) same = k;
                }
                if (same < 0) {
                    c.options.add(new Option(t, i, cost, ddx, ddy, ddz));
                } else if (cost < c.options.get(same).cost) {
                    c.options.set(same, new Option(t, i, cost, ddx, ddy, ddz));
                }
            }
        }
        if (c.options.size() > SLOTS) {
            trim(context, c.options);
        }
        if (dbg) for (Option o : c.options) System.out.println("JDBG option " + o.x + "," + o.y + "," + o.z + " " + o.t.a + "," + o.t.dy + "," + o.t.b + " cost=" + o.cost);
        return c.options;
    }

    // Ostinato: more landings than jump slots (an island over a wide floor: every drop template lands somewhere on
    // it). The planner only asks for the first SLOTS, so keep the cheapest, and drop a landing that a cheaper kept one
    // reaches by walking a few blocks of continuous floor; otherwise the island's real next jump fell off the end.
    private static void trim(CalculationContext context, List<Option> options) {
        options.sort(java.util.Comparator.comparingDouble(o -> o.cost));
        List<Option> kept = new ArrayList<>();
        for (Option o : options) {
            boolean walkable = false;
            for (int k = 0; k < kept.size() && !walkable; k++) {
                walkable = sameFloor(context, kept.get(k), o);
            }
            if (!walkable) {
                kept.add(o);
            }
        }
        options.clear();
        options.addAll(kept.subList(0, Math.min(SLOTS, kept.size())));
    }

    private static boolean sameFloor(CalculationContext context, Option a, Option b) {
        if (a.y != b.y || Math.abs(a.x - b.x) > 2 || Math.abs(a.z - b.z) > 2) {
            return false;
        }
        for (int x = Math.min(a.x, b.x); x <= Math.max(a.x, b.x); x++) {
            for (int z = Math.min(a.z, b.z); z <= Math.max(a.z, b.z); z++) {
                if (!MovementHelper.canWalkOn(context, x, a.y - 1, z) || !MovementHelper.fullyPassable(context, x, a.y, z) || !MovementHelper.fullyPassable(context, x, a.y + 1, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    // Ostinato: head-hitter jumps. Templates assume 2 clear cells over the arc; a solid ceiling cell above the
    // head (template cell 2+ up) does not rule a jump out: it caps or bumps the arc, so the jump is flown in the
    // sim against the real blocks and kept only when the sim lands on dest (the same search the flight runs).
    static boolean headHitterCell(CalculationContext context, int[] cell, int cx, int cy, int cz) {
        return cell[3] == 0 && cell[1] >= 2 && context.get(cx, cy, cz).getFluidState().isEmpty() && !MovementHelper.avoidWalkingInto(context.get(cx, cy, cz));
    }

    static final int MAX_NODE_SIMS = 3, MAX_CALC_SIMS = 150;
    /** Ostinato: partial-block sims: a cheap local search each (~3 ms), the whole plan grid only while the calculation has budget. */
    static final int MAX_NODE_PARTIAL = 16, MAX_CALC_PARTIAL = 800, MAX_CALC_PARTIAL_FULL = 40;

    /** A collision shape short of a full cube that still holds a player (candle, chain, bed, slab, soul sand). */
    static boolean partial(BlockState state) {
        var shape = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
        // by shape, not bounds: a T or cross of panes, iron or copper bars fills the bounds but not the cell
        return !shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) <= 1.0 && !net.minecraft.world.level.block.Block.isShapeFullBlock(shape);
    }

    /** A partial shape in front of take-off at floor level whose top is level with where the player stands: a ledge. */
    static boolean ledgeAt(CalculationContext context, int x, int y, int z, double standY) {
        BlockState state = context.get(x, y, z);
        if (!partial(state) || !state.getFluidState().isEmpty() || MovementHelper.avoidWalkingInto(state)) {
            return false;
        }
        return y + state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).max(net.minecraft.core.Direction.Axis.Y) >= standY - 0.01;
    }

    /** A cell the template wants empty holding a thin shape the sim can fly past (pane, chain, candle, bars). */
    static boolean partialCell(CalculationContext context, int x, int y, int z) {
        BlockState state = context.get(x, y, z);
        if (!state.getFluidState().isEmpty() || MovementHelper.avoidWalkingInto(state)) {
            return false;
        }
        return partial(state);
    }

    /** Flies the template from the real surface: around its own plan first, the whole plan grid while budget lasts. */
    private static boolean partialLands(CalculationContext context, long k, int x, int y, int z, int[] f, JumpTemplates.Template t, int dx, int dy, int dz, double startY, Cache c) {
        long now = System.currentTimeMillis();
        if (now - hhCacheBorn > 30000) {
            HH_CACHE.clear();
            hhCacheBorn = now;
        }
        PlayerSim.World world = new BsiWorld(context.bsi);
        JumpSearch js = jumpSearch(world, x, y, z, f, t, dx, dy, dz);
        PlayerSim start = startState(world, x, z, f, t, startY);
        boolean ok = js.search(start, true);
        boolean full = !ok && c.partialFull < MAX_CALC_PARTIAL_FULL;
        if (full) {
            c.partialFull++;
            ok = js.search(start, false);
        }
        if (ok || full) {
            HH_CACHE.put(k, ok); // a local miss without the full search may still land later: not remembered
        }
        return ok;
    }

    private static JumpSearch jumpSearch(PlayerSim.World world, int x, int y, int z, int[] f, JumpTemplates.Template t, int dx, int dy, int dz) {
        JumpSearch js = new JumpSearch(world);
        js.dirX = f[0];
        js.dirZ = f[1];
        js.edge = (x + 0.5 + 0.5 * f[0]) * f[0] + (z + 0.5 + 0.5 * f[1]) * f[1];
        js.destX = dx;
        js.destY = dy;
        js.destZ = dz;
        js.side = f[4];
        js.ledge = t.ledge;
        System.arraycopy(t.plan, 0, js.plan, 0, JumpSearch.DIMS);
        return js;
    }

    /** Standing still at the template's run-up start, feet at {@code startY}. */
    private static PlayerSim startState(PlayerSim.World world, int x, int z, int[] f, JumpTemplates.Template t, double startY) {
        double lat = Math.min(t.lateral, 0.75);
        double a = t.startA(), b = 0.5 + lat;
        PlayerSim start = new PlayerSim(world);
        start.x = x + 0.5 + (a - 0.5) * f[0] + (b - 0.5) * f[2];
        start.y = startY;
        start.z = z + 0.5 + (a - 0.5) * f[1] + (b - 0.5) * f[3];
        start.onGround = true;
        return start;
    }
    private static final java.util.Map<Long, Boolean> HH_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long hhCacheBorn = System.currentTimeMillis();

    private static long hhKey(int x, int y, int z, int frame, JumpTemplates.Template t, int dx, int dz) {
        int ti = System.identityHashCode(t);
        return (((long) (x & 0xFFFFF)) << 44) ^ (((long) (y & 0x3FF)) << 34) ^ (((long) (z & 0xFFFFF)) << 14) ^ ((long) frame << 10) ^ (ti * 0x9E3779B97F4A7C15L) ^ (((long) dx) * 31 + dz) * 0x632BE59BD9B4E019L;
    }

    private static boolean hhCached(CalculationContext context, int x, int y, int z, int frame, int[] f, JumpTemplates.Template t, int dx, int dy, int dz, Cache c) {
        long now = System.currentTimeMillis();
        if (now - hhCacheBorn > 30000) { // blocks change: forget sims older than ~30 s
            HH_CACHE.clear();
            hhCacheBorn = now;
        }
        long k = hhKey(x, y, z, frame, t, dx, dz);
        Boolean v = HH_CACHE.get(k);
        if (v == null) {
            c.sims++;
            v = headHitterLands(new BsiWorld(context.bsi), x, y, z, f, t, dx, dy, dz);
            HH_CACHE.put(k, v);
        }
        return v;
    }

    private static boolean lowTunnel(CalculationContext context, int x, int y, int z, int[] f) {
        for (int k = 0; k <= 2; k++) {
            if (MovementHelper.fullyPassable(context, x + k * f[0], y + 2, z + k * f[1])) return false;
        }
        return true;
    }

    private static boolean hasShorter(List<Option> options, JumpTemplates.Template t, int x, int y, int z) {
        for (Option o : options) {
            if (o.x == x && o.y == y && o.z == z && JumpTemplates.MOMENTUM.contains(o.t) && o.t.runUp < t.runUp) return true;
        }
        return false;
    }

    static boolean headHitterLands(PlayerSim.World world, int x, int y, int z, int[] f, JumpTemplates.Template t, int dx, int dy, int dz) {
        return headHitterLands(world, x, y, z, f, t, dx, dy, dz, y);
    }

    /** As above, standing at feet height {@code startY} (below y on soul sand and other low tops). */
    static boolean headHitterLands(PlayerSim.World world, int x, int y, int z, int[] f, JumpTemplates.Template t, int dx, int dy, int dz, double startY) {
        JumpSearch js = jumpSearch(world, x, y, z, f, t, dx, dy, dz);
        PlayerSim start = startState(world, x, z, f, t, startY);
        if (JumpTemplates.MOMENTUM.contains(t)) {
            // Ostinato: multi-hop momentum run under the ceiling, from standing still at the run-up start
            start.vy = -0.0784000015258789;
            return MomentumHop.find(world, start, f[0], f[1], dx, dy, dz, false, MomentumHop.PLAN_BUDGET) != null;
        }
        return js.search(start, true) || js.search(start, false);
    }

    public static void cost(CalculationContext context, int x, int y, int z, int slot, MutableMoveResult res) {
        List<Option> o = options(context, x, y, z);
        if (slot >= o.size()) {
            return;
        }
        Option op = o.get(slot);
        int[] f = FRAMES[op.frame];
        res.x = x + op.t.a * f[0] + op.t.b * f[2];
        res.y = y + op.t.dy;
        res.z = z + op.t.a * f[1] + op.t.b * f[3];
        res.cost = op.cost;
    }

    public static MovementJump cost(CalculationContext context, BetterBlockPos src, int slot) {
        List<Option> o = options(context, src.x, src.y, src.z);
        if (slot >= o.size()) {
            return null;
        }
        return new MovementJump(context.getBaritone(), src, o.get(slot).t, o.get(slot).frame);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        CACHE.get().context = null; // the world may have changed since
        for (Option o : options(context, src.x, src.y, src.z)) {
            if (o.t == t && FRAMES[o.frame] == f) {
                return o.cost;
            }
        }
        return COST_INF;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        for (int[] cell : t.cells) {
            if (cell[3] == 0) set.add(at(src, f, cell[0], cell[1], cell[2]));
        }
        for (int r = 0; r <= t.runUp; r++) {
            set.add(at(src, f, -r, 0, 0));
        }
        set.add(dest);
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || !running;
    }

    /**
     * Landing speed worth handing to the next movement: above a walk, and one friction tick still leaves the whole
     * hitbox on {@code dest} (the next movement may spend that tick running at the edge before it jumps).
     */
    private boolean keepsSpeed(PlayerSim s) {
        double hs = Math.hypot(s.vx, s.vz);
        if (hs <= 0.2) {
            return false;
        }
        double nx = s.x + s.vx * 0.546, nz = s.z + s.vz * 0.546;
        return nx > dest.x + PlayerSim.HALF_WIDTH && nx < dest.x + 1 - PlayerSim.HALF_WIDTH
                && nz > dest.z + PlayerSim.HALF_WIDTH && nz < dest.z + 1 - PlayerSim.HALF_WIDTH;
    }

    private void lookAt(MovementState state, float yaw) {
        state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, ctx.playerRotations().getPitch()), true));
    }

    /** Fly the one beam plan tick for tick. It already lands; this does not search again. */
    private MovementState playTas(MovementState state) {
        if (tasTick < tasPlan.ticks()) {
            int in = tasPlan.input[tasTick];
            state.setTarget(new MovementState.MovementTarget(new Rotation(tasPlan.yaw[tasTick], ctx.playerRotations().getPitch()), true));
            state.setInput(Input.MOVE_FORWARD, in > 0);
            state.setInput(Input.MOVE_BACK, in < 0);
            state.setInput(Input.SPRINT, in > 0);
            state.setInput(Input.JUMP, tasPlan.jump[tasTick]);
            tasTick++;
            return state;
        }
        if (ctx.playerFeet().equals(dest) && ctx.player().onGround()) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (++settle > 10) {
            return fail(state, "beam jump missed");
        }
        return state;
    }

    /** World x/z of frame point (a, b), block (0, 0) being src. */
    private double wx(double a, double b) {
        return src.x + 0.5 + (a - 0.5) * f[0] + (b - 0.5) * f[2];
    }

    private double wz(double a, double b) {
        return src.z + 0.5 + (a - 0.5) * f[1] + (b - 0.5) * f[3];
    }

    /** The executor runs a movement again after a setback; a flight that was under way must not be taken as flown. */
    @Override
    public void reset() {
        super.reset();
        js = null;
        real = null;
        running = landed = carrySearched = slewTried = false;
        tasPlan = null;
        tasTick = settle = replans = replanCooldown = 0;
        if (trace != null) {
            trace.finish(false);
            trace = null;
        }
    }

    @Override
    public MovementState updateState(MovementState state) {
        MovementState s = update0(state);
        if (trace != null && s.getStatus() != MovementStatus.RUNNING) {
            trace.finish(s.getStatus() == MovementStatus.SUCCESS);
            trace = null;
        }
        return s;
    }

    private SimTrace trace;

    /** With kinematicTrace on, compare the sim with the real player for this tick (see {@link SimTrace}). */
    private void trace(float yaw, int in, boolean jump) {
        if (!Baritone.settings().kinematicTrace.value || !running) {
            return;
        }
        SimTrace.files = true;
        if (trace == null) {
            trace = new SimTrace("jump " + t.a + "," + t.dy + "," + t.b + " runUp=" + t.runUp, new ClientWorld(ctx));
        }
        trace.observe(real, ctx.player().getYRot());
        trace.commit(real, yaw, in, in > 0, jump);
    }

    private MovementState update0(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        Vec3 p = ctx.player().position();
        if (p.y < Math.min(src.y, dest.y) - 0.6) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (js == null) {
            ClientWorld world = new ClientWorld(ctx);
            js = new JumpSearch(world);
            real = new PlayerSim(world);
            hopWorld = world;
            js.dirX = f[0];
            js.dirZ = f[1];
            js.edge = (src.x + 0.5 + 0.5 * f[0]) * f[0] + (src.z + 0.5 + 0.5 * f[1]) * f[1];
            js.destX = dest.x;
            js.destY = dest.y;
            js.destZ = dest.z;
            js.side = f[4];
            js.ledge = t.ledge;
            System.arraycopy(t.plan, 0, js.plan, 0, JumpSearch.DIMS);
        }
        Vec3 m = ctx.player().getDeltaMovement();
        ClientWorld.readPlayer(ctx, real);
        if (landed) {
            // let the landing settle, then step to the middle for the next movement
            // (a neo lands hanging over the side, feet outside dest: step in once the landing has settled)
            settle++;
            if (ctx.playerFeet().equals(dest) && (settle > 3 || Math.abs(m.x) + Math.abs(m.z) < 0.03)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (settle > 3) {
                MovementHelper.moveTowards(ctx, state, dest);
            }
            return state;
        }
        if (!running) {
            // Arriving from the previous jump still fast: take off from here. Walking back to the run-up start (or
            // standing still to turn) spends the speed on ground friction, and the path executor rewinds a walk back
            // onto the previous block. One full search, then never again while this landing slows down.
            // Head-hitter hops are not this. They need their own run-up and timing; starting one from a fast
            // arrival makes MomentumHop.find fail on the first tick and the jump is dropped.
            double along = real.vx * js.dirX + real.vz * js.dirZ;
            if (!JumpTemplates.MOMENTUM.contains(t) && ctx.playerFeet().equals(src) && real.onGround && along > 0.18 && !carrySearched) {
                carrySearched = true;
                slewTried = true;
                // The look turns 32° a tick. A plan that snaps to a new yaw in the air is what the 3-wall flew
                // 60-71° off, and it walked off the side. Search for a plan the cap can actually fly, from the
                // yaw the player has now. If none exists, fly a snap plan anyway: waiting for the yaw spends
                // the landing speed, and the 2-wall then misses (drill_hh 20:53, 0/8).
                js.slewYaw = true;
                js.faceYaw = ctx.player().getYRot();
                boolean found = js.search(real, true) || js.search(real, false);
                if (!found) {
                    js.slewYaw = false;
                    found = js.search(real, true) || js.search(real, false);
                }
                if (found) {
                    running = true;
                    PositionSync.sync(ctx.player());
                }
            }
            if (!running) {
                // walk to the run-up start, standing clear of the edge so the box stays on the block
                double lat = Math.min(t.lateral, 0.75);
                double tx = wx(t.startA(), 0.5 + lat), tz = wz(t.startA(), 0.5 + lat);
                double dx = tx - p.x, dz = tz - p.z;
                if (dx * dx + dz * dz > 0.15 * 0.15) {
                    MovementHelper.walkToSpot(ctx, state, dx, dz);
                    return state;
                }
                if (!real.onGround) {
                    return state;
                }
                if (JumpTemplates.MOMENTUM.contains(t)) {
                    running = true;
                    PositionSync.sync(ctx.player());
                } else {
                    boolean still = Math.abs(m.x) + Math.abs(m.z) <= 0.02;
                    if (!still) {
                        // Local search only, once a tick, and it does not stop to turn: waiting out the yaw dropped
                        // the 2-wall neo (drill_hh 20:53, 0/8). One capped search first when the take-off yaw is
                        // more than an aim step off. The head-hitter 3-gap (runUp=0) was flown at 68° of lag and
                        // missed; a plan the look can follow is used when this search finds one.
                        float want = js.yaw(js.plan, false, 0);
                        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(want - ctx.player().getYRot()));
                        if (!slewTried && err > JumpSearch.YAW_STEP) {
                            slewTried = true;
                            js.slewYaw = true;
                            js.faceYaw = ctx.player().getYRot();
                            if (!(js.search(real, true) || js.search(real, false))) {
                                js.slewYaw = false;
                            }
                        }
                        if (!js.slewYaw && !js.search(real, true)) {
                            return state;
                        }
                    } else {
                        js.slewYaw = false;
                        if (!js.search(real, true) && !js.search(real, false)) {
                            // Head-hitter lip, facing the gap: the grid has no plan. One beam search does
                            // (17 ticks, and it never steps back onto the previous block, so the path does not rewind).
                            TasBrute brute = new TasBrute(real.world());
                            brute.allowBack = true;
                            brute.maxTurn = JumpSearch.YAW_STEP;
                            brute.beam = 2500;
                            brute.maxTicks = 22;
                            tasPlan = brute.search(real, ctx.player().getYRot(), new TasBrute.Landing(dest.x, dest.y, dest.z));
                            tasTick = 0;
                            if (tasPlan == null) {
                                return fail(state, "no jump from here");
                            }
                        } else {
                            // Stopped, so face the plan before the first tick. The search assumed that facing.
                            float want = js.yaw(js.plan, false, 0);
                            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(want - ctx.player().getYRot())) > TURN_FIRST_DEG && turnWait++ < TURN_FIRST_TICKS) {
                                lookAt(state, want);
                                return state;
                            }
                        }
                    }
                    running = true;
                    // the client only reports moves over 0.03, and a neo starts ~0.01 off the wall: sync the server's copy of our
                    // position first, or it replays the jump from a stale spot inside the wall and rubber-bands us back
                    PositionSync.sync(ctx.player());
                }
            }
        }
        if (tasPlan != null) {
            return playTas(state);
        }
        if (JumpTemplates.MOMENTUM.contains(t)) {
            return momentumTick(state);
        }
        if (js.slewYaw) {
            // run() resims from this tick. The cap has to start at the look we have now, not the yaw at take-off.
            js.faceYaw = ctx.player().getYRot();
        }
        if (replanCooldown > 0) {
            replanCooldown--;
        } else if (!js.run(real, js.plan, js.jumped, js.airTicks, null)) {
            // drifted off the plan: replan around it, else fly it anyway. A full search is expensive, so if the player
            // keeps diverging (it isn't following the inputs) give up and let the path replan instead of searching every tick.
            if (++replans > 4) {
                return fail(state, "jump keeps diverging from plan: v=" + m + " sprint=" + real.sprinting + " ground=" + real.onGround);
            }
            js.search(real, true);
            replanCooldown = 3;
        }
        int in = js.input(js.plan, js.jumped, js.airTicks);
        boolean jump = js.jump(js.plan, real, js.jumped);
        trace(js.yaw(js.plan, js.jumped, js.airTicks), in, jump);
        state.setTarget(new MovementState.MovementTarget(new Rotation(js.yaw(js.plan, js.jumped, js.airTicks), ctx.playerRotations().getPitch()), true));
        state.setInput(Input.MOVE_FORWARD, in > 0);
        state.setInput(Input.MOVE_BACK, in < 0);
        state.setInput(Input.SPRINT, in > 0);
        state.setInput(Input.JUMP, jump);
        if (jump && real.x * js.dirX + real.z * js.dirZ >= js.edge + JumpSearch.EDGE[js.plan[2]]) {
            js.jumped = true;
            js.airTicks = 0;
        } else if (js.jumped) {
            js.airTicks++;
            if (js.airTicks > 1 && real.onGround) {
                // Still fast, and one more ground tick stays on this block: succeed now, so the next jump is pressed
                // before friction (0.546 a tick) eats the landing. Three ticks of no keys, the old settle, leaves a walk.
                // A neo whose feet are beside the block, or a landing that would walk off, settles as before.
                if (ctx.playerFeet().equals(dest) && keepsSpeed(real)) {
                    PathViz.get().jumpLand(this, real.x, real.y, real.z, false);
                    return state.setStatus(MovementStatus.SUCCESS);
                }
                landed = true;
                state.setInput(Input.MOVE_FORWARD, false);
                state.setInput(Input.MOVE_BACK, false);
                state.setInput(Input.SPRINT, false);
            }
        }
        // Ostinato path viz (K3): take-off, apex and landing of the template jump
        if (js.jumped) {
            PathViz viz = PathViz.get();
            if (js.airTicks == 0) {
                viz.jumpTakeoff(this, PathViz.JUMP, "jump " + t.a + "," + t.dy + "," + t.b, real.x, real.y, real.z);
            } else if (landed) {
                viz.jumpLand(this, real.x, real.y, real.z, false);
            } else {
                viz.jumpAir(this, real.x, real.y, real.z);
            }
        }
        return state;
    }

    // Ostinato: fly a multi-hop momentum run. Every grounded tick the hop timing is searched again from the real
    // state (cheap: a few hundred sim ticks), so a slightly different real tick doesn't throw the run off.
    private MovementState momentumTick(MovementState state) {
        float yaw = (float) Math.toDegrees(Math.atan2(-f[0], f[1]));
        state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, ctx.playerRotations().getPitch()), true));
        if (real.onGround && ctx.playerFeet().equals(dest)) {
            if (keepsSpeed(real)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            landed = true;
            return state;
        }
        boolean jump = false;
        if (real.onGround) {
            String p = MomentumHop.find(hopWorld, real, f[0], f[1], dest.x, dest.y, dest.z, hopPrev);
            if (p == null) {
                return fail(state, "momentum hop: no timing reaches dest from here");
            }
            jump = !p.isEmpty() && p.charAt(0) == 'J';
        }
        hopPrev = jump;
        trace(yaw, 1, jump);
        state.setInput(Input.MOVE_FORWARD, true);
        state.setInput(Input.SPRINT, true);
        state.setInput(Input.JUMP, jump);
        return state;
    }
}
