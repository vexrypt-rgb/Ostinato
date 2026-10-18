package baritone.pathing.calc;

import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Scratch: drops onto the pool slime and reports the bounce. Never commit. */
public class SlimeScratchTest {
    @Test
    public void slime() throws Exception {
        String file = System.getenv("PROBE_BLOCKS");
        Assume.assumeTrue(file != null && System.getenv("SLIME") != null);
        PathBench.boot();
        MapProbeTest.World w = new MapProbeTest.World();
        Map<String, BlockState> memo = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            String[] p = line.split(" ");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            if (w.in(x, y, z)) w.v[w.idx(x, y, z)] = MapProbeTest.parse(p[3], memo);
        }
        PlayerSim s = new PlayerSim(new BsiWorld(w));
        for (double h = 69; h <= 82; h += 0.5) {
            for (int fw = 0; fw <= 1; fw++) {
                s.x = -22.7; s.z = 11.0; s.y = h; s.vx = s.vy = s.vz = 0; s.onGround = false; s.sprinting = false; s.collidedH = false; s.swimming = false;
                double apex = -1e9; boolean bounced = false; String trace = "";
                for (int t = 0; t < 80; t++) {
                    boolean north = fw == 1 || bounced;
                    if (s.inWater()) s.tickWater(180f, 0f, north, false, false, false);
                    else s.tick(180f, north ? 1 : 0, false, false);
                    if (s.vy > 0 && !bounced && s.y < 69.5) bounced = true;
                    if (bounced) apex = Math.max(apex, s.y);
                    if (t < 40) trace += String.format(" %.2f/%.2f%s", s.y, s.z, s.inWater() ? "w" : "");
                    if (bounced && s.onGround && s.vy <= 0 && t > 3) break;
                }
                System.out.printf("SLIME h=%.1f fw=%d apex=%.3f end=%.2f,%.3f,%.2f g=%b%n", h, fw, apex, s.x, s.y, s.z, s.onGround);
                if (System.getenv("SLIME_TRACE") != null) System.out.println("SLIME   " + trace);
            }
        }
    }
}
