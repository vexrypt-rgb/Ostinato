package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

/** Sim side of the real-game hh drill (pk_hh.txt): the same blocks, the same hop policy, per tick. */
public class HHProbeManual {
    static PlayerSim.World world(String kind, int r) {
        final int land = kind.equals("full_down1") ? -2 : -1; // landing block y (stand y=0 at 151 -> block 150 is -1)
        return new PlayerSim.World() {
            @Override
            public void collect(double a, double b, double c, double d, double e, double f, List<double[]> out) {
                for (int x = PlayerSim.floor(a); x <= PlayerSim.floor(d); x++)
                    for (int y = PlayerSim.floor(b) - 1; y <= PlayerSim.floor(e); y++)
                        for (int z = PlayerSim.floor(c); z <= PlayerSim.floor(f); z++) {
                            boolean s = x >= -1 && x <= 1 && (
                                    (y == -1 && z >= -r + 1 && z <= 0)
                                    || ((y == 0 || y == 1) && z == -r)
                                    || (y == land && z >= 3 && z <= 8)
                                    || (y == 2 && z >= -r && z <= (kind.equals("open") ? 0 : 8))
                                    || (kind.equals("full_down1") && y == 1 && z >= 3 && z <= 8));
                            if (s) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                        }
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
    }

    static String run(String kind, int r, int firstJump, boolean print) {
        int land = kind.equals("full_down1") ? -1 : 0;
        PlayerSim s = new PlayerSim(world(kind, r));
        s.x = 0.5; s.y = 0; s.z = -r + 1.5; s.onGround = true; s.vy = -0.0784000015258789;
        boolean prev = false;
        double top = 0;
        for (int t = 0; t < 60; t++) {
            boolean og = s.onGround;
            boolean j = og && !prev && t >= firstJump;
            prev = j;
            s.tick(0f, true, true, j);
            top = Math.max(top, Math.abs(s.vz));
            if (print) System.err.printf("SIMHOP %s r=%d t=%d z=%.4f y=%.4f vz=%.4f vy=%.4f og=%b jump=%b%n", kind, r, t, s.z, s.y, s.vz, s.vy, s.onGround, j);
            if (s.onGround && s.z >= 2.7 && s.y >= land - 0.01) return "REACHED t=" + t + " maxVz=" + String.format("%.4f", top);
            if (s.y < land - 1.5) return "FAIL z=" + String.format("%.3f", s.z) + " maxVz=" + String.format("%.4f", top);
        }
        return "TIMEOUT";
    }

    /** Brute force: on every ground tick, jump or not (all 2^n choices), hold W + sprint facing +z. */
    static int[] best = new int[1];
    static String dfs(String kind, int r, PlayerSim s, int t, boolean prev, StringBuilder trail) {
        int land = kind.equals("full_down1") ? -1 : 0;
        if (s.onGround && s.z >= 2.7 && s.y >= land - 0.01) return trail.toString();
        if (s.y < land - 0.6 || t > 45) return null;
        boolean[] opts = s.onGround && !prev ? new boolean[]{true, false} : new boolean[]{false};
        for (boolean j : opts) {
            PlayerSim c = new PlayerSim(world(kind, r));
            c.copyFrom(s);
            c.tick(0f, true, true, j);
            trail.append(j ? 'J' : '.');
            String res = dfs(kind, r, c, t + 1, j, trail);
            trail.setLength(trail.length() - 1);
            if (res != null) return res;
        }
        return null;
    }

    @Test
    public void bruteForce() {
        for (String kind : new String[]{"full", "full_down1"})
            for (int r = 1; r <= 10; r++) {
                PlayerSim s = new PlayerSim(world(kind, r));
                s.x = 0.5; s.y = 0; s.z = -r + 1.5; s.onGround = true; s.vy = -0.0784000015258789;
                String res = dfs(kind, r, s, 0, false, new StringBuilder());
                System.err.println("BRUTE " + kind + " r=" + r + " " + (res == null ? "NONE" : "REACHED " + res));
            }
    }

    @Test
    public void probe() {
        for (String kind : new String[]{"full", "full_down1", "open"})
            for (int r = 1; r <= 10; r++) {
                String best = null;
                for (int fj = 0; fj < 6; fj++) { // brute force: when the first jump goes (phase of the 3-tick cycle at the edge)
                    String res = run(kind, r, fj, false);
                    if (best == null || res.startsWith("REACHED")) { best = "fj=" + fj + " " + res; if (res.startsWith("REACHED")) break; }
                }
                System.err.println("SIMHH " + kind + " r=" + r + " " + best);
            }
        run("full", 8, 0, true);
    }
}
