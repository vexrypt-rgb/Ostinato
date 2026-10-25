package baritone.api;

import baritone.api.process.PvpStats;
import baritone.api.swarm.SwarmPriority;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ostinato: the typed driver API stays wired to the internals it delegates to. */
public class OstinatoApiSurfaceTest {

    @Test
    public void agentPolicyIsAltoClefSettings() {
        assertTrue(baritone.api.policy.IAgentPolicy.class.isAssignableFrom(baritone.altoclef.AltoClefSettings.class));
    }

    @Test
    public void pvpProcessImplementsApi() {
        assertTrue(baritone.api.process.IPvpProcess.class.isAssignableFrom(baritone.process.PvpProcess.class));
    }

    @Test
    public void swarmPriorityNamesMatchTransport() {
        SwarmPriority[] api = SwarmPriority.values();
        baritone.swarm.transport.SwarmPriority[] internal = baritone.swarm.transport.SwarmPriority.values();
        assertEquals(internal.length, api.length);
        for (int i = 0; i < api.length; i++) {
            assertEquals(internal[i].name(), api[i].name());
        }
    }

    @Test
    public void pvpStatsFormatsLikeLegacyStatsLine() {
        assertEquals("attacks=3 crits=1 sprintHits=2 axeHits=0 blocks=4 gapples=1 dmgTaken=7.5",
                new PvpStats(3, 1, 2, 0, 4, 1, 7.5f).toString().replace(',', '.'));
    }

    @Test
    public void worldQueriesImplementsApi() {
        assertTrue(baritone.api.utils.IWorldQueries.class.isAssignableFrom(baritone.utils.WorldQueries.class));
    }
}
