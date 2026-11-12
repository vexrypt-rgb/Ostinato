package baritone.swarm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: the STS sample body. Older and newer peers must never break each other. */
public class SwarmSampleTest {

    private static final SwarmSample FULL = new SwarmSample(12, 199, 35, 812, 2048, 3, 35, 1, 2, "/msg");

    @Test public void roundTrip() {
        String body = FULL.encode();
        assertEquals("p=12;t=199;g=35;m=812/2048;w=3/35;d=1;r=2;c=/msg", body);
        assertEquals(FULL, SwarmSample.parse(body));
    }

    @Test public void unknownEncodesEmptyAndParsesBack() {
        assertEquals("", SwarmSample.UNKNOWN.encode());
        assertTrue(SwarmSample.UNKNOWN.isUnknown());
        assertFalse(FULL.isUnknown());
        assertEquals(SwarmSample.UNKNOWN, SwarmSample.parse(""));
        assertEquals(SwarmSample.UNKNOWN, SwarmSample.parse("-"));
        assertEquals(SwarmSample.UNKNOWN, SwarmSample.parse(null));
    }

    @Test public void partialSampleKeepsOnlyKnownKeys() {
        SwarmSample s = new SwarmSample(-1, 200, -1, 500, -1, -1, -1, -1, -1, "");
        assertEquals("t=200;m=500/-1", s.encode());
        assertEquals(s, SwarmSample.parse(s.encode()));
    }

    @Test public void garbageNeverThrows() {
        String[] bad = {"p=abc", "p=-5", "p=1e3", "t=", "=3", ";;;", "m=1/x", "w=/", "p=12345678901",
                "m=/", "g=0x10", "\u0000", "p=1;p", "r=99999999999999999999", "m=1/2/3", "c="};
        for (String b : bad) {
            SwarmSample s = SwarmSample.parse(b);
            assertTrue(b, s.pathMs() <= 99_999_999L && s.retried() <= 99_999_999L);
        }
        assertEquals(-1, SwarmSample.parse("p=abc").pathMs());
        assertEquals(-1, SwarmSample.parse("p=-5").pathMs());
        assertEquals(-1, SwarmSample.parse("p=12345678901").pathMs());
        assertEquals(-1, SwarmSample.parse("m=1/x").ramMaxMb());
        assertEquals(1, SwarmSample.parse("m=1/x").ramUsedMb());
        assertEquals(64, SwarmSample.parse("m=64").ramUsedMb());
        assertEquals(-1, SwarmSample.parse("m=64").ramMaxMb());
    }

    @Test public void unknownKeysAreIgnored() {
        SwarmSample s = SwarmSample.parse("p=7;z=what;future=1/2;g=40");
        assertEquals(7, s.pathMs());
        assertEquals(40, s.pingMs());
        assertEquals(-1, s.tpsX10());
    }

    @Test public void valuesAreBounded() {
        SwarmSample s = new SwarmSample(-7, 100_000_000, 5, -2, 99_999_999, 0, 0, Long.MAX_VALUE, -1, null);
        assertEquals(-1, s.pathMs());
        assertEquals(-1, s.tpsX10());
        assertEquals(5, s.pingMs());
        assertEquals(-1, s.ramUsedMb());
        assertEquals(99_999_999, s.ramMaxMb());
        assertEquals(-1, s.dropped());
        assertEquals("", s.command());
    }

    @Test public void commandCannotForgeFields() {
        SwarmSample s = SwarmSample.UNKNOWN.withLink(1, 2, 0, 0, "/m;p=1 §c=x\u007f-and-a-long-tail");
        assertEquals(16, s.command().length());
        assertFalse(s.command().contains(";"));
        assertFalse(s.command().contains("="));
        assertFalse(s.command().contains(" "));
        assertFalse(s.command().contains("§"));
        assertEquals(s, SwarmSample.parse(s.encode()));
        assertEquals(-1, SwarmSample.parse(s.encode()).pathMs());
    }

    @Test public void commandOfTemplate() {
        assertEquals("", SwarmSample.commandOf(null));
        assertEquals("/msg", SwarmSample.commandOf("/msg {to} {msg}"));
        assertEquals("/w", SwarmSample.commandOf("/w {to} {msg}"));
        assertEquals("/tell", SwarmSample.commandOf("  /tell {to} {msg}"));
        assertEquals("chat", SwarmSample.commandOf("{msg}"));
        assertEquals("/pc", SwarmSample.commandOf("/pc {msg}"));
    }

    @Test public void withLinkKeepsPerf() {
        SwarmSample s = new SwarmSample(9, 200, 20, 100, 200, -1, -1, -1, -1, "").withLink(4, 35, 0, 1, "/msg");
        assertEquals(9, s.pathMs());
        assertEquals(4, s.chatUsed());
        assertEquals(35, s.chatCap());
        assertEquals(1, s.retried());
        assertEquals("/msg", s.command());
    }
}
