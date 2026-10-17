package baritone.pathing.kinematic;

import baritone.pathing.kinematic.TasBrute.BoxWorld;
import baritone.pathing.physics.PhysicsPathfinder;
import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;

/**
 * Ostinato: what the sim searches other than {@link TasBrute} cost in Java, to decide what else is worth moving to the
 * native library. Off by default: {@code gradlew test -Postinato_bench --tests "*HotPathBench"}.
 */
public class HotPathBench {

    private static PlayerSim standing(PlayerSim.World w, double x, double y, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = y;
        s.z = z;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        return s;
    }

    private interface Job {
        Object run();
    }

    private static void time(String name, Job job) {
        for (int i = 0; i < 3; i++) job.run();
        long[] ns = new long[7];
        Object out = null;
        for (int i = 0; i < ns.length; i++) {
            long t0 = System.nanoTime();
            out = job.run();
            ns[i] = System.nanoTime() - t0;
        }
        Arrays.sort(ns);
        System.out.printf("BENCH %-48s %9.3f ms  (%s)%n", name, ns[ns.length / 2] / 1e6, out);
    }

    @Test
    public void bench() {
        Assume.assumeTrue(Boolean.getBoolean("ostinato.bench"));
        // PhysicsTravel's planner, budget 1500 nodes, on flat ground, over a gap, up a step, and out of budget
        BoxWorld flat = new BoxWorld().fill(-2, -1, -2, 2, -1, 14, BoxWorld.FULL);
        BoxWorld gap = new BoxWorld().fill(-1, -1, -2, 1, -1, 3, BoxWorld.FULL).fill(-1, -1, 7, 1, -1, 10, BoxWorld.FULL);
        BoxWorld step = new BoxWorld().fill(-1, -1, -2, 1, -1, 3, BoxWorld.FULL).fill(-1, 0, 4, 1, 0, 8, BoxWorld.FULL);
        BoxWorld wall = new BoxWorld().fill(-3, -1, -2, 3, -1, 8, BoxWorld.FULL).fill(-3, 0, 4, 3, 2, 4, BoxWorld.FULL);
        time("physics flat 10", () -> size(new PhysicsPathfinder(flat, 1500).plan(standing(flat, 0.5, 0, 0.5), 0.5, 0, 10.5, 0.35)));
        time("physics flat 10 NATIVE", () -> size(new PhysicsPathfinder(flat, 1500).nativeSearch(true).plan(standing(flat, 0.5, 0, 0.5), 0.5, 0, 10.5, 0.35)));
        time("physics gap 3", () -> size(new PhysicsPathfinder(gap, 1500).plan(standing(gap, 0.5, 0, 0.5), 0.5, 0, 8.5, 0.35)));
        time("physics gap 3 NATIVE", () -> size(new PhysicsPathfinder(gap, 1500).nativeSearch(true).plan(standing(gap, 0.5, 0, 0.5), 0.5, 0, 8.5, 0.35)));
        time("physics step up", () -> size(new PhysicsPathfinder(step, 1500).plan(standing(step, 0.5, 0, 0.5), 0.5, 1, 6.5, 0.35)));
        time("physics step up NATIVE", () -> size(new PhysicsPathfinder(step, 1500).nativeSearch(true).plan(standing(step, 0.5, 0, 0.5), 0.5, 1, 6.5, 0.35)));
        time("physics walled off (whole budget)", () -> size(new PhysicsPathfinder(wall, 1500).plan(standing(wall, 0.5, 0, 0.5), 0.5, 0, 6.5, 0.35)));
        time("physics walled off (whole budget) NATIVE", () -> size(new PhysicsPathfinder(wall, 1500).nativeSearch(true).plan(standing(wall, 0.5, 0, 0.5), 0.5, 0, 6.5, 0.35)));

        // JumpSearch: the full grid and a local search round a plan, a 2 gap
        BoxWorld g2 = new BoxWorld().fill(0, -1, 0, 0, -1, 2, BoxWorld.FULL).put(0, -1, 5, BoxWorld.FULL);
        for (boolean nat : new boolean[]{false, true}) {
            String tag = nat ? " NATIVE" : "";
            time("jumpsearch full, gap 2" + tag, () -> {
                JumpSearch js = js(g2);
                js.nativeSearch = nat;
                return js.search(standing(g2, 0.5, 0, 0.5), false);
            });
        }
        JumpSearch planned = js(g2);
        planned.search(standing(g2, 0.5, 0, 0.5), false);
        int[] plan = planned.plan.clone();
        BoxWorld g6 = new BoxWorld().fill(0, -1, 0, 0, -1, 2, BoxWorld.FULL).put(0, -1, 9, BoxWorld.FULL);
        for (boolean nat : new boolean[]{false, true}) {
            String tag = nat ? " NATIVE" : "";
            time("jumpsearch local, gap 2" + tag, () -> {
                JumpSearch js = js(g2);
                js.nativeSearch = nat;
                System.arraycopy(plan, 0, js.plan, 0, JumpSearch.DIMS);
                return js.search(standing(g2, 0.5, 0, 0.5), true);
            });
            time("jumpsearch straight, gap 2" + tag, () -> {
                JumpSearch js = js(g2);
                js.nativeSearch = nat;
                return js.searchStraight(standing(g2, 0.5, 0, 0.5));
            });
            time("jumpsearch full, unreachable gap 6" + tag, () -> {
                JumpSearch js = js(g6);
                js.nativeSearch = nat;
                js.destZ = 9;
                return js.search(standing(g6, 0.5, 0, 0.5), false);
            });
        }

        // MomentumHop under a 2 high ceiling: planner budget and executor budget
        BoxWorld hh = new BoxWorld().fill(0, -1, -8, 0, -1, 0, BoxWorld.FULL).fill(0, 2, -8, 0, 2, 4, BoxWorld.FULL).put(0, -1, 3, BoxWorld.FULL);
        time("momentumhop plan budget 6000", () -> MomentumHop.find(hh, standing(hh, 0.5, 0, -7.5), 0, 1, 0, 0, 3, false, MomentumHop.PLAN_BUDGET));
        time("momentumhop run budget 40000", () -> MomentumHop.find(hh, standing(hh, 0.5, 0, -7.5), 0, 1, 0, 0, 3, false, MomentumHop.BUDGET));
        time("momentumhop plan budget 6000 NATIVE", () -> NativePlanners.momentumHop(hh, standing(hh, 0.5, 0, -7.5), 0f, 0, 1, 0, 0, 3, false, MomentumHop.PLAN_BUDGET));
        time("momentumhop run budget 40000 NATIVE", () -> NativePlanners.momentumHop(hh, standing(hh, 0.5, 0, -7.5), 0f, 0, 1, 0, 0, 3, false, MomentumHop.BUDGET));

        // raw sim speed and the snapshot copy a native call would add
        time("100k sim ticks, flat run", () -> {
            PlayerSim s = standing(flat, 0.5, 0, 0.5);
            for (int i = 0; i < 100_000; i++) {
                if ((i & 31) == 0) s = standing(flat, 0.5, 0, 0.5);
                s.tick(0, true, true, (i & 7) == 0);
            }
            return s.z;
        });
        time("snapshot 22x8x22 (jump, pad 6)", () -> NativeTas.Snapshot.take(g2, -11, -4, -6, 22, 8, 22).boxCount);
        time("snapshot 30x12x30 (physics stretch)", () -> NativeTas.Snapshot.take(flat, -15, -6, -8, 30, 12, 30).boxCount);
    }

    private static JumpSearch js(PlayerSim.World w) {
        JumpSearch js = new JumpSearch(w);
        js.dirX = 0;
        js.dirZ = 1;
        js.edge = 3;
        js.destX = 0;
        js.destY = 0;
        js.destZ = 5;
        return js;
    }

    private static Object size(java.util.List<?> l) {
        return l == null ? "none" : l.size() + " ticks";
    }
}
