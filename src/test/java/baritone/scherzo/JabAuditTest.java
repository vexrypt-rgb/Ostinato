/*
 * Ostinato: new file (Scherzo predictive combat). Not part of upstream Baritone.
 */
package baritone.scherzo;

import baritone.scherzo.AttackFeasibility.Verdict;
import org.junit.Test;

import static org.junit.Assert.*;

public class JabAuditTest {

    @Test
    public void flashInsideTheWaitLands() {
        JabAudit a = new JabAudit();
        a.sent(100, Verdict.SURE, Verdict.LIKELY, true, 0);
        a.tick(101, 0, 10);
        a.tick(102, 10, 10);
        assertEquals(1, a.landed[Verdict.SURE.ordinal()]);
        assertEquals(1, a.sent[Verdict.SURE.ordinal()]);
        assertEquals(1, a.bandLanded[Verdict.LIKELY.ordinal()]);
        assertEquals(1, a.total());
    }

    @Test
    public void noFlashIsAMissOnceTheWaitIsOver() {
        JabAudit a = new JabAudit();
        a.sent(0, Verdict.POSSIBLE, Verdict.POSSIBLE, true, 0);
        for (int t = 1; t <= JabAudit.WAIT; t++) a.tick(t, 0, 10);
        assertEquals("still waiting on the last tick of the wait", 0, a.total());
        a.tick(JabAudit.WAIT + 1, 0, 10);
        assertEquals(1, a.total());
        assertEquals(0, a.landed[Verdict.POSSIBLE.ordinal()]);
    }

    @Test
    public void aFlashOnTheSendTickIsNotOurs() {
        JabAudit a = new JabAudit();
        a.sent(5, Verdict.SURE, Verdict.SURE, true, 0);
        a.tick(5, 10, 10);
        a.tick(6, 9, 10); // decaying, not a fresh flash
        for (int t = 7; t <= 5 + JabAudit.WAIT + 1; t++) a.tick(t, 0, 10);
        assertEquals(0, a.landed[Verdict.SURE.ordinal()]);
        assertEquals(1, a.sent[Verdict.SURE.ordinal()]);
    }

    @Test
    public void ledAndHeldAreCounted() {
        JabAudit a = new JabAudit();
        a.held(3);
        a.held(3);
        a.held(4);
        a.sent(10, Verdict.LIKELY, Verdict.LIKELY, false, 0);
        a.tick(12, 10, 10);
        assertEquals(2, a.held);
        assertEquals(1, a.led);
        assertEquals(1, a.ledLanded);
    }

    @Test
    public void abandonSettlesAsMissed() {
        JabAudit a = new JabAudit();
        a.sent(0, Verdict.SURE, Verdict.SURE, true, 0);
        a.abandon();
        assertEquals(1, a.total());
        assertEquals(0, a.landed[Verdict.SURE.ordinal()]);
        a.abandon();
        assertEquals("settled once", 1, a.total());
    }

    @Test
    public void reportListsEveryVerdict() {
        JabAudit a = new JabAudit();
        assertEquals("", a.report("fight"));
        a.sent(0, Verdict.SURE, Verdict.LIKELY, true, 0);
        a.tick(1, 10, 10);
        String r = a.report("fight");
        assertTrue(r, r.startsWith("scherzo jab fight sent=1 ray sure=1/1 likely=0/0 possible=0/0 miss=0/0 unknown=0/0 | band sure=0/0 likely=1/1"));
        assertTrue(r, r.endsWith("led=0/0 held=0"));
        a.clear();
        assertEquals("", a.report("fight"));
    }

    @Test
    public void scherzoModeParsesAndDefaultsToObserve() {
        assertEquals(Scherzo.Mode.LIVE, Scherzo.parse(" live "));
        assertEquals(Scherzo.Mode.OFF, Scherzo.parse("OFF"));
        assertEquals(Scherzo.Mode.OBSERVE, Scherzo.parse("nonsense"));
    }
}
