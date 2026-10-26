package baritone.maze;

import org.junit.Test;

import static org.junit.Assert.*;

// Ostinato: end-to-end planner + seal check against a synthetic solid world (box verification).
public class MazePipelineIntegrationTest {

    /** Solid stone below y=64, air above; optional air cave pocket. */
    private static BlockProbe world(int caveX0, int caveX1) {
        return (x, y, z) -> {
            if (y < -64 || y >= 320) return BlockProbe.BlockKind.UNKNOWN;
            if (y >= 64) return BlockProbe.BlockKind.AIR;
            if (x >= caveX0 && x < caveX1 && z >= caveX0 && z < caveX1) return BlockProbe.BlockKind.AIR;
            return BlockProbe.BlockKind.SOLID;
        };
    }

    @Test
    public void placeOpenFindsSealedRoomInSolidVolume() {
        MazePlanner.Attempt a = MazePlanner.placeOpen(42L, 0, 0, 30, -64, 320, world(0, 0), null, MazePlanner.SEARCH_RADIUS);
        assertTrue("refused: " + a.refusal, a.ok());
        assertTrue(a.verdict.sealed());
        assertTrue(a.verdict.underground);
        assertTrue(a.verdict.breaches.isEmpty());
    }

    @Test
    public void caveAtHomeCellPushesToNeighbourCell() {
        MazePlanner.Attempt a = MazePlanner.placeOpen(42L, 0, 0, 30, -64, 320, world(0, 32), null, MazePlanner.SEARCH_RADIUS);
        assertTrue("refused: " + a.refusal, a.ok());
        assertFalse(a.cellX == 0 && a.cellZ == 0);
    }

    @Test
    public void nearSurfaceShiftsDownOrRefuses() {
        // Preferred Y pokes out of the ground; the planner must shift down until cover holds, never accept an exposed room.
        MazePlanner.Attempt a = MazePlanner.placeOpen(42L, 0, 0, 61, -64, 320, world(0, 0), null, 0);
        if (a.ok()) {
            assertTrue(a.plan.originY + MazePlanner.SIZE_Y + MazeSeed.COVER <= 64);
        } else {
            assertNotNull(a.refusal);
        }
        // All-air world (ocean/sky): must refuse.
        MazePlanner.Attempt b = MazePlanner.placeOpen(42L, 0, 0, 30, -64, 320, (x, y, z) -> BlockProbe.BlockKind.AIR, null, 1);
        assertFalse(b.ok());
    }
}
