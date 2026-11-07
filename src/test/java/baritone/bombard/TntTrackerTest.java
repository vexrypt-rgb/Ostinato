package baritone.bombard;

import baritone.bombard.TntTracker.Cause;
import baritone.bombard.TntTracker.Fate;
import baritone.bombard.TntTracker.Tnt;
import org.junit.Test;

import static org.junit.Assert.*;

public class TntTrackerTest {
    @Test public void reopeningAnOldFightDoesNotSubtractThisFightsFates() {
        TntTracker tr = new TntTracker();
        at(tr, 1); see(tr, 1, 3, 20); tr.end();
        at(tr, 2); tr.end();
        tr.clearCounters();
        at(tr, 3); see(tr, 2, 4, 20); tr.end();
        at(tr, 4); tr.end();
        assertEquals(1, tr.fate(Fate.GONE_EARLY));
        at(tr, 5); see(tr, 1, 3, 16); tr.end();
        assertEquals(1, tr.fate(Fate.GONE_EARLY));
    }
    private static final double BY = 64 + TntMechanics.BLAST_Y;

    private static void at(TntTracker tr, long tick) {
        tr.begin(tick, 0, 64, 0, 48);
    }

    private static Tnt see(TntTracker tr, int id, double x, int fuse) {
        return tr.see(id, x, 64, 0, 0, 0, 0, fuse, true);
    }

    @Test
    public void aFuseRunningOutWithoutAPacketIsExpiredNotExploded() {
        TntTracker tr = new TntTracker();
        for (int t = 1; t <= 3; t++) {
            at(tr, t);
            see(tr, 1, 3, 4 - t);
            tr.end();
        }
        at(tr, 4);
        tr.end();
        Tnt t = tr.get(1);
        assertEquals(Fate.EXPIRED, t.fate());
        assertEquals(4, t.endTick());
        assertEquals(1, tr.fate(Fate.EXPIRED));
        assertEquals(0, tr.fate(Fate.EXPLODED));
        // the packet a tick late turns it into an observed explosion, counted once
        at(tr, 5);
        assertSame(t, tr.explosion(3, BY, 0, 4, 12, true));
        assertEquals(Fate.EXPLODED, t.fate());
        assertEquals(0, tr.fate(Fate.EXPIRED));
        assertEquals(1, tr.fate(Fate.EXPLODED));
        assertTrue(t.hitUs());
        assertEquals(4, t.observedPower(), 0);
    }

