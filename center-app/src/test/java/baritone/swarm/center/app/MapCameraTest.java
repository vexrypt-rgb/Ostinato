package baritone.swarm.center.app;

import baritone.swarm.center.feed.TerrainFeed;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Ostinato: camera math, terrain lookup and marker hit testing, with no Swing. */
public class MapCameraTest {

    @Test public void roundTripAtEveryZoom() {
        MapCamera camera = new MapCamera();
        camera.size(800, 600);
        double[] xs = {0, 12.5, -40.25, 1000};
        double[] zs = {0, -8.5, 33.75, -250};
        for (int zoom = MapCamera.MIN_ZOOM; zoom <= MapCamera.MAX_ZOOM; zoom++) {
            camera.setZoom(zoom);
            camera.setCenter(20, -10);
            for (int i = 0; i < xs.length; i++) {
                double[] screen = camera.worldToScreen(xs[i], zs[i]);
                double[] world = camera.screenToWorld(screen[0], screen[1]);
                assertEquals(zoom + " x", xs[i], world[0], 1e-6);
                assertEquals(zoom + " z", zs[i], world[1], 1e-6);
            }
        }
    }

    @Test public void zoomKeepsThePointUnderTheCursorAndClamps() {
        MapCamera camera = new MapCamera();
        camera.size(400, 300);
        camera.setZoom(4);
        camera.setCenter(10, 20);
        double[] before = camera.screenToWorld(80, 40);
        camera.zoomAt(80, 40, 3);
        double[] after = camera.screenToWorld(80, 40);
        assertEquals(before[0], after[0], 1e-6);
        assertEquals(before[1], after[1], 1e-6);
        assertEquals(7, camera.zoom());
        camera.setZoom(16);
        camera.zoomAt(80, 40, 5);
        assertEquals(MapCamera.MAX_ZOOM, camera.zoom());
        camera.setZoom(1);
        camera.zoomAt(80, 40, -3);
        assertEquals(MapCamera.MIN_ZOOM, camera.zoom());
    }

    @Test public void panClampsToTheWorldLimit() {
        MapCamera camera = new MapCamera();
        camera.size(200, 200);
        camera.setZoom(1);
        camera.setCenter(0, 0);
        camera.panPixels(-1.0e12, 1.0e12);
        assertEquals(MapCamera.WORLD_LIMIT, camera.centerX(), 0);
        assertEquals(-MapCamera.WORLD_LIMIT, camera.centerZ(), 0);
        camera.setCenter(MapCamera.WORLD_LIMIT + 10, -MapCamera.WORLD_LIMIT - 10);
        assertEquals(MapCamera.WORLD_LIMIT, camera.centerX(), 0);
        assertEquals(-MapCamera.WORLD_LIMIT, camera.centerZ(), 0);
    }

    @Test public void terrainPrefersTheNewerSliceAndIgnoresOtherDimensions() {
        TerrainFeed older = slice("a", 'o', 0, 0, 2, 2, "stone", 10);
        TerrainFeed newer = slice("b", 'o', 1, 1, 2, 2, "water", 20);
        TerrainFeed nether = slice("c", 'n', 0, 0, 2, 2, "netherrack", 50);
        assertEquals("stone", MapTerrain.block(List.of(older, newer, nether), 'o', 0, 0));
        assertEquals("water", MapTerrain.block(List.of(older, newer), 'o', 1, 1));
        assertEquals("?", MapTerrain.block(List.of(older), 'o', 5, 5));
        assertEquals("netherrack", MapTerrain.block(List.of(older, nether), 'n', 0, 0));
        assertEquals("?", MapTerrain.block(List.of(), 'o', 0, 0));
        assertEquals("?", MapTerrain.block(null, 'o', 0, 0));
    }

    @Test public void blockColorsAreStableAndUnknownIsOther() {
        assertEquals(0, BlockColors.color("?"));
        assertEquals(0, BlockColors.color(null));
        assertEquals(BlockColors.color("grass_block"), BlockColors.color("minecraft:grass_block"));
        assertEquals(BlockColors.color("stone"), BlockColors.color("STONE"));
        assertEquals(BlockColors.color("water"), BlockColors.color("water"));
        assertEquals(BlockColors.color("sand"), BlockColors.color("sand"));
        assertEquals(BlockColors.color("oak_log"), BlockColors.color("oak_log"));
        assertEquals(BlockColors.color("oak_leaves"), BlockColors.color("oak_leaves"));
        assertEquals(BlockColors.color("iron_ore"), BlockColors.color("iron_ore"));
        assertEquals(BlockColors.OTHER, BlockColors.color("sponge"));
        assertEquals(BlockColors.color("oak_log"), BlockColors.color("mangrove_log"));
    }

    @Test public void hitTestPicksTheNearestMarkerInsideTheRadius() {
        List<MapHit.Marker> markers = List.of(new MapHit.Marker("Azalea", 10, 10), new MapHit.Marker("Copper", 30, 10));
        assertEquals("Azalea", MapHit.nearest(markers, 12, 11, 10));
        assertEquals("Copper", MapHit.nearest(markers, 28, 10, 10));
        assertNull(MapHit.nearest(markers, 10, 30, 10));
        assertNull(MapHit.nearest(List.of(), 0, 0, 10));
        assertNull(MapHit.nearest(null, 0, 0, 10));
        assertEquals("Azalea", MapHit.nearest(markers, 20, 10, 10));
    }

    @Test public void identityColorIsStablePerName() {
        assertEquals(BotColors.of("Azalea"), BotColors.of("Azalea"));
        assertEquals(BotColors.PALETTE.length, 12);
    }

    private static TerrainFeed slice(String bot, char dim, int x, int z, int w, int d, String block, long written) {
        byte[] blocks = new byte[w * d];
        short[] heights = new short[w * d];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = 1;
            heights[i] = 64;
        }
        return new TerrainFeed(bot, written, 1, dim, x, z, w, d, List.of("?", block), blocks, heights);
    }
}
