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

package baritone.swarm.protocol;

import baritone.swarm.SwarmControl;
import baritone.swarm.SwarmControlTest;
import baritone.swarm.SwarmEndpoint;
import baritone.swarm.TestCircles;
import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.InMemorySwarmBus;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Gate: with swarmCommandProtocol off, command frames are ignored like any unknown type,
 * and the existing ping path still completes.
 */
public class Phase1FlagOffTest {

    @Test
    public void flagOffIgnoresCommandFramesLikeUnknownTypes() throws Exception {
        Link link = Link.open();
        assertNull(link.bob.commandProtocol());
        long before = link.bob.ignoredCount();
        link.aliceEp.send("crew", "Bob", "ZZZ", "nope");
        link.bob.tick();
        long afterUnknown = link.bob.ignoredCount();
        assertEquals(before + 1, afterUnknown);
        link.aliceEp.send("crew", "Bob", CommandProtocol.TYPE_CMD, "1|cmd|not-executed");
        link.bob.tick();
        assertEquals(afterUnknown + 1, link.bob.ignoredCount());
        link.aliceEp.send("crew", "Bob", CommandProtocol.TYPE_ACK, "1|ack|nope");
        link.bob.tick();
        link.aliceEp.send("crew", "Bob", CommandProtocol.TYPE_HELLO, "1|hello|nope");
        link.bob.tick();
        assertEquals(afterUnknown + 3, link.bob.ignoredCount());
    }

    @Test
    public void flagOffDoesNotChangeExistingSwarmPing() throws Exception {
        Link link = Link.open();
        long ignored = link.bob.ignoredCount();
        assertEquals(1, link.alice.ping("crew"));
        link.bob.tick();
        link.alice.tick();
        assertNotNull(link.alice.peer("Bob"));
        assertNotNull(link.bob.peer("Alice"));
        assertEquals(ignored, link.bob.ignoredCount());
        assertNull(link.bob.commandProtocol());
    }

    private static final class Link {
        final SwarmEndpoint aliceEp;
        final SwarmControl alice;
        final SwarmControl bob;

        private Link(SwarmEndpoint aliceEp, SwarmControl alice, SwarmControl bob) {
            this.aliceEp = aliceEp;
            this.alice = alice;
            this.bob = bob;
        }

        static Link open() throws Exception {
            AtomicLong clock = new AtomicLong(Phase1Support.T0);
            SwarmRoster roster = SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob lead=Alice\n");
            Map<String, SigilCircle> circles = new LinkedHashMap<>();
            circles.put("crew", TestCircles.alpha());
            InMemorySwarmBus bus = new InMemorySwarmBus();
            SwarmEndpoint aliceEp = new SwarmEndpoint("Alice", SwarmControlTest.unsignedCfg(), circles, bus.register("Alice"), clock::get);
            SwarmEndpoint bobEp = new SwarmEndpoint("Bob", SwarmControlTest.unsignedCfg(), circles, bus.register("Bob"), clock::get);
            aliceEp.setMemberCheck(roster::isMember);
            bobEp.setMemberCheck(roster::isMember);
            SwarmControl.LocalStatus aliceStatus = status("Alice");
            SwarmControl.LocalStatus bobStatus = status("Bob");
            SwarmControl alice = new SwarmControl(roster, aliceEp, aliceStatus, clock::get, s -> { }, null);
            SwarmControl bob = new SwarmControl(roster, bobEp, bobStatus, clock::get, s -> { }, null);
            return new Link(aliceEp, alice, bob);
        }

        private static SwarmControl.LocalStatus status(String name) {
            return new SwarmControl.LocalStatus() {
                @Override public String name() { return name; }
                @Override public String position() { return "0,64,0"; }
                @Override public String process() { return "idle"; }
            };
        }
    }
}
