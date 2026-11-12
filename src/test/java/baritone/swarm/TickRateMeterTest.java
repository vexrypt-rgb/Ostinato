package baritone.swarm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ostinato: client tick rate for the performance strip. */
public class TickRateMeterTest {

    @Test public void notEnoughDataIsUnknown() {
        TickRateMeter m = new TickRateMeter();
        assertEquals(-1, m.tpsX10(0));
        m.tick(0);
        assertEquals(-1, m.tpsX10(0));
        m.tick(50);
        assertEquals(-1, m.tpsX10(50)); // span under the 1 s minimum
    }

    @Test public void steadyTwentyTicksPerSecond() {
        TickRateMeter m = new TickRateMeter();
        for (long t = 0; t <= 5_000; t += 50) {
            m.tick(t);
        }
        assertEquals(200, m.tpsX10(5_000));
    }

    @Test public void halfSpeedReadsTen() {
        TickRateMeter m = new TickRateMeter();
        for (long t = 0; t <= 4_000; t += 100) {
            m.tick(t);
        }
        assertEquals(100, m.tpsX10(4_000));
    }

    @Test public void stallDragsTheRateDown() {
        TickRateMeter m = new TickRateMeter();
        for (long t = 0; t <= 2_000; t += 50) {
            m.tick(t);
        }
        assertEquals(200, m.tpsX10(2_000));
        assertEquals(100, m.tpsX10(4_000)); // no ticks for 2 s
        assertEquals(-1, m.tpsX10(20_000)); // every tick aged out
    }

    @Test public void runawayCallerIsBounded() {
        TickRateMeter m = new TickRateMeter();
        for (int i = 0; i < 10_000; i++) {
            m.tick(i / 10); // ten ticks a millisecond
        }
        // only the last 4096 ticks are kept (590..999 ms); a span that short reads unknown, never an absurd rate
        assertEquals(-1, m.tpsX10(999));
        TickRateMeter loose = new TickRateMeter(5_000, 100);
        for (int i = 0; i < 10_000; i++) {
            loose.tick(i / 10);
        }
        assertEquals(Math.round(4095 * 10_000.0 / (999 - 590)), loose.tpsX10(999));
    }
}
