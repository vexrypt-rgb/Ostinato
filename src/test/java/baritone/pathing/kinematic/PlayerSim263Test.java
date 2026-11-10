/*
 * Ostinato 2.0: pins the 26.3 movement rules PlayerSim follows where 1.16 differed. Not upstream Baritone.
 * Expected values come from the decompiled 26.3 client (LocalPlayer, LivingEntity, Entity, Mth), not from PlayerSim.
 */
package baritone.pathing.kinematic;

import baritone.pathing.kinematic.TasBrute.BoxWorld;
import net.minecraft.util.Mth;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerSim263Test {
    private static PlayerSim standing(PlayerSim.World w, double x, double y, double z) {
        PlayerSim sim = new PlayerSim(w);
        sim.x = x;
        sim.y = y;
        sim.z = z;
        sim.onGround = true;
        sim.vy = -0.0784000015258789; // a standing player's: still pressed down, so each tick's move lands again
        return sim;
    }

    /** KeyboardInput normalises the key vector, then LocalPlayer.modifyInput scales by 0.98 and squares it back out. */
    @Test
    public void keysGoThroughTheClientInputPipeline() {
        float[] straight = PlayerSim.keyInput(1, 0);
        assertEquals(0.0f, straight[0], 0.0f);
        assertEquals(0.98f, straight[1], 0.0f);
        float[] diagonal = PlayerSim.keyInput(1, 1); // 1.16 held 0.98 on both axes
        float half = 1.0f / (float) Math.sqrt(2.0f);
        assertEquals(half, diagonal[0], 1e-7f);
        assertEquals(half, diagonal[1], 1e-7f);
        float[] back = PlayerSim.keyInput(-1, -1);
        assertEquals(-half, back[0], 1e-7f);
        assertEquals(-half, back[1], 1e-7f);
        float[] none = PlayerSim.keyInput(0, 0);
        assertEquals(0.0f, none[0], 0.0f);
        assertEquals(0.0f, none[1], 0.0f);
    }

    /** Mth.sin/cos read a 65536-entry table; PlayerSim builds its copy with StrictMath so native code can match it. */
    @Test
    public void sineTableIsMths() {
        int differ = 0;
        for (int i = 0; i < 65536; i++) {
            double r = i / 10430.378350470453;
            if (Float.floatToIntBits(PlayerSim.msin(r)) != Float.floatToIntBits(Mth.sin(r))) differ++;
            if (Float.floatToIntBits(PlayerSim.mcos(r)) != Float.floatToIntBits(Mth.cos(r))) differ++;
        }
        assertEquals("table entries differing from Mth", 0, differ);
    }

    private static BoxWorld walled() {
        BoxWorld w = new BoxWorld().fill(-3, 63, -3, 3, 63, 60, BoxWorld.FULL);
        return w.fill(-1, 64, -3, -1, 65, 60, BoxWorld.FULL);
    }

    /** LocalPlayer.aiStep keeps a sprint through a wall met within 8 degrees of the input (1.16 stopped on any wall). */
    @Test
    public void sprintHoldsAlongAShallowWall() {
        PlayerSim sim = standing(walled(), 0.5, 64, 0.5);
        boolean met = false;
        for (int t = 0; t < 40; t++) {
            sim.tick(5, 1, true, false); // 5 degrees of yaw drifts toward -x, into the wall
            if (sim.collidedH) {
                met = true;
                assertTrue("tick " + t, sim.minorCollision);
            }
            assertTrue("tick " + t, sim.sprinting);
        }
        assertTrue(met);
        assertEquals(PlayerSim.HALF_WIDTH, sim.x, 1e-9);
        assertTrue(sim.z > 8);
    }

    @Test
    public void sprintStopsOnAWallMetSideways() {
        PlayerSim sim = standing(walled(), PlayerSim.HALF_WIDTH + 0.01, 64, 0.5);
        for (int t = 0; t < 15; t++) {
            sim.tick(0, 1, true, false);
            assertFalse(sim.collidedH);
            assertTrue(sim.sprinting);
        }
        sim.tick(60, 1, true, false); // the run still carries it along z: 60 degrees off the input
        assertTrue(sim.collidedH);
        assertFalse(sim.minorCollision);
        assertTrue(sim.sprinting); // the sprint is judged on last tick's wall
        sim.tick(60, 1, true, false);
        assertFalse(sim.sprinting);
    }

    /**
     * 26.3 takes friction from the supporting block (Entity.mainSupportingBlockPos), so standing on the edge of an ice
     * row with the centre over air still slides. 1.16 read the block under the centre: air, 0.6.
     */
    @Test
    public void frictionComesFromTheSupportingBlock() {
        BoxWorld w = new BoxWorld();
        for (int z = -2; z <= 40; z++) w.put(0, 63, z, BoxWorld.FULL).slippery(0, 63, z, 0.98f);
        PlayerSim sim = standing(w, 1.2, 64, 0.5);
        sim.vz = 0.4;
        sim.tick(0, 0, false, false);
        assertTrue(sim.onGround);
        assertTrue(sim.supported);
        assertEquals(0, sim.supX);
        assertEquals(63, sim.supY);
        double vz1 = sim.vz;
        sim.tick(0, 0, false, false);
        assertEquals(vz1 * (PlayerSim.modifiedFriction(0.98f) * PlayerSim.AIR_DRAG), sim.vz, 0.0); // not the air under the centre
    }

    /** Standing on slime never bounces: a landing slower than gravity's one tick has no restitution. */
    @Test
    public void standingOnSlimeStaysPut() {
        BoxWorld w = new BoxWorld();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) w.slime(x, 63, z);
        PlayerSim sim = standing(w, 0.5, 64, 0.5);
        for (int t = 0; t < 40; t++) {
            sim.tick(0, 0, false, false);
            assertEquals("tick " + t, 64.0, sim.y, 0.0);
            assertTrue("tick " + t, sim.onGround);
        }
    }

    private static double bounceAfterDrop(PlayerSim.World w) {
        PlayerSim sim = new PlayerSim(w);
        sim.x = 0.5;
        sim.y = 68;
        sim.z = 0.5;
        double impact = 0;
        for (int t = 0; t < 60; t++) {
            double before = sim.vy;
            sim.tick(0, 0, false, false);
            if (sim.vy > 0) {
                assertTrue(sim.vy < -before); // restitution 1 or under, and gravity's share of the tick taken off
                return sim.vy;
            }
            impact = before;
        }
        throw new AssertionError("no bounce, last vy " + impact);
    }

    /** Block.getBounceRestitution: slime 1, beds 0.75 (1.16 beds bounced 0.66). The rest of the landing is the same. */
    @Test
    public void bedBouncesThreeQuartersOfSlime() {
        BoxWorld slime = new BoxWorld();
        BoxWorld stone = new BoxWorld();
        Set<Long> bed = new HashSet<>();
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                slime.slime(x, 63, z);
                stone.put(x, 63, z, BoxWorld.FULL);
                bed.add((long) x << 32 | (z & 0xFFFFFFFFL));
            }
        PlayerSim.World beds = new PlayerSim.World() {
            @Override
            public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, java.util.List<double[]> out) {
                stone.collect(minX, minY, minZ, maxX, maxY, maxZ, out);
            }

            @Override
            public boolean cellBoxes(int x, int y, int z, java.util.List<double[]> out) {
                return stone.cellBoxes(x, y, z, out);
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6f;
            }

            @Override
            public float bounce(int x, int y, int z) {
                return y == 63 && bed.contains((long) x << 32 | (z & 0xFFFFFFFFL)) ? 0.75f : 0.0f;
            }
        };
        // the landing tick ends with travelInAir's gravity and drag on top of the restituted speed: take them off
        double s = bounceAfterDrop(slime) / 0.98f + 0.08, b = bounceAfterDrop(beds) / 0.98f + 0.08;
        assertEquals(0.75, b / s, 1e-9);
    }

    /** Step-up lands on a slab's top, and a ceiling that leaves no room for the player over it stops the step. */
    @Test
    public void stepUpNeedsHeadroom() {
        BoxWorld open = new BoxWorld().fill(-2, 63, -2, 2, 63, 20, BoxWorld.FULL).fill(-2, 64, 3, 2, 64, 20, BoxWorld.BOTTOM_SLAB);
        PlayerSim sim = standing(open, 0.5, 64, 0.5);
        for (int t = 0; t < 30; t++) sim.tick(0, 1, false, false);
        assertEquals(64.5, sim.y, 0.0);
        assertTrue(sim.onGround);
        assertTrue(sim.z > 5);

        BoxWorld low = new BoxWorld().fill(-2, 63, -2, 2, 63, 20, BoxWorld.FULL).fill(-2, 64, 3, 2, 64, 20, BoxWorld.BOTTOM_SLAB)
                .fill(-2, 66, 3, 2, 66, 20, BoxWorld.FULL);
        sim = standing(low, 0.5, 64, 0.5);
        for (int t = 0; t < 30; t++) sim.tick(0, 1, false, false);
        assertEquals(64.0, sim.y, 0.0);
        assertEquals(3 - PlayerSim.HALF_WIDTH, sim.z, 1e-9);
    }
}
