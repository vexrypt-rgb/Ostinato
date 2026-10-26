package baritone.spectate;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

// Ostinato: automatic fight detection without spectate command or server menu
public class FightDetectorTest {
    private static FightDetector.Obs p(int id, double x, double z, float yaw, boolean hurt, boolean swing) {
        return new FightDetector.Obs(id, x, 64, z, yaw, hurt, swing);
    }

    @Test
    public void swingFacingInReachEngagesBoth() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 3, 0, false, false), p(3, 50, 50, 0, false, false)));
        assertTrue(d.engaged(1) && d.engaged(2));
        assertFalse(d.engaged(3));
    }

    @Test
    public void swingAwayOrOutOfReachIsNotAFight() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 180, false, true), p(2, 0, 3, 0, false, false)));
        d.tick(2, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 10, 0, false, false)));
        assertFalse(d.anyFight());
    }

    @Test
    public void hurtNearAnotherPlayerEngages_aloneDoesNot() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, true, false)));
        assertFalse(d.anyFight()); // fall damage, lava: no fight
        d.tick(2, List.of(p(1, 0, 0, 0, true, false), p(2, 5, 0, 0, false, false)));
        assertTrue(d.engaged(1) && d.engaged(2));
    }

    @Test
    public void engagementExpiresAfterHold() {
        FightDetector d = new FightDetector();
        var pair = List.of(p(1, 0, 0, 0, false, false), p(2, 0, 2, 0, false, false));
        d.tick(1, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 2, 0, false, false)));
        d.tick(1 + FightDetector.HOLD_TICKS, pair);
        assertTrue(d.anyFight()); // still inside the pause window
        d.tick(2 + FightDetector.HOLD_TICKS, pair);
        assertFalse(d.anyFight());
    }

    @Test
    public void leavingTheSnapshotEndsTheFight() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 2, 0, false, false)));
        d.tick(2, List.of()); // both left render distance
        assertFalse(d.anyFight());
        assertTrue(d.drainOutcomes().isEmpty());
    }

    @Test
    public void walkingApartEndsWithoutAWinner() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, false, true), p(2, 0, 2, 0, false, false)));
        d.tick(2, List.of(p(1, 0, 0, 0, false, false), p(2, 0, 30, 0, false, false)));
        assertFalse(d.anyFight());
        assertTrue(d.drainOutcomes().isEmpty());
    }

    @Test
    public void proximityAloneIsNotAFight() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(p(1, 0, 0, 0, false, false), p(2, 0, 2, 180, false, false)));
        assertFalse(d.anyFight());
    }

    @Test
    public void sumoKnockOffVoidWithoutDamageIsAWin() {
        FightDetector d = new FightDetector();
        d.voidY(-64);
        d.tick(1, List.of(new FightDetector.Obs(1, 0, 64, 0, 0, false, false, true, false), p(2, 0, 2, 0, false, false)));
        assertTrue(d.engaged(1) && d.engaged(2));
        d.tick(30, List.of(new FightDetector.Obs(1, 0, -70, 0, 0, false, false, false, false), p(2, 0, 2, 0, false, false)));
        var o = d.drainOutcomes();
        assertEquals(1, o.size());
        assertEquals(new FightDetector.Outcome(1, 2, "void"), o.get(0));
    }

    @Test
    public void knockedAloneIsNotAFight() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(new FightDetector.Obs(1, 0, 64, 0, 0, false, false, true, false), p(2, 30, 0, 0, false, false)));
        assertFalse(d.anyFight());
    }

    @Test
    public void sumoShoveFromSwingerIsAContact() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(new FightDetector.Obs(1, 0, 64, 0, 0, false, false, true, false), p(2, 0, 2, 180, false, true)));
        assertEquals(List.of(new FightDetector.Contact(2, 1)), d.drainContacts());
        assertTrue(d.drainContacts().isEmpty());
    }

    @Test
    public void dropBelowTheArenaIsAKnockOffForTheLastAttacker() {
        FightDetector d = new FightDetector();
        // both at the arena height: the helper p() sits at y=64, which would be a fall already
        var foe = new FightDetector.Obs(2, 0, 80, 2, 180, false, true, false, false);
        d.tick(1, List.of(new FightDetector.Obs(1, 0, 80, 0, 0, false, false, true, false), foe));
        d.tick(15, List.of(new FightDetector.Obs(1, 0, 70, 0, 0, false, false, false, false),
                new FightDetector.Obs(2, 0, 80, 2, 180, false, false, false, false)));
        assertEquals(List.of(new FightDetector.Outcome(1, 2, "knockoff")), d.drainOutcomes());
        assertFalse(d.engaged(1));
        assertFalse(d.engaged(2));
    }

    @Test
    public void fallDeathIsCreditedInsideTheWindowOnly() {
        FightDetector d = new FightDetector();
        d.tick(1, List.of(new FightDetector.Obs(1, 0, 64, 0, 0, false, false, true, false), p(2, 0, 2, 180, false, true)));
        d.tick(20, List.of(new FightDetector.Obs(1, 0, 58, 0, 0, true, false, false, true), p(2, 0, 2, 180, false, false)));
        assertEquals(new FightDetector.Outcome(1, 2, "fall"), d.drainOutcomes().get(0));

        FightDetector late = new FightDetector();
        late.tick(1, List.of(new FightDetector.Obs(1, 0, 64, 0, 0, false, false, true, false), p(2, 0, 2, 180, false, true)));
        late.tick(1 + FightDetector.ATTRIB_TICKS + 20, List.of(
                new FightDetector.Obs(1, 0, 50, 0, 0, false, false, false, false), p(2, 0, 2, 180, false, false)));
        var o = late.drainOutcomes();
        assertEquals(1, o.size());
        assertEquals(-1, o.get(0).winner());
        assertEquals("knockoff", o.get(0).cause());
    }

    @Test
    public void velocitySpikeIsAKnockAndTheFirstSampleIsNot() {
        assertFalse(FightDetector.velocityKnock(null, 0.2));
        assertFalse(FightDetector.velocityKnock(0.05, 0.08)); // sprint, under the bar
        assertTrue(FightDetector.velocityKnock(0.02, 0.16));
    }

    @Test
    public void autoDetectOffDoesNotWatch() throws Exception {
        // The settings class pulls in the Minecraft registry, so the default is checked from the source.
        String settings = Files.readString(Path.of("src/api/java/baritone/api/Settings.java"));
        int at = settings.indexOf("spectateAutoDetect = new Setting<>(");
        assertTrue(at > 0);
        assertTrue(settings.substring(at, Math.min(settings.length(), at + 48)).contains("(false)"));
        // both off: a fight in view changes nothing
        assertFalse(SpectateLearner.watching(false, false, true, true, false));
        assertFalse(SpectateLearner.watching(false, false, false, true, true));
        // spectateLearn alone is unchanged by the auto flag being off
        assertTrue(SpectateLearner.watching(true, false, false, true, false));
        assertFalse(SpectateLearner.watching(true, false, false, false, false));
        // auto on records only once a fight is seen, and a server still needs its own setting
        assertFalse(SpectateLearner.watching(false, true, false, true, false));
        assertTrue(SpectateLearner.watching(false, true, true, true, false));
        assertFalse(SpectateLearner.watching(false, true, true, false, false));
        assertTrue(SpectateLearner.watching(false, true, true, false, true));
    }
}
