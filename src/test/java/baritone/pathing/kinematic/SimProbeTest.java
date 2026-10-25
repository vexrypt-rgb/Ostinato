package baritone.pathing.kinematic;

import baritone.pathing.kinematic.TasBrute.BoxWorld;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: fixed jump goals for the sim probe. Tick counts are the headless travel time.
 * A found plan is replayed in {@link PlayerSim}; the Baritone movement backup is not used for that spot.
 */
public class SimProbeTest {

    private static PlayerSim standing(PlayerSim.World w, double x, double y, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = y;
        s.z = z;
        s.onGround = true;
        s.vy = -0.0784000015258789;
        return s;
    }

    private static void replayLands(PlayerSim.World w, PlayerSim start, float yaw, int gx, int gy, int gz, SimProbe.Result result) {
        assertNotNull(result);
        assertTrue(result.found());
        assertTrue(result.ticks() <= SimProbe.MAX_TICKS);
        assertTrue(result.expansions <= SimProbe.MAX_EXPANSIONS);
        PlayerSim s = new PlayerSim(w).copyFrom(start);
        for (boolean jump : result.jumps) {
            s.tick(yaw, true, true, jump);
        }
        assertTrue("plan did not land, ticks=" + result.ticks() + " end=" + s.x + "," + s.y + "," + s.z,
                new TasBrute.Landing(gx, gy, gz).on(s, w));
    }

    @Test
    public void oneBlockGapFromAShortRunUp() {
        BoxWorld w = new BoxWorld().fill(0, -1, -2, 0, -1, 0, BoxWorld.FULL).put(0, -1, 2, BoxWorld.FULL);
        PlayerSim start = standing(w, 0.5, 0, -1.5);
        float yaw = 0;
        long t0 = System.nanoTime();
        SimProbe.Result result = SimProbe.search(w, start, yaw, 0, 0, 2);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("SIM one-gap ticks=" + result.ticks() + " expansions=" + result.expansions + " ms=" + ms);
        replayLands(w, start, yaw, 0, 0, 2, result);
        assertTrue(result.ticks() <= 24);
    }

    @Test
    public void oneBlockStepUp() {
        BoxWorld w = new BoxWorld().put(0, -1, 0, BoxWorld.FULL).put(0, 0, 1, BoxWorld.FULL);
        PlayerSim start = standing(w, 0.5, 0, 0.3);
        float yaw = SimProbe.yawToward(start.x, start.z, 0, 1);
        long t0 = System.nanoTime();
        SimProbe.Result result = SimProbe.search(w, start, yaw, 0, 1, 1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("SIM step-up ticks=" + result.ticks() + " expansions=" + result.expansions + " ms=" + ms);
        replayLands(w, start, yaw, 0, 1, 1, result);
        assertTrue(result.ticks() <= 20);
    }

    @Test
    public void eightBlockGapIsAMissAndTheMissIsCached() {
        BoxWorld w = new BoxWorld().put(0, -1, 0, BoxWorld.FULL);
        PlayerSim start = standing(w, 0.5, 0, 0.5);
        SimProbe.Cache cache = new SimProbe.Cache();
        SimProbe.Result first = cache.solve(w, start, 0, 0, 0, 12);
        SimProbe.Result second = cache.solve(w, start, 0, 0, 0, 12);
        assertFalse(first.found());
        assertNull(first.jumps);
        assertFalse(second.found());
        assertTrue(cache.searches == 1);
        System.out.println("SIM eight-gap miss expansions=" + first.expansions);
    }

    @Test
    public void cacheStaysBounded() {
        BoxWorld w = new BoxWorld().put(0, -1, 0, BoxWorld.FULL);
        SimProbe.Cache cache = new SimProbe.Cache();
        for (int x = 0; x < SimProbe.CACHE_CAP + 40; x++) {
            cache.solve(w, standing(w, x + 0.5, 0, 0.5), 0, x, 0, 12);
        }
        assertTrue(cache.size() <= SimProbe.CACHE_CAP);
    }
}
