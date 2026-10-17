package baritone.pathing.kinematic;

import baritone.natives.NativeLib;
import baritone.pathing.kinematic.TasBrute.BoxWorld;
import baritone.pathing.physics.PhysicsPathfinder;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Random;

import static baritone.pathing.kinematic.NativeParityTest.randomWorld;
import static baritone.pathing.kinematic.NativeParityTest.standing;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ostinato: the native {@code PhysicsPathfinder}, {@link JumpSearch} and {@link MomentumHop} give the Java answers to
 * the bit, on fixed courses and random worlds. Every native answer is checked against the Java search inside the call
 * ({@link NativePlanners#verify}). Skipped when the library is not loaded, unless {@code -Postinato_native_require}.
 */
public class NativePlannerParityTest {

    @BeforeClass
    public static void load() {
        if (NativeLib.get() == null && Boolean.getBoolean("ostinato.native.require")) {
            fail("native library required but " + NativeLib.status());
        }
        Assume.assumeTrue("native library: " + NativeLib.status(), NativeLib.get() != null);
        NativePlanners.verify = true;
    }

    @AfterClass
    public static void done() {
        NativePlanners.verify = false;
        System.out.println("native planner parity: " + NativePlanners.checked.get() + " searches checked against Java");
    }

    private static void physics(PlayerSim.World w, PlayerSim s, double gx, double gy, double gz, int budget) {
        new PhysicsPathfinder(w, budget).nativeSearch(true).plan(s, gx, gy, gz, 0.35); // checked inside
    }

    @Test
    public void physicsCoursesPlanTheSame() {
        int before = NativePlanners.checked.get();
        BoxWorld flat = new BoxWorld().fill(-2, -1, -2, 2, -1, 14, BoxWorld.FULL);
        BoxWorld gap = new BoxWorld().fill(-1, -1, -2, 1, -1, 3, BoxWorld.FULL).fill(-1, -1, 7, 1, -1, 10, BoxWorld.FULL);
        BoxWorld step = new BoxWorld().fill(-1, -1, -2, 1, -1, 3, BoxWorld.FULL).fill(-1, 0, 4, 1, 0, 8, BoxWorld.FULL);
        BoxWorld wall = new BoxWorld().fill(-3, -1, -2, 3, -1, 8, BoxWorld.FULL).fill(-3, 0, 4, 3, 2, 4, BoxWorld.FULL);
        physics(flat, standing(flat, 0.5, 0, 0.5), 0.5, 0, 10.5, 1500);
        physics(gap, standing(gap, 0.5, 0, 0.5), 0.5, 0, 8.5, 1500);
        physics(step, standing(step, 0.5, 0, 0.5), 0.5, 1, 6.5, 1500);
        physics(wall, standing(wall, 0.5, 0, 0.5), 0.5, 0, 6.5, 1500);
        assertEquals(4, NativePlanners.checked.get() - before);
    }

    @Test
    public void physicsRandomWorldsPlanTheSame() {
        Random r = new Random(0xF15);
        int before = NativePlanners.checked.get();
        for (int i = 0; i < 40; i++) {
            NativeParityTest.FlagWorld w = randomWorld(r);
            PlayerSim s = standing(w, 0.3 + 0.4 * r.nextDouble(), 0, -1.5 + 1.8 * r.nextDouble());
            if (r.nextBoolean()) {
                s.sprinting = true;
                s.vz = 0.1 + 0.25 * r.nextDouble();
            }
            physics(w, s, r.nextInt(5) - 2 + 0.5, r.nextInt(3) - 1, 2 + r.nextInt(6) + 0.5, 200 + r.nextInt(1300));
        }
        int checked = NativePlanners.checked.get() - before;
        System.out.println("physics random worlds: " + checked + " of 40 planned natively");
        assertTrue("most random plans stay inside the snapshot", checked >= 30);
    }

    private static JumpSearch js(PlayerSim.World w, int destX, int destY, int destZ) {
        JumpSearch js = new JumpSearch(w);
        js.nativeSearch = true;
        js.dirX = 0;
        js.dirZ = 1;
        js.edge = 1;
        js.destX = destX;
        js.destY = destY;
        js.destZ = destZ;
        return js;
    }

    @Test
    public void jumpSearchCoursesTheSame() {
        int before = NativePlanners.checked.get();
        for (int gap = 1; gap <= 5; gap++) {
            for (int dy = -1; dy <= 1; dy++) {
                BoxWorld w = new BoxWorld().fill(0, -1, -2, 0, -1, 0, BoxWorld.FULL).put(0, dy - 1, gap + 1, BoxWorld.FULL);
                JumpSearch full = js(w, 0, dy, gap + 1);
                full.edge = 1;
                boolean ok = full.search(standing(w, 0.5, 0, -1.5), false);
                if (ok) {
                    // mid-flight: re-search the whole grid from two ticks into the jump, run-up choices fixed
                    PlayerSim s = standing(w, 0.5, 0, -1.5);
                    JumpSearch mid = js(w, 0, dy, gap + 1);
                    System.arraycopy(full.plan, 0, mid.plan, 0, JumpSearch.DIMS);
                    for (int t = 0; t < 60 && !(s.onGround && s.z * mid.dirZ >= mid.edge + JumpSearch.EDGE[mid.plan[2]]); t++) {
                        s.tick(mid.yaw(mid.plan, false, 0), true, true, false);
                    }
                    s.tick(mid.yaw(mid.plan, false, 0), true, true, true);
                    s.tick(mid.yaw(mid.plan, true, 0), true, true, false);
                    mid.jumped = true;
                    mid.airTicks = 1;
                    mid.search(s, false);
                }
            }
        }
        assertTrue(NativePlanners.checked.get() - before >= 20);
    }

    @Test
    public void jumpSearchRandomWorldsTheSame() {
        Random r = new Random(0x15EA);
        int before = NativePlanners.checked.get(), found = 0, threads = NativeTas.threads;
        for (int i = 0; i < 100; i++) {
            NativeTas.threads = 1 + i % 4; // the grid split over 1 to 4 threads gives the same answer
            NativeParityTest.FlagWorld w = randomWorld(r);
            JumpSearch js = js(w, r.nextInt(3) - 1, r.nextInt(3) - 1, 2 + r.nextInt(5));
            js.side = r.nextBoolean() ? 1 : -1;
            js.ledge = r.nextInt(4) == 0;
            js.carry = r.nextInt(5) == 0;
            js.grab = r.nextInt(8) == 0;
            js.anyDest = r.nextInt(8) == 0;
            if (r.nextInt(6) == 0) {
                js.bounceY = r.nextInt(3) - 2;
            }
            if (r.nextInt(5) == 0) {
                js.slewYaw = true;
                js.faceYaw = (float) (r.nextDouble() * 360 - 180);
            }
            for (int k = 0; k < JumpSearch.DIMS; k++) {
                js.plan[k] = r.nextInt(2);
            }
            boolean local = r.nextInt(3) == 0; // around the plan only
            js.anyLanding = r.nextInt(3) == 0; // the first plan that lands, threads stopping early
            PlayerSim s = standing(w, 0.3 + 0.4 * r.nextDouble(), 0, -1.5 + 1.8 * r.nextDouble());
            if (r.nextBoolean()) {
                s.sprinting = true;
                s.vz = 0.1 + 0.25 * r.nextDouble();
            }
            if (r.nextInt(5) == 0) {
                js.jumped = true; // the run-up choices fixed, as a re-search in the air has them
                js.airTicks = r.nextInt(4);
            }
            boolean ok = js.search(s, local);
            if (ok) {
                found++;
            }
        }
        NativeTas.threads = threads;
        int checked = NativePlanners.checked.get() - before;
        System.out.println("jump search random worlds: " + checked + " of 100 searched natively, " + found + " found a plan");
        assertTrue("most random searches stay inside the snapshot", checked >= 75);
    }

    private static void hop(PlayerSim.World w, PlayerSim s, int dirX, int dirZ, int dx, int dy, int dz, boolean prev, int budget) {
        int before = NativePlanners.checked.get();
        MomentumHop.find(w, s, dirX, dirZ, dx, dy, dz, prev, budget, true); // checked inside
        assertEquals("native momentum hop answered", before + 1, NativePlanners.checked.get());
    }

    @Test
    public void momentumHopTheSame() {
        for (int gap = 1; gap <= 3; gap++) {
            BoxWorld hh = new BoxWorld().fill(0, -1, -8, 0, -1, 0, BoxWorld.FULL).fill(0, 2, -8, 0, 2, 4, BoxWorld.FULL).put(0, -1, gap + 1, BoxWorld.FULL);
            for (int budget : new int[]{MomentumHop.PLAN_BUDGET, MomentumHop.BUDGET}) {
                hop(hh, standing(hh, 0.5, 0, -7.5), 0, 1, 0, 0, gap + 1, false, budget);
                hop(hh, standing(hh, 0.5, 0, -3.5), 0, 1, 0, 0, gap + 1, true, budget);
            }
        }
        Random r = new Random(0x40B);
        for (int i = 0; i < 30; i++) {
            NativeParityTest.FlagWorld w = randomWorld(r);
            PlayerSim s = standing(w, 0.5, 0, -1.5);
            hop(w, s, 0, 1, r.nextInt(3) - 1, r.nextInt(3) - 1, 2 + r.nextInt(5), r.nextBoolean(), 2000 + r.nextInt(6000));
        }
    }
}
