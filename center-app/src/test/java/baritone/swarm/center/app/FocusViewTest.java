package baritone.swarm.center.app;

import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.feed.BotFeed;
import org.junit.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the wall focus panel. Click a tile, read every field, leave with Esc or Close, and keep three columns
 * with the panel open.
 */
public class FocusViewTest {

    @Test public void clickingATileOpensThePanelAndCloseOrEscLeavesIt() {
        UiHarness ui = UiHarness.threeBots().refresh();
        assertFalse(ui.find("focus").isVisible());
        clickTile(ui, "Azalea");
        ui.refresh();
        assertTrue(ui.find("focus").isVisible());
        assertEquals("Azalea", ui.text("focus:title"));
        assertEquals(accent(), border(ui, "Azalea"));
        assertEquals(line(), border(ui, "Copper"));

        ui.click("focus:close");
        ui.refresh();
        assertFalse(ui.find("focus").isVisible());
        assertEquals(line(), border(ui, "Azalea"));

        clickTile(ui, "Azalea");
        ui.refresh();
        javax.swing.JComponent wall = (javax.swing.JComponent) ui.find("wall");
        wall.getActionMap().get("focus-clear").actionPerformed(new ActionEvent(wall, 0, "focus-clear"));
        ui.refresh();
        assertFalse(ui.find("focus").isVisible());
    }

    @Test public void selectionSurvivesRefreshAndJoiningAndClearsWhenTheBotVanishes() {
        UiHarness ui = UiHarness.threeBots().refresh();
        clickTile(ui, "Spelunker");
        ui.refresh();
        assertEquals("Spelunker", ui.text("focus:title"));
        ui.swarm.bot("Oak");
        ui.refresh();
        assertTrue(ui.has("tile:Oak"));
        assertEquals("Spelunker", ui.text("focus:title"));
        assertTrue(ui.find("focus").isVisible());
        ui.swarm.remove("Spelunker");
        ui.refresh();
        assertFalse(ui.has("tile:Spelunker"));
        assertFalse(ui.find("focus").isVisible());
    }

    @Test public void everyFocusFieldComesFromTheBotFeed() {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Azalea").dim('n').at(40.5, 70, -12.5).yaw(90).health(14).hunger(9).process("Goto")
                .path(new BotFeed.Route("pathing", "GoalBlock", new int[]{10, 64, 20}, 15.5, 3, 40,
                        List.of(new int[]{1, 64, 2}, new int[]{3, 64, 4})))
                .execution("goto", "running", 2500, "arrived")
                .lease("r:n:0,60,0:4,70,4", 7, "held", 12_000)
                .perf(new BotFeed.Perf(18, 195, 42, 800, 2048, 4, 30, 1, 2))
                .gather(55)
                .link(false);
        ui.refresh();
        clickTile(ui, "Azalea");
        ui.refresh();
        assertEquals("40, 70, -13 (nether)", ui.text("focus:position"));
        assertEquals("nether", ui.text("focus:dimension"));
        assertEquals("pathing · GoalBlock · 15.5 blocks · 2 nodes · 3/40", ui.text("focus:path"));
        assertTrue(ui.text("focus:executions").contains("goto · Copper · running · unknown · 2 s · arrived"));
        assertTrue(ui.text("focus:leases").contains("r:n:0,60,0:4,70,4 · gen 7 · held · 12 s left"));
        assertTrue(ui.text("focus:inventory").startsWith("free 30/36"));
        assertEquals("path 18 ms · 19.5 tps · ping 42 ms · ram 800/2048 MB · chat 4/30 · dropped 1 · retried 2",
                ui.text("focus:perf"));
        assertEquals("down · peers 0 · not started", ui.text("focus:link"));
        assertEquals("0 s · fresh", ui.text("focus:age"));
        assertEquals("gather 55 µs", ui.text("focus:gather"));
        assertTrue(ui.text("focus:alerts").contains("link"));
        assertEquals("19.5 tps · 42 ms · 800/2048 MB", ui.text("tile:Azalea:perf"));
        assertEquals("3/40", ui.text("tile:Azalea:progress"));
    }

    @Test public void anOfflineBotAndABotWithNoWorldStillOpen() {
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.bot("Azalea").age(20_000).notInWorld().perf(BotFeed.Perf.UNKNOWN);
        ui.refresh();
        clickTile(ui, "Azalea");
        ui.refresh();
        assertEquals("not in a world", ui.text("focus:position"));
        assertEquals("not in a world", ui.text("focus:dimension"));
        assertEquals("20 s · offline", ui.text("focus:age"));
        assertTrue(ui.text("focus:perf").startsWith("path n/a"));
        assertEquals("no commands", ui.text("focus:executions"));
        assertEquals("no leases", ui.text("focus:leases"));
        assertTrue(ui.text("focus:alerts").contains("offline"));
    }

    @Test public void emptySwarmHasNoPanelAndOneBotStillSelects() {
        UiHarness ui = UiHarness.many(0, CenterTheme.DEFAULT_DARK).refresh();
        assertFalse(ui.find("focus").isVisible());
        assertTrue(ui.find("wall.empty").isVisible());
        UiHarness one = UiHarness.many(1, CenterTheme.DEFAULT_DARK).refresh();
        clickTile(one, "Copper");
        one.refresh();
        assertEquals("Copper", one.text("focus:title"));
    }

    @Test public void elevenBotsKeepThreeColumnsWithThePanelOpen() throws Exception {
        Map<String, CenterTheme> themes = Map.of("dark", CenterTheme.DEFAULT_DARK, "light", CenterTheme.LIGHT,
                "high-contrast", CenterTheme.HIGH_CONTRAST, "colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            UiHarness h = UiHarness.many(11, e.getValue()).refresh();
            clickTile(h, "Copper");
            h.refresh();
            JPanel grid = (JPanel) h.find("wall.grid");
            assertEquals(e.getKey(), 3, ((GridLayout) grid.getLayout()).getColumns());
            assertTrue(h.find("focus").isVisible());
            for (var b : h.swarm.snapshot().bots()) {
                assertTrue(b.name(), h.has("tile:" + b.name()));
            }
            h.screenshot("focus-" + e.getKey());
        }
    }

    private static void clickTile(UiHarness ui, String bot) {
        Component tile = ui.find("tile:" + bot);
        tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 8, 8, 1,
                false, MouseEvent.BUTTON1));
    }

    private static int border(UiHarness ui, String bot) {
        javax.swing.border.CompoundBorder box = (javax.swing.border.CompoundBorder) ((javax.swing.JComponent) ui.find("tile:" + bot)).getBorder();
        java.awt.Color c = ((javax.swing.border.LineBorder) box.getOutsideBorder()).getLineColor();
        return c.getRGB() & 0xFFFFFF;
    }

    private static int accent() {
        return CenterTheme.DEFAULT_DARK.accent();
    }

    private static int line() {
        return CenterTheme.DEFAULT_DARK.border();
    }
}
