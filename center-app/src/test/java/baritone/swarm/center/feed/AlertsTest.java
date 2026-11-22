package baritone.swarm.center.feed;

import baritone.swarm.center.TileStatus;
import baritone.swarm.center.app.fake.FakeSwarm;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: every alert rule, the stuck tracker, ordering, and the tile status word. */
public class AlertsTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private List<Alert> alerts(FakeSwarm s, String bot) {
        return s.snapshot().alertsFor(bot);
    }

    private static boolean has(List<Alert> a, Alert.Code c) {
        return a.stream().anyMatch(x -> x.code() == c);
    }

    @Test public void aHealthyIdleBotHasNoAlerts() {
        FakeSwarm s = FakeSwarm.threeBots(now::get);
        assertTrue(alerts(s, "Copper").isEmpty());
        assertEquals(new TileStatus(TileStatus.Level.IDLE, "idle"),
                Alerts.status(s.snapshot().bot("Copper"), List.of()));
    }

    @Test public void eachRuleFires() {
        FakeSwarm s = new FakeSwarm(now::get);
        s.bot("A").health(5);
        s.bot("B").hunger(3);
        s.bot("C").dead(true);
        s.bot("D").execution("mine", "failed", 2_000);
        s.bot("E").link(false).execution("goto", "running", 1_000);
        s.bot("F").age(CenterFeed.FRESH_MS + 1);
        s.bot("G").age(CenterFeed.OFFLINE_MS + 1).health(1);
        s.bot("H").inventory(new BotFeed.Inventory(0, 36, 0, List.of(), 5, "iron_pickaxe"));
        assertTrue(has(alerts(s, "A"), Alert.Code.LOW_HEALTH));
        assertTrue(has(alerts(s, "B"), Alert.Code.HUNGRY));
        assertTrue(has(alerts(s, "C"), Alert.Code.DEAD));
        assertFalse("a dead bot is not also low on health", has(alerts(s, "C"), Alert.Code.LOW_HEALTH));
        assertTrue(has(alerts(s, "D"), Alert.Code.FAILED));
        assertTrue(has(alerts(s, "E"), Alert.Code.LINK_DOWN));
        assertTrue(has(alerts(s, "F"), Alert.Code.STALE));
        List<Alert> g = alerts(s, "G");
        assertEquals("offline hides the rest of an old feed", 1, g.size());
        assertEquals(Alert.Code.OFFLINE, g.get(0).code());
        assertTrue(has(alerts(s, "H"), Alert.Code.INVENTORY_FULL));
        assertTrue(has(alerts(s, "H"), Alert.Code.LOW_TOOL));
    }

    @Test public void anOldFailureNoLongerAlerts() {
        FakeSwarm s = new FakeSwarm(now::get);
        s.bot("D").execution("mine", "failed", Alerts.FAILED_MS + 1);
        assertFalse(has(alerts(s, "D"), Alert.Code.FAILED));
    }

    @Test public void aBusyBotThatDoesNotMoveIsStuckAfterThirtySeconds() {
        FakeSwarm s = new FakeSwarm(now::get);
        s.bot("A").process("Goto").at(0, 64, 0);
        assertFalse(has(alerts(s, "A"), Alert.Code.STUCK));
        now.addAndGet(Alerts.STUCK_MS - 1);
        s.bot("A").at(0.5, 64, 0); // under a block from the anchor
        assertFalse(has(alerts(s, "A"), Alert.Code.STUCK));
        now.addAndGet(2);
        assertTrue(has(alerts(s, "A"), Alert.Code.STUCK));
        s.bot("A").at(3, 64, 0); // moved: re-anchored
        assertFalse(has(alerts(s, "A"), Alert.Code.STUCK));
        now.addAndGet(Alerts.STUCK_MS + 1);
        s.bot("A").process("idle");
        assertFalse("an idle bot is never stuck", has(alerts(s, "A"), Alert.Code.STUCK));
    }

    @Test public void worstFirstThenByBot() {
        FakeSwarm s = new FakeSwarm(now::get);
        s.bot("Zed").health(2);
        s.bot("Amy").hunger(2);
        s.bot("Bob").dead(true);
        List<Alert> all = s.snapshot().alerts();
        assertEquals("Bob", all.get(0).bot());
        assertEquals(TileStatus.Level.BAD, all.get(0).level());
        assertEquals("Amy", all.get(1).bot());
        assertEquals("Zed", all.get(2).bot());
    }

    @Test public void statusWordIsTheWorstAlertAndEveryLevelHasAGlyph() {
        FakeSwarm s = new FakeSwarm(now::get);
        s.bot("A").health(4).dead(false).execution("mine", "failed", 1000);
        SwarmView v = s.snapshot();
        TileStatus st = Alerts.status(v.bot("A"), v.alerts());
        assertEquals(TileStatus.Level.BAD, st.level());
        assertEquals("failed", st.word());
        for (Alert a : v.alerts()) {
            assertTrue(a.badge().startsWith(a.level().glyph + " "));
        }
        for (TileStatus.Level l : TileStatus.Level.values()) {
            assertFalse(l.glyph.isBlank());
        }
    }

    @Test public void anExpiredLeaseOnTheCoordinatorAlertsItsBot() {
        FakeSwarm s = FakeSwarm.threeBots(now::get);
        s.coordinator(new CoordinatorFeed("Copper", now.get(), 1, "active", "", List.of(new CoordinatorFeed.Lease("L",
                "mine:iron", "key", '?', null, "Azalea", 1, "expired", "mine iron_ore", "", "", "lease-expired")),
                List.of(), 0));
        assertTrue(has(alerts(s, "Azalea"), Alert.Code.LEASE_ENDED));
    }
}
