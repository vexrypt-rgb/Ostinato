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
