package baritone.pathing.kinematic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: cheap TAS-style probe for one jump the kinematic family and the physics search left behind.
 * Each tick branches only jump or no-jump, sprinting forward at a fixed yaw, and {@link PlayerSim} has to
 * actually stand on the landing. The full {@link TasBrute} beam stays offline. This one is capped so a weak
 * machine pays for a miss once per spot, then the answer is cached.
 */
public final class SimProbe {

    /** Long enough for a short run-up and a jump, short enough to stay off the game thread's budget. */
    public static final int MAX_TICKS = 28;
    /** Grounded and airborne states kept each tick. */
    public static final int BEAM_HALF = 6;
    /** Hard stop. Past this the spot is a miss and Baritone's movement may take it. */
    public static final int MAX_EXPANSIONS = 480;
    public static final int CACHE_CAP = 128;

    private SimProbe() {}

    /** A plan, or a cached miss ({@link #found()} false). {@code jumps[t]} is the jump key on that tick. */
    public static final class Result {
        public final boolean[] jumps;
        public final int expansions;

        Result(boolean[] jumps, int expansions) {
            this.jumps = jumps;
            this.expansions = expansions;
        }

        public boolean found() {
            return jumps != null;
        }

        public int ticks() {
            return jumps == null ? 0 : jumps.length;
        }
    }

    private static final class Node {
        final PlayerSim sim;
        final boolean[] jumps;
        final int depth;
        final long bits;
        double score;

        Node(PlayerSim sim, boolean[] jumps, int depth, long bits) {
            this.sim = sim;
            this.jumps = jumps;
            this.depth = depth;
            this.bits = bits;
        }
    }

    /**
     * Search from {@code start} facing {@code yawDeg} (Minecraft yaw, 0 is +z) until the feet stand on
     * {@code (gx, gy, gz)}. Null jumps means no plan inside the budget.
     */
    public static Result search(PlayerSim.World world, PlayerSim start, float yawDeg, int gx, int gy, int gz) {
        TasBrute.Landing goal = new TasBrute.Landing(gx, gy, gz);
        if (goal.on(start, world)) {
            return new Result(new boolean[0], 0);
        }
        double floor = Math.min(start.y, gy) - 2.5;
        List<Node> layer = new ArrayList<>();
        layer.add(new Node(new PlayerSim(world).copyFrom(start), new boolean[MAX_TICKS], 0, 0));
        int expansions = 0;
        for (int t = 0; t < MAX_TICKS; t++) {
            List<Node> ground = new ArrayList<>();
            List<Node> air = new ArrayList<>();
            for (Node n : layer) {
                int branches = n.sim.onGround ? 2 : 1;
                for (int j = 0; j < branches; j++) {
                    if (++expansions > MAX_EXPANSIONS) {
                        return new Result(null, expansions);
                    }
                    boolean jump = j == 1;
                    PlayerSim c = new PlayerSim(world).copyFrom(n.sim);
                    c.tick(yawDeg, true, true, jump);
                    if (goal.on(c, world)) {
                        boolean[] plan = Arrays.copyOf(n.jumps, t + 1);
                        plan[t] = jump;
                        return new Result(plan, expansions);
                    }
                    if (c.y < floor) {
                        continue;
                    }
                    boolean[] jumps = Arrays.copyOf(n.jumps, MAX_TICKS);
                    jumps[t] = jump;
                    long bits = n.bits | (jump ? 1L << t : 0L);
                    Node m = new Node(c, jumps, t + 1, bits);
                    m.score = score(c, gx, gy, gz);
                    (c.onGround ? ground : air).add(m);
                }
            }
            ground.sort(ORDER);
            air.sort(ORDER);
            int g = Math.min(ground.size(), Math.max(BEAM_HALF, BEAM_HALF * 2 - air.size()));
            layer = new ArrayList<>(ground.subList(0, g));
            layer.addAll(air.subList(0, Math.min(air.size(), BEAM_HALF * 2 - g)));
            if (layer.isEmpty()) {
                return new Result(null, expansions);
            }
        }
        return new Result(null, expansions);
    }

    /** Horizontal distance to the landing centre. Being below the landing is worse. Being above is not. */
    static double score(PlayerSim s, int gx, int gy, int gz) {
        double dx = s.x - (gx + 0.5);
        double dz = s.z - (gz + 0.5);
        double score = dx * dx + dz * dz;
        if (s.y < gy) {
            double dy = gy - s.y;
            score += dy * dy * 4;
        }
        return score;
    }

    private static final Comparator<Node> ORDER = Comparator.<Node>comparingDouble(n -> n.score).thenComparingLong(n -> n.bits);

    /** Feet cell, 45-degree yaw, and three speed buckets. A miss is remembered so the game thread does not search again. */
    public static final class Cache {
        private final Map<Key, Result> map = new LinkedHashMap<>(CACHE_CAP, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Result> eldest) {
                return size() > CACHE_CAP;
            }
        };
        public int searches;

        public Result solve(PlayerSim.World world, PlayerSim start, float yawDeg, int gx, int gy, int gz) {
            Key key = Key.of(start, yawDeg, gx, gy, gz);
            Result hit = map.get(key);
            if (hit != null) {
                return hit;
            }
            searches++;
            Result found = search(world, start, yawDeg, gx, gy, gz);
            map.put(key, found);
            return found;
        }

        public int size() {
            return map.size();
        }
    }

    /** Yaw toward a landing, same atan2 the kinematic controller uses. */
    public static float yawToward(double x, double z, int gx, int gz) {
        return (float) Math.toDegrees(Math.atan2(-(gx + 0.5 - x), gz + 0.5 - z));
    }

    static final class Key {
        final int sx, sy, sz, gx, gy, gz, yaw, speed;

        Key(int sx, int sy, int sz, int gx, int gy, int gz, int yaw, int speed) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.yaw = yaw;
            this.speed = speed;
        }

        static Key of(PlayerSim s, float yawDeg, int gx, int gy, int gz) {
            double v = Math.hypot(s.vx, s.vz);
            int speed = v < 0.08 ? 0 : v < 0.2 ? 1 : 2;
            int yaw = Math.floorMod(Math.round(yawDeg / 45f), 8);
            return new Key(PlayerSim.floor(s.x), PlayerSim.floor(s.y), PlayerSim.floor(s.z), gx, gy, gz, yaw, speed);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return sx == k.sx && sy == k.sy && sz == k.sz && gx == k.gx && gy == k.gy && gz == k.gz && yaw == k.yaw && speed == k.speed;
        }

        @Override
        public int hashCode() {
            int h = sx;
            h = h * 31 + sy;
            h = h * 31 + sz;
            h = h * 31 + gx;
            h = h * 31 + gy;
            h = h * 31 + gz;
            h = h * 31 + yaw;
            h = h * 31 + speed;
            return h;
        }
    }
}
