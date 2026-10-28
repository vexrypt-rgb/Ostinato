package baritone.maze;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: seeded layout for {@code #maze.tunnels}, an endless dig-only 3D maze of 3 wide x 3 high tunnels.
 * <p>
 * Ostinato: nodes sit on a 4-block grid ({@link #STRIDE}) horizontally and {@link #LEVEL} = 4 vertically, so parallel 3 wide
 * tunnels have 1-block walls between them and levels a 1-block floor/ceiling slab. An edge joins a node to a horizontal
 * neighbour (a straight tunnel) or, as a 3 wide stair ramp, to the node two strides away one level up or down (4 steps
 * of 1 block over the 5 columns between the node boxes). A ramp passes through the column of the two mid nodes and
 * notches the slab under the node above its low end, so those three nodes are blocked for good. Whether a node grows an
 * edge in a direction is a pure hash of (seed, node, direction), so the same seed always grows the same maze;
 * straight-ahead is likely, side branches less so (T and cross junctions), ramps rare.
 * <p>
 * Torch rule (minimum torches): one floor torch at the centre of a node only when it is a junction (it grew 2+ new
 * edges) or the top/bottom of a ramp starts there. Nothing else is lit.
 */
public final class TunnelPlan {

    public static final int STRIDE = 4, LEVEL = 4, RAMP_RUN = 2, MIN_LEVEL = -8, MAX_LEVEL = 0;
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    public record Node(int gx, int gy, int gz) {}

    /** One tunnel to carve. {@code from} is already dug. */
    public record Edge(Node from, Node to, int depth) {}

    public final long seed;
    public final int ox, oy, oz;
    private final Set<Node> dug = new HashSet<>();
    private final Set<Node> reserved = new HashSet<>();
    private final Map<Node, Integer> depth = new HashMap<>();
    private final List<Edge> frontier = new ArrayList<>();
    private final Set<Node> torchNodes = new HashSet<>();
    private final Map<Node, List<Node>> rampBlocks = new HashMap<>(); // Ostinato: ramp target -> nodes it blocks

    public TunnelPlan(long seed, int ox, int oy, int oz) {
        this.seed = seed;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    public BlockPos center(Node n) {
        return new BlockPos(ox + n.gx * STRIDE, oy + n.gy * LEVEL, oz + n.gz * STRIDE);
    }

    public int dugCount() {
        return dug.size();
    }

    public int frontierSize() {
        return frontier.size();
    }

    public boolean torchAt(Node n) {
        return torchNodes.contains(n);
    }

    private long hash(Node n, int salt) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = (h ^ n.gx) * 0xBF58476D1CE4E5B9L;
        h = (h ^ n.gy) * 0x94D049BB133111EBL;
        h = (h ^ n.gz) * 0xBF58476D1CE4E5B9L;
        h = (h ^ salt) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private int roll(Node n, int salt) {
        return (int) Math.floorMod(hash(n, salt), 100L);
    }

    /** Marks a node dug and grows its seeded edges. {@code cameFrom} null for the root. */
    public void markDug(Node n, Node cameFrom, int d) {
        dug.add(n);
        reserved.add(n);
        depth.put(n, d);
        frontier.removeIf(e -> e.to.equals(n));
        int ahead = -1;
        if (cameFrom != null && cameFrom.gy == n.gy) {
            for (int i = 0; i < 4; i++) {
                if (n.gx - cameFrom.gx == DIRS[i][0] && n.gz - cameFrom.gz == DIRS[i][1]) ahead = i;
            }
        }
        // Ostinato: roll the ramp before the straight edges. A ramp needs its mid node free, and straight-ahead (80%)
        // used to reserve it first, so run77 dug 30 edges without one. Try all four ways; flip up/down at the level bounds.
        int r = roll(n, 7), ramp = 0;
        if (r < 12) {
            int dy = r < 6 ? -1 : 1;
            if (n.gy + dy < MIN_LEVEL || n.gy + dy > MAX_LEVEL) dy = -dy;
            int i0 = roll(n, 8) % 4;
            for (int k = 0; k < 4 && ramp == 0; k++) {
                int i = (i0 + k) % 4;
                ramp = addRamp(n, DIRS[i][0], dy, DIRS[i][1], d);
            }
        }
        int grown = ramp;
        for (int i = 0; i < 4; i++) {
            int chance = ahead < 0 ? 70 : i == ahead ? 80 : (i + 2) % 4 == ahead ? 0 : 35;
            if (roll(n, i) < chance) grown += add(n, new Node(n.gx + DIRS[i][0], n.gy, n.gz + DIRS[i][1]), d);
        }
        if (grown >= 2 || ramp > 0) torchNodes.add(n);
        if (frontier.isEmpty()) {
            // Ostinato: never let the maze close. Force the first free neighbour of any dug node.
            for (Node m : dug) {
                for (int[] dir : DIRS) {
                    if (add(m, new Node(m.gx + dir[0], m.gy, m.gz + dir[1]), depth.get(m)) > 0) return;
                }
            }
        }
    }

    /**
     * Ostinato: ramp from {@code from} to the node {@link #RAMP_RUN} strides along (dx, dz), one level up or down. It
     * blocks the mid node on both levels and the node above its low end (see {@link #carve}); all must still be free.
     */
    private int addRamp(Node from, int dx, int dy, int dz, int d) {
        Node to = new Node(from.gx + dx * RAMP_RUN, from.gy + dy, from.gz + dz * RAMP_RUN);
        Node lo = dy > 0 ? from : to, hi = dy > 0 ? to : from;
        List<Node> block = List.of(new Node(from.gx + dx, from.gy, from.gz + dz), new Node(from.gx + dx, to.gy, from.gz + dz),
                new Node(lo.gx, hi.gy, lo.gz));
        if (to.gy < MIN_LEVEL || to.gy > MAX_LEVEL || reserved.contains(to)) return 0;
        for (Node b : block) if (reserved.contains(b)) return 0;
        reserved.addAll(block);
        rampBlocks.put(to, block);
        reserved.add(to);
        frontier.add(new Edge(from, to, d + 1));
        return 1;
    }

    /** Ostinato: a node no edge may ever reach (the entry stair passes through it). */
    public void block(Node n) {
        reserved.add(n);
    }

    private int add(Node from, Node to, int d) {
        if (to.gy < MIN_LEVEL || to.gy > MAX_LEVEL || reserved.contains(to)) return 0;
        reserved.add(to);
        frontier.add(new Edge(from, to, d + 1));
        return 1;
    }

    /** Next edge to dig: the frontier edge whose dug end is closest to {@code near}, ties by seeded hash. */
    public Edge next(BlockPos near) {
        Edge best = null;
        double bestD = Double.MAX_VALUE;
        for (Edge e : frontier) {
            double dist = center(e.from).distSqr(near) + (hash(e.to, 11) & 7);
            if (dist < bestD) {
                bestD = dist;
                best = e;
            }
        }
        return best;
    }

    /** Edge failed to dig (lava, bedrock, stuck). Drop it; the node becomes free for another route later. */
    public void abandon(Edge e) {
        frontier.remove(e);
        reserved.remove(e.to);
        List<Node> block = rampBlocks.remove(e.to);
        if (block != null) block.forEach(reserved::remove);
        if (frontier.isEmpty()) markDug(e.from, null, e.depth - 1);
    }

    /**
     * Air cells for one edge: the 3x3x3 node at each end plus the corridor between, 3 wide and 3 high. Floor is the block
     * under the centre, so feet stand at {@code center}.
     * <p>
     * Ostinato: a rise (ramp or entry) is a stair worked out from the low end: feet stay at the low node through its box,
     * then climb 1 per column until level with the high end, so the steps lie between the node boxes (the old profile
     * started climbing inside the low box, which the box then cleared to the floor: a 2-block step nobody could walk).
     * Each column is 3 high over its step, plus one row where the next column steps up (jump headroom). Needs a run of
     * at least rise + 1 columns between the two centres.
     */
    public static Set<BlockPos> carve(BlockPos a, BlockPos b) {
        Set<BlockPos> out = new HashSet<>();
        box(out, a, 3);
        box(out, b, 3);
        int rise = b.getY() - a.getY();
        BlockPos lo = rise >= 0 ? a : b, hi = rise >= 0 ? b : a;
        int dx = Integer.signum(hi.getX() - lo.getX()), dz = Integer.signum(hi.getZ() - lo.getZ());
        int run = Math.max(Math.abs(hi.getX() - lo.getX()), Math.abs(hi.getZ() - lo.getZ()));
        int up = Math.abs(rise);
        for (int t = 0; t <= run; t++) {
            int fy = lo.getY() + Math.max(0, Math.min(t - 1, up));
            int next = lo.getY() + Math.max(0, Math.min(t, up));
            int top = t < run && next > fy ? fy + 3 : fy + 2;
            for (int w = -1; w <= 1; w++) {
                for (int y = fy; y <= top; y++) {
                    out.add(new BlockPos(lo.getX() + dx * t + (dz != 0 ? w : 0), y, lo.getZ() + dz * t + (dx != 0 ? w : 0)));
                }
            }
        }
        return out;
    }

    private static void box(Set<BlockPos> out, BlockPos c, int h) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y < h; y++) out.add(c.offset(x, y, z));
    }
}
