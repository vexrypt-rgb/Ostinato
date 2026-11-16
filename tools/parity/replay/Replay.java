import baritone.pathing.kinematic.PlayerSim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parity harness: replay a real 26.3 client's ParityTrace (r0 yaw and keys per tick) through the protocol bot's
 * PlayerSim on the flat parity world (solid up to y=-60, grass friction) and compare position and velocity each tick.
 * usage: java -cp <bot classpath>;. Replay TRACE [fromTick] [ticks]
 */
public final class Replay {
    public static void main(String[] a) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(a[0]));
        int from = a.length > 1 ? Integer.parseInt(a[1]) : -1;
        int n = a.length > 2 ? Integer.parseInt(a[2]) : 120;
        Pattern p = Pattern.compile("t=(\\d+) .*? r0=(\\S+),(\\S+) me=(\\S+),(\\S+),(\\S+) v=(\\S+),(\\S+),(\\S+) r=(\\S+),\\S+ g=(\\d) .*? in=(\\S*) ");
        PlayerSim.World flat = new PlayerSim.World() {
            @Override
            public void collect(double x0, double y0, double z0, double x1, double y1, double z1, List<double[]> out) {
                for (int bx = (int) Math.floor(x0); bx <= (int) Math.floor(x1); bx++)
                    for (int by = (int) Math.floor(y0); by <= (int) Math.floor(y1); by++)
                        for (int bz = (int) Math.floor(z0); bz <= (int) Math.floor(z1); bz++)
                            if (by <= -61 && by >= -64) out.add(new double[]{bx, by, bz, bx + 1, by + 1, bz + 1});
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }
        };
        PlayerSim sim = null;
        int compared = 0, exact = 0, firstBad = -1;
        double maxErr = 0;
        String prevKeys = "";
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (!m.find()) continue;
            int t = Integer.parseInt(m.group(1));
            double x = Double.parseDouble(m.group(4)), y = Double.parseDouble(m.group(5)), z = Double.parseDouble(m.group(6));
            double vx = Double.parseDouble(m.group(7)), vy = Double.parseDouble(m.group(8)), vz = Double.parseDouble(m.group(9));
            String keys = m.group(12);
            if (sim == null) {
                // Start from the tick before the first keyed tick: state there is the replay's initial condition.
                if (from >= 0 ? t < from : keys.isEmpty()) {
                    prevKeys = keys;
                    prevR = Float.parseFloat(m.group(10));
                    PlayerSim s = new PlayerSim(flat);
                    s.x = x; s.y = y; s.z = z; s.vx = vx; s.vy = vy; s.vz = vz; s.onGround = m.group(11).equals("1");
                    sim0 = s;
                    continue;
                }
                sim = sim0;
                System.out.println("replay starts at t=" + t + " from " + sim.x + "," + sim.y + "," + sim.z);
            }
            String src = System.getProperty("yaw", "r0");
            Matcher lt = Pattern.compile(" lt=([^,]+),").matcher(line);
            float ltYaw = lt.find() ? Float.parseFloat(lt.group(1)) : Float.NaN;
            float[] cands = {Float.parseFloat(m.group(2)), Float.parseFloat(m.group(10)), prevR, ltYaw};
            float yaw = src.equals("lt") ? ltYaw : src.equals("r") ? cands[1] : src.equals("rprev") ? prevR : cands[0];
            prevR = Float.parseFloat(m.group(10));
            boolean fwd = keys.contains("F"), sprint = keys.contains("P"), jump = keys.contains("J");
            if (src.equals("best")) {
                // Ostinato parity: with resync, try every logged rotation and keep one that reproduces the real tick.
                PlayerSim start = new PlayerSim(flat).copyFrom(sim);
                yaw = cands[0];
                for (float c : cands) {
                    if (Float.isNaN(c)) continue;
                    PlayerSim trial = new PlayerSim(flat).copyFrom(start);
                    trial.tick(c, fwd, sprint, jump);
                    if (trial.x == x && trial.y == y && trial.z == z) { yaw = c; break; }
                }
                if (System.getProperty("solve") != null && !(new PlayerSim(flat).copyFrom(start) instanceof PlayerSim ps && tickMatches(ps, yaw, fwd, sprint, jump, x, y, z))) {
                    // The look snaps to 0.15 degree steps; search r0 +- k*0.15 for a yaw that reproduces the tick bit for bit.
                    for (int k = 1; k <= 40; k++) {
                        boolean hit = false;
                        for (int sgn = -1; sgn <= 1; sgn += 2) {
                            float c = cands[0] + sgn * k * 0.15f;
                            if (tickMatches(new PlayerSim(flat).copyFrom(start), c, fwd, sprint, jump, x, y, z)) { yaw = c; hit = true; solved++; break; }
                        }
                        if (hit) break;
                    }
                }
            }
            sim.tick(yaw, fwd, sprint, jump);
            double err = Math.max(Math.abs(sim.x - x), Math.max(Math.abs(sim.y - y), Math.abs(sim.z - z)));
            compared++;
            if (err == 0) exact++;
            else if (firstBad < 0) firstBad = t;
            maxErr = Math.max(maxErr, err);
            if (compared <= 12 || (err != 0 && compared < firstBadPrint)) {
                System.out.printf("t=%d keys=%s yaw=%s real=%s,%s,%s sim=%s,%s,%s err=%.3e%n", t, keys, yaw, x, y, z, sim.x, sim.y, sim.z, err);
            }
            if (compared >= n) break;
            // Re-sync to the real state each tick: this tests one tick of physics at a time, not accumulated drift.
            if (System.getProperty("resync") != null) {
                sim.x = x; sim.y = y; sim.z = z; sim.vx = vx; sim.vy = vy; sim.vz = vz;
            }
        }
        System.out.printf("compared %d ticks, bit-exact %d (yaw solved on %d), first mismatch t=%d, max abs error %.3e%n", compared, exact, solved, firstBad, maxErr);
    }

    private static int solved;

    private static boolean tickMatches(PlayerSim s, float yaw, boolean f, boolean sp, boolean j, double x, double y, double z) {
        s.tick(yaw, f, sp, j);
        return s.x == x && s.y == y && s.z == z;
    }

    private static PlayerSim sim0;
    private static float prevR;
    private static final int firstBadPrint = 30;
}