    @Test
    public void aPacketBeforeTheClientsDiscardMatchesTheLiveTnt() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 2);
        tr.end();
        at(tr, 2);
        Tnt t = tr.explosion(3, BY, 0, 4, 0, false);
        assertNotNull(t);
        // still in the client's world this tick: a sighting does not bring it back to life
        assertSame(t, see(tr, 1, 3, 1));
        tr.end();
        assertEquals(Fate.EXPLODED, t.fate());
        assertEquals(1, tr.fate(Fate.EXPLODED));
        assertTrue(tr.live().isEmpty());
    }

    @Test
    public void anExplosionNoTntExplainsIsUnattributed() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 60);
        tr.end();
        at(tr, 2);
        assertNull(tr.explosion(3, BY, 0, 4, 0, false)); // right place, 59 ticks early
        assertNull(tr.explosion(20, BY, 0, 4, 0, false)); // nothing there
        assertEquals(Fate.LIVE, tr.get(1).fate());
        assertTrue(tr.report("t").contains("unattributed=2"));
    }

    @Test
    public void aMovingTntIsMatchedFurtherAlongItsPath() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        tr.see(1, 0, 64, 0, 0.5, 0, 0, 1, false);
        tr.end();
        at(tr, 2);
        tr.end();
        // 2.5 blocks on: beyond MATCH_DIST alone, within it plus the speed it had
        assertNotNull(tr.explosion(2.5, BY, 0, 4, 0, false));
    }

    @Test
    public void vanishingEarlyIsGoneEarlyAndAtTheEdgeIsLost() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 50);
        see(tr, 2, 47, 50);
        tr.end();
        at(tr, 2);
        tr.end();
        assertEquals(Fate.GONE_EARLY, tr.get(1).fate());
        assertEquals(Fate.LOST, tr.get(2).fate());
    }

    @Test
    public void aMisjudgedEndIsReopenedAndNotCountedTwice() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 50);
        tr.end();
        at(tr, 2);
        tr.end(); // missed a tick's sighting
        assertEquals(1, tr.fate(Fate.GONE_EARLY));
        at(tr, 3);
        Tnt t = see(tr, 1, 3, 48);
        tr.end();
        assertEquals(Fate.LIVE, t.fate());
        assertEquals(0, tr.fate(Fate.GONE_EARLY));
        assertTrue(tr.report("t").startsWith("bombard tnt t seen=1 "));
    }

    @Test
    public void reopeningAfterTheCountersClearedNeverGoesNegative() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 50);
        tr.end();
        at(tr, 2);
        tr.end();
        tr.clearCounters();
        at(tr, 3);
        see(tr, 1, 3, 48);
        tr.end();
        assertEquals(0, tr.fate(Fate.GONE_EARLY));
    }

    @Test
    public void tntWhereWeLitABlockIsOurs() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        tr.ignited(10, 64, 10);
        tr.end();
        at(tr, 2);
        Tnt ours = tr.see(1, 10.5, 64.2, 10.5, 0.01, 0.2, 0.01, 79, false);
        Tnt late = tr.see(2, 10.5, 64.0, 10.5, 0, 0, 0, 30, true); // the right place, a fuse it cannot have
        Tnt away = tr.see(3, 14.5, 64.0, 10.5, 0, 0, 0, 79, true);
        tr.end();
        assertEquals(Cause.OURS, ours.cause());
        assertEquals(Cause.UNKNOWN, late.cause());
        assertEquals(Cause.UNKNOWN, away.cause());
        assertTrue(tr.report("t").contains("ours=1"));
    }

    @Test
    public void tntLitJustAfterABlastNearItIsChain() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        assertNull(tr.explosion(0, 64, 0, 4, 20, false));
        tr.end();
        at(tr, 2);
        Tnt chain = see(tr, 1, 3, 20);
        Tnt far = see(tr, 2, 20, 20);
        Tnt longFuse = see(tr, 3, 4, 75);
        tr.end();
        assertEquals(Cause.CHAIN, chain.cause());
        assertEquals(Cause.UNKNOWN, far.cause());
        assertEquals(Cause.UNKNOWN, longFuse.cause());
        // too long after the blast
        at(tr, 10);
        assertEquals(Cause.UNKNOWN, see(tr, 4, 3, 20).cause());
    }

    @Test
    public void capacityIsBoundedAndEndedTntAreEvictedFirst() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        for (int i = 0; i < TntTracker.CAPACITY; i++) see(tr, i, 3, 50);
        assertNull(see(tr, 999, 3, 50));
        tr.end();
        at(tr, 2);
        for (int i = 1; i < TntTracker.CAPACITY; i++) see(tr, i, 3, 49);
        tr.end(); // id 0 has gone
        at(tr, 3);
        assertNotNull(see(tr, 999, 3, 50));
        assertNull(tr.get(0));
        assertEquals(TntTracker.CAPACITY, tr.all().size());
        assertTrue(tr.report("t").contains("overflow=1"));
    }

    @Test
    public void endedTntAreDroppedAfterRetain() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 50);
        tr.end();
        at(tr, 2);
        tr.end();
        at(tr, 2 + TntTracker.RETAIN);
        tr.end();
        assertNotNull(tr.get(1));
        at(tr, 3 + TntTracker.RETAIN);
        tr.end();
        assertNull(tr.get(1));
        assertEquals(1, tr.fate(Fate.GONE_EARLY)); // still counted
    }

    @Test
    public void aForecastIsSettledAgainstTheObservedBlast() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 3);
        tr.forecast(1, 4, 3.5, BY, 0, "EXACT");
        tr.forecast(1, 9, 9, 9, 9, "EXACT"); // one forecast per TNT
        tr.end();
        for (int t = 2; t <= 3; t++) {
            at(tr, t);
            see(tr, 1, 3, 4 - t);
            tr.end();
        }
        at(tr, 4);
        assertNotNull(tr.explosion(3, BY, 0, 4, 0, false));
        tr.end();
        String r = tr.report("t");
        assertTrue(r, r.endsWith("| forecast n=1 settled=1 pos mean=0.500 max=0.500 tick mean=0.00 max=0"));
    }

    @Test
    public void liveIsSoonestFirst() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 50);
        see(tr, 2, 3, 10);
        see(tr, 3, 3, 30);
        assertEquals(3, tr.seenThisTick());
        tr.end();
        assertEquals(2, tr.live().get(0).id);
        assertEquals(3, tr.live().get(1).id);
        assertEquals(1, tr.live().get(2).id);
    }

    @Test
    public void clearingCountersKeepsWhatIsStillBurning() {
        TntTracker tr = new TntTracker();
        at(tr, 1);
        see(tr, 1, 3, 50);
        tr.end();
        tr.clearCounters();
        assertEquals("", tr.report("t"));
        assertEquals(1, tr.live().size());
        tr.forget();
        assertTrue(tr.all().isEmpty());
        assertEquals(1, tr.fate(Fate.LOST));
    }
}
