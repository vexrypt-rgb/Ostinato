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
 * Nodes sit on a lattice {@link #STRIDE} blocks apart horizontally and {@link #LEVEL} blocks apart vertically. An edge
 * joins a node to a horizontal neighbour (a straight tunnel) or to the neighbour one level up or down (a ramp that rises
 * one block per block, walkable as stairs; ramps are 4 high so the head never clips). Whether a node grows an edge in a
 * direction is a pure hash of (seed, node, direction), so the same seed always grows the same maze; straight-ahead is
 * likely, side branches less so (T and cross junctions), ramps rare.
 * <p>
 * Torch rule (minimum torches): one floor torch at the centre of a node when the node is a junction (it grew 2+ new
 * edges) or its tree depth is a multiple of {@link #TORCH_EVERY} (every 4 edges = every 32 blocks of tunnel). Nothing
 * else is lit.
 */
public final class TunnelPlan {

    public static final int STRIDE = 8, LEVEL = 8, TORCH_EVERY = 4, MIN_LEVEL = -4, MAX_LEVEL = 0;
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
        int grown = 0;
        for (int i = 0; i < 4; i++) {
            int chance = ahead < 0 ? 70 : i == ahead ? 80 : (i + 2) % 4 == ahead ? 0 : 35;
            if (roll(n, i) < chance) grown += add(n, new Node(n.gx + DIRS[i][0], n.gy, n.gz + DIRS[i][1]), d);
        }
        int r = roll(n, 7);
        if (r < 12) {
            int i = roll(n, 8) % 4, dy = r < 6 ? -1 : 1;
            grown += add(n, new Node(n.gx + DIRS[i][0], n.gy + dy, n.gz + DIRS[i][1]), d);
        }
        if (grown >= 2 || d % TORCH_EVERY == 0) torchNodes.add(n);
        if (frontier.isEmpty()) {
            // Ostinato: never let the maze close. Force the first free neighbour of any dug node.
            for (Node m : dug) {
                for (int[] dir : DIRS) {
                    if (add(m, new Node(m.gx + dir[0], m.gy, m.gz + dir[1]), depth.get(m)) > 0) return;
                }
            }
        }
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
        if (frontier.isEmpty()) markDug(e.from, null, e.depth - 1);
    }

    /**
     * Air cells for one edge: the 3x3x3 node at each end plus the corridor between, 3 wide. Ramps rise one block per
     * block (floor steps) and are carved 4 high. Floor is the block under the centre, so feet stand at {@code center}.
     */
    public static Set<BlockPos> carve(BlockPos a, BlockPos b) {
        Set<BlockPos> out = new HashSet<>();
        box(out, a, 3);
        box(out, b, 3);
        int dx = Integer.signum(b.getX() - a.getX()), dz = Integer.signum(b.getZ() - a.getZ());
        int run = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        int rise = b.getY() - a.getY();
        for (int t = 0; t <= run; t++) {
            int fy = a.getY() + (run == 0 ? 0 : Math.round((float) rise * t / run));
            int h = rise == 0 ? 3 : 4;
            for (int w = -1; w <= 1; w++) {
                for (int y = 0; y < h; y++) {
                    out.add(new BlockPos(a.getX() + dx * t + (dz != 0 ? w : 0), fy + y, a.getZ() + dz * t + (dx != 0 ? w : 0)));
                }
            }
            if (rise != 0) {
                // Ostinato: a step only stays walkable if the block one higher at the previous column is clear too.
                for (int w = -1; w <= 1; w++) {
                    out.add(new BlockPos(a.getX() + dx * t + (dz != 0 ? w : 0), fy + h, a.getZ() + dz * t + (dx != 0 ? w : 0)));
                }
            }
        }
        return out;
    }

    private static void box(Set<BlockPos> out, BlockPos c, int h) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y < h; y++) out.add(c.offset(x, y, z));
    }
}
