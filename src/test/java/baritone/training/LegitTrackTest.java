package baritone.training;

import baritone.utils.LegitHorizon;
import org.junit.Test;

import static org.junit.Assert.*;

/** Ostinato: compass needle, render square, pie and sound classification. No client. */
public class LegitTrackTest {

    @Test
    public void lodestoneSpawnRecoveryAndManhunt() {
        CompassNeedle.Reading east = CompassNeedle.read(CompassNeedle.Kind.LODESTONE, true,
                "minecraft:overworld", 10, 64, 0, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertTrue(east.aimed);
        assertEquals("E", east.bearing);
        assertEquals(CompassNeedle.Kind.LODESTONE, east.kind);
        assertEquals(10, east.x);

        CompassNeedle.Reading south = CompassNeedle.read(CompassNeedle.Kind.SPAWN, true,
                "minecraft:overworld", 0, 64, 20, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertEquals(CompassNeedle.Kind.SPAWN, south.kind);
        assertEquals("S", south.bearing);

        CompassNeedle.Reading north = CompassNeedle.read(CompassNeedle.Kind.RECOVERY, true,
                "minecraft:overworld", 0, 64, -10, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertEquals("N", north.bearing);

        CompassNeedle.Reading west = CompassNeedle.read(CompassNeedle.Kind.PLAYER, true,
                "minecraft:overworld", -10, 64, 0, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertEquals(CompassNeedle.Kind.PLAYER, west.kind);
        assertEquals("W", west.bearing);

        // The runner moved. The next needle read is the new client position, not a stored god coordinate.
        CompassNeedle.Reading moved = CompassNeedle.read(CompassNeedle.Kind.PLAYER, true,
                "minecraft:overworld", 0, 70, 40, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertEquals("S", moved.bearing);
        assertEquals(40, moved.z);

        CompassNeedle.Reading hidden = CompassNeedle.read(CompassNeedle.Kind.PLAYER, false,
                "minecraft:overworld", 100, 64, 0, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertFalse(hidden.aimed);
        assertEquals("", hidden.bearing);

        CompassNeedle.Reading other = CompassNeedle.read(CompassNeedle.Kind.LODESTONE, true,
                "minecraft:the_nether", 0, 64, 0, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertFalse(other.aimed);

        CompassNeedle.Reading spin = CompassNeedle.read(CompassNeedle.Kind.LODESTONE, true,
                "minecraft:overworld", 0, 64, 0, "minecraft:overworld", 0.5, 64.5, 0.5);
        assertFalse(spin.aimed);
    }

    @Test
    public void handThenOffhandThenHotbar() {
        assertEquals(CompassNeedle.HAND, CompassNeedle.pick(true, true, new boolean[]{true}));
        assertEquals(CompassNeedle.OFFHAND, CompassNeedle.pick(false, true, new boolean[]{true, false, true}));
        assertEquals(4, CompassNeedle.pick(false, false, new boolean[]{false, false, false, false, true}));
        assertEquals(CompassNeedle.NONE, CompassNeedle.pick(false, false, new boolean[]{false, false}));
        assertEquals(0, CompassNeedle.pick(false, false, new boolean[]{true, true}));
    }

    @Test
    public void renderSquareKeepsNegativeChunks() {
        assertEquals(0, LegitHorizon.chunkOf(0.5));
        assertEquals(-1, LegitHorizon.chunkOf(-0.1));
        assertEquals(-1, LegitHorizon.chunkOf(-16));
        assertEquals(-2, LegitHorizon.chunkOf(-16.1));
        assertTrue(LegitHorizon.inside(0, 0, 0, 0, 8));
        assertTrue(LegitHorizon.inside(16 * 8, 0, 0, 0, 8));
        assertFalse(LegitHorizon.inside(16 * 9, 0, 0, 0, 8));
        assertTrue(LegitHorizon.inside(-1, 0, 0, 0, 2));
        assertTrue(LegitHorizon.inside(-32, 0, 0, 0, 2));
        assertFalse(LegitHorizon.inside(-48, 0, 0, 0, 2));
        assertFalse(LegitHorizon.inside(0, 0, 0, 0, -1));
    }

    @Test
    public void pieAndSoundsStayPlayerVisible() {
        assertEquals("", LegitCues.pieTop(false, "chunkBuilder"));
        assertEquals("", LegitCues.pieTop(true, " "));
        assertEquals("soundEngine", LegitCues.pieTop(true, "soundEngine"));
        assertEquals("FOOTSTEP", LegitCues.soundKind("block.grass.step"));
        assertEquals("BLOCK_BREAK", LegitCues.soundKind("block.stone.hit"));
        assertEquals("BLOCK_BREAK", LegitCues.soundKind("block.stone.break"));
        assertEquals("MOB_AMBIENT", LegitCues.soundKind("entity.zombie.ambient"));
        assertEquals("MOB_HURT", LegitCues.soundKind("entity.zombie.hurt"));
        assertEquals("LAVA", LegitCues.soundKind("block.lava.ambient"));
        assertEquals("WATER", LegitCues.soundKind("block.water.ambient"));
        assertNull(LegitCues.soundKind("entity.player.attack.sweep"));
        assertNull(LegitCues.soundKind("ui.button.click"));
        assertEquals("SELF", LegitCues.bearing(0, 0));
        assertEquals("E", LegitCues.bearing(4, 1));
    }
}
