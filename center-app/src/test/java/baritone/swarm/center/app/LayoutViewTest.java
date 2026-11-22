package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.LayoutStore;
import baritone.swarm.center.app.fake.FakeSwarm;
import baritone.swarm.center.channel.CenterReply;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.text.JTextComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: the header layout toggle remembers the card and follows the shared file. */
public class LayoutViewTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void theToggleSendsTheLayoutAndAChangedFileSwitchesTheCard() throws Exception {
        Path dir = temporary.getRoot().toPath();
        UiHarness ui = UiHarness.threeBots().refresh();
        ui.shell.setPrefsDir(dir);
        ui.type("tile:Azalea:field:goto:target", "10 64 10");
        assertEquals(CenterLayout.WALL, ui.shell.shown());

        ui.click("header.layout.map");
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertEquals(1, ui.sent().size());
        assertEquals("center", ui.sent().get(0).command());
        assertEquals("layout-map", ui.sent().get(0).formId());
        assertTrue(ui.text("footer.reply").contains("accepted"));
        assertTrue(ui.text("footer.reply").contains("center layout map"));
        assertEquals(CenterLayout.MAP, LayoutStore.load(LayoutStore.sharedFile(dir)));

        ui.refresh();
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertEquals("10 64 10", ((JTextComponent) ui.find("tile:Azalea:field:goto:target")).getText());
        assertEquals(1, ui.sent().size());

        Files.writeString(LayoutStore.sharedFile(dir), "nope\n");
        ui.refresh();
        assertEquals(CenterLayout.WALL, ui.shell.shown());
        assertEquals(1, ui.sent().size());

        LayoutStore.save(LayoutStore.sharedFile(dir), CenterLayout.MAP);
        ui.refresh();
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertTrue(ui.has("map:canvas"));
        assertEquals(1, ui.sent().size());

        ui.port.answer(r -> CenterReply.refused(r.id(), "busy", ui.now.get()));
        ui.click("header.layout.wall");
        assertEquals(CenterLayout.WALL, ui.shell.shown());
        assertEquals("layout-wall", ui.sent().get(1).formId());
        assertTrue(ui.text("footer.reply").contains("busy"));
    }

    @Test public void theWindowOpensInTheSavedLayout() throws Exception {
        Path dir = temporary.newFolder("open").toPath();
        LayoutStore.save(LayoutStore.sharedFile(dir), CenterLayout.MAP);
        UiHarness ui = UiHarness.threeBots();
        assertEquals(CenterLayout.WALL, ui.shell.shown());
        ui.shell.setPrefsDir(dir);
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertTrue(ui.sent().isEmpty());
        ui.refresh();
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertTrue(ui.has("map:canvas"));
        assertTrue(ui.sent().isEmpty());
    }

    @Test public void eachCoreThemeShowsTheWarRoom() throws Exception {
        Map<String, CenterTheme> themes = new LinkedHashMap<>();
        themes.put("dark", CenterTheme.DEFAULT_DARK);
        themes.put("light", CenterTheme.LIGHT);
        themes.put("high-contrast", CenterTheme.HIGH_CONTRAST);
        themes.put("colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            AtomicLong now = new AtomicLong(1_000_000);
            UiHarness ui = new UiHarness(now, FakeSwarm.threeBots(now::get), e.getValue(), CenterLayout.WALL).refresh();
            ui.shell.setPrefsDir(temporary.newFolder(e.getKey()).toPath());
            ui.click("header.layout.map");
            assertEquals(CenterLayout.MAP, ui.shell.shown());
            assertEquals("layout-map", ui.sent().get(0).formId());
            ui.screenshot("layout-" + e.getKey());
        }
    }
}
