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


package baritone.swarm;

import baritone.swarm.crypto.SigilException;
import baritone.swarm.crypto.SigilCodec;
import baritone.swarm.crypto.SigilWire;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwarmConfigTest {

    @Test
    public void defaultsMatchSettingDefaults() {
        SwarmConfig c = SwarmConfig.defaults();
        assertEquals(256, c.maxLineChars());
        assertEquals(22, c.lineReserveChars());
        assertEquals(234, c.sealLineBudget());
        assertEquals(SigilWire.S2, c.wire());
        assertTrue(c.requireSignedSender());
        // auto: every line is sealed S2S by default, so the frame budget is the S2S one (signature trailer)
        assertEquals(SigilCodec.maxSingleLinePayloadBytes(SigilWire.S2S, 234), c.maxFrameBytes());
        SwarmConfig.Builder unsigned = SwarmConfig.builder();
        unsigned.requireSignedSender = false;
        assertEquals(139, unsigned.build().maxFrameBytes()); // largest S2C payload sealSingle accepts at 234
        SigilWire.parse("S2"); // default setting value
        SwarmConfig.Builder s1 = SwarmConfig.builder();
        s1.wireVersion = "S1";
        s1.requireSignedSender = false;
        assertEquals(SigilWire.S1, s1.build().wire());
        assertEquals(135, s1.build().maxFrameBytes());
        SwarmConfig.Builder junk = SwarmConfig.builder();
        junk.wireVersion = "plaintext";
        assertEquals(SigilWire.S2, junk.build().wire()); // no plaintext mode: unknown values fall back to S2
        assertEquals(32, c.maxChunks()); // signed command/cancel envelopes need more than eight frames.
        assertEquals(30000L, c.reassemblyTimeoutMs());
        assertEquals(64, c.maxPendingMessages());
        assertEquals(256, c.replayWindow());
        assertEquals(0, c.maxClockSkewSec());
        assertEquals(64, c.maxPeers());
        assertEquals("", c.localSpoolDir());
    }

    @Test
    public void autoFrameBudgetIsExactlyTheSingleLineLimit() throws Exception {
        for (SigilWire wire : SigilWire.values()) {
            if (wire == SigilWire.S2S) {
                continue; // signed: needs a signet, budget covered by the S2S tests
            }
            for (int line : Arrays.asList(96, 150, 234, 256)) {
                int n = SigilCodec.maxSingleLinePayloadBytes(wire, line);
                String fits = repeat('a', n);
                String sealed = SigilCodec.sealSingle(wire, TestCircles.alpha(), fits, line);
                assertTrue(sealed.length() <= line);
                try {
                    SigilCodec.sealSingle(wire, TestCircles.alpha(), repeat('a', n + 1), line);
                    fail(wire + ": n+1 bytes must not fit one line at " + line);
                } catch (SigilException expected) {
                }
            }
        }
    }

    @Test
    public void clampsOutOfRangeValues() {
        SwarmConfig.Builder b = SwarmConfig.builder();
        b.maxLineChars = 10_000;
        b.lineReserveChars = -5;
        b.maxFrameBytes = 1;
        b.maxChunks = 0;
        b.reassemblyTimeoutMs = 1;
        b.maxPendingMessages = -1;
        b.replayWindow = 1;
        b.maxClockSkewSec = 1;
        b.maxPeers = 0;
        b.localSpoolDir = null;
        SwarmConfig c = b.build();
        assertEquals(256, c.maxLineChars());
        assertEquals(0, c.lineReserveChars());
        assertEquals(SwarmConfig.MIN_FRAME_BYTES, c.maxFrameBytes());
        assertEquals(1, c.maxChunks());
        assertEquals(1000L, c.reassemblyTimeoutMs());
        assertEquals(1, c.maxPendingMessages());
        assertEquals(16, c.replayWindow());
        assertEquals(5, c.maxClockSkewSec());
        assertEquals(1, c.maxPeers());
        assertEquals("", c.localSpoolDir());

        SwarmConfig.Builder big = SwarmConfig.builder();
        big.maxFrameBytes = 100_000;
        big.requireSignedSender = false;
        assertEquals(139, big.build().maxFrameBytes()); // never above what fits one line
    }

    static String repeat(char ch, int n) {
        char[] c = new char[n];
        Arrays.fill(c, ch);
        return new String(c);
    }

    static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
