package baritone.swarm.center.app.fake;

import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.CoordinatorFeed;
import baritone.swarm.center.feed.SwarmSource;
import baritone.swarm.center.feed.SwarmView;
import baritone.swarm.center.feed.TerrainBuilder;
import baritone.swarm.center.feed.TerrainFeed;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 3 test scaffolding. A {@link FakeSwarm} that moves: every bot walks a circle around its home, with
 * its next path nodes and goal, while the coordinator holds a region lease and a vein lease and knows a few
 * observations, and every bot has a terrain slice. This is what {@code CenterApp --fake N} shows and what the map
 * slices develop against before any live run. Deterministic for a given clock.
 */
public final class DemoSwarm implements SwarmSource {

    /** One lap takes this long. */
    public static final long LAP_MS = 60_000L;
    public static final double RADIUS = 12.0;

    private final FakeSwarm swarm;
    private final LongSupplier clock;
    private final List<String> names = new ArrayList<>();
    private final List<double[]> homes = new ArrayList<>();

    public DemoSwarm(int n, LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.swarm = FakeSwarm.many(n, this.clock);
        long now = this.clock.getAsLong();
        for (SwarmView.BotState b : swarm.snapshot().bots()) {
            BotFeed.Pos p = b.feed().pos();
            names.add(b.name());
            homes.add(new double[]{p.x(), p.y(), p.z()});
            swarm.terrain(TerrainBuilder.around(b.name(), now, 1, 'o', p.blockX(), p.blockZ(), TERRAIN));
        }
        double[] h0 = homes.get(0);
        int x = (int) h0[0], z = (int) h0[2];
        List<CoordinatorFeed.Lease> leases = List.of(
                new CoordinatorFeed.Lease("demo-region", "r:o:" + (x - 20) + ",56," + (z + 10) + ":" + (x + 10) + ",70,"
                        + (z + 30), "region", 'o', new int[]{x - 20, 56, z + 10, x + 10, 70, z + 30},
                        names.get(Math.min(1, n - 1)), 3, "granted", "mine iron_ore", "running", "unknown", ""),
                new CoordinatorFeed.Lease("demo-vein", "v:o:" + (x + 30) + ",40," + (z - 5) + ":" + (x + 32) + ",42,"
                        + (z - 3), "vein", 'o', new int[]{x + 30, 40, z - 5, x + 32, 42, z - 3},
                        names.get(Math.min(2, n - 1)), 1, "offered", "mine coal_ore", "", "", ""),
                new CoordinatorFeed.Lease("demo-old", "ore1", "key", '?', null, names.get(0), 2, "expired",
                        "mine diamond_ore", "failed", "unknown", "lease-expired"));
        List<CoordinatorFeed.Observation> obs = List.of(
                new CoordinatorFeed.Observation("v:o:" + (x + 30) + ",40," + (z - 5) + ":" + (x + 32) + ",42," + (z - 3),
                        "vein", 'o', new int[]{x + 30, 40, z - 5, x + 32, 42, z - 3}, names.get(0), 90, "coal_ore.6",
                        "confirmed", false, 12_000),
                new CoordinatorFeed.Observation("c:o:" + (x - 8) + ",64," + (z - 12), "chest", 'o',
                        new int[]{x - 8, 64, z - 12, x - 8, 64, z - 12}, names.get(0), 80, "chest", "stale", false, 400_000),
                new CoordinatorFeed.Observation("b:o:" + (x + 5) + ",63," + (z + 40), "block", 'o',
                        new int[]{x + 5, 63, z + 40, x + 5, 63, z + 40}, names.get(0), 70, "spawner", "confirmed", true, 30_000),
                new CoordinatorFeed.Observation("w:o:home", "waypoint", 'o', null, names.get(0), 100, "home", "confirmed",
                        false, 1_000));
        swarm.coordinator(new CoordinatorFeed(names.get(0), now, 1, "active", "demo", leases, obs, obs.size()));
    }

    public FakeSwarm swarm() {
        return swarm;
    }

    @Override
    public SwarmView snapshot() {
        long now = clock.getAsLong();
        for (int i = 0; i < names.size(); i++) {
            double[] h = homes.get(i);
            double a = 2 * Math.PI * ((now % LAP_MS) / (double) LAP_MS) + i;
            FakeBot b = swarm.bot(names.get(i)).at(h[0] + RADIUS * Math.cos(a), h[1], h[2] + RADIUS * Math.sin(a));
            b.yaw = (float) Math.toDegrees(a + Math.PI / 2) + 90f;
            if (i % 2 == 1) {
                List<int[]> nodes = new ArrayList<>();
                for (int k = 1; k <= 8; k++) {
                    double ak = a + k * 0.12;
                    nodes.add(new int[]{(int) Math.floor(h[0] + RADIUS * Math.cos(ak)), (int) h[1],
                            (int) Math.floor(h[2] + RADIUS * Math.sin(ak))});
                }
                int[] goal = nodes.get(nodes.size() - 1);
                b.path(new BotFeed.Route("pathing", "GoalBlock{x=" + goal[0] + ",y=" + goal[1] + ",z=" + goal[2] + "}",
                        goal, 8, 0, 8, nodes)).process("Goto");
            }
        }
        return swarm.snapshot();
    }

    /** Rolling hills with water in the lows, sand at the shore, stone on the tops. */
    static final TerrainBuilder.Columns TERRAIN = new TerrainBuilder.Columns() {
        private double n(int x, int z) {
            return Math.sin(x / 17.0) + Math.cos(z / 23.0) + 0.5 * Math.sin((x + z) / 31.0);
        }

        @Override
        public String block(int x, int z) {
            double v = n(x, z);
            return v < -1.2 ? "water" : v < -0.9 ? "sand" : v > 1.6 ? "stone" : (x * 7 + z * 3) % 29 == 0 ? "oak_leaves"
                    : "grass_block";
        }

        @Override
        public int y(int x, int z) {
            return 63 + (int) Math.round(5 * n(x, z));
        }
    };
}
