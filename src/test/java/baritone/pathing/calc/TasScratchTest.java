package baritone.pathing.calc;

import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.kinematic.TasBrute;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Scratch: beam-search TAS from standing spots to a landing on a dumped map. TAS="sx,sy,sz;..|dx,dy,dz|yaw0,yaw1". Never commit. */
public class TasScratchTest {
    @Test
    public void tas() throws Exception {
        String file = System.getenv("PROBE_BLOCKS");
        String spec = System.getenv("TAS");
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
        int[] d = Arrays.stream(sp[1].split(",")).mapToInt(Integer::parseInt).toArray();
        String[] yw = sp[2].split(",");
        BsiWorld world = new BsiWorld(w);
        for (String st : sp[0].split(";")) {
            double[] s0 = Arrays.stream(st.split(",")).mapToDouble(Double::parseDouble).toArray();
            for (int y0 = Integer.parseInt(yw[0]); y0 <= Integer.parseInt(yw[1]); y0 += 45) {
                PlayerSim s = new PlayerSim(world);
                s.x = s0[0]; s.y = s0[1]; s.z = s0[2]; s.vx = s.vz = 0; s.vy = -0.0784000015258789; s.onGround = true;
                TasBrute b = new TasBrute(world);
                b.beam = Integer.parseInt(System.getenv().getOrDefault("TAS_BEAM", "4000"));
                b.maxTicks = 40; b.allowStrafe = true; b.allowBack = true; b.maxTurn = 32; b.nativeSearch = false; b.debug = System.getenv("TAS_DEBUG") != null;
                TasBrute.Result r = b.search(s, y0, new TasBrute.Landing(d[0], d[1], d[2]));
                System.out.println("TAS from " + st + " yaw0=" + y0 + " -> " + (r == null ? "none" : r.toString()));
            }
        }
    }
}
