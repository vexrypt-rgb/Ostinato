package baritone.swarm.center;

import baritone.swarm.SwarmSample;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/** Ostinato: performance strip text and thresholds. No display. */
public class PerfFormatterTest {

    private static SwarmSample perf(long path, int tps, int ping, int used, int max) {
        return new SwarmSample(path, tps, ping, used, max, -1, -1, -1, -1, "");
    }

    @Test public void unknownFiguresReadNaNeverZero() {
        List<PerfFormatter.Cell> cells = PerfFormatter.cells(SwarmSample.UNKNOWN);
        assertEquals("path n/a", cells.get(0).text());
        assertEquals("tps n/a", cells.get(1).text());
        assertEquals("ping n/a", cells.get(2).text());
        assertEquals("ram n/a", cells.get(3).text());
        for (PerfFormatter.Cell c : cells) {
            assertEquals(TileStatus.Level.IDLE, c.level());
        }
        assertEquals(TileStatus.Level.IDLE, PerfFormatter.worst(cells));
        assertEquals(cells, PerfFormatter.cells(null));
        assertEquals("n/a", PerfFormatter.summary(null));
        assertEquals("n/a", PerfFormatter.summary(SwarmSample.UNKNOWN));
    }

    @Test public void healthyClientReadsOk() {
        SwarmSample s = perf(12, 200, 35, 812, 2048);
        List<PerfFormatter.Cell> cells = PerfFormatter.cells(s);
        assertEquals("path 12 ms", cells.get(0).text());
        assertEquals("20.0 tps", cells.get(1).text());
        assertEquals("ping 35 ms", cells.get(2).text());
        assertEquals("ram 812/2048 MB", cells.get(3).text());
        assertEquals(TileStatus.Level.OK, PerfFormatter.worst(cells));
        assertEquals("path 12 ms · 20.0 tps · ping 35 ms · ram 812/2048 MB", PerfFormatter.summary(s));
    }

    @Test public void pathThresholds() {
        assertEquals(TileStatus.Level.OK, PerfFormatter.path(perf(499, -1, -1, -1, -1)).level());
        assertEquals(TileStatus.Level.WARN, PerfFormatter.path(perf(500, -1, -1, -1, -1)).level());
        assertEquals(TileStatus.Level.BAD, PerfFormatter.path(perf(2000, -1, -1, -1, -1)).level());
        assertEquals("path 0 ms", PerfFormatter.path(perf(0, -1, -1, -1, -1)).text());
    }

    @Test public void tickRateThresholdsAndDecimal() {
        assertEquals("19.7 tps", PerfFormatter.tps(perf(-1, 197, -1, -1, -1)).text());
        assertEquals("0.5 tps", PerfFormatter.tps(perf(-1, 5, -1, -1, -1)).text());
        assertEquals(TileStatus.Level.OK, PerfFormatter.tps(perf(-1, 150, -1, -1, -1)).level());
        assertEquals(TileStatus.Level.WARN, PerfFormatter.tps(perf(-1, 149, -1, -1, -1)).level());
        assertEquals(TileStatus.Level.WARN, PerfFormatter.tps(perf(-1, 100, -1, -1, -1)).level());
        assertEquals(TileStatus.Level.BAD, PerfFormatter.tps(perf(-1, 99, -1, -1, -1)).level());
    }

    @Test public void pingThresholds() {
        assertEquals(TileStatus.Level.OK, PerfFormatter.ping(perf(-1, -1, 150, -1, -1)).level());
        assertEquals(TileStatus.Level.WARN, PerfFormatter.ping(perf(-1, -1, 151, -1, -1)).level());
        assertEquals(TileStatus.Level.WARN, PerfFormatter.ping(perf(-1, -1, 400, -1, -1)).level());
        assertEquals(TileStatus.Level.BAD, PerfFormatter.ping(perf(-1, -1, 401, -1, -1)).level());
    }

    @Test public void ramThresholdsAndUnknownMax() {
        assertEquals(TileStatus.Level.OK, PerfFormatter.ram(perf(-1, -1, -1, 84, 100)).level());
        assertEquals(TileStatus.Level.WARN, PerfFormatter.ram(perf(-1, -1, -1, 85, 100)).level());
        assertEquals(TileStatus.Level.BAD, PerfFormatter.ram(perf(-1, -1, -1, 95, 100)).level());
        PerfFormatter.Cell noMax = PerfFormatter.ram(perf(-1, -1, -1, 640, -1));
        assertEquals("ram 640 MB", noMax.text());
        assertEquals(TileStatus.Level.OK, noMax.level());
    }

    @Test public void worstPicksTheWorstKnownLevel() {
        assertEquals(TileStatus.Level.BAD, PerfFormatter.worst(PerfFormatter.cells(perf(10, 50, 20, 100, 1000))));
        assertEquals(TileStatus.Level.WARN, PerfFormatter.worst(PerfFormatter.cells(perf(-1, -1, 200, -1, -1))));
        assertEquals(TileStatus.Level.OK, PerfFormatter.worst(PerfFormatter.cells(perf(-1, -1, 20, -1, -1))));
    }

    @Test public void partialSampleShowsKnownAndNa() {
        assertEquals("path n/a · tps n/a · ping 20 ms · ram n/a", PerfFormatter.summary(perf(-1, -1, 20, -1, -1)));
    }
}
