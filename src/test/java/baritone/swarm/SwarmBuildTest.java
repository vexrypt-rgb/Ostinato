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

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.InMemorySwarmBus;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SwarmBuildTest {

    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);
    private final List<String> log = new ArrayList<>();
    private SwarmRoster roster;
    private Map<String, SigilCircle> circles;

    /** Stands in for the builder process: busy until {@link #finish()}. */
    private static final class FakeBuilder implements SwarmBuild.RegionBuilder {
        final List<SwarmBuild.Order> started = new ArrayList<>();
        boolean busy;
        boolean cancelled;
        String refuse;

        @Override
        public void start(SwarmBuild.Order order) throws Exception {
            if (refuse != null) {
                throw new IllegalStateException(refuse);
            }
            started.add(order);
            busy = true;
        }

        @Override
        public boolean busy() { return busy; }

        @Override
        public void cancel() {
            cancelled = true;
            busy = false;
        }

        void finish() { busy = false; }
    }

    @Before
    public void setUp() throws Exception {
        roster = SwarmRoster.parse("group crew circle=swarm-test-alpha members=Alice,Bob,Carol lead=Alice\n");
        circles = new LinkedHashMap<>();
        circles.put("crew", TestCircles.alpha());
    }

    private SwarmControl control(String name, InMemorySwarmBus bus, FakeBuilder b) throws Exception {
        SwarmConfig.Builder cfg = SwarmConfig.builder();
        cfg.requireSignedSender = false;
        SwarmEndpoint e = new SwarmEndpoint(name, cfg.build(), circles, bus.register(name), clock::get);
        e.setMemberCheck(roster::isMember);
        SwarmControl c = new SwarmControl(roster, e, new SwarmControl.LocalStatus() {
            @Override
            public String name() { return name; }
            @Override
            public String position() { return "0,64,0"; }
            @Override
            public String process() { return "idle"; }
        }, clock::get, log::add, null);
        c.enableBuild(b);
        return c;
    }

    private void tickAll(SwarmControl... cs) {
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(100);
            for (SwarmControl c : cs) {
                c.tick();
            }
        }
    }

    @Test
    public void stripsStartEveryMemberAndFinish() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        FakeBuilder ab = new FakeBuilder(), bb = new FakeBuilder(), cb = new FakeBuilder();
        SwarmControl alice = control("Alice", bus, ab), bob = control("Bob", bus, bb), carol = control("Carol", bus, cb);
        alice.build().start("crew", "house.schem", 10, 64, -20, "strips", "auto", 1, 0);
        assertEquals(1, ab.started.size()); // the lead builds region 0 itself
        tickAll(alice, bob, carol);
        assertEquals(1, bb.started.size());
        assertEquals(1, cb.started.size());
        SwarmBuild.Order o = cb.started.get(0);
        assertEquals("house.schem", o.file);
        assertEquals(2, o.index);
        assertEquals(3, o.count);
        assertEquals(-20, o.z);
        assertEquals("building", alice.build().slotState(1));
        ab.finish();
        bb.finish();
        cb.finish();
        tickAll(alice, bob, carol);
        assertFalse(alice.build().jobRunning());
        assertTrue(String.join("\n", log), log.stream().anyMatch(l -> l.contains("finished, 3 region(s)")));
    }

    @Test
    public void stopOfAnEarlierJobLeavesTheCurrentOneRunning() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        FakeBuilder ab = new FakeBuilder(), bb = new FakeBuilder(), cb = new FakeBuilder();
        SwarmControl alice = control("Alice", bus, ab), bob = control("Bob", bus, bb), carol = control("Carol", bus, cb);
        alice.build().start("crew", "house.schem", 0, 64, 0, "strips", "auto", 0, 0);
        tickAll(alice, bob, carol);
        String job = bob.build().myOrder().job;
        bob.build().handle(new SwarmMessage("crew", "Alice", "*", 1, 900, SwarmBuild.STOP, "job=older"));
        assertFalse(bb.cancelled);
        assertNotNull(bob.build().myOrder());
        bob.build().handle(new SwarmMessage("crew", "Alice", "*", 1, 901, SwarmBuild.STOP, "job=" + job));
        assertTrue(bb.cancelled);
        assertNull(bob.build().myOrder());
    }

    @Test
    public void layersWaitForTheBandBelow() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        FakeBuilder ab = new FakeBuilder(), bb = new FakeBuilder(), cb = new FakeBuilder();
        SwarmControl alice = control("Alice", bus, ab), bob = control("Bob", bus, bb), carol = control("Carol", bus, cb);
        alice.build().start("crew", "tower.schem", 0, 64, 0, "layers", "auto", 0, 0);
        tickAll(alice, bob, carol);
        assertEquals(1, ab.started.size());
        assertEquals(0, bb.started.size());
        ab.finish();
        tickAll(alice, bob, carol);
        assertEquals(1, bb.started.size());
        assertEquals(0, cb.started.size());
        bb.finish();
        tickAll(alice, bob, carol);
        assertEquals(1, cb.started.size());
    }

    @Test
    public void onlyTheLeadCanOrder() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        FakeBuilder ab = new FakeBuilder(), bb = new FakeBuilder();
        SwarmControl alice = control("Alice", bus, ab), bob = control("Bob", bus, bb);
        try {
            bob.build().start("crew", "house.schem", 0, 0, 0, "strips", "auto", 1, 0);
            fail("Bob is not the lead");
        } catch (IllegalArgumentException expected) {
            // lead only
        }
        // an order Bob forges anyway is ignored
        bob.endpoint().send("crew", "Alice", SwarmBuild.BUILD,
                new SwarmBuild.Order("x", "house.schem", 0, 0, 0, 0, 2, "strips", "auto", 1, 0).body());
        tickAll(alice);
        assertTrue(ab.started.isEmpty());
    }

    @Test
    public void failureAndStopAreReported() throws Exception {
        InMemorySwarmBus bus = new InMemorySwarmBus();
        FakeBuilder ab = new FakeBuilder(), bb = new FakeBuilder(), cb = new FakeBuilder();
        cb.refuse = "no schematic house.schem";
        SwarmControl alice = control("Alice", bus, ab), bob = control("Bob", bus, bb), carol = control("Carol", bus, cb);
        alice.build().start("crew", "house.schem", 0, 64, 0, "grid", "x", 1, 2);
        tickAll(alice, bob, carol);
        assertEquals("failed", alice.build().slotState(2));
        assertTrue(String.join("\n", alice.statusLines()).contains("no schematic house.schem"));
        alice.build().stop();
        tickAll(alice, bob, carol);
        assertTrue(ab.cancelled);
        assertTrue(bb.cancelled);
        assertNull(bob.build().myOrder());
    }

    @Test
    public void fileNamesStayInTheSchematicsFolder() {
        assertNotNull(SwarmBuild.checkFile("house.schem"));
        for (String bad : new String[]{"../x.schem", "a/b.schem", "a\\b", "C:x", ".hidden", "a;b", "a=b", ""}) {
            try {
                SwarmBuild.checkFile(bad);
                fail(bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }
}
