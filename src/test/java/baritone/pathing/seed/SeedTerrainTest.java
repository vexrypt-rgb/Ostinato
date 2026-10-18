/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.seed;

import baritone.Baritone;
import baritone.pathing.SeedPlan;
import baritone.pathing.calc.PathBench;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: seed planning predicts believable base terrain from a seed alone, the same terrain every time, and it is
 * off unless the setting turns it on. Prints the measured cost per predicted column.
 */
public class SeedTerrainTest {

    private static final long SEED = 0x5EED_0571L;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void clear() {
        SeedPlan.clear();
    }

    @Test
    public void offByDefault() {
        assertFalse(Baritone.settings().seedPlanning.defaultValue);
        assertFalse(SeedPlan.active());
        assertNull(SeedPlan.predict(0, 64, 0));
    }

    @Test
    public void overworldLooksLikeTerrain() {
        SeedTerrain t = SeedTerrain.vanilla(SEED, Level.OVERWORLD, -64, 384, 4096);
        int columns = 0, land = 0, solidDeep = 0;
        long nanos = -System.nanoTime();
        for (int x = -2048; x < 2048; x += 256) {
            for (int z = -2048; z < 2048; z += 256) {
                int s = t.surface(x, z);
                assertTrue(x + "," + z + " surface " + s, s > 0 && s < 320);
                assertTrue(t.block(x, s, z).isAir());
                assertFalse(t.block(x, s - 1, z).isAir());
                BlockState top = t.block(x, s - 1, z);
                if (top.getFluidState().isEmpty()) land++;
                if (!t.block(x, -60, z).isAir()) solidDeep++;
                columns++;
            }
        }
        nanos += System.nanoTime();
        assertEquals(256, columns);
        assertTrue("land columns " + land, land > columns / 10); // not all ocean
        assertTrue("solid at y=-60 " + solidDeep, solidDeep > columns * 3 / 4);
        assertEquals(columns, t.generated);
        System.out.printf("SeedTerrain: %d overworld columns, %.3f ms each (cold, incl. registry setup), %.3f ms each generating%n",
                columns, nanos / 1e6 / columns, t.generatedNanos / 1e6 / t.generated);

        long again = -System.nanoTime();
        for (int i = 0; i < 256; i++) t.surface(5000 + i * 16, 7000);
        again += System.nanoTime();
        System.out.printf("SeedTerrain: warm, %.3f ms per new column%n", again / 1e6 / 256);
    }

    @Test
    public void sameSeedSameTerrainOtherSeedNot() {
        SeedTerrain a = SeedTerrain.vanilla(SEED, Level.OVERWORLD, -64, 384, 256);
        SeedTerrain b = SeedTerrain.vanilla(SEED, Level.OVERWORLD, -64, 384, 256);
        SeedTerrain c = SeedTerrain.vanilla(SEED + 1, Level.OVERWORLD, -64, 384, 256);
        int differ = 0;
        for (int i = 0; i < 32; i++) {
            int x = i * 97 - 1500, z = i * 61 + 300;
            assertEquals(a.surface(x, z), b.surface(x, z));
            for (int y = -64; y < 320; y += 7) assertSame(a.block(x, y, z), b.block(x, y, z));
            if (a.surface(x, z) != c.surface(x, z)) differ++;
        }
        assertTrue("seeds differ in " + differ + " of 32 columns", differ > 8);
    }

    @Test
    public void cacheStaysBounded() {
        SeedTerrain t = SeedTerrain.vanilla(SEED, Level.OVERWORLD, -64, 384, 256);
        for (int i = 0; i < 600; i++) t.block(i, 60, 0);
        t.block(599, 60, 0); // most recent: still cached
        assertEquals(600, t.generated);
        t.block(0, 60, 0); // oldest: evicted, generated again
        assertEquals(601, t.generated);
    }

    @Test
    public void netherHasRoofAndFloor() {
        SeedTerrain t = SeedTerrain.vanilla(SEED, Level.NETHER, 0, 256, 1024);
        int roof = 0, floor = 0;
        for (int i = 0; i < 64; i++) {
            int x = i * 131, z = -i * 89;
            if (t.block(x, 126, z).is(Blocks.NETHERRACK) || t.block(x, 127, z).is(Blocks.BEDROCK)) roof++;
            if (!t.block(x, 2, z).isAir()) floor++;
        }
        assertTrue("roof " + roof, roof > 48);
        assertTrue("floor " + floor, floor > 48);
    }

    @Test
    public void installedPredictorAnswersThroughSeedPlan() {
        SeedTerrain t = SeedTerrain.vanilla(SEED, Level.OVERWORLD, -64, 384, 256);
        SeedPlan.use(SEED, t);
        assertTrue(SeedPlan.active());
        assertTrue(SeedPlan.covers(123456, -654321));
        int s = t.surface(1000, 1000);
        assertSame(t.block(1000, s - 1, 1000), SeedPlan.predict(1000, s - 1, 1000));
        SeedPlan.clear();
        assertNull(SeedPlan.predict(1000, s - 1, 1000));
    }
}
