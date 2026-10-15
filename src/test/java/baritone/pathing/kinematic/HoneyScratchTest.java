package baritone.pathing.kinematic;

import org.junit.Test;

import java.util.List;

// TEMP scratch: gap-1 jump off honey (z 0..2 honey at y=-1, stone landing at z=4).
public class HoneyScratchTest {
    static final PlayerSim.World W = new PlayerSim.World() {
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++)
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++)
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                        if (y != -1 || x < -1 || x > 1) continue;
                        if (z >= 0 && z <= 2) out.add(new double[]{x + 1 / 16.0, y, z + 1 / 16.0, x + 15 / 16.0, y + 15 / 16.0, z + 15 / 16.0});
                        if (z == 4 && x == 0) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                    }
        }
        public float slipperiness(int x, int y, int z) { return 0.6f; }
        public float speedFactor(int x, int y, int z) { return y == -1 && z >= 0 && z <= 2 && x >= -1 && x <= 1 ? 0.4f : 1f; }
        public float jumpFactor(int x, int y, int z) { return y == -1 && z >= 0 && z <= 2 && x >= -1 && x <= 1 ? 0.5f : 1f; }
        public boolean sticky(int x, int y, int z) { return y == -1 && z >= 0 && z <= 2 && x >= -1 && x <= 1; }
    };

    @Test
    public void scan() {
        for (double sz : new double[]{0.5, 1.0, 1.5, 2.0, 2.5}) {
            JumpSearch js = new JumpSearch(W);
            js.dirX = 0; js.dirZ = 1; js.edge = 3; js.destX = 0; js.destY = 0; js.destZ = 4;
            PlayerSim s = new PlayerSim(W);
            s.x = 0.5; s.y = -1 / 16.0; s.z = sz; s.onGround = true; s.vy = -0.0784000015258789;
            boolean ok = js.search(s, false);
            StringBuilder p = new StringBuilder();
            for (int k : js.plan) p.append(k);
            System.out.println("HONEY start z=" + sz + " found=" + ok + " plan=" + p + " ticks=" + js.ticks);
        }
        // how far does a sprint jump off honey go, jumping at the edge?
        for (double jz : new double[]{2.6, 2.8, 3.0, 3.2}) {
            PlayerSim s = new PlayerSim(W);
            s.x = 0.5; s.y = -1 / 16.0; s.z = 0.5; s.onGround = true; s.vy = -0.0784000015258789;
            boolean jumped = false; double tz = 0; String res = "";
            for (int t = 0; t < 60; t++) {
                boolean j = !jumped && s.onGround && s.z >= jz;
                if (j) tz = s.z;
                double pz = s.z;
                s.tick(0f, 1, 0, true, j);
                if (j) jumped = true;
                if (jumped && s.y < -0.0625 - 0.01 && res.isEmpty() && s.vy < 0) res = String.format("drop below top at z=%.2f", s.z);
                if (jumped && s.onGround && t > 2) { res += String.format(" land z=%.2f y=%.3f", s.z, s.y); break; }
            }
            System.out.println("HONEY jumpAt>=" + jz + " takeoff z=" + String.format("%.2f", tz) + " " + res);
        }
    }
}
