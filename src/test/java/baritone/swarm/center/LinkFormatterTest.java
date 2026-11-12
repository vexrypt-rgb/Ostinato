package baritone.swarm.center;

import baritone.swarm.SwarmSample;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: swarm link monitor text and levels. No display. */
public class LinkFormatterTest {

    private static SwarmSample link(int used, int cap, long dropped, long retried, String cmd) {
        return SwarmSample.UNKNOWN.withLink(used, cap, dropped, retried, cmd);
    }

    @Test public void olderBotReadsNa() {
        List<PerfFormatter.Cell> cells = LinkFormatter.cells(SwarmSample.UNKNOWN);
        assertEquals("chat n/a", cells.get(0).text());
        assertEquals("cmd n/a", cells.get(1).text());
        assertEquals("dropped n/a", cells.get(2).text());
        assertEquals("retried n/a", cells.get(3).text());
        for (PerfFormatter.Cell c : cells) {
            assertEquals(TileStatus.Level.IDLE, c.level());
        }
        assertEquals(cells, LinkFormatter.cells(null));
    }

    @Test public void chatBudgetAgainstCap() {
        assertEquals("chat 3/35 per min", LinkFormatter.chat(link(3, 35, 0, 0, "")).text());
        assertEquals(TileStatus.Level.OK, LinkFormatter.chat(link(27, 35, 0, 0, "")).level()); // 77 %
        assertEquals(TileStatus.Level.WARN, LinkFormatter.chat(link(28, 35, 0, 0, "")).level()); // 80 %
        assertEquals(TileStatus.Level.BAD, LinkFormatter.chat(link(35, 35, 0, 0, "")).level());
        PerfFormatter.Cell noCap = LinkFormatter.chat(link(4, -1, 0, 0, ""));
        assertEquals("chat 4/min", noCap.text());
        assertEquals(TileStatus.Level.OK, noCap.level());
    }

    @Test public void commandDroppedRetried() {
        SwarmSample s = link(1, 35, 2, 0, "/msg");
        assertEquals("cmd /msg", LinkFormatter.command(s).text());
        assertEquals("dropped 2", LinkFormatter.dropped(s).text());
        assertEquals(TileStatus.Level.WARN, LinkFormatter.dropped(s).level());
        assertEquals("retried 0", LinkFormatter.retried(s).text());
        assertEquals(TileStatus.Level.OK, LinkFormatter.retried(s).level());
        assertEquals(TileStatus.Level.WARN, LinkFormatter.retried(link(1, 35, 0, 4, "/w")).level());
        assertEquals(TileStatus.Level.OK, LinkFormatter.dropped(link(1, 35, 0, 0, "/w")).level());
    }

    @Test public void receivedAndRoute() {
        assertEquals("rx n/a", LinkFormatter.received(-1).text());
        assertEquals("rx 7/min", LinkFormatter.received(7).text());
        assertEquals("this client", LinkFormatter.route(true, true).text());
        assertEquals("local spool", LinkFormatter.route(true, false).text());
        assertEquals("chat", LinkFormatter.route(false, false).text());
    }

    @Test public void sampleAgeGoesStale() {
        long interval = 30_000;
        PerfFormatter.Cell none = LinkFormatter.age(-1, 1_000_000, interval);
        assertEquals("no sample", none.text());
        assertEquals(TileStatus.Level.IDLE, none.level());
        PerfFormatter.Cell fresh = LinkFormatter.age(1_000_000, 1_012_400, interval);
        assertEquals("sample 12 s ago", fresh.text());
        assertEquals(TileStatus.Level.OK, fresh.level());
        assertEquals(TileStatus.Level.OK, LinkFormatter.age(0, 90_000, interval).level()); // exactly 3 samples
        assertEquals(TileStatus.Level.WARN, LinkFormatter.age(0, 90_001, interval).level());
        assertEquals("sample 0 s ago", LinkFormatter.age(5_000, 4_000, interval).text()); // clock skew
    }

    @Test public void budgetNote() {
        assertEquals("chat budget 35/min (vanilla ~70/min)", LinkFormatter.budgetNote(35));
        assertTrue(LinkFormatter.budgetNote(-1).startsWith("chat budget n/a"));
        assertTrue(LinkFormatter.budgetNote(0).startsWith("chat budget n/a"));
    }
}
