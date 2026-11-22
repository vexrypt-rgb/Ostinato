package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.feed.CoordinatorFeed;
import org.junit.Test;

import javax.swing.JComponent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: leases, observations and the layer toggles on the war-room map. */
public class LeaseViewTest {

    @Test public void theMapDrawsEachLeaseAndListsKeys() {
        UiHarness ui = harness();
        MapView map = map(ui);
        map.camera().setZoom(4);
        map.camera().setCenter(4, 4);
        ui.refresh();
        ui.render();
        assertTrue(ui.text("map:legend").contains("vein dashed"));
        assertTrue(ui.text("map:legend").contains("suspect ring"));
        assertTrue(ui.text("map:keys").contains("Copper · gen 2 · expired · lease-expired"));
        JComponent canvas = (JComponent) ui.find("map:canvas");
        MapOverlay.Rect chest = MapOverlay.screenBox(map.camera(), new int[]{30, 64, -4, 30, 64, -4});
        BufferedImage img = ui.render();
        assertTrue(near(img, canvas, chest.x() + chest.w() / 2, chest.y() + chest.h() / 2, CenterTheme.DEFAULT_DARK.ok()));
        MapOverlay.Rect region = MapOverlay.screenBox(map.camera(), new int[]{0, 60, 0, 7, 70, 7});
        int cx = region.x() + region.w() / 2;
        int cy = region.y() + region.h() / 2;
        canvas.dispatchEvent(move(canvas, cx, cy));
        assertTrue(ui.text("map:hover").contains("Azalea · gen 3 · granted"));
        MapOverlay.Rect ended = MapOverlay.screenBox(map.camera(), new int[]{40, 60, 40, 44, 64, 44});
        canvas.dispatchEvent(move(canvas, ended.x() + 2, ended.y() + 2));
        assertTrue(ui.text("map:hover").contains("lease-expired"));
    }

    @Test public void togglesHideLayersAndAnotherDimensionStaysHidden() {
        UiHarness ui = harness();
        MapView map = map(ui);
        map.camera().setZoom(4);
        map.camera().setCenter(4, 4);
        ui.refresh();
        ui.render();
        assertTrue(map.markerScreen("Oak") == null);
        assertTrue(MapOverlay.leases(ui.swarm.snapshot().coordinator(), 'n').stream().anyMatch(l -> "Oak".equals(l.bot())));
        JComponent canvas = (JComponent) ui.find("map:canvas");
        MapOverlay.Rect region = MapOverlay.screenBox(map.camera(), new int[]{0, 60, 0, 7, 70, 7});
        int rx = region.x() + region.w() / 2;
        int ry = region.y() + region.h() / 2;
        canvas.dispatchEvent(move(canvas, rx, ry));
        assertTrue(ui.text("map:hover").contains("granted"));
        ui.click("map:toggle:leases");
        canvas.dispatchEvent(move(canvas, rx, ry));
        assertFalse(ui.text("map:hover").contains("granted"));
        ui.click("map:dim:n");
        ui.refresh();
        ui.render();
        assertEquals('n', map.dimension());
        assertTrue(map.markerScreen("Oak") != null);
        assertTrue(map.markerScreen("Azalea") == null);
        ui.click("map:toggle:observations");
        ui.click("map:toggle:paths");
        ui.click("map:toggle:terrain");
        ui.refresh();
        assertTrue(ui.has("map:toggle:terrain"));
    }

    @Test public void noCoordinatorAndAnEmptySwarmStayQuiet() {
        UiHarness ui = UiHarness.threeBots();
        ui.shell.show(CenterLayout.MAP);
        ui.refresh();
        assertEquals("no key leases", ui.text("map:keys"));
        assertTrue(ui.text("map:hover").isBlank() || " ".equals(ui.text("map:hover")));
        UiHarness empty = UiHarness.many(0, CenterTheme.DEFAULT_DARK);
        empty.shell.show(CenterLayout.MAP);
        empty.refresh();
        assertEquals("no key leases", empty.text("map:keys"));
        assertEquals("0 bots", empty.text("map:bots"));
    }

    @Test public void screenshotsInEveryCoreTheme() throws Exception {
        Map<String, CenterTheme> themes = Map.of("dark", CenterTheme.DEFAULT_DARK, "light", CenterTheme.LIGHT,
                "high-contrast", CenterTheme.HIGH_CONTRAST, "colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            UiHarness h = UiHarness.demo(8, e.getValue(), CenterLayout.MAP).refresh();
            h.screenshot("lease-" + e.getKey());
            assertTrue(h.text("map:legend").contains("region solid edge"));
            assertTrue(h.text("map:keys").contains("expired"));
        }
    }

    private static UiHarness harness() {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Oak").dim('n').at(1.5, 40, 1.5);
        ui.swarm.coordinator(MapOverlayTest.feed());
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

    private static MouseEvent move(JComponent c, int x, int y) {
        return new MouseEvent(c, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false, MouseEvent.NOBUTTON);
    }

    private static boolean near(BufferedImage img, JComponent canvas, int x, int y, int rgb) {
        java.awt.Point top = javax.swing.SwingUtilities.convertPoint(canvas, x, y, canvas.getParent().getParent().getParent());
        // Walk up to the shell if the parent chain is short. Fall back to the canvas point plus its location on screen chain.
        java.awt.Component shell = canvas;
        while (shell.getParent() != null) {
            shell = shell.getParent();
        }
        top = javax.swing.SwingUtilities.convertPoint(canvas, x, y, shell);
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int px = top.x + dx;
                int py = top.y + dy;
                if (px >= 0 && py >= 0 && px < img.getWidth() && py < img.getHeight()
                        && (img.getRGB(px, py) & 0xFFFFFF) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }
}
