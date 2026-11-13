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

package baritone.swarm.world;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Ostinato: Phase 2c. The SWO codec: keys, not block lists; malformed bodies are dropped whole. */
public class ObservationMessageTest {

    private static ObservationMessage.Entry e(String key, String detail, long age, int conf) {
        return new ObservationMessage.Entry(key, detail, age, conf);
    }

    @Test
    public void roundTripKeepsEveryField() {
        ObservationMessage m = new ObservationMessage(ObservationMessage.Op.R, "srv.p2c-1", Arrays.asList(
                e("v:o:0,10,0:1,11,1", "iron_ore.4", 1200, 90),
                e("c:o:5,64,7", "chest", 0, 100),
                e("w:n:fortress_door", "fortress", 30, 80),
                e("e:o:zombie-12", "zombie@3,64,9", 10, 70)));
        String body = m.encode();
        assertEquals("1|R|srv.p2c-1|v:o:0,10,0:1,11,1~iron_ore.4~1200~90;c:o:5,64,7~chest~0~100;"
                + "w:n:fortress_door~fortress~30~80;e:o:zombie-12~zombie@3,64,9~10~70", body);
        ObservationMessage d = ObservationMessage.decode(body);
        assertNotNull(d);
        assertEquals(ObservationMessage.Op.R, d.op);
        assertEquals("srv.p2c-1", d.worldId);
        assertEquals(4, d.entries.size());
        assertEquals("iron_ore.4", d.entries.get(0).detail);
        assertEquals(1200, d.entries.get(0).ageMs);
        assertEquals(90, d.entries.get(0).confidence);
        assertEquals("e:o:zombie-12", d.entries.get(3).key);
    }

    @Test
    public void queryAndEndCarryNoEntries() {
        String q = new ObservationMessage(ObservationMessage.Op.Q, "w1", Collections.emptyList()).encode();
        assertEquals("1|Q|w1|-", q);
        assertNotNull(ObservationMessage.decode(q));
        assertNull("Q with entries", ObservationMessage.decode("1|Q|w1|b:o:1,2,3~stone~0~90"));
        assertNull("E with entries", ObservationMessage.decode("1|E|w1|b:o:1,2,3~stone~0~90"));
    }

    @Test
    public void malformedBodiesAreDropped() {
        assertNull("version", ObservationMessage.decode("2|R|w1|b:o:1,2,3~stone~0~90"));
        assertNull("op", ObservationMessage.decode("1|X|w1|b:o:1,2,3~stone~0~90"));
        assertNull("fields", ObservationMessage.decode("1|R|w1"));
        assertNull("negative age", ObservationMessage.decode("1|R|w1|b:o:1,2,3~stone~-5~90"));
        assertNull("confidence over 100", ObservationMessage.decode("1|R|w1|b:o:1,2,3~stone~0~101"));
        assertNull("entry fields", ObservationMessage.decode("1|R|w1|b:o:1,2,3~stone~0"));
        assertNull("bad world", ObservationMessage.decode("1|R|w 1|b:o:1,2,3~stone~0~90"));
        assertNull("non-canonical vein key", ObservationMessage.decode("1|R|w1|v:o:1,11,1:0,10,0~iron_ore.4~0~90"));
        assertNull("bad dimension", ObservationMessage.decode("1|R|w1|b:x:1,2,3~stone~0~90"));
        assertNull("bad detail", ObservationMessage.decode("1|R|w1|b:o:1,2,3~st one~0~90"));
        assertNull("too long", ObservationMessage.decode("1|R|w1|" + new String(new char[4001]).replace('\0', 'a')));
        // Ostinato: a well-formed body is dropped once it passes the limit, and kept up to it
        String fits = "1|R|w1|b:o:1,2,3~stone~0~90";
        String over = fits;
        while (over.length() <= ObservationMessage.MAX_BODY) {
            fits = over;
            over = over + ";b:o:1,2,3~stone~0~90";
        }
        assertNotNull("within the limit", ObservationMessage.decode(fits));
        assertNull("well-formed but too long", ObservationMessage.decode(over));
        assertNull(ObservationMessage.decode(null));
    }

    @Test
    public void keyKinds() {
        assertEquals(ObservationRecord.Kind.VEIN, ObservationRecord.kindOf("v:o:0,10,0:1,11,1"));
        assertEquals(ObservationRecord.Kind.CHEST, ObservationRecord.kindOf("c:n:5,64,7"));
        assertEquals(ObservationRecord.Kind.BLOCK, ObservationRecord.kindOf("b:e:-1,2,-3"));
        assertEquals(ObservationRecord.Kind.STRUCTURE, ObservationRecord.kindOf("s:o:100,64,-20"));
        assertEquals(ObservationRecord.Kind.WAYPOINT, ObservationRecord.kindOf("w:o:base"));
        assertEquals(ObservationRecord.Kind.ENTITY, ObservationRecord.kindOf("e:o:cow-1"));
        assertNull("region keys are leases, not observations", ObservationRecord.kindOf("r:o:0,0,0:1,1,1"));
        assertNull(ObservationRecord.kindOf("b:o:1,2"));
        assertNull(ObservationRecord.kindOf("w:o:"));
        assertNull(ObservationRecord.kindOf("v:o:1,11,1:0,10,0"));
        assertNull("unknown dimension", ObservationRecord.kindOf("b:x:1,2,3"));
        assertNull("unknown dimension", ObservationRecord.kindOf("w:q:base"));
    }

    @Test
    public void packSplitsUnderTheLimitAndKeepsEveryEntry() {
        List<ObservationMessage.Entry> in = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            in.add(e("b:o:" + i + ",64," + (-i), "deepslate_iron_ore", 123456, 90));
        }
        List<ObservationMessage> out = ObservationMessage.pack(ObservationMessage.Op.R, "w1", in,
                ObservationMessage.MAX_BODY);
        assertTrue("needs more than one body", out.size() > 1);
        int total = 0;
        for (ObservationMessage m : out) {
            String body = m.encode();
            assertTrue(body.length() + " chars", body.length() <= ObservationMessage.MAX_BODY);
            assertNotNull(ObservationMessage.decode(body));
            total += m.entries.size();
        }
        assertEquals(300, total);
        assertEquals("b:o:0,64,0", out.get(0).entries.get(0).key);
    }

    @Test
    public void entryClampsConfidenceAndAge() {
        ObservationMessage.Entry x = e("b:o:1,2,3", "stone", -10, 150);
        assertEquals(0, x.ageMs);
        assertEquals(100, x.confidence);
    }
}
