package baritone.spectate;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/** Ostinato: cheater detection flags only clear, repeated violations. */
public class CheatWatchTest {

    /** A legit player: walking, a few hits at 2.5-3.05, human click gaps. */
    private static void legit(CheatWatch w, int id, int foe, Random r) {
        for (int t = 1; t <= 600; t++) {
            w.move(id, t, t * 0.2, 64, 0, 0, true, false, false);
            w.move(foe, t, t * 0.2, 64, 2.5 + (t % 30 == 1 ? 0 : 0.3 * Math.min(1, (t % 30) / 3.0)), 0, true, false, t % 30 < 10);
            if (r.nextInt(3) == 0) w.swing(id, t);
            if (t % 30 == 0) w.hit(id, foe, t, 2.5 + r.nextDouble() * 0.55, r.nextDouble() * 30, 3 + r.nextInt(10));
        }
    }

    @Test
    public void legitPlayerIsNotFlagged() {
        CheatWatch w = new CheatWatch();
        legit(w, 1, 2, new Random(7));
        assertNull(w.reason(1));
    }

    @Test
    public void reachNeedsRepeats() {
        CheatWatch w = new CheatWatch();
        for (int i = 0; i < 3; i++) w.hit(1, 2, 10 + i * 20, 3.6, 5, 10);
        assertNull("three long hits are not enough", w.reason(1));
        w.hit(1, 2, 100, 3.3, 5, 10);
        assertTrue(w.reason(1).startsWith("reach4x3.60"));
        // a few long hits among many normal ones stay quiet (lag)
        CheatWatch v = new CheatWatch();
        for (int i = 0; i < 40; i++) v.hit(1, 2, i * 20, i % 8 == 0 ? 3.4 : 2.8, 5, 10);
        assertNull(v.reason(1));
        // exactly 3.1 is still allowed
        CheatWatch u = new CheatWatch();
        for (int i = 0; i < 10; i++) u.hit(1, 2, i * 20, 3.1, 5, 10);
        assertNull(u.reason(1));
    }

    @Test
    public void hitsOutsideViewAndSnaps() {
        CheatWatch w = new CheatWatch();
        for (int i = 0; i < 3; i++) w.hit(1, 2, i * 20, 2.5, 120, 10); // hitting someone behind them
        assertTrue(w.reason(1).contains("aim3x"));
        CheatWatch s = new CheatWatch();
        for (int i = 0; i < 3; i++) {
            int t = 100 + i * 40;
            s.move(1, t - 1, 0, 64, 0, 0, true, false, false);
            s.move(1, t, 0, 64, 0, 170, true, false, false); // 170 degrees in one tick, then the hit
            s.hit(1, 2, t, 2.5, 10, 10);
        }
        assertTrue(s.reason(1).contains("aim3x"));
        assertEquals(90, CheatWatch.offAngle(0, 0, 0, -1, 0), 1e-6); // yaw 0 faces +z; -x is 90 degrees off... to the right
        assertEquals(0, CheatWatch.offAngle(0, 0, 0, 0, 5), 1e-6);
        assertEquals(180, CheatWatch.offAngle(0, 0, 0, 0, -5), 1e-6);
    }

    @Test
    public void autoclickerAndRate() {
        CheatWatch w = new CheatWatch();
        for (int t = 0; t < 3 * 31 * 2 + 4; t += 2) w.swing(1, t); // exactly every other tick: 10 CPS like a metronome
        assertTrue(w.reason(1), w.reason(1).contains("cps"));
        CheatWatch h = new CheatWatch();
        Random r = new Random(3);
        for (int t = 0; t < 2000; t += 1 + r.nextInt(4)) h.swing(1, t); // a fast but uneven human
        assertNull(h.reason(1));
        CheatWatch f = new CheatWatch();
        for (int s = 0; s < 3; s++) for (int t = 0; t < 20; t++) f.swing(1, s * 100 + t); // 20 swings a second, three times
        assertTrue(f.reason(1).contains("cps"));
    }

    @Test
    public void flightAndSpeed() {
        CheatWatch w = new CheatWatch();
        for (int t = 1; t <= 60; t++) w.move(1, t, t * 0.1, 80, 0, 0, false, false, false); // hovering for 3 s
        assertTrue(w.reason(1).contains("fly"));
        CheatWatch e = new CheatWatch();
        for (int t = 1; t <= 200; t++) e.move(1, t, t * 0.1, 80, 0, 0, false, true, false); // the same on an elytra
        assertNull(e.reason(1));
        CheatWatch s = new CheatWatch();
        for (int t = 1; t <= 30; t++) s.move(1, t, t * 1.2, 64, 0, 0, true, false, false); // 24 blocks a second on foot
        assertTrue(s.reason(1).contains("speed"));
        CheatWatch k = new CheatWatch();
        for (int t = 1; t <= 30; t++) k.move(1, t, t * 1.2, 64, 0, 0, true, false, t < 25); // knocked about, not cheating
        assertNull(k.reason(1));
        CheatWatch tp = new CheatWatch();
        for (int t = 1; t <= 30; t++) tp.move(1, t, t * 20, 64, 0, 0, true, false, false); // server teleports
        assertNull(tp.reason(1));
    }

    @Test
    public void noKnockback() {
        CheatWatch w = new CheatWatch();
        for (int t = 1; t <= 400; t++) {
            w.move(2, t, 5, 64, 5, 0, true, false, false); // never moves, even when hit
            if (t % 40 == 0) w.hit(1, 2, t, 2.5, 5, 10);
        }
        assertTrue(w.reason(2).startsWith("nokb"));
        CheatWatch n = new CheatWatch();
        double x = 5;
        for (int t = 1; t <= 400; t++) {
            if (t % 40 > 0 && t % 40 <= 3) x += 0.4; // pushed back by each hit
            n.move(2, t, x, 64, 5, 0, true, false, false);
            if (t % 40 == 0) n.hit(1, 2, t, 2.5, 5, 10);
        }
        assertNull(n.reason(2));
    }

    @Test
    public void inhumanReaction() {
        CheatWatch w = new CheatWatch();
        for (int i = 0; i < 10; i++) {
            int t = 100 + i * 40;
            w.move(1, t - 1, 0, 64, 0, 0, true, false, false);
            w.move(1, t, 0, 64, 0, 45, true, false, false);
            w.hit(1, 2, t, 2.5, 10, 0); // turned and hit the tick they came into view
        }
        assertTrue(w.reason(1).contains("react"));
        CheatWatch h = new CheatWatch();
        for (int i = 0; i < 10; i++) h.hit(1, 2, 100 + i * 40, 2.5, 10, 4); // 200 ms
        assertNull(h.reason(1));
    }
}
