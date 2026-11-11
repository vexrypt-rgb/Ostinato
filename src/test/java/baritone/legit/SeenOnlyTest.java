/*
 * Ostinato: seen-only filter tests. Headless. No game client.
 */
package baritone.legit;

import baritone.api.legit.LegitMode;
import baritone.api.legit.SeenOnly;
import baritone.api.legit.SeenOnlyShare;
import baritone.pathing.calc.PathBench;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SeenOnlyTest {

    private static final double EYE_X = 0.5;
    private static final double EYE_Y = 1.62;
    private static final double EYE_Z = 0.5;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void buriedOreIsNotKnowable() {
        Grid grid = stone();
        assertFalse(SeenOnly.knowable(grid, EYE_X, EYE_Y, EYE_Z, 8, 1, 0));
    }

    @Test
    public void exposedLitOreWithAClearLineIsKnowable() {
        Grid grid = corridor();
        assertTrue(SeenOnly.knowable(grid, EYE_X, EYE_Y, EYE_Z, 5, 1, 0));
    }

    @Test
    public void aWallOrDarknessOrAnUnknownCellHidesTheOre() {
        Grid walled = corridor();
        walled.set(2, 1, 0, SeenOnly.Cell.OPAQUE, 12);
        assertFalse(SeenOnly.knowable(walled, EYE_X, EYE_Y, EYE_Z, 5, 1, 0));

        Grid dark = corridor();
        dark.set(4, 1, 0, SeenOnly.Cell.AIR, 0);
        assertFalse(SeenOnly.knowable(dark, EYE_X, EYE_Y, EYE_Z, 5, 1, 0));

        Grid unknown = corridor();
        unknown.set(3, 1, 0, SeenOnly.Cell.UNKNOWN, 12);
        assertFalse(SeenOnly.knowable(unknown, EYE_X, EYE_Y, EYE_Z, 5, 1, 0));
    }

    @Test
    public void aCornerCrossingFailsUnlessBothSideCellsAreOpen() {
        Grid blocked = stone();
        blocked.set(0, 1, 0, SeenOnly.Cell.AIR, 12);
        blocked.set(1, 1, 1, SeenOnly.Cell.AIR, 12);
        assertFalse(SeenOnly.clear(blocked, 0.5, 1.5, 0.5, 1.5, 1.5, 1.5));

        Grid open = stone();
        open.set(0, 1, 0, SeenOnly.Cell.AIR, 12);
        open.set(1, 1, 0, SeenOnly.Cell.AIR, 12);
        open.set(0, 1, 1, SeenOnly.Cell.AIR, 12);
        open.set(1, 1, 1, SeenOnly.Cell.AIR, 12);
        assertTrue(SeenOnly.clear(open, 0.5, 1.5, 0.5, 1.5, 1.5, 1.5));
    }

    @Test
    public void fluidDoesNotHideAnExposedFace() {
        Grid grid = corridor();
        grid.set(4, 1, 0, SeenOnly.Cell.FLUID, 8);
        assertTrue(SeenOnly.knowable(grid, EYE_X, EYE_Y, EYE_Z, 5, 1, 0));
    }

    @Test
    public void filterOffKeepsTheBuriedOreAndFilterOnDoesNot() {
        Grid grid = corridor();
        BlockPos buried = new BlockPos(8, 1, 0);
        BlockPos visible = new BlockPos(5, 1, 0);
        List<BlockPos> candidates = List.of(buried, visible);
        LongOpenHashSet seen = new LongOpenHashSet();

        assertFalse(LegitMode.isActive(LegitMode.Value.AUTO, LegitMode.SessionKind.SINGLEPLAYER));
        assertFalse(LegitMode.isActive(LegitMode.Value.AUTO, LegitMode.SessionKind.LAN));
        assertFalse(LegitMode.isActive(LegitMode.Value.OFF, LegitMode.SessionKind.REMOTE));
        List<BlockPos> unchanged = LegitTargets.select(candidates, seen, grid, EYE_X, EYE_Y, EYE_Z, false);
        assertSame(candidates, unchanged);
        assertTrue(unchanged.contains(buried));
        assertTrue(seen.isEmpty());

        assertTrue(LegitMode.isActive(LegitMode.Value.AUTO, LegitMode.SessionKind.REMOTE));
        assertTrue(LegitMode.isActive(LegitMode.Value.ON, LegitMode.SessionKind.SINGLEPLAYER));
        List<BlockPos> filtered = LegitTargets.select(candidates, seen, grid, EYE_X, EYE_Y, EYE_Z, true);
        assertFalse(filtered.contains(buried));
        assertTrue(filtered.contains(visible));
        assertEquals(1, filtered.size());
    }

    @Test
    public void anOreAdmittedWhileVisibleStaysSelectedAfterTheLineCloses() {
        Grid grid = corridor();
        BlockPos visible = new BlockPos(5, 1, 0);
        LongOpenHashSet seen = new LongOpenHashSet();
        List<BlockPos> first = LegitTargets.select(List.of(visible), seen, grid, EYE_X, EYE_Y, EYE_Z, true);
        assertTrue(first.contains(visible));
        grid.set(2, 1, 0, SeenOnly.Cell.OPAQUE, 12);
        assertFalse(SeenOnly.knowable(grid, EYE_X, EYE_Y, EYE_Z, 5, 1, 0));
        List<BlockPos> later = LegitTargets.select(List.of(visible), seen, grid, EYE_X, EYE_Y, EYE_Z, true);
        assertTrue(later.contains(visible));
    }

    @Test
    public void shareHookAllowsOnlyKnowableBlocksWhileLegitIsOn() {
        assertFalse(SeenOnlyShare.mayShare(true, false));
        assertTrue(SeenOnlyShare.mayShare(true, true));
        assertTrue(SeenOnlyShare.mayShare(false, false));
        assertTrue(SeenOnlyShare.mayShare(false, true));
    }

    @Test
    public void mineAndGetToBlockCallTheFilter() throws Exception {
        String mine = Files.readString(project("src/main/java/baritone/process/MineProcess.java"), StandardCharsets.UTF_8);
        String get = Files.readString(project("src/main/java/baritone/process/GetToBlockProcess.java"), StandardCharsets.UTF_8);
        assertTrue(mine.contains("LegitTargets.active()"));
        assertTrue(mine.contains("LegitTargets.select"));
        assertTrue(mine.contains("LegitScan.nearby"));
        assertTrue(mine.contains("cached ore positions are not targets"));
        assertTrue(get.contains("LegitTargets.active()"));
        assertTrue(get.contains("LegitTargets.select"));
        assertTrue(get.contains("LegitScan.nearby"));
    }

    /** Stone everywhere, light 15. An ore with no carved face is buried. */
    private static Grid stone() {
        return new Grid(SeenOnly.Cell.OPAQUE, 15);
    }

    /** Air corridor from the eye to the west face of the ore at (5,1,0). Other cells are stone, lit. */
    private static Grid corridor() {
        Grid grid = stone();
        for (int x = 0; x <= 4; x++) {
            grid.set(x, 1, 0, SeenOnly.Cell.AIR, 12);
        }
        return grid;
    }

    private static Path project(String rel) {
        Path p = Path.of(rel);
        return Files.exists(p) ? p : Path.of("ostinato").resolve(rel);
    }

    private static final class Grid implements SeenOnly.View {
        private final Map<Long, SeenOnly.Cell> cells = new HashMap<>();
        private final Map<Long, Integer> lights = new HashMap<>();
        private final SeenOnly.Cell fill;
        private final int fillLight;

        Grid(SeenOnly.Cell fill, int fillLight) {
            this.fill = fill;
            this.fillLight = fillLight;
        }

        void set(int x, int y, int z, SeenOnly.Cell cell, int light) {
            long key = SeenOnly.key(x, y, z);
            cells.put(key, cell);
            lights.put(key, light);
        }

        @Override
        public SeenOnly.Cell at(int x, int y, int z) {
            return cells.getOrDefault(SeenOnly.key(x, y, z), fill);
        }

        @Override
        public int light(int x, int y, int z) {
            return lights.getOrDefault(SeenOnly.key(x, y, z), fillLight);
        }
    }
}
