package baritone.swarm.center.app;

import baritone.swarm.center.CenterLayout;
import baritone.swarm.center.CenterTheme;
import baritone.swarm.center.app.fake.DemoSwarm;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.SwarmView;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Ostinato: the demo swarm has everything the map slices draw, it moves, and the window renders it. */
public class DemoSwarmTest {

    @Test public void demoHasPathsLeasesObservationsAndTerrain() {
        AtomicLong now = new AtomicLong(1_000_000);
        SwarmView v = new DemoSwarm(8, now::get).snapshot();
        assertEquals(8, v.bots().size());
        assertEquals(8, v.terrain().size());
        assertNotNull(v.coordinator());
        assertTrue(v.coordinator().leases().stream().anyMatch(l -> "region".equals(l.type()) && l.box() != null));
        assertTrue(v.coordinator().leases().stream().anyMatch(l -> "vein".equals(l.type())));
        assertTrue(v.coordinator().observations().size() >= 4);
        assertTrue(v.bots().stream().anyMatch(b -> !b.feed().path().nodes().isEmpty() && b.feed().path().goalPos() != null));
        for (SwarmView.BotState b : v.bots()) {
            BotFeed.Pos p = b.feed().pos();
            assertTrue(b.name(), v.terrain().get(b.name()).contains(p.blockX(), p.blockZ()));
            assertFalse(v.terrain().get(b.name()).blockAt(p.blockX(), p.blockZ()).equals("?"));
        }
    }

    @Test public void botsMoveWithTheClock() {
        AtomicLong now = new AtomicLong(1_000_000);
        DemoSwarm d = new DemoSwarm(3, now::get);
        BotFeed.Pos a = d.snapshot().bot("Azalea").feed().pos();
        now.addAndGet(DemoSwarm.LAP_MS / 4);
        BotFeed.Pos b = d.snapshot().bot("Azalea").feed().pos();
        assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) > 5);
    }

    @Test public void demoRendersInEveryCoreThemeOnBothCards() throws Exception {
        Map<String, CenterTheme> themes = Map.of("dark", CenterTheme.DEFAULT_DARK, "light", CenterTheme.LIGHT,
                "high-contrast", CenterTheme.HIGH_CONTRAST, "colorblind", CenterTheme.COLORBLIND_SAFE);
        for (Map.Entry<String, CenterTheme> e : themes.entrySet()) {
            for (CenterLayout l : CenterLayout.values()) {
                UiHarness h = UiHarness.demo(8, e.getValue(), l).refresh();
                h.screenshot("demo-" + l.wire() + "-" + e.getKey());
                assertEquals(l, h.shell.shown());
            }
        }
    }
}
