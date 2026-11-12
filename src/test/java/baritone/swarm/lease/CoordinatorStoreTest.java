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

package baritone.swarm.lease;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/** Ostinato: Phase 2a durable coordinator state. */
public class CoordinatorStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void roundTrip() throws Exception {
        Path f = tmp.newFolder().toPath().resolve("coordinator.state");
        CoordinatorStore st = new CoordinatorStore(f);
        assertTrue(st.load().leases.isEmpty());
        Map<String, Long> hw = new LinkedHashMap<>();
        hw.put("ore1", 7L);
        List<LeaseRecord> ls = new ArrayList<>();
        LeaseRecord r = new LeaseRecord(UUID.randomUUID(), "ore1", 7, UUID.randomUUID(), "Bob",
                "mine diamond_ore | weird", LeaseRecord.State.GRANTED);
        r.notBeforeWall = 123L;
        r.endReason = "x";
        ls.add(r);
        st.save(hw, ls);
        CoordinatorStore.Snapshot s = new CoordinatorStore(f).load();
        assertEquals(Long.valueOf(7L), s.highWater.get("ore1"));
        assertEquals(1, s.leases.size());
        LeaseRecord back = s.leases.get(0);
        assertEquals(r.leaseId, back.leaseId);
        assertEquals("mine diamond_ore | weird", back.commandLine);
        assertEquals(LeaseRecord.State.GRANTED, back.state);
        assertEquals(123L, back.notBeforeWall);
    }

    @Test
    public void tornTmpFileIgnored() throws Exception {
        Path dir = tmp.newFolder().toPath();
        Path f = dir.resolve("coordinator.state");
        CoordinatorStore st = new CoordinatorStore(f);
        Map<String, Long> hw = new LinkedHashMap<>();
        hw.put("ore1", 2L);
        st.save(hw, new ArrayList<>());
        Files.write(dir.resolve("coordinator.state.tmp"), "ostinato-coordinator v1\nhw|ore1|".getBytes(StandardCharsets.UTF_8));
        assertEquals(Long.valueOf(2L), st.load().highWater.get("ore1"));
    }

    @Test(expected = IOException.class)
    public void missingEndMarkerRejected() throws Exception {
        Path f = tmp.newFolder().toPath().resolve("coordinator.state");
        Files.write(f, (CoordinatorStore.HEADER + "\nhw|ore1|3\n").getBytes(StandardCharsets.UTF_8));
        new CoordinatorStore(f).load();
    }

    @Test
    public void unreadableStoreRefusesCoordinator() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        Files.write(s.store.path(), "garbage\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(LeaseCoordinator.Mode.REFUSED, s.coord.start());
        assertTrue(s.logged("swarm-lease: refused coordinator=Alice"));
    }

    @Test
    public void generationSurvivesRestart() throws Exception {
        LeaseSupport s = new LeaseSupport(tmp.newFolder().toPath());
        s.activate();
        s.coord.lease("Bob", "ore1", "mine diamond_ore");
        s.pump();
        s.coord.revoke("ore1");
        s.pump();
        s.tick();
        s.coord = s.newCoordinator("Alice", "Alice");
        s.activate();
        assertEquals(1L, s.coord.highWater("ore1"));
        String r = s.coord.lease("Bob", "ore1", "mine diamond_ore");
        assertTrue(r, r.contains("gen 2"));
    }
}
