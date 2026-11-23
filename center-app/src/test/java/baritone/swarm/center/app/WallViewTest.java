package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ostinato: the Mission control wall, the reference slice. Every test drives the real window through
 * {@link UiHarness}: read tiles by component name, type, click, and check what reached the fake command port.
 */
public class WallViewTest {

    final UiHarness ui = UiHarness.threeBots();

    @Test public void oneTilePerBotWithStatusTaskHealthAndInventory() {
        ui.refresh();
        for (String b : List.of("Copper", "Azalea", "Spelunker")) {
            assertTrue(b, ui.has("tile:" + b));
        }
        assertEquals("idle", ui.text("tile:Azalea:status"));
        assertTrue(ui.text("tile:Spelunker:task"), ui.text("tile:Spelunker:task").startsWith("Mine · mine running"));
        assertEquals("40, 64, -13 (overworld)", ui.text("tile:Azalea:position"));
        assertTrue(ui.text("tile:Copper:inventory").startsWith("free 30/36 · food 4 · 64 cobblestone, 12 oak_log"));
        assertTrue(ui.text("tile:Copper:inventory").endsWith("iron_pickaxe 80%"));
        assertEquals("Server localhost:25565", ui.text("header.server"));
        assertEquals("Host Copper", ui.text("header.host"));
        assertEquals("○ No alerts", ui.text("header.alerts"));
    }

    @Test public void alertsShowOnTheTileAndInTheHeaderWithAGlyph() {
        ui.swarm.bot("Azalea").health(4);
        ui.swarm.bot("Spelunker").age(20_000);
        ui.refresh();
        assertEquals("low health", ui.text("tile:Azalea:status"));
        assertEquals("▲", ui.text("tile:Azalea:glyph"));
        assertEquals("offline", ui.text("tile:Spelunker:status"));
        assertEquals("✖", ui.text("tile:Spelunker:glyph"));
        Container alerts = (Container) ui.find("tile:Azalea:alerts");
        assertEquals("▲ health 4", ((JLabel) alerts.getComponent(0)).getText());
        assertEquals("▲ 2 alerts", ui.text("header.alerts"));
        assertTrue(ui.text("tile:Spelunker:position").endsWith("updated 20 s ago"));
    }

    @Test public void aStuckBotIsFlaggedAfterThirtySeconds() {
        ui.swarm.bot("Azalea").process("Goto");
        ui.refresh();
        ui.advance(31_000).refresh();
        assertEquals("stuck", ui.text("tile:Azalea:status"));
    }

    @Test public void gotoFromAnotherBotsTileShowsAndSendsTheSwarmSendLine() {
        ui.refresh();
        ui.type("tile:Azalea:field:goto:target", "100 64 200");
        assertEquals("#swarm send Azalea goto 100 64 200", ui.text("tile:Azalea:preview"));
        ui.click("tile:Azalea:button:goto");
        assertEquals(1, ui.sent().size());
        CenterRequest r = ui.sent().get(0);
        assertEquals(CenterRequest.Op.PRESS, r.op());
        assertEquals("goto", r.command());
        assertEquals("goto", r.formId());
        assertEquals("Azalea", r.bot());
        assertEquals(Map.of("target", "100 64 200"), r.values());
        assertEquals("● Copper: #swarm send Azalea goto 100 64 200 - accepted", ui.text("tile:Azalea:result"));
        assertEquals("● Copper: #swarm send Azalea goto 100 64 200 - accepted", ui.text("footer.reply"));
    }

    @Test public void theHostsOwnTileRunsTheLineDirectly() {
        ui.refresh();
        ui.type("tile:Copper:field:goto:target", "1 2 3");
        assertEquals("#goto 1 2 3", ui.text("tile:Copper:preview"));
    }

    @Test public void mineSendsBothFields() {
        ui.refresh();
        ui.type("tile:Spelunker:field:mine:count", "16").type("tile:Spelunker:field:mine:block", "iron_ore");
        assertEquals("#swarm lease Spelunker obs:iron_ore mine 16 iron_ore", ui.text("tile:Spelunker:preview")); // Ostinato: mine runs under a lease
        ui.click("tile:Spelunker:button:mine");
        assertEquals(Map.of("count", "16", "block", "iron_ore"), ui.sent().get(0).values());
    }

