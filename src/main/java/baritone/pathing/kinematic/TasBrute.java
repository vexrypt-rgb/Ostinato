package baritone.pathing.kinematic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ostinato: TAS style brute force over per-tick inputs (camera yaw, W with sprint, jump) in {@link PlayerSim}. A beam
 * search: every tick each kept state is expanded with every input, near-identical states are merged, and the best
 * {@link #beam} by a progress heuristic go on. Offline only (seconds per jump): proves a jump the planner has no
 * template for is possible, and finds the inputs to build one from. The camera turns at most {@link #maxTurn} degrees
 * a tick, so whatever it finds the bot can also fly.
 */
public final class TasBrute {

    /** Where the run has to end: standing on the landing block, or hanging on the ladder there ({@link #grab}). */
    public static final class Landing {
        public final int x, y, z;
        public boolean grab;

        /** Landing node (x, y, z): the feet cell, or the cell above a low top (candle, bed, slab) as the planner counts it. */
        public Landing(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Landing grab() {
            grab = true;
            return this;
        }

        /** Done: hanging on the ladder cell, or standing on the landing block. */
        boolean reached(PlayerSim s, PlayerSim.World w) {
            return grab ? PlayerSim.floor(s.x) == x && PlayerSim.floor(s.y) == y && PlayerSim.floor(s.z) == z && s.climbable() : on(s, w);
        }

        /** Standing at the landing's height on the landing block alone (a neo lands with the centre over its side). */
        boolean on(PlayerSim s, PlayerSim.World w) {
            if (!s.onGround || !PlayerSim.nodeHeight(s.y, y)) {
                return false;
            }
            List<double[]> under = new ArrayList<>();
            double h = PlayerSim.HALF_WIDTH - 1e-4;
            w.collect(s.x - h, s.y - 0.01, s.z - h, s.x + h, s.y, s.z + h, under);
            boolean any = false;
            for (double[] b : under) {
                if (b[4] < s.y - 0.01 || b[1] > s.y || b[3] <= s.x - h || b[0] >= s.x + h || b[5] <= s.z - h || b[2] >= s.z + h) {
                    continue; // not touching the feet
                }
                if (PlayerSim.floor(b[0] + 1e-6) != x || PlayerSim.floor(b[2] + 1e-6) != z) {
                    return false;
                }
                any = true;
            }
            return any;
        }
    }

    public static final class Result {
        public final float[] yaw;
        public final int[] input;
        /** The strafe key every tick: +1 A (left), -1 D (right), 0 neither. */
        public final int[] strafe;
        public final boolean[] jump;
        public final PlayerSim end;

        Result(float[] yaw, int[] input, int[] strafe, boolean[] jump, PlayerSim end) {
            this.yaw = yaw;
            this.input = input;
            this.strafe = strafe;
            this.jump = jump;
            this.end = end;
        }

        public int ticks() {
            return yaw.length;
        }

        /** One character per tick: J jump, W forward, . coast, B back, then a or d for a strafe; the yaw every tick it changed. */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            float last = Float.NaN;
            for (int t = 0; t < yaw.length; t++) {
                sb.append(jump[t] ? 'J' : input[t] > 0 ? 'W' : input[t] < 0 ? 'B' : '.');
                if (strafe[t] != 0) {
                    sb.append(strafe[t] > 0 ? 'a' : 'd');
                }
                if (yaw[t] != last) {
                    sb.append('(').append(Math.round(yaw[t])).append(')');
                    last = yaw[t];
                }
            }
            return sb + String.format(" end=%.3f,%.3f,%.3f", end.x, end.y, end.z);
        }
    }

    private static final class Node {
        final PlayerSim s;
        final float yaw;
        final Node parent;
        final int input;
        final int strafe;
        final boolean jump;
        final int depth;
        double score;
        /** The merge key; also breaks ties in score, so the beam's order never depends on hash-table layout. */
        long key;
        boolean flew;

        Node(PlayerSim s, float yaw, Node parent, int input, int strafe, boolean jump, int depth) {
            this.s = s;
            this.yaw = yaw;
            this.parent = parent;
            this.input = input;
            this.strafe = strafe;
            this.jump = jump;
            this.depth = depth;
        }
    }

    public int beam = 3000;
    public int maxTicks = 45;
    /** The camera's turn per tick, degrees. */
    public float maxTurn = 30;
    /** Turn steps tried per tick, as fractions of {@link #maxTurn}. */
    public float[] turns = {-1, -0.5f, -0.2f, 0, 0.2f, 0.5f, 1};
    public boolean allowBack = false;
    /**
     * Also try W with A or with D. Vanilla scales a diagonal to length 1 where W alone is 0.98, and the run goes 45
     * degrees off the camera, so a strafe both runs faster and curves round a wall sooner than the camera can turn.
     */
    public boolean allowStrafe = false;
    public boolean debug = false;
    /** Ostinato: run the search in the native library when it is loaded ({@link NativeTas}); the answer is the same. */
    public boolean nativeSearch = true;
    /** Lowest feet y before a branch is dropped (fell past the landing). */
    public double floorY = Double.NEGATIVE_INFINITY;

    private final PlayerSim.World world;

    public TasBrute(PlayerSim.World world) {
        this.world = world;
    }

    /** Searches from {@code start} (a standing or moving state, camera at {@code yaw0}) to the landing. */
    public Result search(PlayerSim start, float yaw0, Landing goal) {
        if (nativeSearch && !debug) {
            NativeTas.Answer a = NativeTas.search(this, world, start, yaw0, goal);
            if (a != null) {
                if (NativeTas.verify) {
                    NativeTas.check(a.plan(), searchJava(start, yaw0, goal));
                }
                return a.plan();
            }
        }
        return searchJava(start, yaw0, goal);
    }

    /** The reference search, and the fallback whenever the native one does not answer. */
    Result searchJava(PlayerSim start, float yaw0, Landing goal) {
        field = new Field(world, start, goal);
        double floor = Math.max(floorY, Math.min(start.y, goal.y - 1) - 1.0);
        List<Node> layer = new ArrayList<>();
        layer.add(new Node(new PlayerSim(world).copyFrom(start), yaw0, null, 0, 0, false, 0));
        int[][] inputs = inputs(allowBack, allowStrafe);
        for (int t = 0; t < maxTicks; t++) {
            Map<Long, Node> next = new HashMap<>();
            for (Node n : layer) {
                for (float tf : turns) {
                    float yaw = n.yaw + tf * maxTurn;
                    for (int[] key : inputs) {
                        int in = key[0], st = key[1];
                        for (int j = 0; j < (n.s.onGround ? 2 : 1); j++) {
                            boolean jump = j == 1;
                            PlayerSim c = new PlayerSim(world).copyFrom(n.s);
                            c.tick(yaw, in, st, in > 0, jump);
                            Node m = new Node(c, yaw, n, in, st, jump, t + 1);
                            m.flew = n.flew || !c.onGround;
                            if ((m.flew || goal.grab) && goal.reached(c, world) && holds(c, yaw, goal)) {
                                return trace(m);
                            }
                            if (c.y < floor || !c.onGround && c.vy < 0 && c.y < goal.y - (goal.grab ? 0 : 0.75) - 1e-3 && m.flew) {
                                continue; // falling below the landing's top: it can only come down lower now
                            }
                            m.score = score(c, goal.y);
                            m.key = key(c, yaw);
                            Node old = next.get(m.key);
                            if (old == null || m.score < old.score) {
                                next.put(m.key, m);
                            }
                        }
                    }
                }
            }
            // half the beam each for grounded and airborne states: the jumps rate better than the run-up they need
            List<Node> ground = new ArrayList<>(), air = new ArrayList<>();
            for (Node n : next.values()) {
                (n.s.onGround ? ground : air).add(n);
            }
            ground.sort(BEAM_ORDER);
            air.sort(BEAM_ORDER);
            int g = Math.min(ground.size(), Math.max(beam / 2, beam - air.size()));
            layer = new ArrayList<>(ground.subList(0, g));
            layer.addAll(air.subList(0, Math.min(air.size(), beam - g)));
            layer.sort(BEAM_ORDER);
            if (debug && !layer.isEmpty()) {
                PlayerSim b = layer.get(0).s;
                System.out.printf("TASDBG t=%d n=%d best=%.3f pos=%.3f,%.3f,%.3f og=%b%n", t, layer.size(), layer.get(0).score, b.x, b.y, b.z, b.onGround);
            }
            if (layer.isEmpty()) {
                return null;
            }
        }
        return null;
    }

    /** The keys tried every tick as (forward, strafe), in the order the native search tries them too. */
    static int[][] inputs(boolean back, boolean strafe) {
        List<int[]> l = new ArrayList<>();
        l.add(new int[]{1, 0});
        if (strafe) {
            l.add(new int[]{1, 1});
            l.add(new int[]{1, -1});
        }
        l.add(new int[]{0, 0});
        if (back) {
            l.add(new int[]{-1, 0});
        }
        return l.toArray(new int[0][]);
    }

    private static final Comparator<Node> BEAM_ORDER = Comparator.<Node>comparingDouble(n -> n.score).thenComparingLong(n -> n.key);

    /**
     * The landing has to last: three ticks later the player still stands on the block (or hangs on the ladder). On a
     * block the keys step back in toward its middle ({@link #stepKeys}), as the executors do, so a landing on the
     * smallest sliver of the box with speed still outward counts when those keys keep it there.
     */
    private boolean holds(PlayerSim landed, float yaw, Landing goal) {
        PlayerSim c = new PlayerSim(world).copyFrom(landed);
        for (int k = 0; k < 3; k++) {
            if (goal.grab) {
                c.tick(yaw, 0, false, false);
            } else {
                int[] keys = stepKeys(yaw, goal.x + 0.5 - c.x, goal.z + 0.5 - c.z);
                c.tick(yaw, keys[0], keys[1], false, false);
            }
        }
        return goal.grab ? c.climbable() : goal.on(c, world);
    }

    /**
     * The keys that push most toward (dx, dz) from the look {@code yawDeg}: {forward, strafe}, strafe +1 to the left.
     * Both 0 within a hundredth of the point. {@link baritone.pathing.movement.StepIn} with StrictMath, so the native
     * search picks the same keys.
     */
    public static int[] stepKeys(float yawDeg, double dx, double dz) {
        if (dx * dx + dz * dz < 1e-4) {
            return new int[]{0, 0};
        }
        double yaw = Math.toRadians(yawDeg);
        double sin = StrictMath.sin(yaw), cos = StrictMath.cos(yaw);
        int bestIn = 0, bestSt = 0;
        double best = 0;
        for (int in = -1; in <= 1; in++) {
            for (int st = -1; st <= 1; st++) {
                if (in == 0 && st == 0) {
                    continue;
                }
                double len = in != 0 && st != 0 ? Math.sqrt(2) : 1;
                double fx = st / len, fz = in / len;
                double dot = (fx * cos - fz * sin) * dx + (fz * cos + fx * sin) * dz;
                if (dot > best) {
                    best = dot;
                    bestIn = in;
                    bestSt = st;
                }
            }
        }
        return new int[]{bestIn, bestSt};
    }

    private Field field;

    /** Lower is better: path distance left (around walls) after coasting a few ticks, and height below the landing. */
    private double score(PlayerSim s, int ty) {
        double d = field.at(s.x + s.vx * 4, s.z + s.vz * 4);
        if (Double.isInfinite(d)) {
            d = field.at(s.x, s.z) + 0.5;
        }
        double below = Math.max(0, ty - s.y);
        return d + below * 0.5;
    }

    /**
     * Distance to the landing for the box's centre on a fine grid, around whatever blocks the standing box at the
     * jump's height (a neo's wall): a straight line rates swinging out round the wall as going the wrong way.
     */
    private static final class Field {
        static final double RES = 0.1;
        final double x0, z0;
        final int nx, nz;
        final double[] dist;

        Field(PlayerSim.World world, PlayerSim start, Landing goal) {
            double minX = Math.min(start.x, goal.x) - 4, maxX = Math.max(start.x, goal.x + 1) + 4;
            double minZ = Math.min(start.z, goal.z) - 4, maxZ = Math.max(start.z, goal.z + 1) + 4;
            x0 = minX;
            z0 = minZ;
            nx = (int) Math.ceil((maxX - minX) / RES);
            nz = (int) Math.ceil((maxZ - minZ) / RES);
            dist = new double[nx * nz];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            boolean[] free = new boolean[nx * nz];
            double y = Math.max(start.y, goal.y - 1 + 0.01) + 0.05;
            List<double[]> boxes = new ArrayList<>();
            world.collect(minX - 1, y, minZ - 1, maxX + 1, y + PlayerSim.HEIGHT - 0.1, maxZ + 1, boxes);
            double h = PlayerSim.HALF_WIDTH;
            for (int i = 0; i < nx; i++)
                for (int k = 0; k < nz; k++) {
                    double cx = x0 + (i + 0.5) * RES, cz = z0 + (k + 0.5) * RES;
                    boolean ok = true;
                    for (double[] b : boxes) {
                        if (b[4] > y && b[1] < y + PlayerSim.HEIGHT - 0.1 && b[3] > cx - h && b[0] < cx + h && b[5] > cz - h && b[2] < cz + h) {
                            ok = false;
                            break;
                        }
                    }
                    free[i * nz + k] = ok;
                }
            java.util.PriorityQueue<double[]> open = new java.util.PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
            for (int i = 0; i < nx; i++)
                for (int k = 0; k < nz; k++) {
                    double cx = x0 + (i + 0.5) * RES, cz = z0 + (k + 0.5) * RES;
                    if (PlayerSim.floor(cx) == goal.x && PlayerSim.floor(cz) == goal.z) {
                        dist[i * nz + k] = 0;
                        open.add(new double[]{0, i, k});
                    }
                }
            while (!open.isEmpty()) {
                double[] c = open.poll();
                int i = (int) c[1], k = (int) c[2];
                if (c[0] > dist[i * nz + k]) continue;
                for (int di = -1; di <= 1; di++)
                    for (int dk = -1; dk <= 1; dk++) {
                        int a = i + di, b = k + dk;
                        if ((di | dk) == 0 || a < 0 || b < 0 || a >= nx || b >= nz || !free[a * nz + b]) continue;
                        double nd = c[0] + RES * (di != 0 && dk != 0 ? Math.sqrt(2) : 1);
                        if (nd < dist[a * nz + b]) {
                            dist[a * nz + b] = nd;
                            open.add(new double[]{nd, a, b});
                        }
                    }
            }
        }

        double at(double x, double z) {
            int i = (int) Math.floor((x - x0) / RES), k = (int) Math.floor((z - z0) / RES);
            if (i < 0 || k < 0 || i >= nx || k >= nz) {
                return Double.POSITIVE_INFINITY;
            }
            return dist[i * nz + k];
        }
    }

    private static long key(PlayerSim s, float yaw) {
        long h = Math.round(s.x * 32);
        h = h * 1_000_003 + Math.round(s.y * 32);
        h = h * 1_000_003 + Math.round(s.z * 32);
        h = h * 1_000_003 + Math.round(s.vx * 64);
        h = h * 1_000_003 + Math.round(s.vy * 64);
        h = h * 1_000_003 + Math.round(s.vz * 64);
        h = h * 31 + Math.round(yaw / 15);
        h = h * 31 + (s.onGround ? 1 : 0) + (s.sprinting ? 2 : 0);
        return h;
    }

    private static Result trace(Node end) {
        int n = end.depth;
        float[] yaw = new float[n];
        int[] in = new int[n], st = new int[n];
        boolean[] jump = new boolean[n];
        for (Node k = end; k.parent != null; k = k.parent) {
            yaw[k.depth - 1] = k.yaw;
            in[k.depth - 1] = k.input;
            st[k.depth - 1] = k.strafe;
            jump[k.depth - 1] = k.jump;
        }
        return new Result(yaw, in, st, jump, end.s);
    }

    /** A block world for offline searches: cells hold collision boxes in cell-local coordinates. */
    public static final class BoxWorld implements PlayerSim.World {
        public static final double[][] FULL = {{0, 0, 0, 1, 1, 1}};
        public static final double[][] CANDLE = {{7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 6 / 16.0, 9 / 16.0}};
        public static final double[][] BED = {{0, 0, 0, 1, 9 / 16.0, 1}};
        public static final double[][] BOTTOM_SLAB = {{0, 0, 0, 1, 0.5, 1}};
        public static final double[][] CHAIN_Z = {{6.5 / 16, 6.5 / 16, 0, 9.5 / 16, 9.5 / 16, 1}};
        public static final double[][] CHAIN_X = {{0, 6.5 / 16, 6.5 / 16, 1, 9.5 / 16, 9.5 / 16}};
        public static final double[][] PANE_POST = {{7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 1, 9 / 16.0}};

        private final Map<Long, double[][]> cells = new HashMap<>();
        private final Set<Long> climb = new HashSet<>(), bounce = new HashSet<>();
        private final Map<Long, Float> speed = new HashMap<>(), jumpF = new HashMap<>(), slip = new HashMap<>();

        private static long k(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
        }

        public BoxWorld put(int x, int y, int z, double[][] boxes) {
            cells.put(k(x, y, z), boxes);
            return this;
        }

        public BoxWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, double[][] boxes) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                    for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                        put(x, y, z, boxes);
            return this;
        }

        /** A glass pane, iron or copper bars (all one IronBarsBlock shape) at (x, y, z) joined on its four sides, as vanilla shapes it. */
        public BoxWorld pane(int x, int y, int z, boolean north, boolean south, boolean west, boolean east) {
            List<double[]> b = new ArrayList<>();
            b.add(PANE_POST[0]);
            if (north) b.add(new double[]{7 / 16.0, 0, 0, 9 / 16.0, 1, 9 / 16.0});
            if (south) b.add(new double[]{7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 1, 1});
            if (west) b.add(new double[]{0, 0, 7 / 16.0, 9 / 16.0, 1, 9 / 16.0});
            if (east) b.add(new double[]{7 / 16.0, 0, 7 / 16.0, 1, 1, 9 / 16.0});
            return put(x, y, z, b.toArray(new double[0][]));
        }

        public BoxWorld ladder(int x, int y, int z, double[][] boxes) {
            climb.add(k(x, y, z));
            return put(x, y, z, boxes);
        }

        public BoxWorld factors(int x, int y, int z, float speedFactor, float jumpFactor) {
            speed.put(k(x, y, z), speedFactor);
            jumpF.put(k(x, y, z), jumpFactor);
            return this;
        }

        /** A slime block: full cube, bounces a landing back up, slippery 0.8. */
        public BoxWorld slime(int x, int y, int z) {
            bounce.add(k(x, y, z));
            slip.put(k(x, y, z), 0.8f);
            return put(x, y, z, FULL);
        }

        @Override
        public boolean bouncy(int x, int y, int z) {
            return bounce.contains(k(x, y, z));
        }

        public BoxWorld slippery(int x, int y, int z, float slipperiness) {
            slip.put(k(x, y, z), slipperiness);
            return this;
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++)
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++)
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                        cellBoxes(x, y, z, out);
                    }
        }

        @Override
        public boolean cellBoxes(int x, int y, int z, List<double[]> out) {
            double[][] b = cells.get(k(x, y, z));
            if (b != null) {
                for (double[] q : b) out.add(new double[]{x + q[0], y + q[1], z + q[2], x + q[3], y + q[4], z + q[5]});
            }
            return true;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return slip.getOrDefault(k(x, y, z), 0.6f);
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return climb.contains(k(x, y, z));
        }

        @Override
        public float speedFactor(int x, int y, int z) {
            return speed.getOrDefault(k(x, y, z), 1f);
        }

        @Override
        public float jumpFactor(int x, int y, int z) {
            return jumpF.getOrDefault(k(x, y, z), 1f);
        }

        @Override
        public String toString() {
            return "BoxWorld" + Arrays.toString(new int[]{cells.size()});
        }
    }
}
