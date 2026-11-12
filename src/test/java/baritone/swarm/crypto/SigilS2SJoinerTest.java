/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.swarm.crypto;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: multi-line signed S2S frames, sealed with {@link SigilS2S#seal} and joined by {@link SigilS2SJoiner}. */
public class SigilS2SJoinerTest {

    private static final int LINE = 234;

    private static SigilCircle circle;
    private static SigilEd25519 alice;
    private static SigilEd25519 mallory;
    private static List<SigilCircle> ring;
    private static List<SigilEd25519> pins;

    @BeforeClass
    public static void derive() throws Exception {
        circle = SigilCircle.derive("s2s-join-test", "public-s2s-test-passphrase-DO-NOT-USE");
        alice = SigilEd25519.testSignet("alice-DO-NOT-USE");
        mallory = SigilEd25519.testSignet("mallory-DO-NOT-USE");
        ring = Collections.singletonList(circle);
        pins = Collections.singletonList(alice);
    }

    private static String text(int bytes, int seed) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            b.append((char) ('a' + (i * 7 + seed) % 26));
        }
        return b.toString();
    }

    /** Seals, opens every line and joins; returns the text that came out. */
    private static String roundTrip(String s, SigilS2SJoiner j, long now) throws Exception {
        List<String> lines = SigilS2S.seal(circle, alice, s, "", LINE);
        String out = null;
        for (String line : lines) {
            assertTrue(line.length() + " > " + LINE, line.length() <= LINE);
            SigilS2S.Opened o = SigilS2S.open(line, ring, pins);
            if (o.total == 1) {
                assertEquals(1, lines.size());
                out = o.text;
            } else {
                SigilS2SJoiner.Result r = j.offer(o, pins, now);
                if (r.status == SigilS2SJoiner.Status.JOINED) {
                    out = r.text;
                } else {
                    assertEquals(r.reason, SigilS2SJoiner.Status.PENDING, r.status);
                }
            }
        }
        return out;
    }

    @Test
    public void everyLengthRoundTrips() throws Exception {
        SigilS2SJoiner j = new SigilS2SJoiner(30_000L, 64);
        for (int n = 0; n <= 420; n++) {
            String s = text(n, n);
            assertEquals("length " + n, s, roundTrip(s, j, 1_000L));
        }
        assertEquals(0, j.pendingCount());
    }

    /** The lengths that used to make one oversize part (room 133..139 bytes) and multi-byte text around them. */
    @Test
    public void lengthsNearTheOnePartLimitRoundTrip() throws Exception {
        SigilS2SJoiner j = new SigilS2SJoiner(30_000L, 64);
        for (int n = 125; n <= 145; n++) {
            String ascii = text(n, 3);
            assertEquals(ascii, roundTrip(ascii, j, 1_000L));
            String wide = text(n - 4, 5) + "é€"; // 2- and 3-byte code points at the cut
            assertEquals(wide, roundTrip(wide, j, 1_000L));
        }
    }

    @Test
    public void twoLinesHoldMaxPayloadBytes() throws Exception {
        int max = SigilS2S.maxPayloadBytes(LINE, 2);
        assertTrue("max=" + max, max > 2 * SigilS2S.maxSingleLinePayloadBytes(LINE));
        String s = text(max, 1);
        assertEquals(max, s.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(SigilS2S.seal(circle, alice, s, "", LINE).size() <= 2);
        assertEquals(1, SigilS2S.seal(circle, alice, text(SigilS2S.maxPayloadBytes(LINE, 1), 1), "", LINE).size());
    }

    @Test
    public void splicedPartsNeverJoin() throws Exception {
        List<String> a = SigilS2S.seal(circle, alice, text(300, 1), "", LINE);
        List<String> b = SigilS2S.seal(circle, alice, text(300, 2), "", LINE);
        assertTrue(a.size() >= 2 && b.size() == a.size());
        SigilS2SJoiner j = new SigilS2SJoiner(30_000L, 64);
        // First part of A, then the remaining parts of B: neither message completes.
        assertEquals(SigilS2SJoiner.Status.PENDING, j.offer(SigilS2S.open(a.get(0), ring, pins), pins, 0L).status);
        for (int i = 1; i < b.size(); i++) {
            assertEquals(SigilS2SJoiner.Status.PENDING, j.offer(SigilS2S.open(b.get(i), ring, pins), pins, 0L).status);
        }
        assertEquals(2, j.pendingCount());
        assertEquals(2, j.expire(30_000L));
    }

    @Test
    public void tamperedPartBreaksTheSignature() throws Exception {
        List<String> a = SigilS2S.seal(circle, alice, text(300, 1), "", LINE);
        SigilS2SJoiner j = new SigilS2SJoiner(30_000L, 64);
        List<SigilS2S.Opened> parts = new ArrayList<>();
        for (String line : a) {
            parts.add(SigilS2S.open(line, ring, pins));
        }
        byte[] pt = parts.get(0).plaintext; // flip a bit of part 1 after it decrypted
        pt[pt.length - 1] ^= 1;
        SigilS2SJoiner.Result last = null;
        for (SigilS2S.Opened o : parts) {
            last = j.offer(o, pins, 0L);
        }
        assertEquals(SigilS2SJoiner.Status.BAD, last.status);
        assertEquals("signature does not verify", last.reason);
    }

    @Test
    public void unpinnedSignerRejected() throws Exception {
        List<String> m = SigilS2S.seal(circle, mallory, text(300, 4), "", LINE);
        List<SigilEd25519> both = Arrays.asList(alice, mallory);
        SigilS2SJoiner j = new SigilS2SJoiner(30_000L, 64);
        SigilS2SJoiner.Result r = null;
        for (String line : m) {
            r = j.offer(SigilS2S.open(line, ring, both), pins, 0L);
        }
        assertEquals(SigilS2SJoiner.Status.BAD, r.status);
        assertEquals("signer is not a pinned key", r.reason);
    }

    @Test
    public void duplicateAndEchoedPartsAreIgnored() throws Exception {
        List<String> a = SigilS2S.seal(circle, alice, text(300, 6), "", LINE);
        SigilS2SJoiner j = new SigilS2SJoiner(30_000L, 64);
        SigilS2S.Opened p0 = SigilS2S.open(a.get(0), ring, pins);
        assertEquals(SigilS2SJoiner.Status.PENDING, j.offer(p0, pins, 0L).status);
        assertEquals(SigilS2SJoiner.Status.DUPLICATE, j.offer(p0, pins, 0L).status);
        SigilS2SJoiner.Result r = null;
        for (int i = 1; i < a.size(); i++) {
            r = j.offer(SigilS2S.open(a.get(i), ring, pins), pins, 0L);
        }
        assertEquals(SigilS2SJoiner.Status.JOINED, r.status);
        // The chat echo of every line arrives afterwards.
        for (String line : a) {
            assertEquals(SigilS2SJoiner.Status.DUPLICATE, j.offer(SigilS2S.open(line, ring, pins), pins, 5L).status);
        }
        assertEquals(0, j.pendingCount());
    }

    @Test
    public void incompleteMessagesExpire() throws Exception {
        List<String> a = SigilS2S.seal(circle, alice, text(300, 7), "", LINE);
        SigilS2SJoiner j = new SigilS2SJoiner(10_000L, 64);
        j.offer(SigilS2S.open(a.get(0), ring, pins), pins, 1_000L);
        assertEquals(0, j.expire(10_999L));
        assertEquals(1, j.expire(11_000L));
        assertEquals(0, j.pendingCount());
    }
}