    @Test public void anIncompleteFormIsNotSent() {
        ui.refresh();
        ui.click("tile:Azalea:button:mine");
        assertTrue(ui.sent().isEmpty());
        assertEquals("not sent: missing block", ui.text("tile:Azalea:result"));
    }

    @Test public void aRefusalIsShownWithItsReason() {
        ui.port.answer(r -> new CenterReply(r.id(), 0, false, false, "busy", "swarm send Azalea goto 1 2 3", "Copper"));
        ui.refresh();
        ui.type("tile:Azalea:field:goto:target", "1 2 3").click("tile:Azalea:button:goto");
        assertEquals("▲ Copper: #swarm send Azalea goto 1 2 3 - busy", ui.text("tile:Azalea:result"));
    }

    @Test public void cancelTargetsTheBotsRunningCommand() {
        ui.refresh();
        ui.click("tile:Spelunker:button:cancel");
        assertEquals("cancel", ui.sent().get(0).formId());
        assertEquals("● Copper: #swarm cancel cmd-0 - accepted", ui.text("tile:Spelunker:result"));
    }

    @Test public void despawnNeedsASecondClick() {
        ui.refresh();
        ui.click("tile:Azalea:button:despawn");
        assertTrue(ui.sent().isEmpty());
        assertEquals("Confirm despawn", ui.text("tile:Azalea:button:despawn"));
        ui.click("tile:Azalea:button:despawn");
        assertEquals(1, ui.sent().size());
        assertEquals(Map.of("name", "Azalea"), ui.sent().get(0).values());
    }

    @Test public void typedTextSurvivesARefresh() {
        ui.refresh();
        ui.type("tile:Azalea:field:goto:target", "5 5 5");
        ui.swarm.bot("Azalea").at(41, 64, -12);
        ui.refresh();
        assertEquals("5 5 5", ((JTextField) ui.find("tile:Azalea:field:goto:target")).getText());
    }

    @Test public void withoutACatalogThereAreNoButtonsAndTheTileSaysWhy() {
        ui.swarm.catalog(false);
        ui.refresh();
        assertFalse(ui.has("tile:Azalea:button:goto"));
        assertEquals("commands off: no catalog from a command host", ui.text("tile:Azalea:preview"));
    }

    @Test public void withoutAHostTheHeaderSaysSo() {
        ui.swarm.bot("Copper").host(false);
        ui.refresh();
        assertEquals("▲ no command host", ui.text("header.host"));
    }

    @Test public void botsComeAndGo() {
        ui.refresh();
        ui.swarm.bot("Oak");
        ui.swarm.remove("Spelunker");
        ui.refresh();
        assertTrue(ui.has("tile:Oak"));
        assertFalse(ui.has("tile:Spelunker"));
    }

    @Test public void theLayoutToggleSwitchesCardsAndReportsThePick() {
        ui.refresh();
        CenterLayout[] picked = new CenterLayout[1];
        ui.shell.onLayoutPicked(l -> picked[0] = l);
        ui.click("header.layout.map");
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertEquals(CenterLayout.MAP, picked[0]);
        assertEquals("3 bots", ui.text("map:bots"));
        assertTrue(ui.has("map:canvas"));
    }

    @Test public void buttonsNeverTakeFocus() {
        ui.refresh();
        assertFalse(ui.find("tile:Azalea:button:goto").isFocusable());
        assertFalse(ui.find("header.layout.wall").isFocusable());
    }

    /** 11 bots (the doc's upper case) in every core theme: all tiles exist, and the PNGs are kept for review. */
    @Test public void elevenBotsRenderInEveryCoreTheme() throws Exception {
        Map<String, CenterTheme> themes = Map.of("dark", CenterTheme.DEFAULT_DARK, "light", CenterTheme.LIGHT,
                "high-contrast", CenterTheme.HIGH_CONTRAST, "colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            UiHarness h = UiHarness.many(11, e.getValue()).refresh();
            BufferedImage img = h.screenshot("wall-11-" + e.getKey());
            assertEquals(e.getValue().background(), img.getRGB(UiHarness.WIDTH - 10, 400) & 0xFFFFFF);
            for (var b : h.swarm.snapshot().bots()) {
                assertTrue(b.name(), h.has("tile:" + b.name()));
            }
        }
    }
}
