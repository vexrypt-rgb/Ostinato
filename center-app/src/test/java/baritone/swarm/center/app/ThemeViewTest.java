package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.ThemeChoice;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.feed.BotFeed;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: the header theme picker applies at once and follows the shared theme file. */
public class ThemeViewTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void thePickerSendsTheThemeAndAChangedFileSwitchesIt() throws Exception {
        Path dir = temporary.getRoot().toPath();
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.shell.setPrefsDir(dir);
        assertTrue(((AbstractButton) ui.find("header.theme.dark")).isSelected());
        assertEquals("Default dark", ((AbstractButton) ui.find("header.theme.dark")).getText());
        assertEquals("Colorblind safe", ((AbstractButton) ui.find("header.theme.colorblind")).getText());

        ui.click("header.theme.high-contrast");
        assertEquals("high-contrast", ui.shell.themeName());
        assertEquals(CenterTheme.HIGH_CONTRAST.background(), ui.shell.component().getBackground().getRGB() & 0xFFFFFF);
        assertEquals(1, ui.sent().size());
        assertEquals("theme", ui.sent().get(0).command());
        assertEquals("theme-pick", ui.sent().get(0).formId());
        assertEquals("high-contrast", ui.sent().get(0).values().get("name"));
        assertTrue(ui.text("footer.reply").contains("accepted"));
        assertTrue(ui.text("footer.reply").contains("theme high-contrast"));
        assertEquals("high-contrast", ThemeChoice.load(ThemeChoice.file(dir)));

        ui.refresh();
        assertEquals("high-contrast", ui.shell.themeName());
        assertEquals(1, ui.sent().size());

        ui.port.answer(r -> CenterReply.refused(r.id(), "busy", ui.now.get()));
        ui.click("header.theme.light");
        assertEquals("light", ui.shell.themeName());
        assertTrue(ui.text("footer.reply").contains("busy"));

        Files.writeString(ThemeChoice.file(dir), "nope\n");
        ui.refresh();
        assertEquals("dark", ui.shell.themeName());
        assertTrue(((AbstractButton) ui.find("header.theme.dark")).isSelected());
    }

    @Test public void stateSurvivesAThemeChange() throws Exception {
        Path dir = temporary.newFolder("state").toPath();
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.shell.setPrefsDir(dir);
        ui.type("tile:Azalea:field:goto:target", "3 4 5");
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof WallView wall) {
                wall.select("Azalea");
            }
        }
        ui.click("header.layout.map");
        MapView map = map(ui);
        map.placeCamera(9, 80, -15);
        map.replaceSelection(List.of("Azalea", "Spelunker"));
        ui.click("tile:Azalea:button:goto");
        String reply = ui.text("footer.reply");
        assertTrue(reply.contains("accepted"));
        int sent = ui.sent().size();

        ThemeChoice.save(ThemeChoice.file(dir), "light");
        ui.refresh();
        assertEquals("light", ui.shell.themeName());
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertEquals("3 4 5", ((JTextComponent) ui.find("tile:Azalea:field:goto:target")).getText());
        MapView again = map(ui);
        assertEquals(9, again.camera().zoom());
        assertEquals(80, again.camera().centerX(), 0.01);
        assertEquals(-15, again.camera().centerZ(), 0.01);
        assertEquals(List.of("Azalea", "Spelunker"), again.selected());
        assertEquals(reply, ui.text("footer.reply"));
        assertEquals(sent, ui.sent().size());
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof WallView wall) {
                assertEquals("Azalea", wall.selected());
            }
        }
        assertEquals(CenterTheme.LIGHT.background(), ui.render().getRGB(UiHarness.WIDTH - 10, 400) & 0xFFFFFF);
    }

    @Test public void theWindowOpensInTheSavedTheme() throws Exception {
        Path dir = temporary.newFolder("open").toPath();
        ThemeChoice.save(ThemeChoice.file(dir), "colorblind");
        UiHarness ui = UiHarness.threeBots();
        assertEquals("dark", ui.shell.themeName());
        ui.shell.setPrefsDir(dir);
        assertEquals("colorblind", ui.shell.themeName());
        assertTrue(ui.sent().isEmpty());
        ui.refresh();
        assertEquals("colorblind", ui.shell.themeName());
        assertTrue(((AbstractButton) ui.find("header.theme.colorblind")).isSelected());
        assertTrue(ui.sent().isEmpty());
    }

    @Test public void wallAndMapInEveryCoreTheme() throws Exception {
        Map<String, CenterTheme> themes = new LinkedHashMap<>();
        themes.put("dark", CenterTheme.DEFAULT_DARK);
        themes.put("light", CenterTheme.LIGHT);
        themes.put("high-contrast", CenterTheme.HIGH_CONTRAST);
        themes.put("colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            AtomicLong now = new AtomicLong(1_000_000);
            FakeSwarm swarm = FakeSwarm.threeBots(now::get);
            swarm.bot("Azalea").path(new BotFeed.Route("pathing", "GoalBlock", new int[]{48, 64, -12}, 8, 1, 8,
                    List.of(new int[]{44, 64, -12}, new int[]{48, 64, -12})));
            UiHarness ui = new UiHarness(now, swarm, e.getValue(), CenterLayout.WALL).refresh();
            ui.screenshot("theme-wall-" + e.getKey());
            ui.click("header.layout.map");
            MapView map = map(ui);
            map.camera().setZoom(4);
            assertTrue(map.centerOn("Azalea"));
            ui.refresh();
            BufferedImage img = ui.screenshot("theme-map-" + e.getKey());
            JComponent canvas = (JComponent) ui.find("map:canvas");
            double[] node = map.camera().worldToScreen(44, -12);
            Point top = SwingUtilities.convertPoint(canvas, (int) Math.round(node[0]), (int) Math.round(node[1]),
                    ui.shell.component());
            assertTrue(e.getKey(), near(img, top.x, top.y, e.getValue().identity("Azalea")));
        }
    }

    private static MapView map(UiHarness ui) {
        for (LayoutView v : ui.shell.views()) {
            if (v instanceof MapView map) {
                return map;
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
}
