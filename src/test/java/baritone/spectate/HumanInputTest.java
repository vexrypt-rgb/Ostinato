package baritone.spectate;

import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Ostinato: the human cursor path and typing timings. */
public class HumanInputTest {

    @Test
    public void pathEndsOnTargetWithHumanDurationAndNoTeleport() {
        Random r = new Random(7);
        for (int k = 0; k < 200; k++) {
            double x0 = r.nextInt(800), y0 = r.nextInt(450), x1 = r.nextInt(800), y1 = r.nextInt(450);
            List<double[]> p = HumanInput.cursorPath(x0, y0, x1, y1, r);
            double[] last = p.get(p.size() - 1);
            assertEquals(x1, last[1], 1e-9);
            assertEquals(y1, last[2], 1e-9);
            double dist = Math.hypot(x1 - x0, y1 - y0);
            if (dist > 100) {
                assertTrue("too quick: " + last[0] + " ms for " + dist, last[0] >= 100);
                assertTrue("too slow: " + last[0], last[0] <= 2500);
                assertTrue(p.size() >= 8);
            }
            // no single step jumps more than half the way (no instant warp)
            double px = x0, py = y0;
            for (int i = 0; i < p.size() - 1; i++) {
                double step = Math.hypot(p.get(i)[1] - px, p.get(i)[2] - py);
                assertTrue("step " + step + " of " + dist, step <= Math.max(6, dist * 0.5));
                px = p.get(i)[1];
                py = p.get(i)[2];
            }
        }
    }

    @Test
    public void pathsDiffer() {
        Random r = new Random(1);
        List<double[]> a = HumanInput.cursorPath(0, 0, 400, 200, r), b = HumanInput.cursorPath(0, 0, 400, 200, r);
        double diff = 0;
        for (int i = 0; i < Math.min(a.size(), b.size()) - 1; i++) diff += Math.abs(a.get(i)[2] - b.get(i)[2]);
        assertTrue(diff > 10);
    }

    @Test
    public void typingAndDelaysStayInRange() {
        Random r = new Random(3);
        long[] d = HumanInput.keyDelays("register Abc123xyz Abc123xyz", r, 60, 180);
        assertEquals(28, d.length);
        int long_ = 0;
        long min = Long.MAX_VALUE;
        for (long v : d) {
            assertTrue(v >= 60 && v <= 180 + 60 + 650);
            min = Math.min(min, v);
            if (v > 240) long_++;
        }
        assertTrue(long_ < d.length / 3);
        for (int i = 0; i < 1000; i++) {
            long v = HumanInput.between(r, 250, 900);
            assertTrue(v >= 250 && v <= 900);
        }
        assertEquals(80, HumanInput.between(r, 80, 80));
    }
}
