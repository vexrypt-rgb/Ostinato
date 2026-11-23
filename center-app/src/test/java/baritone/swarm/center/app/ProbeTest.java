package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: the window log lines a live check reads (layout/theme state, map markers). */
public class ProbeTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void stateLineOnlyOnChangeAndShotPerState() throws Exception {
        Path log = temporary.newFile("window.log").toPath();
        Path shots = temporary.getRoot().toPath().resolve("shots");
        UiHarness ui = UiHarness.many(3, CenterTheme.DEFAULT_DARK).refresh();
        CenterApp.Probe probe = new CenterApp.Probe(new CenterApp.Logger(log), shots);
        probe.after(ui.shell, ui.swarm.snapshot());
        probe.after(ui.shell, ui.swarm.snapshot());
        List<String> lines = Files.readAllLines(log);
        assertEquals(1, lines.stream().filter(l -> l.contains(" state layout=wall theme=dark")).count());
        assertTrue(Files.isRegularFile(shots.resolve("01-wall-dark.png")));
    }

    @Test public void autoFileLinesRunInOrderAndTheFileIsConsumed() throws Exception {
        Path log = temporary.newFile("window.log").toPath();
        Path file = temporary.getRoot().toPath().resolve("auto.txt");
        UiHarness ui = UiHarness.threeBots();
        ui.refresh();
        Files.writeString(file, "@|header.layout.map|\n@set|area:ore|coal_ore\n\n@map|select:Azalea+Spelunker\n");
        java.util.concurrent.ScheduledExecutorService worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            CenterApp.runAutoFile(file, worker, ui.shell, new CenterApp.Logger(log));
            worker.submit(() -> {}).get();
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
        } finally {
            worker.shutdownNow();
        }
        assertTrue(!Files.exists(file));
        assertEquals(CenterLayout.MAP, ui.shell.shown());
        assertEquals("coal_ore", ui.text("area:ore"));
        List<String> lines = Files.readAllLines(log);
        assertTrue(lines.toString(), lines.get(0).endsWith("auto: press @header.layout.map"));
        assertTrue(lines.toString(), lines.get(1).endsWith("auto: set area:ore=coal_ore"));
        assertTrue(lines.toString(), lines.get(2).contains("auto: map select:Azalea+Spelunker -> selected=[Azalea, Spelunker]"));
    }

    @Test public void mapAutoSelectsTwoAndDrawsTheExactArea() {
        UiHarness ui = UiHarness.threeBots();
        ui.shell.show(CenterLayout.MAP);
        ui.refresh();
        assertEquals("selected=[Azalea, Spelunker]", CenterApp.mapAuto(ui.shell, "select:Azalea+Spelunker"));
        assertEquals("area=310,310,317,313", CenterApp.mapAuto(ui.shell, "area:310,310,317,313"));
        assertEquals("area=-9,4,-2,5", CenterApp.mapAuto(ui.shell, "area:-2,5,-9,4"));
        assertEquals("bad map spec", CenterApp.mapAuto(ui.shell, "zoom:3"));
    }

    @Test public void mapLineListsEachDrawnLeaseWithItsRectangle() throws Exception {
        Path log = temporary.newFile("window.log").toPath();
        UiHarness ui = UiHarness.threeBots();
        ui.swarm.coordinator(MapOverlayTest.feed());
        ui.shell.show(CenterLayout.MAP);
        ui.refresh();
        new CenterApp.Probe(new CenterApp.Logger(log), null).after(ui.shell, ui.swarm.snapshot());
        String map = Files.readAllLines(log).stream().filter(l -> l.contains(" map dim=")).findFirst().orElse("");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\| lease \\S+ Azalea gen=3 state=granted live=true box=0,60,0,7,70,7 rect=(-?\\d+),(-?\\d+),(\\d+),(\\d+) expect=(-?\\d+),(-?\\d+),(\\d+),(\\d+)")
                .matcher(map);
        assertTrue(map, m.find());
        for (int i = 1; i <= 4; i++) {
            assertTrue(map, Math.abs(Integer.parseInt(m.group(i)) - Integer.parseInt(m.group(i + 4))) <= 1);
        }
    }

    @Test public void mapLineHasEveryMarkerAndItsHit() throws Exception {
        Path log = temporary.newFile("window.log").toPath();
        UiHarness ui = UiHarness.many(3, CenterTheme.DEFAULT_DARK);
        ui.shell.show(CenterLayout.MAP);
        ui.refresh();
        CenterApp.Probe probe = new CenterApp.Probe(new CenterApp.Logger(log), null);
        probe.after(ui.shell, ui.swarm.snapshot());
        String map = Files.readAllLines(log).stream().filter(l -> l.contains(" map dim=")).findFirst().orElse("");
        assertTrue(map, map.contains("map dim=o"));
        for (var b : ui.swarm.snapshot().bots()) {
            assertTrue(map, map.contains("| " + b.name() + " feed="));
            assertTrue(map, map.contains(" hit=" + b.name()));
        }
    }
}
