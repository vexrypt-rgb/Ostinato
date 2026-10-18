package baritone.pathing.calc;

import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Scratch: brute-forces run-up + jump + air yaw from a platform to a landing cell on a dumped map. Never commit. */
public class JumpFeasScratchTest {
    @Test
    public void feas() throws Exception {
        String file = System.getenv("PROBE_BLOCKS");
        String spec = System.getenv("FEAS"); // fromX,fromZ blocks "x,z;x,z" | feetY | dest x,y,z
        Assume.assumeTrue(file != null && spec != null);
        PathBench.boot();
        MapProbeTest.World w = new MapProbeTest.World();
        Map<String, BlockState> memo = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            String[] p = line.split(" ");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            if (w.in(x, y, z)) w.v[w.idx(x, y, z)] = MapProbeTest.parse(p[3], memo);
        }
        String[] sp = spec.split("[|]");
        List<int[]> blocks = new ArrayList<>();
        if (System.getenv("FEAS_FINE") != null) { fine(w, sp); return; }
        for (String b : sp[0].split(";")) blocks.add(Arrays.stream(b.split(",")).mapToInt(Integer::parseInt).toArray());
        double feet = Double.parseDouble(sp[1]);
        int[] d = Arrays.stream(sp[2].split(",")).mapToInt(Integer::parseInt).toArray();
        BsiWorld world = new BsiWorld(w);
        PlayerSim s = new PlayerSim(world);
        if (System.getenv("FEAS_FINE") != null) { fine(w, sp); return; }
        if (System.getenv("FEAS_TRACE") != null) {
            s.x = -19.5; s.z = 12.5; s.y = feet; s.vy = -0.0784000015258789; s.onGround = true;
            for (int t = 0; t < 14; t++) { s.tick(90f, 1, true, t == 2); System.out.printf("FEAS t%d %.3f %.3f %.3f g=%b%n", t, s.x, s.y, s.z, s.onGround); }
            return;
        }
        int ok = 0, runs = 0;
        double bestMiss = 9;
        String best = null;
        for (int[] b : blocks) for (double fx = 0.0; fx <= 1.0; fx += 0.25) for (double fz = 0.0; fz <= 1.0; fz += 0.25)
            for (int head = Integer.parseInt(sp[3]); head <= Integer.parseInt(sp[4]); head += 5) for (int g = 0; g <= 12; g++) for (int ay = -90; ay <= 90; ay += 15) for (int sw = 0; sw <= 1; sw++) {
                s.x = b[0] + 0.2 + fx * 0.6; s.z = b[1] + 0.2 + fz * 0.6; s.y = feet; s.vx = s.vy = s.vz = 0; s.vy = -0.0784000015258789;
                s.onGround = true; s.sprinting = false; s.collidedH = false; s.jumpTicks = 0;
                runs++;
                boolean fell = false, jumped = false;
                int air = 0, settle = -1;
                for (int t = 0; t < 60; t++) {
                    boolean j = !jumped && t >= g;
                    float yaw = jumped ? head + ay * (sw == 0 || air >= 3 ? 1 : 0) : head;
                    if (!jumped && !s.onGround && t > 0) { fell = true; break; }
                    s.tick(yaw, settle < 0 ? 1 : 0, settle < 0, j && s.onGround);
                    if (j) jumped = true; else if (jumped) air++;
                    if (s.y < feet - 1) { fell = true; break; }
                    if (jumped && air > 1 && s.onGround && settle < 0) {
                        if (!PlayerSim.nodeHeight(s.y, d[1])) { fell = true; break; }
                        settle = 0;
                    }
                    if (settle >= 0 && settle++ >= 3) break;
                }
                if (fell || settle < 0) continue;
                if (PlayerSim.floor(s.x) == d[0] && PlayerSim.floor(s.z) == d[2]) {
                    ok++;
                    double m = Math.max(Math.abs(s.x - d[0] - 0.5), Math.abs(s.z - d[2] - 0.5));
                    if (m < bestMiss) { bestMiss = m; best = String.format("block %d,%d f=%.2f,%.2f head=%d g=%d ay=%d sw=%d land=%.3f,%.3f,%.3f", b[0], b[1], fx, fz, head, g, ay, sw, s.x, s.y, s.z); }
                }
            }
        System.out.println("FEAS runs=" + runs + " ok=" + ok + " best=" + best);
    }

    /** FEAS_FINE: take-off near (tx,tz) at full sprint along heading h, air yaw switch + strafe. sp[0]="tx,tz" sp[3..4] heading range */
    static void fine(MapProbeTest.World w, String[] sp) {
        MapProbeTest.World flat = new MapProbeTest.World();
        for (int x = -60; x < 60; x++) for (int z = -60; z < 60; z++) flat.v[flat.idx(x, 0, z)] = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        PlayerSim f = new PlayerSim(new BsiWorld(flat));
        PlayerSim s = new PlayerSim(new BsiWorld(w));
        double[] tk = Arrays.stream(sp[0].split(",")).mapToDouble(Double::parseDouble).toArray();
        double feet = Double.parseDouble(sp[1]);
        int[] d = Arrays.stream(sp[2].split(",")).mapToInt(Integer::parseInt).toArray();
        int ok = 0; long runs = 0; String best = null; double bestM = 9;
        for (int h = Integer.parseInt(sp[3]); h <= Integer.parseInt(sp[4]); h++) for (int g = 3; g <= 14; g++) {
            f.x = 0.5; f.z = 0.5; f.y = 1; f.vx = f.vy = f.vz = 0; f.vy = -0.0784000015258789; f.onGround = true; f.sprinting = false; f.collidedH = false; f.jumpTicks = 0;
            for (int t = 0; t < g; t++) f.tick(h, 1, true, false);
            double dx = f.x - 0.5, dz = f.z - 0.5;
            for (double u = 0; u <= 0.301; u += 0.1) for (double v = 0; v <= 0.301; v += 0.1)
                for (int ay = -90; ay <= 30; ay += 10) for (int sw = 0; sw <= 3; sw++) for (int st = -1; st <= 1; st++) {
                    s.x = tk[0] + u * Math.signum(-dx) * -1 * 0 + u - dx; s.z = tk[1] + v - dz;
                    s.x = tk[0] + u - dx; s.y = feet; s.vx = s.vy = s.vz = 0; s.vy = -0.0784000015258789; s.onGround = true; s.sprinting = false; s.collidedH = false; s.jumpTicks = 0;
                    runs++;
                    boolean bad = false; int air = -1, settle = -1;
                    for (int t = 0; t < 60 && !bad; t++) {
                        boolean j = t == g;
                        if (t < g && t > 0 && !s.onGround) { bad = true; break; }
                        float yaw = air >= sw ? h + ay : h;
                        int in = settle < 0 ? 1 : 0;
                        s.tick(yaw, in, settle < 0 && air >= 0 ? st : 0, settle < 0, j);
                        if (j) air = 0; else if (air >= 0) air++;
                        if (s.y < feet - 1) bad = true;
                        else if (air > 1 && s.onGround && settle < 0) { if (!PlayerSim.nodeHeight(s.y, d[1])) bad = true; else settle = 0; }
                        else if (settle >= 0 && settle++ >= 3) break;
                    }
                    if (bad || settle < 0 || PlayerSim.floor(s.x) != d[0] || PlayerSim.floor(s.z) != d[2]) continue;
                    ok++;
                    double m = Math.max(Math.abs(s.x - d[0] - 0.5), Math.abs(s.z - d[2] - 0.5));
                    if (m < bestM) { bestM = m; best = String.format("h=%d g=%d u=%.2f v=%.2f ay=%d sw=%d st=%d land=%.3f,%.3f,%.3f", h, g, u, v, ay, sw, st, s.x, s.y, s.z); }
                }
        }
        System.out.println("FEAS fine runs=" + runs + " ok=" + ok + " best=" + best);
    }
}
