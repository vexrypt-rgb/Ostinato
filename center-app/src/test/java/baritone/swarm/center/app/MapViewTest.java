package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.feed.BotFeed;
import org.junit.Test;

import javax.swing.JComponent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Ostinato: the war-room map over a fake swarm. Markers, paths, dimensions, pan and zoom. */
public class MapViewTest {

    @Test public void markersSitOnTheBotsAtFourPixelsPerBlock() {
        UiHarness ui = mapOf(UiHarness.threeBots());
        MapView map = map(ui);
        map.camera().setZoom(4);
        assertTrue(map.centerOn("Azalea"));
        ui.refresh();
        double[] azalea = map.markerScreen("Azalea");
        JComponent canvas = (JComponent) ui.find("map:canvas");
        assertEquals(canvas.getWidth() / 2.0, azalea[0], 1);
        assertEquals(canvas.getHeight() / 2.0, azalea[1], 1);
        double[] copper = map.markerScreen("Copper");
        double[] expected = map.camera().worldToScreen(0.5, 0.5);
        assertEquals(expected[0], copper[0], 1);
        assertEquals(expected[1], copper[1], 1);
        assertNotNull(map.markerScreen("Spelunker"));
    }

    @Test public void aPathIsDrawnAndAnotherDimensionIsHidden() throws Exception {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Azalea").path(new BotFeed.Route("pathing", "GoalBlock", new int[]{48, 64, -12}, 8, 1, 8,
                List.of(new int[]{44, 64, -12}, new int[]{48, 64, -12})));
        ui.swarm.bot("Spelunker").dim('n');
        ui = mapOf(ui);
        MapView map = map(ui);
        map.camera().setZoom(4);
        map.centerOn("Azalea");
        ui.refresh();
        assertNull(map.markerScreen("Spelunker"));
        BufferedImage img = ui.render();
        JComponent canvas = (JComponent) ui.find("map:canvas");
        int path = BotColors.of("Azalea");
        double[] node = map.camera().worldToScreen(44, -12);
        java.awt.Point top = javax.swing.SwingUtilities.convertPoint(canvas, (int) Math.round(node[0]),
                (int) Math.round(node[1]), ui.shell.component());
        assertTrue(near(img, top.x, top.y, path));
        ui.click("map:dim:n");
        ui.refresh();
        assertEquals('n', map.dimension());
        assertNotNull(map.markerScreen("Spelunker"));
        assertNull(map.markerScreen("Azalea"));
        assertNull(map.markerScreen("Copper"));
        ui.click("map:dim:e");
        ui.refresh();
        assertNull(map.markerScreen("Spelunker"));
        assertEquals("0 bots", ui.text("map:bots"));
    }

    @Test public void dragWheelAndArrowsMoveTheCameraAndRightClickCenters() {
        UiHarness ui = mapOf(UiHarness.threeBots());
        MapView map = map(ui);
        map.centerOn("Copper");
        ui.refresh();
        JComponent canvas = (JComponent) ui.find("map:canvas");
        double before = map.camera().centerX();
        canvas.dispatchEvent(press(canvas, 100, 80, MouseEvent.BUTTON1));
        canvas.dispatchEvent(drag(canvas, 140, 80));
        assertTrue(map.camera().centerX() < before);
        int zoom = map.camera().zoom();
        canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                40, 40, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1));
        assertEquals(Math.min(MapCamera.MAX_ZOOM, zoom + 1), map.camera().zoom());
        double zBefore = map.camera().centerZ();
        // Headless AWT drops a KeyEvent dispatched at a component that does not own focus, so the test
        // hands the same event to the canvas listener. A focused window delivers it unchanged.
        KeyEvent up = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED);
        canvas.dispatchEvent(up);
        if (!(map.camera().centerZ() < zBefore)) {
            for (java.awt.event.KeyListener listener : canvas.getKeyListeners()) {
                listener.keyPressed(up);
            }
        }
        assertTrue(map.camera().centerZ() < zBefore);
        canvas.dispatchEvent(press(canvas, (int) map.markerScreen("Azalea")[0], (int) map.markerScreen("Azalea")[1],
                MouseEvent.BUTTON3));
        assertTrue(ui.find("map:menu").isVisible());
        ui.click("map:center:Azalea");
        assertEquals(40.5, map.camera().centerX(), 0.01);
        assertEquals(-12.5, map.camera().centerZ(), 0.01);
    }

    @Test public void panAndZoomSurviveARefreshAndAnEmptySwarmDraws() {
        UiHarness ui = mapOf(UiHarness.threeBots());
        MapView map = map(ui);
        map.camera().setZoom(9);
        map.camera().setCenter(123, -45);
        ui.swarm.bot("Oak").dim('e').notInWorld();
        ui.refresh();
        assertEquals(9, map.camera().zoom());
        assertEquals(123, map.camera().centerX(), 0);
        assertEquals(-45, map.camera().centerZ(), 0);
        assertNull(map.markerScreen("Oak"));
        UiHarness empty = mapOf(UiHarness.many(0, CenterTheme.DEFAULT_DARK));
        assertEquals("0 bots", empty.text("map:bots"));
        assertNull(map(empty).markerScreen("Copper"));
    }

    @Test public void doubleClickOnATileCentersTheMap() {
        UiHarness ui = UiHarness.threeBots().refresh();
        java.awt.Component tile = ui.find("tile:Azalea");
        tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, 4, 2,
                false, MouseEvent.BUTTON1));
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        ui.refresh();
        MapView map = map(ui);
        double[] at = map.markerScreen("Azalea");
        JComponent canvas = (JComponent) ui.find("map:canvas");
        assertEquals(canvas.getWidth() / 2.0, at[0], 1);
        assertEquals(canvas.getHeight() / 2.0, at[1], 1);
    }

    @Test public void elevenBotsWithTerrainRepaintInsideTheBudget() {
        UiHarness ui = UiHarness.demo(11, CenterTheme.DEFAULT_DARK, CenterLayout.MAP).refresh();
        BufferedImage img = new BufferedImage(UiHarness.WIDTH, UiHarness.HEIGHT, BufferedImage.TYPE_INT_RGB);
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            ui.shell.component().paint(img.getGraphics());
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("20 repaints took " + ms + " ms", ms < 300);
    }

    @Test public void screenshotsInEveryCoreTheme() throws Exception {
        Map<String, CenterTheme> themes = Map.of("dark", CenterTheme.DEFAULT_DARK, "light", CenterTheme.LIGHT,
                "high-contrast", CenterTheme.HIGH_CONTRAST, "colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            UiHarness h = UiHarness.demo(8, e.getValue(), CenterLayout.MAP).refresh();
            BufferedImage img = h.screenshot("map-" + e.getKey());
            assertEquals(e.getValue().background(), img.getRGB(8, UiHarness.HEIGHT / 2) & 0xFFFFFF);
            assertTrue(h.has("map:canvas"));
            assertTrue(h.text("map:bots").endsWith("bots"));
        }
    }

    private static UiHarness mapOf(UiHarness ui) {
        ui.shell.show(CenterLayout.MAP);
        return ui.refresh();
    }

    private static MapView map(UiHarness ui) {
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof MapView m) {
                return m;
            }
        }
        throw new AssertionError("no map");
    }

    private static boolean near(BufferedImage img, int x, int y, int rgb) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (px >= 0 && py >= 0 && px < img.getWidth() && py < img.getHeight()
                        && (img.getRGB(px, py) & 0xFFFFFF) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }

    private static MouseEvent press(JComponent c, int x, int y, int button) {
        return new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x, y, 1, false, button);
    }

    private static MouseEvent drag(JComponent c, int x, int y) {
        return new MouseEvent(c, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK,
                x, y, 0, false, MouseEvent.BUTTON1);
    }
}
